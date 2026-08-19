package io.github.fplayer.feature.device

import io.github.fplayer.core.device.DeviceFramePriority
import io.github.fplayer.core.device.DeviceTransport
import io.github.fplayer.core.device.TransportFailure
import io.github.fplayer.core.device.TransportFailureCode
import io.github.fplayer.core.device.TransportListener
import io.github.fplayer.core.device.TransportState
import io.github.fplayer.core.device.TransportType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSessionTest {
    @Test
    fun discoveryReturnsOnlyBackendSafeUiModels() {
        val backend = FakeBackend(
            discovered = listOf(DiscoveredDeviceUi("opaque-1", "Device", "xx:xx:xx:12:34:56")),
        )
        val latch = CountDownLatch(1)
        val events = CopyOnWriteArrayList<DeviceSessionEvent>()
        val session = session(backend) { event ->
            events += event
            if (event is DeviceSessionEvent.DiscoveryFinished) latch.countDown()
        }

        session.discover(TransportType.BLE)

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        val result = events.filterIsInstance<DeviceSessionEvent.DiscoveryFinished>().single()
        assertEquals("opaque-1", result.devices.single().id)
        assertEquals("xx:xx:xx:12:34:56", result.devices.single().detail)
        session.close()
    }

    @Test
    fun connectBuildsSafetyControllerBeforeReportingReady() {
        val backend = FakeBackend()
        val connected = CountDownLatch(1)
        val session = session(backend) { event ->
            if (event is DeviceSessionEvent.ConnectionChanged &&
                event.status == DeviceConnectionStatus.CONNECTED
            ) connected.countDown()
        }

        session.connect(validRequest())

        assertTrue(connected.await(2, TimeUnit.SECONDS))
        assertEquals(TransportState.CONNECTED, backend.transport.state)
        session.close()
    }

    @Test
    fun safeTestWritesOnlyBoundedPositionsThenReportsCompletion() {
        val backend = FakeBackend()
        val connected = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val session = session(backend) { event ->
            if (event is DeviceSessionEvent.ConnectionChanged &&
                event.status == DeviceConnectionStatus.CONNECTED
            ) connected.countDown()
            if (event == DeviceSessionEvent.TestProgress(false)) completed.countDown()
        }
        session.connect(validRequest())
        assertTrue(connected.await(2, TimeUnit.SECONDS))

        session.runSafeTest(
            SafeTestPlan(
                axis = io.github.fplayer.core.model.AxisId("L0"),
                positions = listOf(45, 55, 50),
                stepDelayMs = 10,
            ),
        )

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        val normalFrames = backend.transport.writes.filter { it.second == DeviceFramePriority.NORMAL }
        assertEquals(listOf("L04500I250\n", "L05499I250\n", "L05000I250\n"), normalFrames.map { it.first })
        session.close()
    }

    @Test
    fun disconnectProducesEmergencyStopAndClearsSession() {
        val backend = FakeBackend()
        val connected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val session = session(backend) { event ->
            if (event is DeviceSessionEvent.ConnectionChanged) {
                if (event.status == DeviceConnectionStatus.CONNECTED) connected.countDown()
                if (event.status == DeviceConnectionStatus.DISCONNECTED) disconnected.countDown()
            }
        }
        session.connect(validRequest())
        assertTrue(connected.await(2, TimeUnit.SECONDS))

        session.disconnect()

        assertTrue(disconnected.await(2, TimeUnit.SECONDS))
        assertTrue(backend.transport.writes.any { it.second == DeviceFramePriority.EMERGENCY })
        assertEquals(TransportState.CLOSED, backend.transport.state)
        session.close()
    }

    @Test
    fun emergencyStopUsesEmergencyPriorityAndKeepsTransportConnected() {
        val backend = FakeBackend()
        val connected = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val session = session(backend) { event ->
            if (event is DeviceSessionEvent.ConnectionChanged &&
                event.status == DeviceConnectionStatus.CONNECTED
            ) connected.countDown()
            if (event is DeviceSessionEvent.Diagnostic && event.diagnostic.code == "EMERGENCY_STOP") {
                stopped.countDown()
            }
        }
        session.connect(validRequest())
        assertTrue(connected.await(2, TimeUnit.SECONDS))

        session.emergencyStop()

        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        assertTrue(backend.transport.writes.any { it.second == DeviceFramePriority.EMERGENCY })
        assertEquals(TransportState.CONNECTED, backend.transport.state)
        session.close()
    }

    @Test
    fun earlyTransportCreationFailureHasStableFailureCode() {
        val backend = FakeBackend(createFailure = IllegalStateException("private backend detail"))
        val failed = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<TransportFailure?>()
        val session = session(backend) { event ->
            if (event is DeviceSessionEvent.ConnectionChanged &&
                event.status == DeviceConnectionStatus.FAILED
            ) {
                failures += event.failure
                failed.countDown()
            }
        }

        session.connect(validRequest())

        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertEquals(TransportFailureCode.CONNECT_FAILED, failures.single()?.code)
        assertEquals("Connection failed", failures.single()?.message)
        session.close()
    }

    @Test
    fun remoteDisconnectCancelsSafeTestAndPreventsLaterTargets() {
        val backend = FakeBackend()
        val connected = CountDownLatch(1)
        val firstWrite = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val session = session(backend) { event ->
            if (event is DeviceSessionEvent.ConnectionChanged &&
                event.status == DeviceConnectionStatus.CONNECTED
            ) connected.countDown()
            if (event == DeviceSessionEvent.TestProgress(false)) cancelled.countDown()
        }
        backend.onWrite = { priority ->
            if (priority == DeviceFramePriority.NORMAL) firstWrite.countDown()
        }
        session.connect(validRequest())
        assertTrue(connected.await(2, TimeUnit.SECONDS))
        session.runSafeTest(
            SafeTestPlan(
                axis = io.github.fplayer.core.model.AxisId("L0"),
                positions = listOf(45, 55, 50),
                stepDelayMs = 250,
            ),
        )
        assertTrue(firstWrite.await(2, TimeUnit.SECONDS))

        backend.transport.remoteDisconnect()

        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        Thread.sleep(600)
        assertEquals(1, backend.transport.writes.count { it.second == DeviceFramePriority.NORMAL })
        session.close()
    }

    private fun session(backend: FakeBackend, sink: (DeviceSessionEvent) -> Unit) = DeviceSession(
        backend = backend,
        callbackExecutor = Executor(Runnable::run),
        eventSink = sink,
    )

    private fun validRequest(): DeviceConnectionRequest = DeviceUiState(
        network = NetworkConnectionSettings(host = "127.0.0.1", port = "8000"),
    ).buildConnectionRequest().getOrThrow()

    private class FakeBackend(
        private val discovered: List<DiscoveredDeviceUi> = emptyList(),
        private val createFailure: Exception? = null,
    ) : DeviceBackend {
        lateinit var transport: FakeTransport
        var onWrite: (DeviceFramePriority) -> Unit = {}

        override fun discover(transportType: TransportType): List<DiscoveredDeviceUi> = discovered

        override fun createTransport(
            request: DeviceConnectionRequest,
            listener: TransportListener,
        ): DeviceTransport {
            createFailure?.let { throw it }
            return FakeTransport(listener, onWrite).also { transport = it }
        }
    }

    private class FakeTransport(
        private val listener: TransportListener,
        private val onWrite: (DeviceFramePriority) -> Unit,
    ) : DeviceTransport {
        @Volatile private var transportState = TransportState.DISCONNECTED
        val writes = CopyOnWriteArrayList<Pair<String, DeviceFramePriority>>()

        override val state: TransportState get() = transportState
        override val pendingFrameCount: Int get() = 0
        override val diagnostics: String get() = "fake state=${state.name.lowercase()} pending=0"

        override fun connect() {
            transportState = TransportState.CONNECTING
            listener.onStateChanged(transportState, null)
            transportState = TransportState.CONNECTED
            listener.onStateChanged(transportState, null)
        }

        override fun write(frame: ByteArray, priority: DeviceFramePriority) {
            check(transportState == TransportState.CONNECTED)
            writes += frame.toString(Charsets.UTF_8) to priority
            onWrite(priority)
        }

        fun remoteDisconnect() {
            transportState = TransportState.DISCONNECTED
            listener.onStateChanged(transportState, null)
        }

        override fun disconnect() {
            transportState = TransportState.DISCONNECTED
            listener.onStateChanged(transportState, null)
        }

        override fun close() {
            transportState = TransportState.CLOSED
            listener.onStateChanged(transportState, null)
        }
    }
}
