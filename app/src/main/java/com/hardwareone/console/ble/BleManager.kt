package com.hardwareone.console.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.hardwareone.console.security.SecureChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A small, coroutine-friendly wrapper around the raw `android.bluetooth` GATT API that
 * drives the firmware-mandated connect sequence and exposes everything the UI needs as
 * flows.
 *
 * Security is **app-layer** (HardwareOne Secure Channel v1): no BLE pairing/bonding. When a
 * PSK is configured ([setPsk]) the manager runs the X25519/ChaCha20-Poly1305 handshake
 * after subscribing and before "Ready", then frames all REQUEST/RESPONSE traffic through
 * [SecureChannel]. With no PSK it talks plaintext, exactly as the firmware's plaintext mode.
 *
 * Threading: GATT callbacks arrive on a binder thread; we only touch thread-safe
 * [MutableStateFlow.value] / [MutableSharedFlow.tryEmit]. GATT operations are serialised
 * through a tiny queue. Timeouts / idle flushing run on a main-thread [Handler].
 */
@SuppressLint("MissingPermission") // every BLE call site is guarded by a runtime check.
class BleManager(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val bluetoothManager: BluetoothManager? =
        ContextCompat.getSystemService(appContext, BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner?
        get() = adapter?.bluetoothLeScanner

    // --- Public state ---------------------------------------------------------------

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _scanResults = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val scanResults: StateFlow<List<DiscoveredDevice>> = _scanResults.asStateFlow()

    private val _authenticated = MutableStateFlow(false)
    val authenticated: StateFlow<Boolean> = _authenticated.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    /** Plain-text reply lines for the console. */
    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    /** Connection lifecycle / info / error lines for the console. */
    private val _messages = MutableSharedFlow<BleMessage>(extraBufferCapacity = 128)
    val messages: SharedFlow<BleMessage> = _messages.asSharedFlow()

    val isBluetoothSupported: Boolean get() = adapter != null
    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    // --- Secure channel -------------------------------------------------------------

    @Volatile private var psk: ByteArray? = null
    private var channel: SecureChannel? = null
    private var secureEstablished = false
    private val rxLock = Any()
    private val rxBuffer = StringBuilder()

    /** Configure (or clear) the pre-shared key. Takes effect on the next connect. */
    fun setPsk(newPsk: ByteArray?) {
        psk = newPsk
    }

    val secureModeConfigured: Boolean get() = psk != null

    // --- Internal connection state --------------------------------------------------

    private var gatt: BluetoothGatt? = null
    private var requestChar: BluetoothGattCharacteristic? = null
    private var responseChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null

    private var lastDevice: BluetoothDevice? = null
    private var currentName: String = "HardwareOne"
    private var negotiatedMtu: Int = 23
    private var userInitiatedDisconnect = false

    /** Unregister/teardown. Call from ViewModel.onCleared(). */
    fun release() {
        closeGatt()
    }

    // --- Scanning -------------------------------------------------------------------

    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = addScanResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::addScanResult)
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            emitError("Scan failed (code $errorCode).")
            _state.value = ConnectionState.Disconnected
        }
    }

    private fun addScanResult(result: ScanResult) {
        val device = DiscoveredDevice(
            address = result.device.address,
            name = result.scanRecord?.deviceName ?: "HardwareOne",
            rssi = result.rssi,
        )
        val current = _scanResults.value
        val idx = current.indexOfFirst { it.address == device.address }
        _scanResults.value =
            if (idx >= 0) current.toMutableList().also { it[idx] = device } else current + device
    }

    fun startScan() {
        if (scanning) return
        val scanner = scanner
        if (adapter == null || scanner == null) {
            emitError("Bluetooth is not available on this device.")
            return
        }
        if (!isBluetoothEnabled) {
            emitError("Bluetooth is off — turn it on and scan again.")
            return
        }
        if (!hasScanPermission()) {
            emitError("BLUETOOTH_SCAN permission not granted.")
            return
        }
        _scanResults.value = emptyList()
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.COMMAND_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        _state.value = ConnectionState.Scanning
        emitInfo("Scanning for HardwareOne…")
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        if (hasScanPermission()) scanner?.stopScan(scanCallback)
        if (_state.value is ConnectionState.Scanning) _state.value = ConnectionState.Disconnected
    }

    // --- Connect sequence -----------------------------------------------------------

    fun connect(address: String) {
        val device = adapter?.getRemoteDevice(address)
        if (device == null) {
            emitError("Unknown device: $address")
            return
        }
        connect(device)
    }

    fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            emitError("BLUETOOTH_CONNECT permission not granted.")
            return
        }
        stopScan()
        closeGatt()

        lastDevice = device
        currentName = runCatching { device.name }.getOrNull() ?: "HardwareOne"
        userInitiatedDisconnect = false
        _authenticated.value = false
        _deviceInfo.value = DeviceInfo(name = currentName, address = device.address)

        _state.value = ConnectionState.Connecting(currentName)
        emitInfo("Connecting to $currentName (${device.address})…")
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun reconnect() {
        val device = lastDevice
        if (device == null) {
            emitError("No previous device to reconnect to.")
            return
        }
        connect(device)
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        val g = gatt
        if (g != null && hasConnectPermission()) {
            emitInfo("Disconnecting…")
            g.disconnect()
        } else {
            closeGatt()
            _state.value = ConnectionState.Disconnected
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGatt()
                _authenticated.value = false
                _state.value = ConnectionState.Failed("Connection error (status $status).")
                emitError("Connection error (status $status).")
                return
            }
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    emitInfo("Connected. Requesting high priority + discovering services…")
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    _state.value = ConnectionState.DiscoveringServices(currentName)
                    g.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    val wasIntentional = userInitiatedDisconnect
                    closeGatt()
                    _authenticated.value = false
                    if (wasIntentional) {
                        _state.value = ConnectionState.Disconnected
                        emitInfo("Disconnected.")
                    } else {
                        _state.value = ConnectionState.Failed("Device disconnected.")
                        emitError("Device disconnected.")
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Service discovery failed (status $status).")
                return
            }
            val service = g.getService(BleConstants.COMMAND_SERVICE)
            if (service == null) {
                fail("Command service not found — is this a HardwareOne device?")
                return
            }
            requestChar = service.getCharacteristic(BleConstants.REQUEST_CHAR)
            responseChar = service.getCharacteristic(BleConstants.RESPONSE_CHAR)
            statusChar = service.getCharacteristic(BleConstants.STATUS_CHAR)
            if (requestChar == null || responseChar == null) {
                fail("Required characteristics missing on device.")
                return
            }
            _state.value = ConnectionState.NegotiatingMtu(currentName)
            emitInfo("Services found. Negotiating MTU…")
            g.requestMtu(BleConstants.TARGET_MTU)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            emitInfo("MTU = $negotiatedMtu (max ${negotiatedMtu - 3} bytes/notification).")
            _state.value = ConnectionState.EnablingNotifications(currentName)
            enableResponseNotifications(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != BleConstants.CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Failed to enable notifications (status $status).")
                return
            }
            // Subscribed. Either run the secure handshake, or go straight to Ready (plaintext).
            if (psk != null) startSecureHandshake() else becomeReady(secure = false)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) emitError("Write failed (status $status).")
            onOpComplete()
        }

        // Android 13+ delivers the value; older devices use the deprecated overload.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            handleNotification(characteristic, value)

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) =
            handleNotification(characteristic, characteristic.value ?: ByteArray(0))

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(characteristic, value, status)
            onOpComplete()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handleRead(characteristic, characteristic.value ?: ByteArray(0), status)
            onOpComplete()
        }
    }

    private fun enableResponseNotifications(g: BluetoothGatt) {
        val char = responseChar ?: return fail("Response characteristic missing.")
        if (!g.setCharacteristicNotification(char, true)) {
            fail("setCharacteristicNotification returned false.")
            return
        }
        val cccd = char.getDescriptor(BleConstants.CCCD) ?: return fail("Response CCCD (0x2902) not found.")
        writeDescriptorCompat(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    private fun becomeReady(secure: Boolean) {
        _deviceInfo.value = _deviceInfo.value?.copy(mtu = negotiatedMtu, secure = secure)
        _state.value = ConnectionState.Ready(currentName, negotiatedMtu)
        if (secure) emitInfo("Secure channel established. Log in with:  login <username> <password>")
        else emitInfo("Ready. Log in with:  login <username> <password>")
        requestDeviceInfo()
    }

    // --- Secure handshake -----------------------------------------------------------

    private fun startSecureHandshake() {
        val key = psk ?: return becomeReady(secure = false)
        val ch = SecureChannel(key)
        channel = ch
        secureEstablished = false
        synchronized(rxLock) { rxBuffer.clear() }
        _state.value = ConnectionState.Securing(currentName)
        emitInfo("Securing channel…")
        scheduleHandshakeTimeout()
        enqueue(GattOp.WriteRequest(ch.hello()))
    }

    private fun handleHandshakeMessage(ch: SecureChannel, msg: ByteArray) {
        if (msg.isEmpty()) return
        when (msg[0]) {
            SecureChannel.T_HELLO_ACK -> {
                val confirm = ch.onHelloAck(msg)
                if (confirm != null) enqueue(GattOp.WriteRequest(confirm))
                else failSecure("Secure handshake failed (bad HELLO_ACK).")
            }
            SecureChannel.T_CONFIRM_ACK -> {
                if (ch.onConfirmAck(msg)) {
                    cancelHandshakeTimeout()
                    secureEstablished = true
                    becomeReady(secure = true)
                } else {
                    failSecure("Secure handshake failed — wrong passphrase?")
                }
            }
            // Anything else (e.g. a plaintext line from a device not in secure mode) → fail.
            else -> failSecure("Device did not complete the secure handshake — check the passphrase / device secret.")
        }
    }

    private fun failSecure(reason: String) {
        cancelHandshakeTimeout()
        channel = null
        secureEstablished = false
        fail(reason)
    }

    private val handshakeTimeout = Runnable {
        if (channel != null && !secureEstablished) {
            failSecure("Secure handshake timed out — check the passphrase / device secret.")
        }
    }

    private fun scheduleHandshakeTimeout() = handler.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS)
    private fun cancelHandshakeTimeout() = handler.removeCallbacks(handshakeTimeout)

    // --- Inbound (notifications) ----------------------------------------------------

    private fun handleNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != BleConstants.RESPONSE_CHAR) return
        val ch = channel
        if (ch == null) {
            // Plaintext mode: one notification == one reply (may contain '\n').
            val text = value.toString(Charsets.UTF_8)
            updateAuthFromReply(text)
            _incoming.tryEmit(text)
            return
        }
        if (ch.state != SecureChannel.State.ESTABLISHED) {
            handleHandshakeMessage(ch, value)
            return
        }
        // Established: decrypt a DATA frame and stream it.
        val pt = ch.decrypt(value)
        if (pt != null) {
            appendSecureRx(String(pt, Charsets.UTF_8))
        } else {
            // Diagnostic: a RESPONSE we couldn't decrypt while secured. Distinguish a
            // plaintext leak (path A unencrypted) from a counter/tag mismatch.
            val looksAscii = value.isNotEmpty() && value.all { (it.toInt() and 0xff) in 0x09..0x7e }
            if (looksAscii) {
                emitError("⟂ plaintext on secure link: \"${value.toString(Charsets.UTF_8).take(120)}\"")
            } else {
                val b0 = value.firstOrNull()?.let { "0x%02x".format(it) } ?: "—"
                emitError("⟂ undecryptable secure frame (${value.size} B, first byte $b0)")
            }
        }
    }

    /** Reassemble the device's chunked, unframed reply stream into console lines. */
    private fun appendSecureRx(text: String) {
        synchronized(rxLock) { rxBuffer.append(text) }
        while (true) {
            val line = synchronized(rxLock) {
                val idx = rxBuffer.indexOf("\n")
                if (idx < 0) return@synchronized null
                rxBuffer.substring(0, idx).also { rxBuffer.delete(0, idx + 1) }
            } ?: break
            emitSecureLine(line)
        }
        // Flush a trailing partial line shortly after the stream goes quiet.
        handler.removeCallbacks(idleFlush)
        handler.postDelayed(idleFlush, IDLE_FLUSH_MS)
    }

    private val idleFlush = Runnable {
        val s = synchronized(rxLock) {
            if (rxBuffer.isEmpty()) null else rxBuffer.toString().also { rxBuffer.clear() }
        }
        if (s != null) emitSecureLine(s)
    }

    private fun emitSecureLine(line: String) {
        val l = line.trimEnd('\r')
        updateAuthFromReply(l)
        _incoming.tryEmit(l)
    }

    private fun updateAuthFromReply(text: String) {
        when {
            text.contains("Login successful") -> _authenticated.value = true
            text.contains("Authentication required") -> _authenticated.value = false
            text.contains("Logged out", ignoreCase = true) -> _authenticated.value = false
        }
    }

    private fun handleRead(characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) return
        val text = value.toString(Charsets.UTF_8).trim()
        if (text.isEmpty()) return
        when (characteristic.uuid) {
            BleConstants.STATUS_CHAR -> emitInfo("status: $text")
            BleConstants.FIRMWARE_CHAR -> {
                emitInfo("firmware: $text")
                _deviceInfo.value = _deviceInfo.value?.copy(firmware = text)
            }
            BleConstants.MODEL_CHAR -> {
                emitInfo("model: $text")
                _deviceInfo.value = _deviceInfo.value?.copy(model = text)
            }
            BleConstants.MANUFACTURER_CHAR -> {
                emitInfo("manufacturer: $text")
                _deviceInfo.value = _deviceInfo.value?.copy(manufacturer = text)
            }
        }
    }

    // --- Outbound operations (serialised) -------------------------------------------

    private sealed interface GattOp {
        class WriteRequest(val bytes: ByteArray) : GattOp
        class ReadChar(val characteristic: BluetoothGattCharacteristic) : GattOp
    }

    private val opLock = Any()
    private val opQueue = ArrayDeque<GattOp>()
    private var opInFlight = false

    private fun enqueue(op: GattOp) = synchronized(opLock) { opQueue.addLast(op); runNextLocked() }

    private fun onOpComplete() = synchronized(opLock) { opInFlight = false; runNextLocked() }

    private fun runNextLocked() {
        if (opInFlight) return
        val g = gatt ?: run { opQueue.clear(); return }
        val op = opQueue.removeFirstOrNull() ?: return
        opInFlight = true
        val ok = when (op) {
            is GattOp.WriteRequest -> requestChar?.let { writeCharacteristicCompat(g, it, op.bytes) } ?: false
            is GattOp.ReadChar -> g.readCharacteristic(op.characteristic)
        }
        if (!ok) {
            opInFlight = false
            emitError("BLE operation could not be started.")
            runNextLocked()
        }
    }

    /** Send a free-text CLI command (encrypted as a DATA frame when the channel is up). */
    fun sendCommand(command: String) {
        if (_state.value !is ConnectionState.Ready) {
            emitError("Not connected.")
            return
        }
        if (!hasConnectPermission()) {
            emitError("BLUETOOTH_CONNECT permission not granted.")
            return
        }
        val bytes = command.toByteArray(Charsets.UTF_8)
        val ch = channel
        if (ch != null && secureEstablished) {
            val maxPlain = (negotiatedMtu - 28).coerceAtLeast(20)
            if (bytes.size > maxPlain) {
                emitError("Command too long for one secure frame (${bytes.size} > $maxPlain bytes).")
                return
            }
            enqueue(GattOp.WriteRequest(ch.encrypt(bytes)))
        } else {
            if (bytes.size > BleConstants.MAX_COMMAND_BYTES) {
                emitError("Command too long (${bytes.size} > ${BleConstants.MAX_COMMAND_BYTES} bytes).")
                return
            }
            enqueue(GattOp.WriteRequest(bytes))
        }
    }

    /** Read the STATUS characteristic (JSON snapshot — plaintext, outside the channel). */
    fun readStatus() {
        val char = statusChar
        if (char == null || _state.value !is ConnectionState.Ready) {
            emitError("Not connected.")
            return
        }
        enqueue(GattOp.ReadChar(char))
    }

    private fun requestDeviceInfo() {
        val g = gatt ?: return
        val info = g.getService(BleConstants.DEVICE_INFO_SERVICE) ?: return
        listOf(BleConstants.FIRMWARE_CHAR, BleConstants.MODEL_CHAR, BleConstants.MANUFACTURER_CHAR).forEach { uuid ->
            info.getCharacteristic(uuid)?.let { enqueue(GattOp.ReadChar(it)) }
        }
    }

    // --- API-level compatibility shims ----------------------------------------------

    private fun writeCharacteristicCompat(g: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run {
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                char.value = value
                g.writeCharacteristic(char)
            }
        }

    private fun writeDescriptorCompat(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run { descriptor.value = value; g.writeDescriptor(descriptor) }
        }
        if (!ok) fail("Failed to write CCCD descriptor.")
    }

    // --- Helpers --------------------------------------------------------------------

    private fun closeGatt() {
        cancelHandshakeTimeout()
        handler.removeCallbacks(idleFlush)
        channel = null
        secureEstablished = false
        synchronized(rxLock) { rxBuffer.clear() }
        synchronized(opLock) { opQueue.clear(); opInFlight = false }
        if (hasConnectPermission()) gatt?.close()
        gatt = null
        requestChar = null
        responseChar = null
        statusChar = null
        _deviceInfo.value = null
    }

    private fun fail(reason: String) {
        emitError(reason)
        _state.value = ConnectionState.Failed(reason)
        userInitiatedDisconnect = true
        if (hasConnectPermission()) gatt?.disconnect()
    }

    private fun emitInfo(text: String) = _messages.tryEmit(BleMessage(text, BleMessage.Kind.INFO)).let {}
    private fun emitError(text: String) = _messages.tryEmit(BleMessage(text, BleMessage.Kind.ERROR)).let {}

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        else hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        else true

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 6_000L
        private const val IDLE_FLUSH_MS = 250L

        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
