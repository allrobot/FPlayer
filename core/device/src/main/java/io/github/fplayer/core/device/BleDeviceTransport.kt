package io.github.fplayer.core.device

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class BleDeviceSelector(
    val serviceUuid: UUID,
    val writeCharacteristicUuid: UUID,
    val notifyCharacteristicUuid: UUID? = null,
    val confirmedAddress: String? = null,
    val advertisedName: String? = null,
    val writeType: BleWriteType = BleWriteType.WITH_RESPONSE,
) {
    init {
        confirmedAddress?.let { require(it.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) }
        advertisedName?.let { require(it.isNotBlank()) }
    }
}

object TCodeEsp32BleProfile {
    val serviceUuid: UUID = UUID.fromString("ff1b451d-3070-4276-9c81-5dc5ea1043bc")
    val characteristicUuid: UUID = UUID.fromString("c5f1543e-338d-47a0-8525-01e3c621359d")

    fun selector(confirmedAddress: String? = null, advertisedName: String? = "TCODE-ESP32") = BleDeviceSelector(
        serviceUuid = serviceUuid,
        writeCharacteristicUuid = characteristicUuid,
        confirmedAddress = confirmedAddress,
        advertisedName = advertisedName,
        writeType = BleWriteType.WITHOUT_RESPONSE,
    )
}

enum class BleWriteType { WITH_RESPONSE, WITHOUT_RESPONSE }

data class BleDiscoveryResult(val address: String, val name: String?, val rssi: Int)

data class BleTransportConfig(
    val scanTimeoutMs: Int = 5_000,
    val operationTimeoutMs: Int = 1_000,
    val requestedMtu: Int = 247,
) {
    init {
        require(scanTimeoutMs > 0)
        require(operationTimeoutMs > 0)
        require(requestedMtu in 23..517)
    }
}

class BleDiscovery(private val context: Context) {
    fun scan(serviceUuid: UUID, timeoutMs: Int = 5_000, name: String? = null): List<BleDiscoveryResult> {
        require(timeoutMs > 0)
        val manager = context.applicationContext.getSystemService(BluetoothManager::class.java)
            ?: throw TransportException(TransportFailureCode.BLUETOOTH_UNAVAILABLE, "Bluetooth service is unavailable")
        val adapter = manager.adapter
            ?: throw TransportException(TransportFailureCode.BLUETOOTH_UNAVAILABLE, "Bluetooth adapter is unavailable")
        if (!adapter.isEnabled) throw TransportException(TransportFailureCode.BLUETOOTH_DISABLED, "Bluetooth is disabled")
        checkScanPermission(context)
        val scanner = adapter.bluetoothLeScanner
            ?: throw TransportException(TransportFailureCode.BLUETOOTH_UNAVAILABLE, "BLE scanner is unavailable")
        val results = linkedMapOf<String, BleDiscoveryResult>()
        val scanFailure = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val device = result.device
                val resultName = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
                if (name == null || resultName == name) {
                    synchronized(results) { results[device.address] = BleDiscoveryResult(device.address, resultName, result.rssi) }
                }
            }
            override fun onScanFailed(errorCode: Int) { scanFailure.set(true); finished.countDown() }
        }
        val filter = ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(serviceUuid)).apply {
            name?.let(::setDeviceName)
        }.build()
        try {
            scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            finished.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        if (scanFailure.get()) throw TransportException(TransportFailureCode.BLE_SCAN_FAILED, "BLE scan failed")
        return synchronized(results) { results.values.sortedBy { it.address } }
    }

    private companion object {
        fun checkScanPermission(context: Context) {
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                throw TransportException(TransportFailureCode.BLE_PERMISSION_DENIED, "BLE scan permission is required")
            }
            if (Build.VERSION.SDK_INT < 31 && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                throw TransportException(TransportFailureCode.BLE_PERMISSION_DENIED, "BLE scan location permission is required")
            }
        }
    }
}

internal interface BleBackend {
    fun open(selector: BleDeviceSelector, config: BleTransportConfig, connectTimeoutMs: Int, isCancelled: () -> Boolean): BleConnection
}

internal interface BleConnection : Closeable {
    val maximumWritePayload: Int
    fun writeChunk(bytes: ByteArray, writeType: BleWriteType, timeoutMs: Int)
    fun read(timeoutMs: Int): ByteArray?
}

class BleDeviceTransport internal constructor(
    private val backend: BleBackend,
    private val selector: BleDeviceSelector,
    private val bleConfig: BleTransportConfig = BleTransportConfig(),
    config: TransportConfig = TransportConfig(),
    listener: TransportListener? = null,
    receiver: TransportReceiver? = null,
) : QueuedDeviceTransport("ble", config, listener, receiver) {
    @Volatile private var connection: BleConnection? = null

    constructor(
        context: Context,
        selector: BleDeviceSelector,
        bleConfig: BleTransportConfig = BleTransportConfig(),
        config: TransportConfig = TransportConfig(),
        listener: TransportListener? = null,
        receiver: TransportReceiver? = null,
    ) : this(AndroidBleBackend(context.applicationContext), selector, bleConfig, config, listener, receiver)

    override val diagnostics: String
        get() = "ble state=${state.name.lowercase()} pending=$pendingFrameCount mtu=${connection?.maximumWritePayload?.plus(3) ?: 23}"

    override fun openConnection() {
        connection = backend.open(selector, bleConfig, config.connectTimeoutMs) { closing }
    }

    override fun writeFrame(frame: ByteArray) {
        val active = connection ?: throw IOException("BLE connection is closed")
        val chunkSize = active.maximumWritePayload.coerceAtLeast(1)
        var offset = 0
        while (offset < frame.size) {
            if (closing) throw IOException("BLE write cancelled")
            val end = minOf(offset + chunkSize, frame.size)
            active.writeChunk(frame.copyOfRange(offset, end), selector.writeType, bleConfig.operationTimeoutMs)
            offset = end
        }
    }

    override fun monitorConnection() {
        val active = connection ?: return
        while (!closing) {
            active.read(bleConfig.operationTimeoutMs)?.let { receiver?.onBytesReceived(it) }
        }
    }

    override fun closeConnection() {
        runCatching { connection?.close() }
        connection = null
    }
}

private class AndroidBleBackend(private val context: Context) : BleBackend {
    private val app = context.applicationContext
    override fun open(selector: BleDeviceSelector, config: BleTransportConfig, connectTimeoutMs: Int, isCancelled: () -> Boolean): BleConnection {
        return try {
            openChecked(selector, config, connectTimeoutMs, isCancelled)
        } catch (error: SecurityException) {
            throw TransportException(TransportFailureCode.BLE_PERMISSION_DENIED, "BLE permission was denied", error)
        }
    }

    private fun openChecked(selector: BleDeviceSelector, config: BleTransportConfig, connectTimeoutMs: Int, isCancelled: () -> Boolean): BleConnection {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(connectTimeoutMs.toLong())
        val manager = app.getSystemService(BluetoothManager::class.java)
            ?: throw TransportException(TransportFailureCode.BLUETOOTH_UNAVAILABLE, "Bluetooth service is unavailable")
        val adapter = manager.adapter
            ?: throw TransportException(TransportFailureCode.BLUETOOTH_UNAVAILABLE, "Bluetooth adapter is unavailable")
        if (!adapter.isEnabled) throw TransportException(TransportFailureCode.BLUETOOTH_DISABLED, "Bluetooth is disabled")
        checkConnectPermission()
        val device = selector.confirmedAddress?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
            ?: findDevice(adapter, selector, minOf(config.scanTimeoutMs, remainingMillis(deadline)), isCancelled)
            ?: throw TransportException(TransportFailureCode.BLE_DEVICE_NOT_FOUND, "BLE device was not discovered")
        if (isCancelled()) throw IOException("Connect was cancelled")
        return AndroidBleConnection(app, adapter, device, selector, config, remainingMillis(deadline), isCancelled)
    }

    private fun findDevice(adapter: BluetoothAdapter, selector: BleDeviceSelector, scanTimeoutMs: Int, isCancelled: () -> Boolean): BluetoothDevice? {
        if (Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            throw TransportException(TransportFailureCode.BLE_PERMISSION_DENIED, "BLE scan permission is required")
        }
        if (Build.VERSION.SDK_INT < 31 && app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw TransportException(TransportFailureCode.BLE_PERMISSION_DENIED, "BLE scan location permission is required")
        }
        val scanner = adapter.bluetoothLeScanner ?: return null
        val found = AtomicReference<BluetoothDevice>()
        val scanFailed = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val resultName = result.scanRecord?.deviceName
                if ((selector.advertisedName == null || selector.advertisedName == resultName) && result.scanRecord?.serviceUuids?.any { it.uuid == selector.serviceUuid } == true) {
                    found.set(result.device); done.countDown()
                }
            }
            override fun onScanFailed(errorCode: Int) { scanFailed.set(true); done.countDown() }
        }
        val filter = ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(selector.serviceUuid)).apply {
            selector.advertisedName?.let(::setDeviceName)
        }.build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(scanTimeoutMs.toLong())
            while (found.get() == null && !isCancelled()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                done.await(minOf(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1), 50), TimeUnit.MILLISECONDS)
            }
            if (scanFailed.get()) throw TransportException(TransportFailureCode.BLE_SCAN_FAILED, "BLE scan failed")
            return found.get()
        } finally { runCatching { scanner.stopScan(callback) } }
    }

    private fun checkConnectPermission() {
        if (Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw TransportException(TransportFailureCode.BLE_PERMISSION_DENIED, "BLE connect permission is required")
        }
    }

    private fun remainingMillis(deadline: Long): Int = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        .coerceAtLeast(1)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}

private class AndroidBleConnection(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    device: BluetoothDevice,
    selector: BleDeviceSelector,
    private val config: BleTransportConfig,
    connectTimeoutMs: Int,
    isCancelled: () -> Boolean,
) : BleConnection {
    private val incoming = ArrayBlockingQueue<ByteArray>(32)
    private val closed = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    private val mtuReady = CountDownLatch(1)
    private val writeReady = AtomicReference<CountDownLatch>()
    @Volatile private var failure: TransportException? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var negotiatedMtu = 23
    override val maximumWritePayload: Int get() = (negotiatedMtu - 3).coerceAtLeast(20)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!adapter.isEnabled) failure = TransportException(TransportFailureCode.BLUETOOTH_DISABLED, "Bluetooth was disabled")
            else if (status != BluetoothGatt.GATT_SUCCESS) failure = TransportException(TransportFailureCode.BLE_GATT_FAILED, "BLE GATT operation failed")
            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS && failure == null) {
                if (!g.discoverServices()) {
                    failure = TransportException(TransportFailureCode.BLE_SERVICE_NOT_FOUND, "BLE service discovery could not be queued")
                    mtuReady.countDown()
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                failure = failure ?: TransportException(TransportFailureCode.REMOTE_CLOSED, "BLE device disconnected")
            }
            ready.countDown()
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { failure = TransportException(TransportFailureCode.BLE_SERVICE_NOT_FOUND, "BLE services could not be discovered"); mtuReady.countDown(); return }
            val service = g.getService(selector.serviceUuid)
            writeCharacteristic = service?.getCharacteristic(selector.writeCharacteristicUuid)
            notifyCharacteristic = selector.notifyCharacteristicUuid?.let { service?.getCharacteristic(it) }
            if (service == null) failure = TransportException(TransportFailureCode.BLE_SERVICE_NOT_FOUND, "BLE service is unavailable")
            else if (writeCharacteristic == null) failure = TransportException(TransportFailureCode.BLE_CHARACTERISTIC_NOT_FOUND, "BLE write characteristic is unavailable")
            else if (selector.notifyCharacteristicUuid != null && notifyCharacteristic == null) failure = TransportException(TransportFailureCode.BLE_CHARACTERISTIC_NOT_FOUND, "BLE notify characteristic is unavailable")
            if (failure == null) {
                val requiredProperty = if (selector.writeType == BleWriteType.WITH_RESPONSE) BluetoothGattCharacteristic.PROPERTY_WRITE else BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                if (writeCharacteristic!!.properties and requiredProperty == 0) {
                    failure = TransportException(TransportFailureCode.CONFIGURATION_FAILED, "BLE characteristic does not support the selected write type")
                }
            }
            if (failure == null && notifyCharacteristic != null) {
                val properties = notifyCharacteristic!!.properties
                val subscriptionValue = when {
                    properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    else -> null
                }
                if (subscriptionValue == null) {
                    failure = TransportException(TransportFailureCode.BLE_SUBSCRIPTION_FAILED, "BLE characteristic does not support notifications")
                } else if (!g.setCharacteristicNotification(notifyCharacteristic, true)) {
                    failure = TransportException(TransportFailureCode.BLE_SUBSCRIPTION_FAILED, "BLE notification subscription failed")
                }
                val descriptor = notifyCharacteristic!!.descriptors.firstOrNull { it.uuid == CCCD_UUID }
                if (failure == null && descriptor == null) {
                    failure = TransportException(TransportFailureCode.BLE_SUBSCRIPTION_FAILED, "BLE notification descriptor is unavailable")
                } else if (failure == null) {
                    checkNotNull(descriptor)
                    checkNotNull(subscriptionValue)
                    val queued = if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(descriptor, subscriptionValue) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = subscriptionValue
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(descriptor)
                    }
                    if (!queued) failure = TransportException(TransportFailureCode.BLE_SUBSCRIPTION_FAILED, "BLE notification subscription could not be queued")
                    if (failure == null) return
                }
            }
            if (failure == null && !g.requestMtu(config.requestedMtu)) {
                failure = TransportException(TransportFailureCode.BLE_MTU_FAILED, "BLE MTU request could not be queued")
            }
            if (failure != null) mtuReady.countDown()
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            else failure = TransportException(TransportFailureCode.BLE_MTU_FAILED, "BLE MTU negotiation failed")
            mtuReady.countDown()
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { if (status != BluetoothGatt.GATT_SUCCESS) failure = TransportException(TransportFailureCode.BLE_GATT_FAILED, "BLE write failed"); writeReady.getAndSet(null)?.countDown() }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) failure = TransportException(TransportFailureCode.BLE_SUBSCRIPTION_FAILED, "BLE notification subscription failed")
            if (failure == null && !g.requestMtu(config.requestedMtu)) failure = TransportException(TransportFailureCode.BLE_MTU_FAILED, "BLE MTU request could not be queued")
            if (failure != null) mtuReady.countDown()
        }
        @Suppress("DEPRECATION") override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { incoming.offer(characteristic.value.copyOf()) }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { incoming.offer(value.copyOf()) }
    }

    init {
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(connectTimeoutMs.toLong())
            gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) else device.connectGatt(context, false, callback)
            if (!awaitCancellable(ready, remainingMillis(deadline), isCancelled)) throw TransportException(TransportFailureCode.CONNECT_TIMEOUT, "BLE connection timed out")
            if (failure != null) throw failure!!
            if (!awaitCancellable(mtuReady, minOf(config.operationTimeoutMs, remainingMillis(deadline)), isCancelled)) {
                failure?.let { throw it }
                throw TransportException(TransportFailureCode.BLE_MTU_FAILED, "BLE MTU negotiation timed out")
            }
            if (failure != null) throw failure!!
        } catch (error: SecurityException) { close(); throw TransportException(TransportFailureCode.BLE_PERMISSION_DENIED, "BLE permission was denied", error) }
        catch (error: Exception) { close(); throw error }
    }

    override fun writeChunk(bytes: ByteArray, writeType: BleWriteType, timeoutMs: Int) {
        if (closed.get()) throw IOException("BLE connection is closed")
        val g = gatt ?: throw IOException("BLE connection is closed")
        val characteristic = writeCharacteristic ?: throw TransportException(TransportFailureCode.BLE_CHARACTERISTIC_NOT_FOUND, "BLE write characteristic is unavailable")
        val completion = CountDownLatch(1)
        writeReady.set(completion)
        characteristic.writeType = if (writeType == BleWriteType.WITH_RESPONSE) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val androidWriteType = characteristic.writeType
        val queued = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(characteristic, bytes, androidWriteType) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }
        if (!queued) throw TransportException(TransportFailureCode.BLE_GATT_FAILED, "BLE write could not be queued")
        if (!completion.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
            writeReady.compareAndSet(completion, null)
            throw TransportException(TransportFailureCode.WRITE_TIMEOUT, "BLE write timed out")
        }
        failure?.let { throw it }
    }

    override fun read(timeoutMs: Int): ByteArray? {
        failure?.let { throw it }
        return incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    }

    override fun close() { if (!closed.compareAndSet(false, true)) return; runCatching { gatt?.disconnect() }; runCatching { gatt?.close() }; gatt = null; incoming.clear() }
    private fun awaitCancellable(latch: CountDownLatch, timeoutMs: Int, isCancelled: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        while (latch.count > 0) {
            if (isCancelled()) throw IOException("Connect was cancelled")
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            latch.await(minOf(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1), 50), TimeUnit.MILLISECONDS)
        }
        return true
    }
    private fun remainingMillis(deadline: Long): Int = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        .coerceAtLeast(1)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    private companion object { val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") }
}
