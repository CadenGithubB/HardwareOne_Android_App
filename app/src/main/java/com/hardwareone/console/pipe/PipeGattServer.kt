package com.hardwareone.console.pipe

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.hardwareone.console.pipe.PipeAttPolicy.Action
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What the GATT server needs from the running pipe. */
interface PipeGattHost {
    /** The pipe is configured and its PSK is derived, so a session may be admitted. */
    fun ready(): Boolean
    /** A fresh copy of the PSK (the caller wipes it), or null when not [ready]. */
    fun psk(): ByteArray?
    /** The global authentication cooldown is running (R14). */
    fun coolingDown(): Boolean
    /** Runs [block] on the runtime thread after [delayMs]. */
    fun post(delayMs: Long = 0, block: () -> Unit)
    /** A session is being admitted: keep the CPU up from before the CCCD answer (R5). Any thread. */
    fun wake()
    fun onChange()
}

/**
 * The phone as BLE peripheral for the stage-4 relay pipe: one primary service (RX write without
 * response, TX notify + CCCD, plain permissions: the HW1-PIPE-v1 channel is the authentication)
 * and connectable legacy advertising with the service UUID in the primary payload (the P4 scans
 * passively and only reads legacy advertisements).
 *
 * Admission (R13). Android reports every LE link to every GATT server, including links where the
 * phone is the client (the app's own console) and other apps' links, so a connection is never a
 * candidate. The first valid CCCD enable is admitted: one session at a time, advertising stops,
 * and only that device is made a link holder so the session can drop it. Unrelated links are never
 * held, cancelled or disconnected.
 *
 * Instances (FW-5). Each open is a fresh server with its own callback object; callbacks reaching a
 * retired or replaced instance are dropped. A session that ended with a notification unsettled
 * retires its instance: once that link is gone, the server is closed and reopened before anyone is
 * admitted again, so a late platform callback or a delayed send can never touch a later session.
 * The service is never added or removed while a session is up (the P4 ends on Service Changed).
 *
 * Callbacks arrive on binder threads; everything here is short and never touches the network.
 */
@SuppressLint("MissingPermission")
class PipeGattServer(
    private val context: Context,
    private val engine: PipeEngine,
    private val host: PipeGattHost,
) {
    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val lock = Any()
    private var current: Instance? = null
    private var nextInstance = 1
    private var nextToken = 1L
    private var admitted: Admitted? = null
    /** An instance being retired: nothing is admitted until it is closed and reopened. */
    private var retiring: Instance? = null
    /** Set by [dispose]: this object never opens a server or advertises again. */
    private var disposed = false
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertising = false
    private var openFailures = 0
    private var openRetryAt = 0L
    private var advertiseFailures = 0
    private var advertiseRetryAt = 0L

    @Volatile var advertiseError: String? = null
        private set
    @Volatile var openError: String? = null
        private set

    /** Short per-device names for the log, so it never carries a Bluetooth address. */
    private val deviceIds = ConcurrentHashMap<String, Int>()
    private val nextDeviceId = AtomicInteger()
    private fun dev(device: BluetoothDevice): String {
        if (deviceIds.size > 64) deviceIds.clear()
        return "dev" + deviceIds.computeIfAbsent(device.address) { nextDeviceId.incrementAndGet() }
    }

    /** Callbacks that reached a retired or replaced server instance and were dropped (FW-5). */
    private val dropped = AtomicLong()
    val droppedCallbacks: Long get() = dropped.get()

    val isAdvertising: Boolean get() = synchronized(lock) { advertising }
    val isOpen: Boolean get() = synchronized(lock) { current?.serviceReady == true }
    /** A device is admitted (from its CCCD enable until its session's teardown). */
    val hasCandidate: Boolean get() = synchronized(lock) { admitted != null }

    private class Admitted(val instance: Instance, val device: BluetoothDevice, val token: Long)

    /** Opens a server and adds the service, unless one is open or being retired. */
    fun open(): Boolean {
        synchronized(lock) {
            if (disposed) return false
            if (current != null || retiring != null) return true
            val m = manager
            val adapter = m?.adapter
            if (m == null || adapter == null || !adapter.isEnabled) {
                openError = "Bluetooth is off."
                return false
            }
            val inst = Instance(nextInstance++)
            // openGattServer returns null right after STATE_ON while the service binds: retried.
            val s = runCatching { m.openGattServer(context, inst.callback) }.getOrNull()
            if (s == null) {
                Log.w(TAG, "server#${inst.id} openGattServer returned null")
                openError = "Android refused to open a GATT server."
                noteOpenFailureLocked()
                return false
            }
            inst.server = s
            val service = BluetoothGattService(PipeWire.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            // Read permission only so a read reaches this code and gets 0x06, as v1 specifies.
            val rx = BluetoothGattCharacteristic(
                PipeWire.RX_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ,
            )
            val txChar = BluetoothGattCharacteristic(
                PipeWire.TX_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
            txChar.addDescriptor(
                BluetoothGattDescriptor(
                    PipeWire.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ,
                ),
            )
            service.addCharacteristic(rx)
            service.addCharacteristic(txChar)
            inst.tx = txChar
            current = inst
            inst.addedAt = SystemClock.elapsedRealtime()
            if (!runCatching { s.addService(service) }.getOrDefault(false)) {
                Log.w(TAG, "server#${inst.id} addService refused")
                openError = "Android refused to add the pipe service."
                closeInstanceLocked(inst)
                current = null
                noteOpenFailureLocked()
                return false
            }
            openError = null
        }
        host.onChange()
        return true
    }

    /** Bluetooth turned off (or going off): end the session, drop the server; [open] may run again. */
    fun close() {
        val token = synchronized(lock) { admitted?.token }
        if (token != null) engine.end(token, PipeEndKind.TRANSPORT)
        synchronized(lock) {
            stopAdvertisingLocked()
            current?.let { closeInstanceLocked(it) }
            retiring?.let { closeInstanceLocked(it) }
            current = null
            retiring = null
            admitted = null
        }
        host.onChange()
    }

    /** Service stop: like [close], and nothing queued afterwards can reopen or advertise. */
    fun dispose() {
        synchronized(lock) { disposed = true }
        close()
    }

    /**
     * The engine finished a session ([PipeEnd] after teardown). Frees the admission slot, or retires
     * the instance when a submission was left unsettled.
     */
    fun sessionEnded(end: PipeEnd) {
        var retire: Admitted? = null
        synchronized(lock) {
            val a = admitted ?: return@synchronized
            if (a.token != end.token) return@synchronized
            admitted = null
            if (end.unsettled && current === a.instance) {
                a.instance.retired = true
                a.instance.retiredPeer = a.device.address
                retiring = a.instance
                current = null
                retire = a
            }
            // Release this app's hold on the link (idempotent), whichever way the session ended:
            // it also clears a direct connect the holder registration may have left pending.
            runCatching { a.instance.server?.cancelConnection(a.device) }
        }
        retire?.let { a ->
            stopAdvertising()
            // Reopen once the old link is gone (its disconnect callback), or after a bound.
            host.post(RETIRE_WAIT_MS) { finishRetirement(a.instance) }
        }
        refreshAdvertising()
    }

    private fun finishRetirement(inst: Instance) {
        synchronized(lock) {
            if (retiring !== inst) return
            closeInstanceLocked(inst)
            retiring = null
        }
        open()
    }

    /** Ends the current session (authority change etc. go through the engine; this drops the link). */
    fun dropCandidate() {
        val a = synchronized(lock) { admitted } ?: return
        engine.end(a.token, PipeEndKind.CANCELLED)
    }

    /**
     * Re-evaluates advertising: on while open, configured (PSK ready), not cooling down, nobody
     * admitted and no instance being retired. Decided under the lock, so the last caller sees every
     * input that changed before it.
     */
    fun refreshAdvertising() {
        synchronized(lock) {
            if (disposed) return
            val inst = current
            val want = inst != null && inst.serviceReady && admitted == null && retiring == null &&
                host.ready() && !host.coolingDown()
            val started = advertiseCallback != null
            if (want && !started && SystemClock.elapsedRealtime() >= advertiseRetryAt) startAdvertisingLocked()
            if (!want && started) stopAdvertisingLocked()
        }
        host.onChange()
    }

    private fun stopAdvertising() = synchronized(lock) { stopAdvertisingLocked() }

    /**
     * Once a second from the runtime: retry a failed open/addService and a failed advertising start
     * with backoff, and pick up a cooldown that has just ended.
     */
    fun tick(bluetoothOn: Boolean) {
        var reopen = false
        synchronized(lock) {
            if (disposed || !bluetoothOn) return
            val inst = current
            val now = SystemClock.elapsedRealtime()
            // addService accepted but onServiceAdded never came (e.g. the registration was lost).
            if (inst != null && !inst.serviceReady && !inst.serviceFailed && now - inst.addedAt > SERVICE_ADD_WAIT_MS) {
                inst.serviceFailed = true
                openError = "Android did not confirm the pipe service; retrying."
                noteOpenFailureLocked()
            }
            if (retiring == null && (inst == null || inst.serviceFailed) && now >= openRetryAt) {
                inst?.let { closeInstanceLocked(it) }
                current = null
                reopen = true
            }
        }
        if (reopen) open()
        refreshAdvertising()
    }

    private fun noteOpenFailureLocked() {
        openRetryAt = SystemClock.elapsedRealtime() + backoff(openFailures++)
    }

    private fun closeInstanceLocked(inst: Instance) {
        inst.retired = true
        runCatching { inst.server?.close() }
        inst.server = null
        inst.tx = null
        inst.serviceReady = false
        inst.connected.clear()
        inst.mtus.clear()
    }

    private fun startAdvertisingLocked() {
        val adapter = manager?.adapter
        val adv = adapter?.bluetoothLeAdvertiser
        if (adv == null || !adapter.isMultipleAdvertisementSupported) {
            advertiseError = "This phone cannot advertise as a BLE peripheral."
            advertiseRetryAt = SystemClock.elapsedRealtime() + backoff(advertiseFailures++)
            return
        }
        val settings = AdvertiseSettings.Builder()
            // ~100 ms: the P4 scans passively for 2 s with a 30 ms window every 100 ms.
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Flags + the 128-bit service UUID (AD type 0x07) = 21 of 31 bytes. No name, no endpoint.
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(PipeWire.SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        // A fresh callback per start: results posted for an earlier start are recognisably stale.
        val cb = AdvertiseResult()
        advertiser = adv
        advertiseCallback = cb
        if (runCatching { adv.startAdvertising(settings, data, cb) }.isFailure) {
            advertiser = null
            advertiseCallback = null
            advertiseError = "Advertising was refused (missing Nearby devices permission?)."
            advertiseRetryAt = SystemClock.elapsedRealtime() + backoff(advertiseFailures++)
        }
    }

    private fun stopAdvertisingLocked() {
        // Stop through the instance that started it: in TURNING_OFF the adapter returns no
        // advertiser, which would otherwise leave a set running that nothing can stop.
        val adv = advertiser
        val cb = advertiseCallback
        if (adv != null && cb != null) {
            runCatching { adv.stopAdvertising(cb) }
            Log.i(TAG, "advertising stopped")
        }
        advertiser = null
        advertiseCallback = null
        advertising = false
    }

    private inner class AdvertiseResult : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            synchronized(lock) {
                if (advertiseCallback !== this) {
                    // Started after we already stopped (results are posted to the main looper).
                    runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(this) }
                    return
                }
                advertising = true
                advertiseError = null
                advertiseFailures = 0
                Log.i(TAG, "advertising started")
            }
            host.onChange()
        }

        override fun onStartFailure(errorCode: Int) {
            synchronized(lock) {
                if (advertiseCallback !== this) return
                Log.w(TAG, "advertising failed code=$errorCode")
                advertiser = null
                advertiseCallback = null
                advertising = false
                advertiseRetryAt = SystemClock.elapsedRealtime() + backoff(advertiseFailures++)
                advertiseError = when (errorCode) {
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many apps are advertising; retrying."
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "This phone cannot advertise as a BLE peripheral."
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertising data too large."
                    else -> "Advertising failed (code $errorCode); retrying."
                }
            }
            host.onChange()
        }
    }

    /** The admitted session's link: bound to its own server instance and device. */
    private inner class Link(val inst: Instance, val device: BluetoothDevice) : PipeLink {
        override fun notify(value: ByteArray): PipeSubmit {
            if (inst.retired) return PipeSubmit.REFUSED
            val s = inst.server ?: return PipeSubmit.REFUSED
            val c = inst.tx ?: return PipeSubmit.REFUSED
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    when (s.notifyCharacteristicChanged(device, c, false, value)) {
                        BluetoothStatusCodes.SUCCESS -> PipeSubmit.OK
                        // The gatt-thread wait timed out: the send may still happen later.
                        BluetoothStatusCodes.ERROR_UNKNOWN -> PipeSubmit.UNCERTAIN
                        else -> PipeSubmit.REFUSED
                    }
                } else {
                    @Suppress("DEPRECATION")
                    c.value = value
                    @Suppress("DEPRECATION")
                    if (s.notifyCharacteristicChanged(device, c, false)) PipeSubmit.OK else PipeSubmit.REFUSED
                }
            } catch (e: IllegalArgumentException) {
                PipeSubmit.REFUSED
            } catch (e: NullPointerException) {
                PipeSubmit.REFUSED
            } catch (e: SecurityException) {
                PipeSubmit.REFUSED
            } catch (e: RuntimeException) {
                // A dead binder (RemoteException rethrown): unknown whether it went out.
                PipeSubmit.UNCERTAIN
            }
        }

        override fun disconnect() {
            runCatching { inst.server?.cancelConnection(device) }
        }
    }

    /** One opened server and everything that belongs to it, including its own callback object. */
    private inner class Instance(val id: Int) {
        @Volatile var server: BluetoothGattServer? = null
        @Volatile var tx: BluetoothGattCharacteristic? = null
        @Volatile var serviceReady = false
        @Volatile var serviceFailed = false
        @Volatile var retired = false
        @Volatile var addedAt = 0L
        /** The device whose session retired this instance: only its disconnect finishes the retirement early. */
        @Volatile var retiredPeer: String? = null
        val connected: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val mtus = ConcurrentHashMap<String, Int>()

        private fun live(): Boolean = (!retired && synchronized(lock) { current === this }).also { if (!it) dropped.incrementAndGet() }

        private fun respond(device: BluetoothDevice, requestId: Int, status: Int): Boolean =
            runCatching { server?.sendResponse(device, requestId, status, 0, null) == true }.getOrDefault(false)

        private fun admittedHere(device: BluetoothDevice): Admitted? = synchronized(lock) {
            admitted?.takeIf { it.instance === this && it.device.address.equals(device.address, ignoreCase = true) }
        }

        val callback = object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                if (!live() || service?.uuid != PipeWire.SERVICE_UUID) return
                Log.i(TAG, "server#$id service added status=$status")
                synchronized(lock) {
                    serviceReady = status == BluetoothGatt.GATT_SUCCESS
                    serviceFailed = !serviceReady
                    if (serviceReady) openFailures = 0
                    else {
                        openError = "Android could not add the pipe service (status $status); retrying."
                        noteOpenFailureLocked()
                    }
                }
                refreshAdvertising()
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                // For failed P4 attempts (firmware-008 §4): the link-level outcome, without the address.
                Log.i(TAG, "server#$id ${dev(device)} ${stateName(newState)} status=$status" +
                    " current=${!retired && synchronized(lock) { current === this@Instance }} admitted=${admittedHere(device) != null}")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (live()) connected += device.address
                    return
                }
                if (newState != BluetoothProfile.STATE_DISCONNECTED) return
                connected -= device.address
                mtus.remove(device.address)
                val wasRetiring = synchronized(lock) { retiring === this@Instance }
                if (wasRetiring) {
                    // Android reports every LE link: only the retired session's own link going
                    // ends the wait early; anything else waits for the bound.
                    if (device.address.equals(retiredPeer, ignoreCase = true)) host.post(0) { finishRetirement(this@Instance) }
                    return
                }
                if (!live()) return
                admittedHere(device)?.let { engine.linkLost(it.token) }
                refreshAdvertising()
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                Log.i(TAG, "server#$id ${dev(device)} mtu=$mtu")
                if (live()) mtus[device.address] = mtu
            }

            override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
                respond(device, requestId, PipeAttPolicy.read())
            }

            override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
                respond(device, requestId, PipeAttPolicy.read())
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
            ) {
                if (!live()) {
                    if (responseNeeded) respond(device, requestId, PipeAttPolicy.ATT_REQUEST_NOT_SUPPORTED)
                    return
                }
                val a = admittedHere(device)
                val d = PipeAttPolicy.rxWrite(
                    isRx = characteristic.uuid == PipeWire.RX_UUID, responseNeeded = responseNeeded,
                    preparedWrite = preparedWrite, offset = offset, admitted = a != null,
                )
                when (d.action) {
                    Action.DELIVER -> engine.onWrite(a!!.token, value)
                    Action.END_AFTER -> {
                        d.status?.let { respond(device, requestId, it) }
                        engine.end(a!!.token, PipeEndKind.TRANSPORT, PipeError.BAD_PROTOCOL)
                    }
                    Action.END -> engine.end(a!!.token, PipeEndKind.TRANSPORT, PipeError.BAD_PROTOCOL)
                    else -> d.status?.let { respond(device, requestId, it) }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
            ) {
                if (!live()) {
                    if (responseNeeded) respond(device, requestId, PipeAttPolicy.ATT_INSUFFICIENT_RESOURCES)
                    return
                }
                var admittedNow: Admitted? = null
                var busy = false
                val d: PipeAttPolicy.Decision
                synchronized(lock) {
                    if (current !== this@Instance || retired) {
                        // Closed or replaced since the live() check above.
                        dropped.incrementAndGet()
                        busy = true
                        d = PipeAttPolicy.Decision(PipeAttPolicy.ATT_INSUFFICIENT_RESOURCES, Action.NONE)
                        return@synchronized
                    }
                    val a = admitted
                    val mine = a != null && a.instance === this@Instance && a.device.address.equals(device.address, ignoreCase = true)
                    d = PipeAttPolicy.cccdWrite(
                        isCccd = descriptor.uuid == PipeWire.CCCD_UUID && descriptor.characteristic?.uuid == PipeWire.TX_UUID,
                        responseNeeded = responseNeeded, preparedWrite = preparedWrite, offset = offset, value = value,
                        admitted = mine, otherAdmitted = a != null && !mine,
                        coolingDown = host.coolingDown() || retiring != null, ready = host.ready(),
                        mtu = mtus[device.address] ?: 0,
                    )
                    if (d.action == Action.ADMIT) {
                        val token = nextToken++
                        val candidate = Admitted(this@Instance, device, token)
                        val psk = host.psk()
                        // Prepare the channel BEFORE answering, so HELLO can follow the answer at once.
                        if (psk != null && engine.begin(Link(this@Instance, device), mtus[device.address] ?: 0, psk, token)) {
                            admitted = candidate
                            admittedNow = candidate
                            stopAdvertisingLocked()
                        } else {
                            busy = true
                        }
                        psk?.fill(0)
                    }
                }
                Log.i(TAG, "server#$id ${dev(device)} cccd action=${d.action} status=${d.status} busy=$busy mtu=${mtus[device.address] ?: 0}")
                when (d.action) {
                    Action.ADMIT -> {
                        val a = admittedNow
                        if (busy || a == null) {
                            respond(device, requestId, PipeAttPolicy.ATT_INSUFFICIENT_RESOURCES)
                            return
                        }
                        host.wake()
                        val started = SystemClock.elapsedRealtime()
                        if (!respond(device, requestId, PipeAttPolicy.ATT_SUCCESS)) {
                            // The answer never went out, so neither may HELLO (R3); a local failure.
                            engine.end(a.token, PipeEndKind.TRANSPORT, PipeError.NO_RESOURCES)
                            host.onChange()
                            return
                        }
                        // The P4 opened this link, so the app does not hold it and cancelConnection
                        // would be a no-op. Holding it lets a failed session actually drop the link.
                        // On 17 the result only reflects binder/registration success, not the stack's.
                        val held = runCatching { server?.connect(device, false) == true }.getOrDefault(false)
                        // Our own answer and holder registration both count toward a local stall (A-5).
                        val localMs = SystemClock.elapsedRealtime() - started
                        if (held) engine.subscribed(a.token, localMs)
                        else engine.end(a.token, PipeEndKind.TRANSPORT, PipeError.NO_RESOURCES)
                        host.onChange()
                    }
                    Action.END_AFTER -> {
                        d.status?.let { respond(device, requestId, it) }
                        admittedHere(device)?.let { engine.end(it.token, PipeEndKind.TRANSPORT, PipeError.BAD_PROTOCOL) }
                    }
                    Action.END -> admittedHere(device)?.let { engine.end(it.token, PipeEndKind.TRANSPORT, PipeError.BAD_PROTOCOL) }
                    else -> d.status?.let { respond(device, requestId, it) }
                }
            }

            override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
                respond(device, requestId, PipeAttPolicy.executeWrite())
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                // Local enqueue only, never remote delivery. Only the admitted session's own
                // instance and device count; anything else is stale.
                if (!live()) return
                admittedHere(device)?.let { engine.onNotificationSent(it.token, status) }
            }
        }
    }

    companion object {
        private const val TAG = "PipeGatt"

        private fun stateName(state: Int): String = when (state) {
            BluetoothProfile.STATE_CONNECTED -> "connected"
            BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
            BluetoothProfile.STATE_CONNECTING -> "connecting"
            BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
            else -> "state$state"
        }

        /** Longest wait for a retired session's link to go before the server is closed anyway. */
        const val RETIRE_WAIT_MS = 2_000L
        /** How long addService may go without onServiceAdded before the server is reopened. */
        const val SERVICE_ADD_WAIT_MS = 5_000L

        /** 2 s, 5 s, 15 s, then 30 s between retries of a failed open or advertising start. */
        fun backoff(failures: Int): Long = when (failures) {
            0 -> 2_000L
            1 -> 5_000L
            2 -> 15_000L
            else -> 30_000L
        }
    }
}
