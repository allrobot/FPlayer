package io.github.fplayer.core.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class UsbSerialSelector(
    val vendorId: Int,
    val productId: Int,
    val confirmedDeviceId: Int,
    val portIndex: Int = 0,
) {
    init {
        require(vendorId in 0..0xffff)
        require(productId in 0..0xffff)
        require(confirmedDeviceId >= 0)
        require(portIndex >= 0)
    }
}

enum class UsbSerialStopBits { ONE, ONE_POINT_FIVE, TWO }
enum class UsbSerialParity { NONE, ODD, EVEN, MARK, SPACE }
enum class UsbSerialFlowControl { NONE, RTS_CTS, DTR_DSR, XON_XOFF }

data class UsbSerialParameters(
    val baudRate: Int = 115_200,
    val dataBits: Int = 8,
    val stopBits: UsbSerialStopBits = UsbSerialStopBits.ONE,
    val parity: UsbSerialParity = UsbSerialParity.NONE,
    val flowControl: UsbSerialFlowControl = UsbSerialFlowControl.NONE,
    val dtr: Boolean? = null,
    val rts: Boolean? = null,
    val readTimeoutMs: Int = 100,
) {
    init {
        require(baudRate > 0)
        require(dataBits in 5..8)
        require(readTimeoutMs in 1..1_000)
    }
}

data class UsbSerialDeviceDescriptor(
    val vendorId: Int,
    val productId: Int,
    val deviceId: Int,
    val portCount: Int,
)

class UsbSerialDiscovery(context: Context) {
    private val applicationContext = context.applicationContext

    fun discover(): List<UsbSerialDeviceDescriptor> {
        val manager = applicationContext.getSystemService(UsbManager::class.java) ?: return emptyList()
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            .map { driver ->
                val device = driver.device
                UsbSerialDeviceDescriptor(device.vendorId, device.productId, device.deviceId, driver.ports.size)
            }
            .sortedWith(compareBy(UsbSerialDeviceDescriptor::vendorId, UsbSerialDeviceDescriptor::productId, UsbSerialDeviceDescriptor::deviceId))
    }
}

internal interface UsbSerialBackend {
    fun open(
        selector: UsbSerialSelector,
        parameters: UsbSerialParameters,
        permissionTimeoutMs: Int,
        isCancelled: () -> Boolean,
    ): UsbSerialConnection
}

internal interface UsbSerialConnection : Closeable {
    fun read(buffer: ByteArray, timeoutMs: Int): Int
    fun write(bytes: ByteArray, timeoutMs: Int)
}

class UsbSerialDeviceTransport internal constructor(
    private val backend: UsbSerialBackend,
    private val selector: UsbSerialSelector,
    private val parameters: UsbSerialParameters = UsbSerialParameters(),
    config: TransportConfig = TransportConfig(),
    listener: TransportListener? = null,
    receiver: TransportReceiver? = null,
) : QueuedDeviceTransport("usb-serial", config, listener, receiver) {
    @Volatile private var connection: UsbSerialConnection? = null

    constructor(
        context: Context,
        selector: UsbSerialSelector,
        parameters: UsbSerialParameters = UsbSerialParameters(),
        config: TransportConfig = TransportConfig(),
        listener: TransportListener? = null,
        receiver: TransportReceiver? = null,
    ) : this(AndroidUsbSerialBackend(context.applicationContext), selector, parameters, config, listener, receiver)

    override val diagnostics: String
        get() = "usb-serial state=${state.name.lowercase()} pending=$pendingFrameCount baud=${parameters.baudRate}"

    override fun openConnection() {
        connection = backend.open(selector, parameters, config.connectTimeoutMs) { closing }
    }

    override fun writeFrame(frame: ByteArray) {
        val active = connection ?: throw IOException("USB serial connection is closed")
        active.write(frame, config.writeTimeoutMs)
    }

    override fun monitorConnection() {
        val active = connection ?: return
        val buffer = ByteArray(config.maximumFrameBytes)
        while (!closing) {
            val count = active.read(buffer, parameters.readTimeoutMs)
            if (count < 0) throw TransportException(TransportFailureCode.DEVICE_DETACHED, "USB device detached")
            if (count > 0) receiver?.onBytesReceived(buffer.copyOf(count))
        }
    }

    override fun closeConnection() {
        runCatching { connection?.close() }
        connection = null
    }
}

private class AndroidUsbSerialBackend(context: Context) : UsbSerialBackend {
    private val applicationContext = context.applicationContext
    private val manager = applicationContext.getSystemService(UsbManager::class.java)
        ?: throw IllegalStateException("USB service is unavailable")

    override fun open(
        selector: UsbSerialSelector,
        parameters: UsbSerialParameters,
        permissionTimeoutMs: Int,
        isCancelled: () -> Boolean,
    ): UsbSerialConnection {
        val device = manager.deviceList.values.firstOrNull {
            it.vendorId == selector.vendorId &&
                it.productId == selector.productId &&
                it.deviceId == selector.confirmedDeviceId
        } ?: throw TransportException(TransportFailureCode.DEVICE_NOT_FOUND, "selected USB device is not attached")
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: throw TransportException(TransportFailureCode.UNSUPPORTED_DEVICE, "selected USB device has no supported serial driver")
        val port = driver.ports.getOrNull(selector.portIndex)
            ?: throw TransportException(TransportFailureCode.UNSUPPORTED_DEVICE, "selected USB serial port is unavailable")

        ensurePermission(device, permissionTimeoutMs, isCancelled)
        if (isCancelled()) throw IOException("Connect was cancelled")
        if (manager.deviceList.values.none { it.deviceId == device.deviceId }) {
            throw TransportException(TransportFailureCode.DEVICE_DETACHED, "USB device detached during connect")
        }

        val usbConnection = manager.openDevice(device)
            ?: throw TransportException(TransportFailureCode.CONNECT_FAILED, "USB device could not be opened")
        val detached = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val receiver = detachReceiver(device, detached, port, usbConnection)
        registerReceiver(receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        try {
            port.open(usbConnection)
            port.setParameters(
                parameters.baudRate,
                parameters.dataBits,
                parameters.stopBits.driverValue,
                parameters.parity.driverValue,
            )
            port.setFlowControl(parameters.flowControl.driverValue)
            parameters.dtr?.let(port::setDTR)
            parameters.rts?.let(port::setRTS)
            if (isCancelled()) throw IOException("Connect was cancelled")
            return AndroidUsbSerialConnection(applicationContext, port, usbConnection, receiver, detached, closed)
        } catch (error: Exception) {
            if (closed.compareAndSet(false, true)) runCatching { applicationContext.unregisterReceiver(receiver) }
            runCatching { port.close() }
            runCatching { usbConnection.close() }
            when (error) {
                is TransportException -> throw error
                is SecurityException -> throw TransportException(TransportFailureCode.PERMISSION_DENIED, "USB permission was revoked", error)
                is UnsupportedOperationException -> throw TransportException(TransportFailureCode.CONFIGURATION_FAILED, "USB serial parameters are unsupported", error)
                else -> throw TransportException(TransportFailureCode.CONNECT_FAILED, "USB serial port setup failed", error)
            }
        }
    }

    private fun ensurePermission(device: UsbDevice, timeoutMs: Int, isCancelled: () -> Boolean) {
        if (manager.hasPermission(device)) return
        val requestId = permissionRequestIds.incrementAndGet()
        val action = "${applicationContext.packageName}.USB_PERMISSION.$requestId"
        val result = AtomicReference<Boolean?>(null)
        val received = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != action) return
                val receivedDevice = intent.usbDeviceExtra()
                if (receivedDevice?.deviceId != device.deviceId) return
                result.set(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                received.countDown()
            }
        }
        registerReceiver(receiver, IntentFilter(action))
        try {
            val intent = Intent(action).setPackage(applicationContext.packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                requestId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            manager.requestPermission(device, pendingIntent)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
            while (result.get() == null) {
                if (isCancelled()) throw IOException("Connect was cancelled")
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw TransportException(TransportFailureCode.CONNECT_TIMEOUT, "USB permission request timed out")
                received.await(minOf(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1), 50), TimeUnit.MILLISECONDS)
            }
            if (result.get() != true || !manager.hasPermission(device)) {
                throw TransportException(TransportFailureCode.PERMISSION_DENIED, "USB permission was denied")
            }
        } finally {
            runCatching { applicationContext.unregisterReceiver(receiver) }
        }
    }

    private fun detachReceiver(
        device: UsbDevice,
        detached: AtomicBoolean,
        port: UsbSerialPort,
        connection: UsbDeviceConnection,
    ) = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            if (intent.usbDeviceExtra()?.deviceId != device.deviceId) return
            detached.set(true)
            runCatching { port.close() }
            runCatching { connection.close() }
        }
    }

    private fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            applicationContext.registerReceiver(receiver, filter)
        }
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private val UsbSerialStopBits.driverValue: Int
        get() = when (this) {
            UsbSerialStopBits.ONE -> UsbSerialPort.STOPBITS_1
            UsbSerialStopBits.ONE_POINT_FIVE -> UsbSerialPort.STOPBITS_1_5
            UsbSerialStopBits.TWO -> UsbSerialPort.STOPBITS_2
        }

    private val UsbSerialParity.driverValue: Int
        get() = when (this) {
            UsbSerialParity.NONE -> UsbSerialPort.PARITY_NONE
            UsbSerialParity.ODD -> UsbSerialPort.PARITY_ODD
            UsbSerialParity.EVEN -> UsbSerialPort.PARITY_EVEN
            UsbSerialParity.MARK -> UsbSerialPort.PARITY_MARK
            UsbSerialParity.SPACE -> UsbSerialPort.PARITY_SPACE
        }

    private val UsbSerialFlowControl.driverValue: UsbSerialPort.FlowControl
        get() = when (this) {
            UsbSerialFlowControl.NONE -> UsbSerialPort.FlowControl.NONE
            UsbSerialFlowControl.RTS_CTS -> UsbSerialPort.FlowControl.RTS_CTS
            UsbSerialFlowControl.DTR_DSR -> UsbSerialPort.FlowControl.DTR_DSR
            UsbSerialFlowControl.XON_XOFF -> UsbSerialPort.FlowControl.XON_XOFF
        }

    private companion object {
        val permissionRequestIds = AtomicInteger()
    }
}

private class AndroidUsbSerialConnection(
    private val context: Context,
    private val port: UsbSerialPort,
    private val usbConnection: UsbDeviceConnection,
    private val receiver: BroadcastReceiver,
    private val detached: AtomicBoolean,
    private val closed: AtomicBoolean,
) : UsbSerialConnection {
    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        ensureAttached()
        return try {
            port.read(buffer, timeoutMs)
        } catch (error: IOException) {
            if (detached.get()) throw TransportException(TransportFailureCode.DEVICE_DETACHED, "USB device detached", error)
            throw TransportException(TransportFailureCode.READ_FAILED, "USB serial read failed", error)
        }
    }

    override fun write(bytes: ByteArray, timeoutMs: Int) {
        ensureAttached()
        try {
            port.write(bytes, timeoutMs)
        } catch (error: SerialTimeoutException) {
            throw TransportException(TransportFailureCode.WRITE_TIMEOUT, "USB serial write timed out", error)
        } catch (error: IOException) {
            if (detached.get()) throw TransportException(TransportFailureCode.DEVICE_DETACHED, "USB device detached", error)
            throw TransportException(TransportFailureCode.WRITE_FAILED, "USB serial write failed", error)
        }
    }

    private fun ensureAttached() {
        if (detached.get()) throw TransportException(TransportFailureCode.DEVICE_DETACHED, "USB device detached")
        if (closed.get()) throw IOException("USB serial connection is closed")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { port.close() }
        runCatching { usbConnection.close() }
    }
}
