package io.github.fplayer.core.device

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

abstract class QueuedDeviceTransport(
    private val label: String,
    protected val config: TransportConfig,
    private val listener: TransportListener?,
    protected val receiver: TransportReceiver? = null,
) : DeviceTransport {
    @Volatile private var timingListener: TransportTimingListener? = null
    private val queue = BoundedFrameQueue(config.maximumPendingFrames)
    @Volatile private var transportState = TransportState.DISCONNECTED
    @Volatile private var worker: Thread? = null
    @Volatile private var connectionMonitor: Thread? = null
    @Volatile protected var closing = false
    @Volatile private var connectionEpoch = 0L
    @Volatile private var connectCallActive = false
    private val writeWatchdogTriggered = AtomicBoolean(false)
    private val writeWatchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "$label-write-watchdog").also { it.isDaemon = true }
    }

    override val state: TransportState get() = transportState
    override val pendingFrameCount: Int get() = queue.size()
    override val diagnostics: String get() = "$label state=${transportState.name.lowercase()} pending=${queue.size()}"

    override fun setTimingListener(listener: TransportTimingListener?) {
        timingListener = listener
    }

    /** Adapter implementations call this only for a correlated response/echo. */
    protected fun emitRoundTripSample(sentAtMonotonicMs: Long, receivedAtMonotonicMs: Long) {
        if (sentAtMonotonicMs < 0L || receivedAtMonotonicMs < sentAtMonotonicMs) return
        timingListener?.onRoundTripSample(sentAtMonotonicMs, receivedAtMonotonicMs)
    }

    protected abstract fun openConnection()
    protected abstract fun writeFrame(frame: ByteArray)
    protected abstract fun closeConnection()
    protected open fun monitorConnection() {
        while (!closing) Thread.sleep(100)
    }

    override fun connect() {
        val attemptEpoch = synchronized(this) {
            check(transportState != TransportState.CLOSED) { "Transport is closed" }
            if (transportState == TransportState.CONNECTED || transportState == TransportState.CONNECTING) return
            check(!connectCallActive) { "Previous connect cancellation is still completing" }
            connectCallActive = true
                closing = false
                writeWatchdogTriggered.set(false)
                transition(TransportState.CONNECTING, null)
            ++connectionEpoch
        }
        try {
            openConnection()
            synchronized(this) {
                if (closing || transportState != TransportState.CONNECTING || attemptEpoch != connectionEpoch) {
                    throw IOException("Connect was cancelled")
                }
                transition(TransportState.CONNECTED, null)
                worker = Thread({ runWriter(attemptEpoch) }, "$label-writer").also { it.isDaemon = true; it.start() }
                connectionMonitor = Thread({ runMonitor(attemptEpoch) }, "$label-monitor").also { it.isDaemon = true; it.start() }
            }
        } catch (error: Exception) {
            queue.clear()
            synchronized(this) {
                if (!closing && transportState == TransportState.CONNECTING && attemptEpoch == connectionEpoch) {
                    transition(TransportState.FAILED, failureFor(error, TransportFailureCode.CONNECT_FAILED))
                }
            }
            closeConnectionQuietly()
            throw error
        } finally {
            connectCallActive = false
        }
    }

    @Synchronized
    override fun write(frame: ByteArray, priority: DeviceFramePriority) {
        if (frame.size > config.maximumFrameBytes) {
            notifyFailure(TransportFailureCode.FRAME_TOO_LARGE, "frame exceeds configured byte limit")
            throw IOException("Frame exceeds configured limit")
        }
        if (transportState != TransportState.CONNECTED) throw IOException("Transport is not connected")
        if (!queue.offer(QueuedFrame(frame.copyOf(), priority))) {
            notifyFailure(TransportFailureCode.BACKPRESSURE, "bounded frame queue is full")
            throw IOException("Transport frame queue is full")
        }
    }

    private fun runWriter(epoch: Long) {
        try {
            while (!closing && epoch == connectionEpoch) {
                val frame = queue.poll()
                if (frame == null) {
                    Thread.sleep(5)
                    continue
                }
                try {
                    timedWrite(frame.bytes)
                } catch (error: Exception) {
                    if (epoch != connectionEpoch) return
                    queue.clear()
                    disconnectInternal(
                        TransportState.FAILED,
                        epoch,
                        failure = failureFor(error, TransportFailureCode.WRITE_FAILED),
                    )
                    return
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun timedWrite(bytes: ByteArray) {
        val timedOut = AtomicBoolean(false)
        writeWatchdogTriggered.set(false)
        val timeoutTask = writeWatchdog.schedule({
            timedOut.set(true)
            writeWatchdogTriggered.set(true)
            closeConnectionQuietly()
        }, config.writeTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        try {
            writeFrame(bytes)
        } catch (error: Exception) {
            if (timedOut.get()) throw SocketTimeoutException("write timed out")
            throw error
        } finally {
            timeoutTask.cancel(false)
        }
        if (timedOut.get()) throw SocketTimeoutException("write timed out")
    }

    private fun runMonitor(epoch: Long) {
        try {
            monitorConnection()
            if (!closing && epoch == connectionEpoch && !writeWatchdogTriggered.get()) remoteClosed(epoch)
        } catch (error: Exception) {
            if (!closing && epoch == connectionEpoch && !writeWatchdogTriggered.get()) {
                remoteClosed(epoch, failureFor(error, TransportFailureCode.REMOTE_CLOSED))
            }
        }
    }

    @Synchronized override fun disconnect() { disconnectInternal(TransportState.DISCONNECTED) }

    @Synchronized
    private fun disconnectInternal(
        finalState: TransportState,
        expectedEpoch: Long? = null,
        failure: TransportFailure? = null,
    ) {
        if (expectedEpoch != null && expectedEpoch != connectionEpoch) return
        if (transportState == TransportState.CLOSED && finalState != TransportState.CLOSED) return
        closing = true
        connectionEpoch += 1
        queue.clear()
        val oldWorker = worker
        val oldMonitor = connectionMonitor
        oldWorker?.interrupt()
        oldMonitor?.interrupt()
        closeConnectionQuietly()
        awaitExit(oldWorker)
        awaitExit(oldMonitor)
        worker = null
        connectionMonitor = null
        transition(finalState, failure)
    }

    override fun close() {
        synchronized(this) {
            if (transportState == TransportState.CLOSED) return
            disconnectInternal(TransportState.CLOSED)
            writeWatchdog.shutdownNow()
        }
    }

    private fun remoteClosed(
        epoch: Long,
        failure: TransportFailure = TransportFailure(TransportFailureCode.REMOTE_CLOSED, "remote endpoint closed"),
    ) {
        queue.clear()
        disconnectInternal(
            TransportState.FAILED,
            epoch,
            failure,
        )
    }

    private fun closeConnectionQuietly() = runCatching { closeConnection() }
    private fun awaitExit(thread: Thread?) {
        if (thread == null || thread === Thread.currentThread()) return
        runCatching { thread.join((config.writeTimeoutMs + 100L).coerceAtMost(5_000L)) }
    }
    private fun transition(state: TransportState, failure: TransportFailure?) {
        transportState = state
        listener?.onStateChanged(state, failure)
    }
    protected fun notifyFailure(code: TransportFailureCode, message: String) {
        listener?.onStateChanged(transportState, TransportFailure(code, message))
    }
    private fun failureFor(error: Exception, fallbackCode: TransportFailureCode): TransportFailure = when (error) {
        is TransportException -> TransportFailure(error.failureCode, error.safeMessage)
        is SocketTimeoutException -> {
            val code = if (fallbackCode == TransportFailureCode.CONNECT_FAILED) {
                TransportFailureCode.CONNECT_TIMEOUT
            } else {
                TransportFailureCode.WRITE_TIMEOUT
            }
            TransportFailure(code, if (code == TransportFailureCode.CONNECT_TIMEOUT) "connect timed out" else "write timed out")
        }
        else -> TransportFailure(fallbackCode, "${error::class.simpleName ?: label} failure")
    }
}

class TcpDeviceTransport(
    private val host: String,
    private val port: Int,
    config: TransportConfig = TransportConfig(),
    listener: TransportListener? = null,
    receiver: TransportReceiver? = null,
) : QueuedDeviceTransport("tcp", config, listener, receiver) {
    @Volatile private var socket: Socket? = null
    @Volatile private var output: BufferedOutputStream? = null
    init { require(port in 1..65535) }
    override val diagnostics: String get() = "tcp ${if (state == TransportState.CONNECTED) "connected" else state.name.lowercase()} pending=$pendingFrameCount"
    override fun openConnection() {
        val candidate = Socket()
        socket = candidate
        candidate.connect(InetSocketAddress(host, port), config.connectTimeoutMs)
        candidate.soTimeout = config.writeTimeoutMs
        output = BufferedOutputStream(candidate.getOutputStream())
    }
    override fun writeFrame(frame: ByteArray) {
        val out = output ?: throw IOException("socket is closed")
        out.write(frame); out.flush()
    }
    override fun monitorConnection() {
        val stream = socket?.getInputStream() ?: return
        val buffer = ByteArray(config.maximumFrameBytes)
        while (!closing) {
            try {
                val count = stream.read(buffer)
                if (count < 0) return
                if (count > 0) receiver?.onBytesReceived(buffer.copyOf(count))
            } catch (_: SocketTimeoutException) {
                continue
            }
        }
    }
    override fun closeConnection() { runCatching { socket?.close() }; runCatching { output?.close() }; output = null; socket = null }
}

class UdpDeviceTransport(
    private val host: String,
    private val port: Int,
    config: TransportConfig = TransportConfig(),
    listener: TransportListener? = null,
    receiver: TransportReceiver? = null,
) : QueuedDeviceTransport("udp", config, listener, receiver) {
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var address: InetSocketAddress? = null
    init { require(port in 1..65535) }
    override val diagnostics: String get() = "udp ${if (state == TransportState.CONNECTED) "ready" else state.name.lowercase()} pending=$pendingFrameCount"
    override fun openConnection() {
        address = InetSocketAddress(host, port)
        socket = DatagramSocket().also { it.soTimeout = config.writeTimeoutMs; it.connect(address) }
    }
    override fun writeFrame(frame: ByteArray) { val target = address ?: throw IOException("udp is closed"); socket?.send(DatagramPacket(frame, frame.size, target)) ?: throw IOException("udp is closed") }
    override fun closeConnection() { socket?.close(); socket = null; address = null }
    override fun monitorConnection() {
        val buffer = ByteArray(config.maximumFrameBytes)
        while (!closing) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket?.receive(packet) ?: return
                receiver?.onBytesReceived(packet.data.copyOf(packet.length))
            } catch (_: SocketTimeoutException) {
                continue
            }
        }
    }
}

class RawWebSocketDeviceTransport(
    private val host: String,
    private val port: Int,
    private val path: String = "/ws",
    private val secure: Boolean = false,
    config: TransportConfig = TransportConfig(),
    listener: TransportListener? = null,
    receiver: TransportReceiver? = null,
    private val monotonicClockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : QueuedDeviceTransport("websocket", config, listener, receiver) {
    @Volatile private var socket: Socket? = null
    @Volatile private var input: BufferedInputStream? = null
    @Volatile private var output: BufferedOutputStream? = null
    private var frameLock = Any()
    private val timingProbeIds = AtomicLong(0L)
    private val pendingTimingProbes = mutableMapOf<Long, Long>()
    @Volatile private var lastTimingProbeMs = Long.MIN_VALUE
    init { require(port in 1..65535); require(path.startsWith('/')); require(path.length <= 256) }
    override val diagnostics: String get() = "websocket ${if (state == TransportState.CONNECTED) "connected" else state.name.lowercase()} pending=$pendingFrameCount"
    override fun openConnection() {
        synchronized(pendingTimingProbes) { pendingTimingProbes.clear() }
        lastTimingProbeMs = Long.MIN_VALUE
        val s = if (secure) SSLSocketFactory.getDefault().createSocket() as SSLSocket else Socket()
        socket = s
        s.connect(InetSocketAddress(host, port), config.connectTimeoutMs)
        s.soTimeout = config.connectTimeoutMs
        if (s is SSLSocket) {
            s.sslParameters = s.sslParameters.also { it.endpointIdentificationAlgorithm = "HTTPS" }
            s.startHandshake()
        }
        input = BufferedInputStream(s.getInputStream()); output = BufferedOutputStream(s.getOutputStream())
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })
        val request = "GET $path HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        output!!.write(request.toByteArray(StandardCharsets.US_ASCII)); output!!.flush()
        val response = readHeaders(input!!)
        val headers = response.lineSequence().drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
        }.toMap()
        val accept = headers["sec-websocket-accept"]
        val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.US_ASCII)))
        val connectionTokens = headers["connection"]?.split(',')?.map { it.trim().lowercase() }.orEmpty()
        if (!response.lineSequence().first().matches(Regex("HTTP/1\\.[01] 101(?: .*)?")) ||
            headers["upgrade"]?.equals("websocket", ignoreCase = true) != true ||
            "upgrade" !in connectionTokens || accept != expected
        ) throw IOException("websocket handshake rejected")
        s.soTimeout = config.writeTimeoutMs
    }
    override fun writeFrame(frame: ByteArray) {
        val mask = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
        val payload = ByteArray(frame.size) { i -> (frame[i].toInt() xor mask[i % 4].toInt()).toByte() }
        synchronized(frameLock) {
            val out = output ?: throw IOException("websocket is closed")
            out.write(0x81)
            if (payload.size <= 125) {
                out.write(0x80 or payload.size)
            } else {
                out.write(0x80 or 126)
                out.write((payload.size ushr 8) and 0xff)
                out.write(payload.size and 0xff)
            }
            out.write(mask); out.write(payload); out.flush()
        }
    }
    override fun monitorConnection() {
        val source = input ?: return
        while (!closing) {
            val first = try { source.read() } catch (_: SocketTimeoutException) {
                sendTimingProbe()
                continue
            }
            if (first < 0) return
            val second = source.read()
            if (second < 0) return
            val opcode = first and 0x0f
            if (first and 0x80 == 0) throw IOException("fragmented websocket frames are not supported")
            var length = second and 0x7f
            if (length == 126) length = (source.read() shl 8) or source.read()
            if (length == 127) throw IOException("unsupported websocket response length")
            if (length > config.maximumFrameBytes) throw IOException("websocket response exceeds frame limit")
            if (opcode >= 0x8 && length > 125) throw IOException("invalid websocket control frame")
            val mask = if (second and 0x80 != 0) ByteArray(4).also { readFully(source, it) } else null
            val payload = ByteArray(length).also { readFully(source, it) }
            if (mask != null) payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
            if (opcode == 0x8) return
            if (opcode == 0x9) sendControlFrame(0xA, payload)
            if (opcode == 0xA) receiveTimingPong(payload)
            if (opcode == 0x1 || opcode == 0x2) receiver?.onBytesReceived(payload.copyOf())
        }
    }

    private fun sendTimingProbe() {
        val now = monotonicMs()
        if (lastTimingProbeMs != Long.MIN_VALUE && now - lastTimingProbeMs < TIMING_PROBE_INTERVAL_MS) return
        val id = timingProbeIds.incrementAndGet()
        synchronized(pendingTimingProbes) {
            pendingTimingProbes[id] = now
            while (pendingTimingProbes.size > MAX_PENDING_TIMING_PROBES) {
                pendingTimingProbes.remove(pendingTimingProbes.keys.first())
            }
        }
        lastTimingProbeMs = now
        sendControlFrame(0x9, ByteBuffer.allocate(8).putLong(id).array())
    }

    private fun receiveTimingPong(payload: ByteArray) {
        if (payload.size != 8) return
        val id = ByteBuffer.wrap(payload).long
        val sent = synchronized(pendingTimingProbes) { pendingTimingProbes.remove(id) } ?: return
        emitRoundTripSample(sent, monotonicMs())
    }

    private fun monotonicMs(): Long = monotonicClockMs()
    private fun sendControlFrame(opcode: Int, payload: ByteArray) {
        if (payload.size > 125) throw IOException("invalid websocket control frame")
        val mask = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
        synchronized(frameLock) {
            val out = output ?: return
            out.write(0x80 or opcode); out.write(0x80 or payload.size); out.write(mask)
            payload.indices.forEach { out.write(payload[it].toInt() xor mask[it % 4].toInt()) }
            out.flush()
        }
    }
    private fun readFully(source: BufferedInputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = source.read(target, offset, target.size - offset)
            if (read < 0) throw EOFException("websocket frame ended")
            offset += read
        }
    }
    override fun closeConnection() {
        synchronized(pendingTimingProbes) { pendingTimingProbes.clear() }
        runCatching { socket?.close() }
        runCatching { output?.close() }
        runCatching { input?.close() }
        output = null
        input = null
        socket = null
    }
    private fun readHeaders(input: BufferedInputStream): String {
        val bytes = ByteArrayOutputStreamCompat()
        var matched = 0
        while (bytes.size < 8192) {
            val b = input.read(); if (b < 0) throw EOFException("websocket handshake ended")
            bytes.write(b)
            matched = if (b == "\r\n\r\n"[matched].code) matched + 1 else if (b == 13) 1 else 0
            if (matched == 4) return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
        }
        throw IOException("websocket handshake headers too large")
    }
    private class ByteArrayOutputStreamCompat { private val b = ArrayList<Byte>(); val size get() = b.size; fun write(v: Int) { b += v.toByte() }; fun toByteArray() = b.toByteArray() }

    private companion object {
        const val TIMING_PROBE_INTERVAL_MS = 1_000L
        const val MAX_PENDING_TIMING_PROBES = 4
    }
}
