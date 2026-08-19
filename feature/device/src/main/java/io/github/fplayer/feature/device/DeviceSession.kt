package io.github.fplayer.feature.device

import io.github.fplayer.core.device.DeviceSafetyConfig
import io.github.fplayer.core.device.DeviceSafetyController
import io.github.fplayer.core.device.DeviceController
import io.github.fplayer.core.device.DeviceTarget
import io.github.fplayer.core.device.DeviceTransport
import io.github.fplayer.core.script.LatencyCompensationConfig
import io.github.fplayer.core.script.LatencyEstimate
import io.github.fplayer.core.script.LatencyEstimator
import io.github.fplayer.core.script.LatencySample
import io.github.fplayer.core.device.StopBehavior
import io.github.fplayer.core.device.StopReason
import io.github.fplayer.core.device.TransportFailure
import io.github.fplayer.core.device.TransportFailureCode
import io.github.fplayer.core.device.TransportListener
import io.github.fplayer.core.device.TransportState
import io.github.fplayer.core.device.TransportType
import io.github.fplayer.core.device.TCodeVersion
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface DeviceBackend {
    fun discover(transportType: TransportType): List<DiscoveredDeviceUi>
    fun createTransport(
        request: DeviceConnectionRequest,
        listener: TransportListener,
    ): DeviceTransport
}

interface DeviceSessionLifecycle {
    fun onControllerConnected(controller: DeviceController) = Unit
    fun onConnectionLost() = Unit
    fun onSessionClosed() = Unit
}

sealed interface DeviceSessionEvent {
    data object DiscoveryStarted : DeviceSessionEvent
    data class DiscoveryFinished(val devices: List<DiscoveredDeviceUi>) : DeviceSessionEvent
    data class ConnectionChanged(
        val status: DeviceConnectionStatus,
        val diagnostics: String,
        val failure: TransportFailure? = null,
    ) : DeviceSessionEvent
    data class Diagnostic(val diagnostic: DeviceDiagnostic) : DeviceSessionEvent
    data class TestProgress(val running: Boolean) : DeviceSessionEvent
}

class DeviceSession(
    private val backend: DeviceBackend,
    private val callbackExecutor: Executor,
    private val eventSink: (DeviceSessionEvent) -> Unit,
    private val schedulerClear: (StopReason) -> Unit = {},
    private val monotonicNowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val lifecycle: DeviceSessionLifecycle? = null,
    private val timingSampleSink: (LatencyEstimate) -> Unit = {},
    private val worker: ExecutorService = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "device-ui-session").also { it.isDaemon = true }
    },
) : Closeable {
    private val operationEpoch = AtomicLong(0)
    private val testEpoch = AtomicLong(0)
    private val testRunning = AtomicBoolean(false)
    private val lock = Any()
    private var transport: DeviceTransport? = null
    private var controller: DeviceSafetyController? = null
    private val latencyEstimator = LatencyEstimator(LatencyCompensationConfig())

    @Synchronized
    fun latencyEstimate(): LatencyEstimate = synchronized(latencyEstimator) {
        latencyEstimator.estimate(monotonicNowMs())
    }

    fun discover(transportType: TransportType) {
        emit(DeviceSessionEvent.DiscoveryStarted)
        worker.execute {
            runCatching { backend.discover(transportType) }
                .onSuccess { emit(DeviceSessionEvent.DiscoveryFinished(it)) }
                .onFailure {
                    emit(DeviceSessionEvent.DiscoveryFinished(emptyList()))
                    emit(
                        DeviceSessionEvent.Diagnostic(
                            DeviceDiagnostic("DISCOVERY_FAILED", "Device discovery failed", isError = true),
                        ),
                    )
                }
        }
    }

    fun connect(request: DeviceConnectionRequest) {
        val epoch = operationEpoch.incrementAndGet()
        testEpoch.incrementAndGet()
        testRunning.set(false)
        worker.execute {
            closeActiveConnection()
            if (epoch != operationEpoch.get()) return@execute

            emit(
                DeviceSessionEvent.ConnectionChanged(
                    DeviceConnectionStatus.CONNECTING,
                    diagnostics = "${request.transportType.name.lowercase()} connecting pending=0",
                ),
            )
            var created: DeviceTransport? = null
            val failureReported = AtomicBoolean(false)
            val terminalHandled = AtomicBoolean(false)
            try {
                val listener = TransportListener { state, failure ->
                    if (epoch != operationEpoch.get()) return@TransportListener
                    when (state) {
                        TransportState.FAILED -> if (failureReported.compareAndSet(false, true)) {
                            if (terminalHandled.compareAndSet(false, true)) {
                                handleUnexpectedTransportTermination(created)
                            }
                            emit(
                                DeviceSessionEvent.ConnectionChanged(
                                    DeviceConnectionStatus.FAILED,
                                    created?.diagnostics ?: "transport failed pending=0",
                                    failure ?: TransportFailure(
                                        TransportFailureCode.CONNECT_FAILED,
                                        "Connection failed",
                                    ),
                                ),
                            )
                        }
                        TransportState.DISCONNECTED -> {
                            if (terminalHandled.compareAndSet(false, true)) {
                                handleUnexpectedTransportTermination(created)
                            }
                            emit(
                                DeviceSessionEvent.ConnectionChanged(
                                    DeviceConnectionStatus.DISCONNECTED,
                                    created?.diagnostics ?: "transport disconnected pending=0",
                                ),
                            )
                        }
                        else -> Unit
                    }
                }
                created = backend.createTransport(request, listener)
                created.setTimingListener { sentAtMonotonicMs, receivedAtMonotonicMs ->
                    val estimate = runCatching {
                        synchronized(latencyEstimator) {
                            latencyEstimator.recordSample(LatencySample(sentAtMonotonicMs, receivedAtMonotonicMs))
                        }
                    }.getOrNull() ?: return@setTimingListener
                    emit(
                        DeviceSessionEvent.Diagnostic(
                            DeviceDiagnostic(
                                code = "LATENCY_${estimate.state.name}",
                                message = "state=${estimate.state.name.lowercase()} samples=${estimate.sampleCount} " +
                                    "medianRttMs=${estimate.medianRoundTripMs ?: 0L} offsetMs=${estimate.automaticOffsetMs}",
                            ),
                        ),
                    )
                    timingSampleSink(estimate)
                }
                synchronized(lock) { transport = created }
                created.connect()
                if (epoch != operationEpoch.get()) {
                    created.close()
                    return@execute
                }
                val safetyController = DeviceSafetyController(
                    DeviceSafetyConfig(
                        version = TCodeVersion.V0_3,
                        axes = request.axes,
                        stopBehavior = StopBehavior.CENTER,
                    ),
                    created,
                ).also(DeviceSafetyController::connect)
                synchronized(lock) { controller = safetyController }
                lifecycle?.onControllerConnected(safetyController)
                emit(
                    DeviceSessionEvent.ConnectionChanged(
                        DeviceConnectionStatus.CONNECTED,
                        created.diagnostics,
                    ),
                )
            } catch (_: Exception) {
                created?.close()
                synchronized(lock) {
                    if (transport === created) transport = null
                    controller = null
                }
                if (epoch == operationEpoch.get()) {
                    if (failureReported.compareAndSet(false, true)) {
                        emit(
                            DeviceSessionEvent.ConnectionChanged(
                                DeviceConnectionStatus.FAILED,
                                created?.diagnostics ?: "transport failed pending=0",
                                TransportFailure(
                                    TransportFailureCode.CONNECT_FAILED,
                                    "Connection failed",
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun disconnect() {
        operationEpoch.incrementAndGet()
        testEpoch.incrementAndGet()
        testRunning.set(false)
        worker.execute {
            closeActiveConnection()
            emit(
                DeviceSessionEvent.ConnectionChanged(
                    DeviceConnectionStatus.DISCONNECTED,
                    diagnostics = "transport disconnected pending=0",
                ),
            )
        }
    }

    fun emergencyStop() {
        testEpoch.incrementAndGet()
        val wasTesting = testRunning.getAndSet(false)
        synchronized(lock) {
            controller?.stop(StopReason.USER)
        }
        if (wasTesting) emit(DeviceSessionEvent.TestProgress(running = false))
        emit(
            DeviceSessionEvent.Diagnostic(
                DeviceDiagnostic("EMERGENCY_STOP", "Emergency stop requested"),
            ),
        )
    }

    fun runSafeTest(plan: SafeTestPlan) {
        if (!testRunning.compareAndSet(false, true)) return
        val connectionEpoch = operationEpoch.get()
        val activeTestEpoch = testEpoch.incrementAndGet()
        emit(DeviceSessionEvent.TestProgress(running = true))
        worker.execute {
            try {
                plan.positions.forEachIndexed { index, position ->
                    val stepTimeMs = index * plan.stepDelayMs
                    synchronized(lock) {
                        check(connectionEpoch == operationEpoch.get()) { "Test was cancelled" }
                        check(activeTestEpoch == testEpoch.get()) { "Test was cancelled" }
                        val activeController = controller
                            ?: error("Device controller is not connected")
                        val generation = activeController.currentGeneration
                        activeController.submit(
                            DeviceTarget(
                                axis = plan.axis,
                                position = position,
                                durationMs = plan.durationMs,
                                generation = generation,
                                mediaTimeMs = stepTimeMs,
                            ),
                        )
                        activeController.drain(
                            wallTimeMs = stepTimeMs,
                            mediaTimeMs = stepTimeMs,
                            currentGeneration = generation,
                        )
                    }
                    if (index != plan.positions.lastIndex) Thread.sleep(plan.stepDelayMs)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                if (connectionEpoch == operationEpoch.get() && activeTestEpoch == testEpoch.get()) {
                    emit(
                        DeviceSessionEvent.Diagnostic(
                            DeviceDiagnostic("SAFE_TEST_FAILED", "Safe test stopped", isError = true),
                        ),
                    )
                }
            } finally {
                if (testEpoch.compareAndSet(activeTestEpoch, activeTestEpoch)) {
                    testRunning.set(false)
                    emit(DeviceSessionEvent.TestProgress(running = false))
                }
            }
        }
    }

    override fun close() {
        operationEpoch.incrementAndGet()
        testEpoch.incrementAndGet()
        testRunning.set(false)
        closeActiveConnection()
        lifecycle?.onSessionClosed()
        worker.shutdownNow()
    }

    private fun closeActiveConnection() {
        val activeController: DeviceSafetyController?
        val activeTransport: DeviceTransport?
        synchronized(lock) {
            activeController = controller
            activeTransport = transport
            controller = null
            transport = null
        }
        synchronized(latencyEstimator) { latencyEstimator.reset() }
        schedulerClear(StopReason.CONNECTION_LOST)
        lifecycle?.onConnectionLost()
        runCatching { activeController?.onConnectionLost() }
        runCatching { activeController?.close() }
        runCatching { activeTransport?.disconnect() }
        runCatching { activeTransport?.close() }
    }

    private fun handleUnexpectedTransportTermination(candidate: DeviceTransport?) {
        testEpoch.incrementAndGet()
        val wasTesting = testRunning.getAndSet(false)
        synchronized(lock) {
            if (transport === candidate) {
                controller?.onConnectionLost()
                controller = null
            }
        }
        synchronized(latencyEstimator) { latencyEstimator.reset() }
        schedulerClear(StopReason.CONNECTION_LOST)
        lifecycle?.onConnectionLost()
        if (wasTesting) emit(DeviceSessionEvent.TestProgress(running = false))
    }

    private fun emit(event: DeviceSessionEvent) {
        callbackExecutor.execute { eventSink(event) }
    }
}
