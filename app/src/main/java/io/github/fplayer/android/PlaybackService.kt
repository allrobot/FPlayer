package io.github.fplayer.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import io.github.fplayer.core.index.MaterializedOrder
import io.github.fplayer.core.index.SortDirection
import io.github.fplayer.core.index.SortField
import io.github.fplayer.core.index.SortSpec
import io.github.fplayer.core.index.db.FPlayerIndexDatabase
import io.github.fplayer.feature.feed.BackgroundPlaybackCallbacks
import io.github.fplayer.feature.feed.BackgroundPlaybackController
import io.github.fplayer.feature.feed.CompletionDisposition
import io.github.fplayer.feature.feed.FeedMedia
import io.github.fplayer.feature.feed.FeedSlot
import io.github.fplayer.feature.feed.PlaybackSession
import io.github.fplayer.feature.feed.SlotPlayer
import io.github.fplayer.feature.feed.SlotPlayerFactory
import io.github.fplayer.core.model.MediaId
import io.github.fplayer.core.model.MediaLocator
import io.github.fplayer.core.player.PlaybackRequest
import io.github.fplayer.core.player.PlayerEngine
import io.github.fplayer.core.player.PlayerEvent
import io.github.fplayer.core.script.PlaybackClock
import io.github.fplayer.core.script.PlaybackDiscontinuity
import io.github.fplayer.core.script.ManualAxisTarget
import io.github.fplayer.core.script.ScriptBundle
import io.github.fplayer.core.script.ScriptSchedulerConfig
import io.github.fplayer.core.device.StopReason
import io.github.fplayer.player.mpv.LibMpvPlayer
import io.github.fplayer.feature.device.DevicePlaybackCoordinator
import io.github.fplayer.feature.device.DevicePlaybackCoordinatorRegistry
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlaybackService : Service(), BackgroundPlaybackCallbacks {
    private val binder = LocalBinder()
    private lateinit var controller: BackgroundPlaybackController
    private lateinit var mediaSession: MediaSession
    private lateinit var playbackSession: PlaybackSession
    private lateinit var engine: PlayerEngine
    private lateinit var indexDatabase: FPlayerIndexDatabase
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val indexExecutor = Executors.newSingleThreadExecutor()
    private var queueGeneration = 0L
    private var queueLoadRequest = 0L
    private var destroyed = false
    private var preparedEventCount = 0L
    private var completedEventCount = 0L
    private var failedEventCount = 0L
    private lateinit var playbackCoordinator: DevicePlaybackCoordinator
    private val scriptTick = object : Runnable {
        override fun run() {
            if (destroyed) return
            playbackCoordinator.tick()
            mainHandler.postDelayed(this, SCRIPT_TICK_INTERVAL_MS)
        }
    }

    data class Diagnostics(
        val preparedEvents: Long,
        val completedEvents: Long,
        val failedEvents: Long,
        val currentMediaId: String?,
        val positionMs: Long,
        val durationMs: Long?,
        val isPlaying: Boolean,
    )

    inner class LocalBinder : Binder() {
        fun setActivityVisible(visible: Boolean) = controller.onActivityVisibilityChanged(visible)
        fun refreshLibrary() = loadCommittedLibrary()
        fun selectMedia(mediaId: MediaId) {
            playbackCoordinator.onDiscontinuity(PlaybackDiscontinuity.SEEK)
            if (playbackSession.snapshot().order?.mediaIds?.contains(mediaId) == true) {
                playbackSession.select(mediaId)
            } else {
                loadCommittedLibrary(mediaId)
            }
            controller.startPlayback()
        }
        fun setLooping(enabled: Boolean) {
            playbackCoordinator.onDiscontinuity(PlaybackDiscontinuity.LOOP)
            playbackSession.setLooping(enabled)
        }
        fun seekTo(positionMs: Long) {
            playbackCoordinator.onDiscontinuity(PlaybackDiscontinuity.SEEK)
            engine.seekTo(positionMs)
        }
        fun setSpeed(speed: Float) {
            playbackCoordinator.onDiscontinuity(PlaybackDiscontinuity.SPEED_CHANGED)
            engine.setSpeed(speed.toDouble())
        }
        fun loadScript(bundle: ScriptBundle, config: ScriptSchedulerConfig = ScriptSchedulerConfig()) {
            playbackCoordinator.load(bundle, config)
        }
        fun sendManualAxis(target: ManualAxisTarget, allowWhenPaused: Boolean = false): Boolean =
            playbackCoordinator.submitManual(target, allowWhenPaused)
        fun play() = controller.startPlayback()
        fun pause() = controller.pausePlayback()
        fun stopDevices() = controller.stopDevices()
        fun enableBackgroundPlayback() {
            startForegroundService(Intent(this@PlaybackService, PlaybackService::class.java).setAction(ACTION_START))
        }
        fun exitBackgroundPlayback() {
            controller.exitBackgroundPlayback()
            stopSelf()
        }
        fun replaceQueue(items: List<FeedMedia>, selected: MediaId? = null) {
            queueLoadRequest += 1
            applyQueue(items, selected)
        }
        fun diagnostics(): Diagnostics {
            val player = engine.snapshot()
            return Diagnostics(
                preparedEventCount,
                completedEventCount,
                failedEventCount,
                playbackSession.snapshot().current?.id?.value,
                player.positionMs,
                player.durationMs,
                player.isPlaying,
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        controller = BackgroundPlaybackController(this)
        engine = LibMpvPlayer(this)
        playbackSession = PlaybackSession(SingleEngineSlotFactory(engine))
        playbackCoordinator = DevicePlaybackCoordinatorRegistry.install(
            PlaybackClock { engine.snapshot() },
        )
        mainHandler.post(scriptTick)
        engine.setEventListener { event -> mainHandler.post { handlePlayerEvent(event) } }
        indexDatabase = FPlayerIndexDatabase.open(this)
        mediaSession = MediaSession(this, TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { this@PlaybackService.controller.startPlayback() }
                override fun onPause() { this@PlaybackService.controller.pausePlayback() }
                override fun onStop() { this@PlaybackService.controller.exitBackgroundPlayback() }
            })
            isActive = true
        }
        createNotificationChannel()
        loadCommittedLibrary()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (PlaybackIntentCommands.decode(intent?.action)) {
            PlaybackIntentCommand.START -> {
                controller.setBackgroundPlaybackEnabled(true)
                startForegroundCompat()
            }
            PlaybackIntentCommand.PLAY -> controller.startPlayback()
            PlaybackIntentCommand.PAUSE -> controller.pausePlayback()
            PlaybackIntentCommand.STOP_DEVICES -> controller.stopDevices()
            PlaybackIntentCommand.EXIT -> {
                controller.exitBackgroundPlayback()
                stopSelfResult(startId)
            }
            PlaybackIntentCommand.UNKNOWN -> Unit
        }
        updateNotification()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacks(scriptTick)
        if (::controller.isInitialized) controller.onServiceDestroyed()
        if (::playbackCoordinator.isInitialized) {
            playbackCoordinator.clear(StopReason.SERVICE_DESTROYED)
            playbackCoordinator.close()
            DevicePlaybackCoordinatorRegistry.remove(playbackCoordinator)
        }
        if (::playbackSession.isInitialized) playbackSession.close()
        if (::engine.isInitialized) engine.release()
        if (::mediaSession.isInitialized) mediaSession.release()
        indexExecutor.shutdownNow()
        runCatching { indexExecutor.awaitTermination(1, TimeUnit.SECONDS) }
        if (::indexDatabase.isInitialized) indexDatabase.close()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Task removal is a best-effort stop path; force-stop may skip all callbacks.
        if (::controller.isInitialized) controller.exitBackgroundPlayback()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun pauseMedia() {
        playbackCoordinator.clear(StopReason.PLAYBACK_PAUSED)
        playbackSession.pause()
        updatePlaybackState(PlaybackState.STATE_PAUSED)
    }
    override fun resumeMedia() { playbackSession.play(); updatePlaybackState(PlaybackState.STATE_PLAYING) }
    override fun stopDevices() {
        playbackCoordinator.clear(StopReason.USER)
        updateNotification()
    }
    override fun stopPlayback() {
        playbackCoordinator.clear(StopReason.SERVICE_DESTROYED)
        releasePlaybackResources()
        updatePlaybackState(PlaybackState.STATE_NONE)
    }
    override fun acquirePlaybackResources() {
        if (wakeLock == null) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:playback").apply {
                setReferenceCounted(false)
            }
        }
        wakeLock?.takeUnless { it.isHeld }?.acquire()
    }
    override fun releasePlaybackResources() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun loadCommittedLibrary(selected: MediaId? = null) {
        if (destroyed) return
        val request = ++queueLoadRequest
        indexExecutor.execute {
            val items = indexDatabase.indexDao().currentMediaLibrarySnapshot().map { media ->
                FeedMedia(MediaId(media.id), MediaLocator(media.locator))
            }
            mainHandler.post {
                if (!destroyed && request == queueLoadRequest) applyQueue(items, selected)
            }
        }
    }

    private fun applyQueue(items: List<FeedMedia>, selected: MediaId? = null) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "PLAYBACK_QUEUE_REQUIRES_MAIN_THREAD" }
        queueGeneration += 1
        playbackSession.setItems(items)
        playbackSession.setOrder(
            MaterializedOrder(
                scopeId = "ALL",
                sortSpec = SortSpec(SortField.TITLE, SortDirection.ASCENDING),
                generation = queueGeneration,
                mediaIds = items.map { it.id },
            ),
        )
        if (selected != null) playbackSession.select(selected)
    }

    private fun handlePlayerEvent(event: PlayerEvent) {
        if (destroyed) return
        when (event) {
            is PlayerEvent.Prepared -> {
                if (playbackSession.onPrepared(FeedSlot.CURRENT, event.mediaId, event.generation)) {
                    preparedEventCount += 1
                }
            }
            is PlayerEvent.Completed -> when (playbackSession.onCompleted(event.mediaId, event.generation)) {
                CompletionDisposition.IGNORED -> Unit
                CompletionDisposition.LOOP_RELOADING, CompletionDisposition.ADVANCED -> completedEventCount += 1
                CompletionDisposition.ENDED -> {
                    completedEventCount += 1
                    playbackCoordinator.onPlaybackEnded()
                    controller.pausePlayback()
                }
            }
            is PlayerEvent.PrepareFailed -> {
                if (playbackSession.onPrepareFailed(
                        FeedSlot.CURRENT,
                        event.mediaId,
                        event.errorCode,
                        event.generation,
                    )
                ) {
                    failedEventCount += 1
                    controller.pausePlayback()
                }
            }
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (controller.backgroundPlaybackEnabled) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val launch = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
        fun action(action: String, title: String, request: Int): Notification.Action = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_play),
            title,
            PendingIntent.getService(this, request, Intent(this, PlaybackService::class.java).setAction(action), immutableFlag()),
        ).build()
        val playing = controller.state.name == "PLAYING"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (playing) "正在播放" else "已暂停")
            .setContentIntent(launch)
            .setOngoing(true)
            .addAction(action(if (playing) ACTION_PAUSE else ACTION_PLAY, if (playing) "暂停" else "播放", 2))
            .addAction(action(ACTION_STOP_DEVICES, "停止设备", 3))
            .addAction(action(ACTION_EXIT, "退出后台", 4))
            .build()
    }

    private fun updatePlaybackState(state: Int) {
        mediaSession.setPlaybackState(PlaybackState.Builder().setState(state, 0L, 1f).build())
        updateNotification()
    }

    private fun immutableFlag() = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "后台播放", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val TAG = "FPlayerPlayback"
        private const val CHANNEL_ID = "fplayer.playback"
        private const val NOTIFICATION_ID = 1001
        private const val SCRIPT_TICK_INTERVAL_MS = 50L
        const val ACTION_START = "io.github.fplayer.android.action.START"
        const val ACTION_PLAY = "io.github.fplayer.android.action.PLAY"
        const val ACTION_PAUSE = "io.github.fplayer.android.action.PAUSE"
        const val ACTION_STOP_DEVICES = "io.github.fplayer.android.action.STOP_DEVICES"
        const val ACTION_EXIT = "io.github.fplayer.android.action.EXIT"
    }

    private class SingleEngineSlotFactory(private val engine: PlayerEngine) : SlotPlayerFactory {
        override fun create(slot: FeedSlot): SlotPlayer = if (slot == FeedSlot.CURRENT) EngineSlotPlayer(engine) else NoopSlotPlayer
    }

    private class EngineSlotPlayer(private val engine: PlayerEngine) : SlotPlayer {
        override fun prepare(item: FeedMedia, resumePositionMs: Long, generation: Long) {
            engine.prepare(
                PlaybackRequest(
                    MediaId(item.id.value),
                    MediaLocator(item.locator.value),
                    resumePositionMs,
                    generation,
                ),
            )
        }
        override fun play() = engine.play()
        override fun pause() = engine.pause()
        override fun seekTo(positionMs: Long) = engine.seekTo(positionMs)
        override fun release() = Unit
    }

    private object NoopSlotPlayer : SlotPlayer {
        override fun prepare(item: FeedMedia, resumePositionMs: Long, generation: Long) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun release() = Unit
    }
}
