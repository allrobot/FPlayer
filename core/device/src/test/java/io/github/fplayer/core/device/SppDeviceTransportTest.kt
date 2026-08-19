package io.github.fplayer.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SppDeviceTransportTest {
    private val selector = TCodeEsp32SppProfile.selector("AA:BB:CC:DD:EE:FF")

    @Test
    fun `spp writes and receives through injected rfcomm connection`() {
        val connection = FakeConnection()
        val backend = FakeBackend(connection)
        val received = LinkedBlockingQueue<String>()
        val transport = SppDeviceTransport(
            backend,
            selector,
            listener = StateRecorder(),
            receiver = TransportReceiver { received += it.toString(StandardCharsets.US_ASCII) },
        )

        transport.connect()
        transport.write(ascii("L05000I100\n"), DeviceFramePriority.NORMAL)
        assertEquals("L05000I100\n", connection.writes.poll(2, TimeUnit.SECONDS))
        connection.receive(ascii("TCode v0.3\n"))
        assertEquals("TCode v0.3\n", received.poll(2, TimeUnit.SECONDS))
        assertEquals(selector, backend.lastSelector)
        transport.close()
    }

    @Test
    fun `setup failures have stable codes and diagnostics redact selection`() {
        val failures = listOf(
            TransportException(TransportFailureCode.SPP_PERMISSION_DENIED, "Bluetooth permission was denied"),
            TransportException(TransportFailureCode.BLUETOOTH_DISABLED, "Bluetooth is disabled"),
            TransportException(TransportFailureCode.SPP_DEVICE_NOT_PAIRED, "Selected device is not paired"),
            SocketTimeoutException("SPP connect timed out"),
            TransportException(TransportFailureCode.SPP_SOCKET_FAILED, "RFCOMM socket failed"),
        )
        val expectedCodes = listOf(
            TransportFailureCode.SPP_PERMISSION_DENIED,
            TransportFailureCode.BLUETOOTH_DISABLED,
            TransportFailureCode.SPP_DEVICE_NOT_PAIRED,
            TransportFailureCode.CONNECT_TIMEOUT,
            TransportFailureCode.SPP_SOCKET_FAILED,
        )

        failures.zip(expectedCodes).forEach { (failure, expectedCode) ->
            val states = StateRecorder()
            val transport = SppDeviceTransport(FakeBackend(openFailure = failure), selector, listener = states)
            assertFails { transport.connect() }
            assertEquals(TransportState.FAILED, transport.state)
            assertEquals(expectedCode, states.lastFailure()?.code)
            assertEquals(0, transport.pendingFrameCount)
            assertFalse(transport.diagnostics.contains("AA:BB"))
            assertFalse(transport.diagnostics.contains(TCodeEsp32SppProfile.deviceName))
            transport.close()
        }
    }

    @Test
    fun `read failure clears queue and reconnect starts without replay`() {
        val first = FakeConnection()
        val backend = FakeBackend(first)
        val states = StateRecorder()
        val transport = SppDeviceTransport(
            backend,
            selector,
            config = TransportConfig(maximumPendingFrames = 2),
            listener = states,
        )

        transport.connect()
        first.failRead(TransportException(TransportFailureCode.READ_FAILED, "SPP read failed"))
        await { transport.state == TransportState.FAILED }
        assertEquals(TransportFailureCode.READ_FAILED, states.lastFailure()?.code)
        assertEquals(0, transport.pendingFrameCount)

        val second = FakeConnection()
        backend.connection = second
        transport.connect()
        assertEquals(TransportState.CONNECTED, transport.state)
        assertTrue(second.writes.isEmpty())
        transport.close()
    }

    @Test
    fun `remote eof clears queue and reports remote closed`() {
        val connection = FakeConnection()
        val states = StateRecorder()
        val transport = SppDeviceTransport(FakeBackend(connection), selector, listener = states)

        transport.connect()
        connection.remoteClose()
        await { transport.state == TransportState.FAILED }
        assertEquals(TransportFailureCode.REMOTE_CLOSED, states.lastFailure()?.code)
        assertEquals(0, transport.pendingFrameCount)
        transport.close()
    }

    @Test
    fun `write timeout clears pending frames without replay`() {
        val connection = FakeConnection().also { it.writeGate = CountDownLatch(1) }
        val states = StateRecorder()
        val transport = SppDeviceTransport(
            FakeBackend(connection),
            selector,
            config = TransportConfig(writeTimeoutMs = 50),
            listener = states,
        )

        transport.connect()
        transport.write(ascii("blocked\n"), DeviceFramePriority.NORMAL)
        await { transport.state == TransportState.FAILED }
        assertEquals(TransportFailureCode.WRITE_TIMEOUT, states.lastFailure()?.code)
        assertEquals(0, transport.pendingFrameCount)
        transport.close()
    }

    @Test
    fun `write failure is stable and clears queued frames`() {
        val connection = FakeConnection().also {
            it.writeFailure = TransportException(TransportFailureCode.WRITE_FAILED, "SPP write failed")
        }
        val states = StateRecorder()
        val transport = SppDeviceTransport(FakeBackend(connection), selector, listener = states)

        transport.connect()
        transport.write(ascii("failed\n"), DeviceFramePriority.NORMAL)
        await { transport.state == TransportState.FAILED }
        assertEquals(TransportFailureCode.WRITE_FAILED, states.lastFailure()?.code)
        assertEquals(0, transport.pendingFrameCount)
        transport.close()
    }

    @Test
    fun `emergency stop evicts normal backlog and preserves emergency fifo`() {
        val connection = FakeConnection().also { it.writeGate = CountDownLatch(1) }
        val states = StateRecorder()
        val transport = SppDeviceTransport(
            FakeBackend(connection),
            selector,
            config = TransportConfig(maximumPendingFrames = 2, writeTimeoutMs = 2_000),
            listener = states,
        )

        transport.connect()
        transport.write(ascii("in-flight\n"), DeviceFramePriority.NORMAL)
        await { connection.writeStarted }
        transport.write(ascii("queued-1\n"), DeviceFramePriority.NORMAL)
        transport.write(ascii("queued-2\n"), DeviceFramePriority.NORMAL)
        assertFails { transport.write(ascii("overflow\n"), DeviceFramePriority.NORMAL) }
        assertEquals(TransportFailureCode.BACKPRESSURE, states.lastFailure()?.code)
        transport.write(ascii("stop-1\n"), DeviceFramePriority.EMERGENCY)
        transport.write(ascii("stop-2\n"), DeviceFramePriority.EMERGENCY)
        connection.writeGate?.countDown()

        assertEquals("in-flight\n", connection.writes.poll(2, TimeUnit.SECONDS))
        assertEquals("stop-1\n", connection.writes.poll(2, TimeUnit.SECONDS))
        assertEquals("stop-2\n", connection.writes.poll(2, TimeUnit.SECONDS))
        assertFalse(connection.writes.contains("queued-1\n"))
        assertFalse(connection.writes.contains("queued-2\n"))
        transport.close()
    }

    @Test
    fun `disconnect cancels an active connect attempt`() {
        val backend = CancellingBackend()
        val transport = SppDeviceTransport(backend, selector)
        val connectFinished = CountDownLatch(1)
        Thread {
            runCatching { transport.connect() }
            connectFinished.countDown()
        }.start()

        assertTrue(backend.started.await(2, TimeUnit.SECONDS))
        transport.disconnect()
        assertTrue(connectFinished.await(2, TimeUnit.SECONDS))
        assertTrue(backend.observedCancellation)
        assertEquals(TransportState.DISCONNECTED, transport.state)
        assertEquals(0, transport.pendingFrameCount)
        transport.close()
    }

    @Test
    fun `firmware profile uses standard spp service and locked device name`() {
        assertEquals("00001101-0000-1000-8000-00805f9b34fb", selector.serviceUuid.toString())
        assertEquals("TCodeESP32", TCodeEsp32SppProfile.deviceName)
        assertFails { TCodeEsp32SppProfile.selector("not-an-address") }
    }

    private class FakeBackend(
        var connection: FakeConnection = FakeConnection(),
        private val openFailure: Exception? = null,
    ) : SppBackend {
        @Volatile var lastSelector: SppDeviceSelector? = null

        override fun open(
            selector: SppDeviceSelector,
            connectTimeoutMs: Int,
            isCancelled: () -> Boolean,
        ): SppConnection {
            lastSelector = selector
            openFailure?.let { throw it }
            if (isCancelled()) throw IOException("Connect was cancelled")
            return connection
        }
    }

    private class CancellingBackend : SppBackend {
        val started = CountDownLatch(1)
        @Volatile var observedCancellation = false

        override fun open(
            selector: SppDeviceSelector,
            connectTimeoutMs: Int,
            isCancelled: () -> Boolean,
        ): SppConnection {
            started.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!isCancelled() && System.nanoTime() < deadline) Thread.sleep(5)
            observedCancellation = isCancelled()
            throw IOException("Connect was cancelled")
        }
    }

    private class FakeConnection : SppConnection {
        private object Eof
        private data class Failure(val error: Exception)

        val writes = LinkedBlockingQueue<String>()
        private val incoming = LinkedBlockingQueue<Any>()
        var writeGate: CountDownLatch? = null
        var writeFailure: Exception? = null
        @Volatile var writeStarted = false

        override fun read(buffer: ByteArray): Int {
            return when (val event = incoming.take()) {
                Eof -> -1
                is Failure -> throw event.error
                is ByteArray -> {
                    event.copyInto(buffer, endIndex = minOf(buffer.size, event.size))
                    minOf(buffer.size, event.size)
                }
                else -> throw AssertionError("unexpected fake read event")
            }
        }

        override fun write(bytes: ByteArray) {
            writeStarted = true
            writeGate?.await()
            writeFailure?.let { throw it }
            writes += bytes.toString(StandardCharsets.US_ASCII)
        }

        fun receive(bytes: ByteArray) {
            incoming += bytes.copyOf()
        }

        fun failRead(error: Exception) {
            incoming += Failure(error)
        }

        fun remoteClose() {
            incoming += Eof
        }

        override fun close() {
            writeGate?.countDown()
            incoming.offer(Eof)
        }
    }

    private class StateRecorder : TransportListener {
        private val events = mutableListOf<Pair<TransportState, TransportFailure?>>()

        @Synchronized
        override fun onStateChanged(state: TransportState, failure: TransportFailure?) {
            events += state to failure
        }

        @Synchronized
        fun lastFailure() = events.asReversed().firstNotNullOfOrNull { it.second }
    }

    private fun ascii(value: String) = value.toByteArray(StandardCharsets.US_ASCII)

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition()) {
            if (System.nanoTime() >= deadline) throw AssertionError("condition timed out")
            Thread.sleep(10)
        }
    }

    private fun assertFails(block: () -> Unit) {
        if (runCatching(block).exceptionOrNull() == null) throw AssertionError("expected failure")
    }
}
