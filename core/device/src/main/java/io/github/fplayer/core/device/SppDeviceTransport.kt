package io.github.fplayer.core.device

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class SppDeviceSelector(
    val confirmedAddress: String,
    val serviceUuid: UUID = STANDARD_SPP_UUID,
) {
    init {
        require(confirmedAddress.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")))
    }

    companion object {
        val STANDARD_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}

object TCodeEsp32SppProfile {
    const val deviceName = "TCodeESP32"
    val serviceUuid: UUID = SppDeviceSelector.STANDARD_SPP_UUID

    fun selector(confirmedAddress: String) = SppDeviceSelector(
        confirmedAddress = confirmedAddress,
        serviceUuid = serviceUuid,
    )
}

data class SppPairedDevice(val address: String, val name: String?)

class SppDiscovery(private val context: Context) {
    fun pairedDevices(name: String? = null): List<SppPairedDevice> {
        name?.let { require(it.isNotBlank()) }
        return try {
            val adapter = requireSppAdapter(context.applicationContext)
            adapter.bondedDevices
                .asSequence()
                .filter { it.bondState == BluetoothDevice.BOND_BONDED }
                .map { SppPairedDevice(it.address, it.name) }
                .filter { name == null || it.name == name }
                .sortedBy { it.address }
                .toList()
        } catch (error: SecurityException) {
            throw TransportException(
                TransportFailureCode.SPP_PERMISSION_DENIED,
                "Bluetooth paired-device permission was denied",
                error,
            )
        }
    }
}

internal interface SppBackend {
    fun open(selector: SppDeviceSelector, connectTimeoutMs: Int, isCancelled: () -> Boolean): SppConnection
}

internal interface SppConnection : Closeable {
    fun read(buffer: ByteArray): Int
    fun write(bytes: ByteArray)
}

class SppDeviceTransport internal constructor(
    private val backend: SppBackend,
    private val selector: SppDeviceSelector,
    config: TransportConfig = TransportConfig(),
    listener: TransportListener? = null,
    receiver: TransportReceiver? = null,
) : QueuedDeviceTransport("spp", config, listener, receiver) {
    @Volatile private var connection: SppConnection? = null

    constructor(
        context: Context,
        selector: SppDeviceSelector,
        config: TransportConfig = TransportConfig(),
        listener: TransportListener? = null,
        receiver: TransportReceiver? = null,
    ) : this(AndroidSppBackend(context.applicationContext), selector, config, listener, receiver)

    override val diagnostics: String
        get() = "spp state=${state.name.lowercase()} pending=$pendingFrameCount"

    override fun openConnection() {
        connection = backend.open(selector, config.connectTimeoutMs) { closing }
    }

    override fun writeFrame(frame: ByteArray) {
        connection?.write(frame) ?: throw IOException("SPP connection is closed")
    }

    override fun monitorConnection() {
        val active = connection ?: return
        val buffer = ByteArray(config.maximumFrameBytes)
        while (!closing) {
            val count = active.read(buffer)
            if (count < 0) return
            if (count > 0) receiver?.onBytesReceived(buffer.copyOf(count))
        }
    }

    override fun closeConnection() {
        runCatching { connection?.close() }
        connection = null
    }
}

private class AndroidSppBackend(context: Context) : SppBackend {
    private val app = context.applicationContext

    override fun open(
        selector: SppDeviceSelector,
        connectTimeoutMs: Int,
        isCancelled: () -> Boolean,
    ): SppConnection {
        return try {
            openChecked(selector, connectTimeoutMs, isCancelled)
        } catch (error: SecurityException) {
            throw TransportException(
                TransportFailureCode.SPP_PERMISSION_DENIED,
                "Bluetooth connection permission was denied",
                error,
            )
        } catch (error: SocketTimeoutException) {
            throw error
        } catch (error: TransportException) {
            throw error
        } catch (error: IOException) {
            throw TransportException(TransportFailureCode.CONNECT_FAILED, "SPP connection failed", error)
        }
    }

    private fun openChecked(
        selector: SppDeviceSelector,
        connectTimeoutMs: Int,
        isCancelled: () -> Boolean,
    ): SppConnection {
        val adapter = requireSppAdapter(app)
        val device = adapter.bondedDevices.firstOrNull {
            it.bondState == BluetoothDevice.BOND_BONDED &&
                it.address.equals(selector.confirmedAddress, ignoreCase = true)
        } ?: throw TransportException(
            TransportFailureCode.SPP_DEVICE_NOT_PAIRED,
            "Selected Bluetooth device is not paired",
        )
        if (isCancelled()) throw IOException("SPP connect was cancelled")
        val socket = try {
            device.createRfcommSocketToServiceRecord(selector.serviceUuid)
        } catch (error: IOException) {
            throw TransportException(
                TransportFailureCode.SPP_SOCKET_FAILED,
                "SPP RFCOMM socket could not be created",
                error,
            )
        }
        try {
            connectSocket(socket, adapter, connectTimeoutMs, isCancelled)
            return AndroidSppConnection(adapter, socket)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun connectSocket(
        socket: BluetoothSocket,
        adapter: BluetoothAdapter,
        timeoutMs: Int,
        isCancelled: () -> Boolean,
    ) {
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Exception?>()
        val connector = Thread({
            try {
                socket.connect()
            } catch (error: Exception) {
                failure.set(error)
            } finally {
                completed.countDown()
            }
        }, "spp-rfcomm-connect").also { it.isDaemon = true; it.start() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        while (!completed.await(25, TimeUnit.MILLISECONDS)) {
            if (isCancelled()) {
                runCatching { socket.close() }
                connector.join(250)
                throw IOException("SPP connect was cancelled")
            }
            if (System.nanoTime() >= deadline) {
                runCatching { socket.close() }
                connector.join(250)
                throw SocketTimeoutException("SPP connect timed out")
            }
        }
        failure.get()?.let { error ->
            if (!adapter.isEnabled) {
                throw TransportException(TransportFailureCode.BLUETOOTH_DISABLED, "Bluetooth was disabled", error)
            }
            when (error) {
                is SecurityException -> throw error
                is IOException -> throw error
                else -> throw IOException("SPP connect failed", error)
            }
        }
    }
}

private class AndroidSppConnection(
    private val adapter: BluetoothAdapter,
    private val socket: BluetoothSocket,
) : SppConnection {
    private val input = BufferedInputStream(socket.inputStream)
    private val output = BufferedOutputStream(socket.outputStream)

    override fun read(buffer: ByteArray): Int {
        return try {
            input.read(buffer)
        } catch (error: IOException) {
            throw connectionFailure(TransportFailureCode.READ_FAILED, "SPP read failed", error)
        }
    }

    override fun write(bytes: ByteArray) {
        try {
            output.write(bytes)
            output.flush()
        } catch (error: IOException) {
            throw connectionFailure(TransportFailureCode.WRITE_FAILED, "SPP write failed", error)
        }
    }

    private fun connectionFailure(
        fallback: TransportFailureCode,
        message: String,
        error: IOException,
    ): TransportException {
        return try {
            if (!adapter.isEnabled) {
                TransportException(TransportFailureCode.BLUETOOTH_DISABLED, "Bluetooth was disabled", error)
            } else {
                TransportException(fallback, message, error)
            }
        } catch (permissionError: SecurityException) {
            TransportException(
                TransportFailureCode.SPP_PERMISSION_DENIED,
                "Bluetooth connection permission was denied",
                permissionError,
            )
        }
    }

    override fun close() {
        runCatching { socket.close() }
        runCatching { input.close() }
        runCatching { output.close() }
    }
}

private fun requireSppAdapter(context: Context): BluetoothAdapter {
    if (Build.VERSION.SDK_INT >= 31 &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) {
        throw TransportException(
            TransportFailureCode.SPP_PERMISSION_DENIED,
            "Bluetooth connection permission is required",
        )
    }
    val manager = context.getSystemService(BluetoothManager::class.java)
        ?: throw TransportException(
            TransportFailureCode.BLUETOOTH_UNAVAILABLE,
            "Bluetooth service is unavailable",
        )
    val adapter = manager.adapter
        ?: throw TransportException(
            TransportFailureCode.BLUETOOTH_UNAVAILABLE,
            "Bluetooth adapter is unavailable",
        )
    if (!adapter.isEnabled) {
        throw TransportException(TransportFailureCode.BLUETOOTH_DISABLED, "Bluetooth is disabled")
    }
    return adapter
}
