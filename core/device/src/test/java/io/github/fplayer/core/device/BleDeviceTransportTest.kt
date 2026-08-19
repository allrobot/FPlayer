package io.github.fplayer.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class BleDeviceTransportTest {
    private val selector = BleDeviceSelector(
        serviceUuid = UUID.fromString("ff1b451d-3070-4276-9c81-5dc5ea1043bc"),
        writeCharacteristicUuid = UUID.fromString("c5f1543e-338d-47a0-8525-01e3c621359d"),
        notifyCharacteristicUuid = UUID.fromString("50300003-0023-4bd4-bbd5-a6920e4c5653"),
    )

    @Test
    fun `ble writes frame in mtu chunks and delivers notifications`() {
        val connection = FakeConnection(maximumWritePayload = 4)
        val backend = FakeBackend(connection)
        val received = LinkedBlockingQueue<String>()
        val transport = BleDeviceTransport(backend, selector, listener = StateRecorder(), receiver = TransportReceiver { received += it.toString(StandardCharsets.US_ASCII) })
        transport.connect()
        transport.write("abcdefghi".toByteArray(StandardCharsets.US_ASCII), DeviceFramePriority.NORMAL)
        await { synchronized(connection.writes) { connection.writes.size == 3 } }
        assertEquals(listOf("abcd", "efgh", "i"), connection.writes.map { it.toString(StandardCharsets.US_ASCII) })
        assertTrue(connection.writeTypes.all { it == BleWriteType.WITH_RESPONSE })
        connection.incoming += "TCode v0.3\n".toByteArray(StandardCharsets.US_ASCII)
        assertEquals("TCode v0.3\n", received.poll(2, TimeUnit.SECONDS))
        transport.close()
    }

    @Test
    fun `gatt failure clears queue and reconnect has no replay`() {
        val first = FakeConnection()
        val states = StateRecorder()
        val backend = FakeBackend(first)
        val transport = BleDeviceTransport(backend, selector, config = TransportConfig(maximumPendingFrames = 2), listener = states)
        transport.connect()
        first.readFailure = TransportException(TransportFailureCode.BLE_GATT_FAILED, "GATT operation failed")
        await { transport.state == TransportState.FAILED }
        assertEquals(0, transport.pendingFrameCount)
        val second = FakeConnection()
        backend.next = second
        transport.connect()
        assertEquals(TransportState.CONNECTED, transport.state)
        assertTrue(second.writes.isEmpty())
        assertEquals(TransportFailureCode.BLE_GATT_FAILED, states.lastFailure()?.code)
        transport.close()
    }

    @Test
    fun `permission and bluetooth failures are stable and diagnostics are redacted`() {
        val states = StateRecorder()
        val transport = BleDeviceTransport(
            FakeBackend(openFailure = TransportException(TransportFailureCode.BLE_PERMISSION_DENIED, "BLE permission was denied")),
            selector.copy(confirmedAddress = "AA:BB:CC:DD:EE:FF"), listener = states,
        )
        assertFails { transport.connect() }
        assertEquals(TransportFailureCode.BLE_PERMISSION_DENIED, states.lastFailure()?.code)
        assertFalse(transport.diagnostics.contains("AA:BB"))
        transport.close()
    }

    @Test
    fun `backpressure rejects overflow and emergency clears normal backlog`() {
        val states = StateRecorder()
        val gate = CountDownLatch(1)
        val connection = FakeConnection(writeGate = gate)
        val transport = BleDeviceTransport(
            FakeBackend(connection), selector,
            config = TransportConfig(writeTimeoutMs = 2_000, maximumPendingFrames = 2), listener = states,
        )
        transport.connect()
        transport.write("first".toByteArray(), DeviceFramePriority.NORMAL)
        await { connection.writeStarted.count == 0L }
        transport.write("queued".toByteArray(), DeviceFramePriority.NORMAL)
        transport.write("queued-2".toByteArray(), DeviceFramePriority.NORMAL)
        assertFails { transport.write("overflow".toByteArray(), DeviceFramePriority.NORMAL) }
        assertEquals(TransportFailureCode.BACKPRESSURE, states.lastFailure()?.code)
        transport.write("DSTOP\n".toByteArray(), DeviceFramePriority.EMERGENCY)
        gate.countDown()
        await { synchronized(connection.writes) { connection.writes.size == 2 } }
        assertEquals(listOf("first", "DSTOP\n"), connection.writes.map { it.toString(StandardCharsets.US_ASCII) })
        transport.close()
    }

    @Test
    fun `write timeout clears queue and reports stable failure`() {
        val states = StateRecorder()
        val connection = FakeConnection(writeGate = CountDownLatch(1))
        val transport = BleDeviceTransport(
            FakeBackend(connection), selector,
            config = TransportConfig(writeTimeoutMs = 50), listener = states,
        )
        transport.connect()
        transport.write("blocked".toByteArray(), DeviceFramePriority.NORMAL)
        await { transport.state == TransportState.FAILED }
        assertEquals(TransportFailureCode.WRITE_TIMEOUT, states.lastFailure()?.code)
        assertEquals(0, transport.pendingFrameCount)
        transport.close()
    }

    @Test
    fun `recoverable setup failures keep stable codes`() {
        val codes = listOf(
            TransportFailureCode.BLUETOOTH_DISABLED,
            TransportFailureCode.CONNECT_TIMEOUT,
            TransportFailureCode.BLE_GATT_FAILED,
            TransportFailureCode.BLE_SUBSCRIPTION_FAILED,
        )
        for (code in codes) {
            val states = StateRecorder()
            val transport = BleDeviceTransport(FakeBackend(openFailure = TransportException(code, "BLE setup failed")), selector, listener = states)
            assertFails { transport.connect() }
            assertEquals(code, states.lastFailure()?.code)
            assertEquals(0, transport.pendingFrameCount)
            transport.close()
        }
    }

    @Test
    fun `firmware profile uses locked UUIDs and write without response`() {
        val profile = TCodeEsp32BleProfile.selector(confirmedAddress = "AA:BB:CC:DD:EE:FF")
        assertEquals(selector.serviceUuid, profile.serviceUuid)
        assertEquals(selector.writeCharacteristicUuid, profile.writeCharacteristicUuid)
        assertEquals(BleWriteType.WITHOUT_RESPONSE, profile.writeType)
        assertEquals(null, profile.notifyCharacteristicUuid)
    }

    private class FakeBackend(var next: FakeConnection? = null, private val openFailure: Exception? = null) : BleBackend {
        override fun open(selector: BleDeviceSelector, config: BleTransportConfig, connectTimeoutMs: Int, isCancelled: () -> Boolean): BleConnection {
            openFailure?.let { throw it }
            return next ?: FakeConnection().also { next = it }
        }
    }

    private class FakeConnection(
        override val maximumWritePayload: Int = 20,
        private val writeGate: CountDownLatch? = null,
    ) : BleConnection {
        val writes = mutableListOf<ByteArray>()
        val writeTypes = mutableListOf<BleWriteType>()
        val incoming = LinkedBlockingQueue<ByteArray>()
        val writeStarted = CountDownLatch(1)
        @Volatile var readFailure: Exception? = null
        override fun writeChunk(bytes: ByteArray, writeType: BleWriteType, timeoutMs: Int) {
            writeStarted.countDown()
            writeGate?.await()
            synchronized(writes) { writes += bytes.copyOf(); writeTypes += writeType }
        }
        override fun read(timeoutMs: Int): ByteArray? {
            readFailure?.let { throw it }
            return incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        }
        override fun close() { writeGate?.countDown() }
    }

    private class StateRecorder : TransportListener {
        private val events = mutableListOf<Pair<TransportState, TransportFailure?>>()
        @Synchronized override fun onStateChanged(state: TransportState, failure: TransportFailure?) { events += state to failure }
        @Synchronized fun lastFailure() = events.asReversed().firstNotNullOfOrNull { it.second }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition()) { if (System.nanoTime() >= deadline) throw AssertionError("condition timed out"); Thread.sleep(10) }
    }
    private fun assertFails(block: () -> Unit) { if (runCatching(block).exceptionOrNull() == null) throw AssertionError("expected failure") }
}
