package io.github.fplayer.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import io.github.fplayer.core.model.AxisId

class NetworkDeviceTransportsTest {
    @Test
    fun `websocket correlated ping pong reports monotonic response sample`() {
        WebSocketLoopback().use { server ->
            val samples = mutableListOf<Pair<Long, Long>>()
            val received = CountDownLatch(1)
            val clockIndex = AtomicInteger()
            val clockValues = longArrayOf(100L, 220L)
            val transport = RawWebSocketDeviceTransport(
                loopback(),
                server.port,
                config = TransportConfig(writeTimeoutMs = 50),
                monotonicClockMs = { clockValues[clockIndex.getAndIncrement().coerceAtMost(1)] },
            )
            transport.setTimingListener { sentAt, receivedAt ->
                synchronized(samples) { samples += sentAt to receivedAt }
                received.countDown()
            }

            transport.connect()

            assertTrue(received.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(100L to 220L), synchronized(samples) { samples.toList() })
            transport.close()
        }
    }
    @Test
    fun `tcp sends frames detects remote close and reconnects without replay`() {
        TcpLoopback().use { server ->
            val states = StateRecorder()
            val responses = LinkedBlockingQueue<String>()
            val transport = TcpDeviceTransport(
                loopback(), server.port, listener = states,
                receiver = TransportReceiver { responses += it.toString(StandardCharsets.US_ASCII) },
            )
            transport.connect()
            transport.write(ascii("L05000I100\n"), DeviceFramePriority.NORMAL)
            assertEquals("L05000I100\n", server.takeText())
            server.send("TCode v0.3\n")
            assertEquals("TCode v0.3\n", responses.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("response timed out"))

            server.closeClient()
            await { transport.state == TransportState.FAILED }
            assertEquals(TransportFailureCode.REMOTE_CLOSED, states.lastFailure()?.code)

            transport.connect()
            assertEquals(TransportState.CONNECTED, transport.state)
            assertFalse(server.hasFrame())
            transport.write(ascii("DSTOP\n"), DeviceFramePriority.EMERGENCY)
            assertEquals("DSTOP\n", server.takeText())
            transport.close()
        }
    }

    @Test
    fun `udp sends one datagram per frame and reconnects explicitly`() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 2_000
            val transport = UdpDeviceTransport(loopback(), server.localPort)
            transport.connect()
            transport.write(ascii("L01000I50\n"), DeviceFramePriority.NORMAL)
            assertEquals("L01000I50\n", receiveText(server))
            transport.disconnect()
            assertEquals(TransportState.DISCONNECTED, transport.state)
            assertFails { transport.write(ascii("stale\n"), DeviceFramePriority.NORMAL) }

            transport.connect()
            transport.write(ascii("DSTOP\n"), DeviceFramePriority.EMERGENCY)
            assertEquals("DSTOP\n", receiveText(server))
            transport.close()
        }
    }

    @Test
    fun `raw websocket performs handshake masks text and handles close`() {
        WebSocketLoopback().use { server ->
            val states = StateRecorder()
            val transport = RawWebSocketDeviceTransport(loopback(), server.port, listener = states)
            transport.connect()
            transport.write(ascii("L07500I100\n"), DeviceFramePriority.NORMAL)
            assertEquals("L07500I100\n", server.takeText())
            assertTrue(server.lastFrameWasMasked)

            server.sendClose()
            await { transport.state == TransportState.FAILED }
            assertEquals(TransportFailureCode.REMOTE_CLOSED, states.lastFailure()?.code)
            transport.close()
        }
    }

    @Test
    fun `bounded queue rejects pressure and emergency discards normal backlog`() {
        val states = StateRecorder()
        val gate = CountDownLatch(1)
        val writes = LinkedBlockingQueue<String>()
        val transport = BlockingTransport(gate, writes, states)
        transport.connect()
        transport.write(ascii("first\n"), DeviceFramePriority.NORMAL)
        await { transport.writeStarted.count == 0L }
        transport.write(ascii("queued\n"), DeviceFramePriority.NORMAL)
        assertFails { transport.write(ascii("overflow\n"), DeviceFramePriority.NORMAL) }
        assertEquals(TransportFailureCode.BACKPRESSURE, states.lastFailure()?.code)

        transport.write(ascii("DSTOP\n"), DeviceFramePriority.EMERGENCY)
        gate.countDown()
        assertEquals("first\n", writes.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("first frame timed out"))
        assertEquals("DSTOP\n", writes.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("stop frame timed out"))
        assertFalse(writes.contains("queued\n"))
        transport.close()
    }

    @Test
    fun `multiple emergency frames preserve order and never evict each other`() {
        val states = StateRecorder()
        val gate = CountDownLatch(1)
        val writes = LinkedBlockingQueue<String>()
        val transport = BlockingTransport(gate, writes, states, capacity = 2)
        transport.connect()
        transport.write(ascii("in-flight\n"), DeviceFramePriority.NORMAL)
        await { transport.writeStarted.count == 0L }
        transport.write(ascii("stop-1\n"), DeviceFramePriority.EMERGENCY)
        transport.write(ascii("stop-2\n"), DeviceFramePriority.EMERGENCY)
        assertFails { transport.write(ascii("stop-3\n"), DeviceFramePriority.EMERGENCY) }

        gate.countDown()
        assertEquals("in-flight\n", writes.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("in-flight frame timed out"))
        assertEquals("stop-1\n", writes.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("first stop timed out"))
        assertEquals("stop-2\n", writes.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("second stop timed out"))
        assertEquals(TransportFailureCode.BACKPRESSURE, states.lastFailure()?.code)
        transport.close()
    }

    @Test
    fun `frame limit and failed connect are reported without endpoint disclosure`() {
        val states = StateRecorder()
        val unavailablePort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val transport = TcpDeviceTransport(
            loopback(), unavailablePort,
            TransportConfig(maximumFrameBytes = 4), states,
        )
        assertFails { transport.connect() }
        assertEquals(TransportState.FAILED, transport.state)
        assertEquals(TransportFailureCode.CONNECT_FAILED, states.lastFailure()?.code)
        assertFalse(transport.diagnostics.contains(loopback()))

        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            val udp = UdpDeviceTransport(loopback(), server.localPort, TransportConfig(maximumFrameBytes = 4), states)
            udp.connect()
            assertFails { udp.write(ascii("12345"), DeviceFramePriority.NORMAL) }
            assertEquals(TransportFailureCode.FRAME_TOO_LARGE, states.lastFailure()?.code)
            udp.close()
        }
    }

    @Test
    fun `safety controller normal frame and emergency stop reach tcp in order`() {
        TcpLoopback().use { server ->
            val transport = TcpDeviceTransport(loopback(), server.port)
            transport.connect()
            val controller = DeviceSafetyController(
                DeviceSafetyConfig(
                    version = TCodeVersion.V0_3,
                    axes = mapOf(AxisId("L0") to AxisSafetyConfig()),
                    minimumFrameIntervalMs = 0,
                ),
                transport,
            )
            controller.connect()
            controller.submit(DeviceTarget(AxisId("L0"), 25, 100, 1, 100))
            controller.drain(0, 0, 1)
            assertEquals("L02500I100\n", server.takeText())

            controller.stop(StopReason.PLAYBACK_SEEK)
            assertEquals("DSTOP\n", server.takeText())
            transport.close()
        }
    }

    @Test
    fun `blocked write times out clears queue and reports failure`() {
        val states = StateRecorder()
        val transport = TimeoutTransport(states)
        transport.connect()
        transport.write(ascii("blocked\n"), DeviceFramePriority.NORMAL)

        await { transport.state == TransportState.FAILED }
        assertEquals(TransportFailureCode.WRITE_TIMEOUT, states.lastFailure()?.code)
        assertEquals(0, transport.pendingFrameCount)
        transport.close()
    }

    @Test
    fun `websocket connect can be cancelled while handshake is stalled`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val accepted = CountDownLatch(1)
            Thread({ server.accept().use { accepted.countDown(); Thread.sleep(2_000) } }, "stalled-ws").also {
                it.isDaemon = true
                it.start()
            }
            val transport = RawWebSocketDeviceTransport(
                loopback(), server.localPort,
                config = TransportConfig(connectTimeoutMs = 5_000, writeTimeoutMs = 5_000),
            )
            val finished = CountDownLatch(1)
            Thread({ runCatching { transport.connect() }; finished.countDown() }, "ws-connect").also {
                it.isDaemon = true
                it.start()
            }
            assertTrue(accepted.await(1, TimeUnit.SECONDS))

            transport.disconnect()

            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals(TransportState.DISCONNECTED, transport.state)
            transport.close()
        }
    }

    @Test
    fun `write racing disconnect leaves no frame for reconnect`() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 250
            val transport = UdpDeviceTransport(loopback(), server.localPort)
            transport.connect()
            val start = CountDownLatch(1)
            val finished = CountDownLatch(1)
            Thread({
                start.await()
                repeat(100) { runCatching { transport.write(ascii("stale\n"), DeviceFramePriority.NORMAL) } }
                finished.countDown()
            }, "write-race").also { it.isDaemon = true; it.start() }
            start.countDown()
            transport.disconnect()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals(0, transport.pendingFrameCount)

            while (runCatching { receiveText(server) }.isSuccess) Unit
            transport.connect()
            assertFails { receiveText(server) }
            transport.close()
        }
    }

    private class BlockingTransport(
        private val gate: CountDownLatch,
        private val writes: LinkedBlockingQueue<String>,
        listener: TransportListener,
        capacity: Int = 1,
    ) : QueuedDeviceTransport(
        "blocking", TransportConfig(writeTimeoutMs = 2_000, maximumPendingFrames = capacity), listener,
    ) {
        val writeStarted = CountDownLatch(1)
        override fun openConnection() = Unit
        override fun writeFrame(frame: ByteArray) {
            writeStarted.countDown()
            gate.await()
            writes += frame.toString(StandardCharsets.US_ASCII)
        }
        override fun closeConnection() { gate.countDown() }
    }

    private class TimeoutTransport(listener: TransportListener) : QueuedDeviceTransport(
        "timeout", TransportConfig(writeTimeoutMs = 50), listener,
    ) {
        private val release = CountDownLatch(1)
        override fun openConnection() = Unit
        override fun writeFrame(frame: ByteArray) { release.await() }
        override fun closeConnection() { release.countDown() }
    }

    private class StateRecorder : TransportListener {
        private val events = mutableListOf<Pair<TransportState, TransportFailure?>>()
        @Synchronized override fun onStateChanged(state: TransportState, failure: TransportFailure?) { events += state to failure }
        @Synchronized fun lastFailure() = events.asReversed().firstNotNullOfOrNull { it.second }
    }

    private class TcpLoopback : AutoCloseable {
        private val server = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
        private val frames = LinkedBlockingQueue<String>()
        @Volatile private var client: Socket? = null
        @Volatile private var closed = false
        val port get() = server.localPort
        init { Thread(::acceptLoop, "tcp-loopback").also { it.isDaemon = true; it.start() } }
        private fun acceptLoop() {
            while (!closed) runCatching {
                val accepted = server.accept(); client = accepted
                val input = accepted.getInputStream(); val buffer = ByteArray(1024)
                while (!closed) { val count = input.read(buffer); if (count < 0) break; frames += buffer.copyOf(count).toString(StandardCharsets.US_ASCII) }
            }
        }
        fun takeText(): String = frames.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("TCP frame timed out")
        fun hasFrame() = frames.poll(150, TimeUnit.MILLISECONDS) != null
        fun send(value: String) { client?.getOutputStream()?.apply { write(value.toByteArray(StandardCharsets.US_ASCII)); flush() } }
        fun closeClient() { client?.close() }
        override fun close() { closed = true; client?.close(); server.close() }
    }

    private inner class WebSocketLoopback : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val frames = LinkedBlockingQueue<String>()
        @Volatile private var client: Socket? = null
        @Volatile var lastFrameWasMasked = false
        val port get() = server.localPort
        init { Thread(::serve, "ws-loopback").also { it.isDaemon = true; it.start() } }
        private fun serve() {
            val socket = server.accept(); client = socket
            val input = BufferedInputStream(socket.getInputStream()); val output = BufferedOutputStream(socket.getOutputStream())
            val headers = readHeaders(input)
            val key = headers.lines().first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.US_ASCII)))
            output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)); output.flush()
            while (!socket.isClosed) {
                val first = input.read(); if (first < 0) break
                val second = input.read(); if (second < 0) break
                lastFrameWasMasked = second and 0x80 != 0
                var length = second and 0x7f
                if (length == 126) length = (input.read() shl 8) or input.read()
                val mask = ByteArray(4); input.readFully(mask)
                val payload = ByteArray(length); input.readFully(payload)
                payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
                val opcode = first and 0x0f
                if (opcode == 0x9) {
                    output.write(0x8A)
                    output.write(payload.size)
                    output.write(payload)
                    output.flush()
                } else {
                    frames += payload.toString(StandardCharsets.US_ASCII)
                }
            }
        }
        fun takeText(): String = frames.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("WebSocket frame timed out")
        fun sendClose() { client?.getOutputStream()?.apply { write(byteArrayOf(0x88.toByte(), 0)); flush() } }
        override fun close() { client?.close(); server.close() }
    }

    private fun BufferedInputStream.readFully(target: ByteArray) { var offset = 0; while (offset < target.size) { val n = read(target, offset, target.size - offset); if (n < 0) throw IOException("unexpected EOF"); offset += n } }
    private fun readHeaders(input: BufferedInputStream): String { val text = StringBuilder(); var suffix = ""; while (!suffix.endsWith("\r\n\r\n")) { val c = input.read(); if (c < 0) throw IOException("unexpected EOF"); text.append(c.toChar()); suffix = (suffix + c.toChar()).takeLast(4) }; return text.toString() }
    private fun receiveText(socket: DatagramSocket): String { val bytes = ByteArray(1024); val packet = DatagramPacket(bytes, bytes.size); socket.receive(packet); return packet.data.copyOf(packet.length).toString(StandardCharsets.US_ASCII) }
    private fun ascii(value: String) = value.toByteArray(StandardCharsets.US_ASCII)
    private fun loopback(): String = InetAddress.getLoopbackAddress().hostAddress ?: throw AssertionError("loopback has no address")
    private fun await(condition: () -> Boolean) { val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); while (!condition()) { if (System.nanoTime() >= deadline) throw AssertionError("condition timed out"); Thread.sleep(10) } }
    private fun assertFails(block: () -> Unit) { if (runCatching(block).exceptionOrNull() == null) throw AssertionError("expected failure") }
}
