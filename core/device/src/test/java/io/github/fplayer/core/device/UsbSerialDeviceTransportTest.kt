package io.github.fplayer.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class UsbSerialDeviceTransportTest {
    @Test
    fun `serial writes and receives through injected backend`() {
        val backend = FakeBackend(FakeConnection())
        val received = LinkedBlockingQueue<String>()
        val transport = UsbSerialDeviceTransport(
            backend,
            UsbSerialSelector(0x1234, 0x5678, confirmedDeviceId = 7),
            listener = StateRecorder(),
            receiver = TransportReceiver { received += it.toString(StandardCharsets.US_ASCII) },
        )
        transport.connect()
        transport.write(ascii("L05000I100\n"), DeviceFramePriority.NORMAL)
        assertEquals("L05000I100\n", backend.connection.writes.poll(2, TimeUnit.SECONDS))
        backend.connection.incoming += ascii("TCode v0.3\n")
        assertEquals("TCode v0.3\n", received.poll(2, TimeUnit.SECONDS))
        transport.close()
    }

    @Test
    fun `permission denial is stable and diagnostics are redacted`() {
        val states = StateRecorder()
        val backend = FakeBackend(openFailure = TransportException(TransportFailureCode.PERMISSION_DENIED, "USB permission was denied"))
        val transport = UsbSerialDeviceTransport(
            backend,
            UsbSerialSelector(0xabcd, 0xef01, confirmedDeviceId = 42),
            listener = states,
        )
        assertFails { transport.connect() }
        assertEquals(TransportState.FAILED, transport.state)
        assertEquals(TransportFailureCode.PERMISSION_DENIED, states.lastFailure()?.code)
        assertFalse(transport.diagnostics.contains("abcd"))
        assertFalse(transport.diagnostics.contains("42"))
        transport.close()
    }

    @Test
    fun `detach clears queue and reconnect starts empty`() {
        val connection = FakeConnection()
        val states = StateRecorder()
        val backend = FakeBackend(connection)
        val transport = UsbSerialDeviceTransport(
            backend,
            UsbSerialSelector(1, 2, confirmedDeviceId = 3),
            config = TransportConfig(maximumPendingFrames = 2),
            listener = states,
        )
        transport.connect()
        connection.readFailure = TransportException(TransportFailureCode.DEVICE_DETACHED, "USB device detached")
        await { transport.state == TransportState.FAILED }
        assertEquals(TransportFailureCode.DEVICE_DETACHED, states.lastFailure()?.code)
        assertEquals(0, transport.pendingFrameCount)
        backend.connection = FakeConnection()
        transport.connect()
        assertTrue(backend.connection.writes.isEmpty())
        transport.close()
    }

    @Test
    fun `write timeout clears pending frames without replay`() {
        val connection = FakeConnection().also { it.writeFailure = TransportException(TransportFailureCode.WRITE_TIMEOUT, "USB serial write timed out") }
        val states = StateRecorder()
        val transport = UsbSerialDeviceTransport(
            FakeBackend(connection),
            UsbSerialSelector(1, 2, 3),
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
    fun `emergency stop evicts normal backlog and keeps stop fifo`() {
        val connection = FakeConnection().also { it.writeGate = CountDownLatch(1) }
        val states = StateRecorder()
        val transport = UsbSerialDeviceTransport(
            FakeBackend(connection),
            UsbSerialSelector(1, 2, 3),
            config = TransportConfig(maximumPendingFrames = 2, writeTimeoutMs = 2_000),
            listener = states,
        )
        transport.connect()
        transport.write(ascii("in-flight\n"), DeviceFramePriority.NORMAL)
        await { connection.writeStarted }
        transport.write(ascii("queued-1\n"), DeviceFramePriority.NORMAL)
        transport.write(ascii("queued-2\n"), DeviceFramePriority.NORMAL)
        assertFails { transport.write(ascii("overflow\n"), DeviceFramePriority.NORMAL) }
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

    private class FakeBackend(var connection: FakeConnection = FakeConnection(), private val openFailure: Exception? = null) : UsbSerialBackend {
        override fun open(selector: UsbSerialSelector, parameters: UsbSerialParameters, permissionTimeoutMs: Int, isCancelled: () -> Boolean): UsbSerialConnection {
            openFailure?.let { throw it }
            if (isCancelled()) throw IOException("Connect was cancelled")
            return connection
        }
    }

    private class FakeConnection : UsbSerialConnection {
        val writes = LinkedBlockingQueue<String>()
        val incoming = LinkedBlockingQueue<ByteArray>()
        var writeGate: CountDownLatch? = null
        var writeFailure: Exception? = null
        var readFailure: Exception? = null
        @Volatile var writeStarted = false

        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            readFailure?.let { throw it }
            val bytes = incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            bytes.copyInto(buffer, endIndex = minOf(buffer.size, bytes.size))
            return minOf(buffer.size, bytes.size)
        }

        override fun write(bytes: ByteArray, timeoutMs: Int) {
            writeStarted = true
            writeGate?.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                ?.also { if (!it) throw TransportException(TransportFailureCode.WRITE_TIMEOUT, "USB serial write timed out") }
            writeFailure?.let { throw it }
            writes += bytes.toString(StandardCharsets.US_ASCII)
        }

        override fun close() { writeGate?.countDown() }
    }

    private class StateRecorder : TransportListener {
        private val events = mutableListOf<Pair<TransportState, TransportFailure?>>()
        @Synchronized override fun onStateChanged(state: TransportState, failure: TransportFailure?) { events += state to failure }
        @Synchronized fun lastFailure() = events.asReversed().firstNotNullOfOrNull { it.second }
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
