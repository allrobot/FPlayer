package io.github.fplayer.feature.device

import io.github.fplayer.core.device.DeviceFramePriority
import io.github.fplayer.core.device.DeviceTransport
import io.github.fplayer.core.device.TransportFailure
import io.github.fplayer.core.device.TransportFailureCode
import io.github.fplayer.core.device.TransportListener
import io.github.fplayer.core.device.TransportState
import io.github.fplayer.core.device.TransportTimingListener
import io.github.fplayer.core.device.TransportType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualAxisOutputIntegrationTest {
    @Test
    fun correlatedTimingSampleIsForwardedAsRedactedDiagnostic() {
        val backend = FakeBackend()
        val diagnostics = CopyOnWriteArrayList<DeviceSessionEvent>()
        val session = DeviceSession(
            backend = backend,
            callbackExecutor = Executor(Runnable::run),
            eventSink = diagnostics::add,
            monotonicNowMs = { 220L },
        )

        session.connect(validRequest())
        waitFor { backend.transport?.state == TransportState.CONNECTED }
        backend.transport!!.emitTimingSample(100L, 220L)

        val event = diagnostics.filterIsInstance<DeviceSessionEvent.Diagnostic>()
            .last { it.diagnostic.code == "LATENCY_MEASURED" }
        assertTrue(event.diagnostic.message.contains("state=measured"))
        assertTrue(event.diagnostic.message.contains("samples=1"))
        assertTrue(event.diagnostic.message.contains("offsetMs=-60"))
        assertTrue(!event.diagnostic.message.contains("127.0.0.1"))
        session.close()
    }

    @Test
    fun disconnectClearsTimingEstimateAndEmitsConnectionLossStop() {
        val backend = FakeBackend()
        val session = DeviceSession(
            backend = backend,
            callbackExecutor = Executor(Runnable::run),
            eventSink = {},
            monotonicNowMs = { 220L },
        )

        session.connect(validRequest())
        waitFor { backend.transport?.state == TransportState.CONNECTED }
        backend.transport!!.emitTimingSample(100L, 220L)
        assertEquals("MEASURED", session.latencyEstimate().state.name)

        session.disconnect()

        waitFor { backend.transport?.state == TransportState.CLOSED }
        assertEquals("UNMEASURED", session.latencyEstimate().state.name)
        assertTrue(backend.transport!!.writes.any { it.second == DeviceFramePriority.EMERGENCY })
        session.close()
    }

    private fun validRequest() = DeviceUiState(
        network = NetworkConnectionSettings(host = "synthetic-host", port = "8000"),
    ).buildConnectionRequest().getOrThrow()

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }

    private class FakeBackend : DeviceBackend {
        var transport: FakeTransport? = null

        override fun discover(transportType: TransportType): List<DiscoveredDeviceUi> = emptyList()
        override fun createTransport(request: DeviceConnectionRequest, listener: TransportListener): DeviceTransport =
            FakeTransport(listener).also { transport = it }
    }

    private class FakeTransport(private val listener: TransportListener) : DeviceTransport {
        @Volatile private var currentState = TransportState.DISCONNECTED
        private var registeredTimingListener: TransportTimingListener? = null
        val writes = CopyOnWriteArrayList<Pair<String, DeviceFramePriority>>()
        override fun setTimingListener(listener: TransportTimingListener?) {
            registeredTimingListener = listener
        }
        override val state: TransportState get() = currentState
        override val pendingFrameCount: Int get() = 0
        override val diagnostics: String get() = "fake state=${state.name.lowercase()} pending=0"
        override fun connect() {
            currentState = TransportState.CONNECTED
            listener.onStateChanged(currentState, null)
        }
        override fun write(frame: ByteArray, priority: DeviceFramePriority) {
            writes += frame.toString(Charsets.UTF_8) to priority
        }
        override fun disconnect() {
            currentState = TransportState.DISCONNECTED
            listener.onStateChanged(currentState, null)
        }
        override fun close() { currentState = TransportState.CLOSED }
        fun emitTimingSample(sentAtMs: Long, receivedAtMs: Long) = registeredTimingListener?.onRoundTripSample(sentAtMs, receivedAtMs)
    }
}
