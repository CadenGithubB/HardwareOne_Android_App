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
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
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
 * Threading: GATT callbacks arrive on a binder thread. We only ever touch
 * [MutableStateFlow.value] (thread-safe) and [MutableSharedFlow.tryEmit] (thread-safe,
 * non-suspending) from those callbacks, so no extra synchronisation is needed for state.
 * GATT *operations* are serialised through a tiny queue ([opQueue]) because Android
 * allows only one outstanding GATT request at a time.
 *
 * All BLE calls are guarded by [hasConnectPermission]/[hasScanPermission]; the Activity
 * is responsible for actually requesting them at runtime.
 */
@SuppressLint("MissingPermission") // every BLE call site is guarded by a runtime check.
class BleManager(context: Context) {

    private val appContext = context.applicationContext

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

    /** Plain-text replies from the device (one notification == one emission). */
    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    /** Connection lifecycle / info / error lines for the console. */
    private val _messages = MutableSharedFlow<BleMessage>(extraBufferCapacity = 128)
    val messages: SharedFlow<BleMessage> = _messages.asSharedFlow()

    val isBluetoothSupported: Boolean get() = adapter != null
    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    // --- Internal connection state --------------------------------------------------

    private var gatt: BluetoothGatt? = null
    private var requestChar: BluetoothGattCharacteristic? = null
    private var responseChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null

    private var lastDevice: BluetoothDevice? = null
    private var currentName: String = "HardwareOne"
    private var negotiatedMtu: Int = 23
    private var userInitiatedDisconnect = false

    // --- Scanning -------------------------------------------------------------------

    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::addScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            emitError("Scan failed (code $errorCode).")
            _state.value = ConnectionState.Disconnected
        }
    }

    private fun addScanResult(result: ScanResult) {
        val record = result.scanRecord
        val name = record?.deviceName ?: "HardwareOne"
        val device = DiscoveredDevice(
            address = result.device.address,
            name = name,
            rssi = result.rssi,
        )
        val current = _scanResults.value
        val idx = current.indexOfFirst { it.address == device.address }
        _scanResults.value =
            if (idx >= 0) current.toMutableList().also { it[idx] = device }
            else current + device
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
        // Match by advertised service UUID, not by name.
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
        if (_state.value is ConnectionState.Scanning) {
            _state.value = ConnectionState.Disconnected
        }
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
        closeGatt() // drop any previous session first

        lastDevice = device
        currentName = runCatching { device.name }.getOrNull() ?: "HardwareOne"
        userInitiatedDisconnect = false
        _authenticated.value = false

        _state.value = ConnectionState.Connecting(currentName)
        emitInfo("Connecting to $currentName (${device.address})…")
        // autoConnect=false for a fast, direct connection; explicit LE transport.
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Reconnect to the most recently used device, if any. */
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
            g.disconnect() // onConnectionStateChange(DISCONNECTED) closes the gatt
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
                    // Ask for a low-latency connection interval for snappy CLI I/O.
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
            // Firmware does NOT start MTU negotiation — the app must.
            g.requestMtu(BleConstants.TARGET_MTU)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            val payload = negotiatedMtu - 3
            emitInfo("MTU = $negotiatedMtu (max $payload bytes/notification).")
            _state.value = ConnectionState.EnablingNotifications(currentName)
            enableResponseNotifications(g)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != BleConstants.CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Failed to enable notifications (status $status).")
                return
            }
            _state.value = ConnectionState.Ready(currentName, negotiatedMtu)
            emitInfo("Ready. Log in with:  login <username> <password>")
            // Pull device-information (firmware version etc.) as the first queued reads.
            requestDeviceInfo()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitError("Write failed (status $status).")
            }
            onOpComplete()
        }

        // Notifications: Android 13+ delivers the value directly; older devices use the
        // deprecated overload with characteristic.value. We handle both.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = handleNotification(characteristic, value)

        // Pre-API-33 delivery path.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) = handleNotification(characteristic, characteristic.value ?: ByteArray(0))

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleRead(characteristic, value, status)
            onOpComplete()
        }

        // Pre-API-33 delivery path.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handleRead(characteristic, characteristic.value ?: ByteArray(0), status)
            onOpComplete()
        }
    }

    private fun enableResponseNotifications(g: BluetoothGatt) {
        val char = responseChar ?: return fail("Response characteristic missing.")
        // Step 1: tell Android to route notifications for this characteristic to us.
        if (!g.setCharacteristicNotification(char, true)) {
            fail("setCharacteristicNotification returned false.")
            return
        }
        // Step 2: write 0x0001 to the CCCD. Skip this and the device sends nothing.
        val cccd = char.getDescriptor(BleConstants.CCCD)
        if (cccd == null) {
            fail("Response CCCD (0x2902) not found.")
            return
        }
        writeDescriptorCompat(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    private fun handleNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != BleConstants.RESPONSE_CHAR) return
        val text = value.toString(Charsets.UTF_8)
        updateAuthFromReply(text)
        _incoming.tryEmit(text)
    }

    private fun updateAuthFromReply(text: String) {
        when {
            text.contains("Login successful") -> _authenticated.value = true
            text.contains("Authentication required") -> _authenticated.value = false
            text.contains("Logged out", ignoreCase = true) -> _authenticated.value = false
        }
    }

    private fun handleRead(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS) return
        val text = value.toString(Charsets.UTF_8).trim()
        if (text.isEmpty()) return
        when (characteristic.uuid) {
            BleConstants.STATUS_CHAR -> emitInfo("status: $text")
            BleConstants.FIRMWARE_CHAR -> emitInfo("firmware: $text")
            BleConstants.MODEL_CHAR -> emitInfo("model: $text")
            BleConstants.MANUFACTURER_CHAR -> emitInfo("manufacturer: $text")
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

    private fun enqueue(op: GattOp) {
        synchronized(opLock) {
            opQueue.addLast(op)
            runNextLocked()
        }
    }

    private fun onOpComplete() {
        synchronized(opLock) {
            opInFlight = false
            runNextLocked()
        }
    }

    private fun runNextLocked() {
        if (opInFlight) return
        val g = gatt ?: run { opQueue.clear(); return }
        val op = opQueue.removeFirstOrNull() ?: return
        opInFlight = true
        val ok = when (op) {
            is GattOp.WriteRequest -> {
                val char = requestChar
                if (char == null) false
                else writeCharacteristicCompat(g, char, op.bytes)
            }
            is GattOp.ReadChar -> g.readCharacteristic(op.characteristic)
        }
        if (!ok) {
            // The framework rejected the request synchronously — don't wait for a callback.
            opInFlight = false
            emitError("BLE operation could not be started.")
            runNextLocked()
        }
    }

    /** Send a free-text CLI command (e.g. "help", or "login <user> <pass>"). */
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
        if (bytes.size > BleConstants.MAX_COMMAND_BYTES) {
            emitError("Command too long (${bytes.size} > ${BleConstants.MAX_COMMAND_BYTES} bytes).")
            return
        }
        enqueue(GattOp.WriteRequest(bytes))
    }

    /** Read the STATUS characteristic (JSON snapshot). */
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
        listOf(
            BleConstants.FIRMWARE_CHAR,
            BleConstants.MODEL_CHAR,
            BleConstants.MANUFACTURER_CHAR,
        ).forEach { uuid ->
            info.getCharacteristic(uuid)?.let { enqueue(GattOp.ReadChar(it)) }
        }
    }

    // --- API-level compatibility shims ----------------------------------------------

    private fun writeCharacteristicCompat(
        g: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                char,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            char.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
    }

    private fun writeDescriptorCompat(
        g: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ) {
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
        if (!ok) fail("Failed to write CCCD descriptor.")
    }

    // --- Helpers --------------------------------------------------------------------

    private fun closeGatt() {
        synchronized(opLock) {
            opQueue.clear()
            opInFlight = false
        }
        if (hasConnectPermission()) gatt?.close()
        gatt = null
        requestChar = null
        responseChar = null
        statusChar = null
    }

    private fun fail(reason: String) {
        emitError(reason)
        _state.value = ConnectionState.Failed(reason)
        userInitiatedDisconnect = true // suppress the "device disconnected" follow-up
        if (hasConnectPermission()) gatt?.disconnect()
    }

    private fun emitInfo(text: String) {
        _messages.tryEmit(BleMessage(text, BleMessage.Kind.INFO))
    }

    private fun emitError(text: String) {
        _messages.tryEmit(BleMessage(text, BleMessage.Kind.ERROR))
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED

    fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true // legacy BLUETOOTH permission is install-time on API <= 30
        }

    companion object {
        /** The runtime permissions to request for the current API level. */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
