package io.github.fplayer.feature.feed

import io.github.fplayer.core.index.MaterializedOrder
import io.github.fplayer.core.model.MediaId
import io.github.fplayer.core.model.MediaLocator

data class FeedMedia(
    val id: MediaId,
    val locator: MediaLocator,
)

enum class FeedSlot { PREVIOUS, CURRENT, NEXT }

enum class SlotStatus { EMPTY, PREPARING, READY, FAILED }

enum class CompletionDisposition { IGNORED, LOOP_RELOADING, ADVANCED, ENDED }

data class SlotSnapshot(
    val slot: FeedSlot,
    val item: FeedMedia?,
    val status: SlotStatus,
    val error: String? = null,
)

data class PlaybackSessionSnapshot(
    val order: MaterializedOrder?,
    val selectedIndex: Int?,
    val current: FeedMedia?,
    val slots: List<SlotSnapshot>,
    val looping: Boolean,
)

interface SlotPlayer {
    fun prepare(item: FeedMedia, resumePositionMs: Long, generation: Long)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun release()
}

fun interface SlotPlayerFactory {
    fun create(slot: FeedSlot): SlotPlayer
}

interface PlaybackCheckpointStore {
    fun resumePositionMs(mediaId: MediaId): Long
    fun saveResumePosition(mediaId: MediaId, positionMs: Long)
    fun markCompleted(mediaId: MediaId)
}

object NoopPlaybackCheckpointStore : PlaybackCheckpointStore {
    override fun resumePositionMs(mediaId: MediaId): Long = 0L
    override fun saveResumePosition(mediaId: MediaId, positionMs: Long) = Unit
    override fun markCompleted(mediaId: MediaId) = Unit
}

/**
 * Deterministic queue/session state machine. All callbacks are generation-token guarded,
 * so a fast swipe or order replacement cannot make an old decoder start the wrong item.
 */
class PlaybackSession(
    private val playerFactory: SlotPlayerFactory,
    private val checkpoints: PlaybackCheckpointStore = NoopPlaybackCheckpointStore,
    private val onStateChanged: (PlaybackSessionSnapshot) -> Unit = {},
) {
    private var order: MaterializedOrder? = null
    private var items: Map<MediaId, FeedMedia> = emptyMap()
    private var selectedIndex: Int? = null
    private var loop = false
    private var playWhenReady = false
    private var completionRecordedFor: MediaId? = null
    private var token = 0L
    private val slots = linkedMapOf(
        FeedSlot.PREVIOUS to SlotRuntime(),
        FeedSlot.CURRENT to SlotRuntime(),
        FeedSlot.NEXT to SlotRuntime(),
    )

    fun setItems(values: Collection<FeedMedia>) {
        val oldId = currentId()
        items = values.associateBy { it.id }
        reconcileSelection()
        if (currentId() != oldId) completionRecordedFor = null
        if (order != null) {
            token += 1
            rebuildSlots(autoPlay = playWhenReady)
        }
        publish()
    }

    fun setOrder(value: MaterializedOrder) {
        val oldId = selectedIndex?.let { order?.mediaIds?.getOrNull(it) }
        order = value
        selectedIndex = if (value.mediaIds.isEmpty()) {
            null
        } else {
            oldId?.let { value.mediaIds.indexOf(it).takeIf { index -> index >= 0 } }
                ?: selectedIndex?.coerceIn(0, value.mediaIds.lastIndex)
                ?: value.mediaIds.indices.firstOrNull { items.containsKey(value.mediaIds[it]) }
        }
        if (currentId() != oldId) completionRecordedFor = null
        token += 1
        rebuildSlots(autoPlay = playWhenReady)
        publish()
    }

    fun select(index: Int) {
        val ids = order?.mediaIds ?: return
        if (index !in ids.indices) return
        if (selectedIndex == index && slots.getValue(FeedSlot.CURRENT).status != SlotStatus.FAILED) {
            playWhenReady = true
            val current = slots.getValue(FeedSlot.CURRENT)
            if (current.status == SlotStatus.READY) current.player?.play()
            publish()
            return
        }
        val oldId = currentId()
        selectedIndex = index
        if (currentId() != oldId) completionRecordedFor = null
        token += 1
        rebuildSlots(autoPlay = true)
        publish()
    }

    fun select(mediaId: MediaId) {
        val index = order?.mediaIds?.indexOf(mediaId) ?: -1
        if (index >= 0) select(index)
    }

    fun setLooping(enabled: Boolean) {
        loop = enabled
        publish()
    }

    fun pause() {
        playWhenReady = false
        slots.getValue(FeedSlot.CURRENT).player?.pause()
        publish()
    }

    fun play() {
        playWhenReady = true
        val current = slots.getValue(FeedSlot.CURRENT)
        if (current.status == SlotStatus.READY) current.player?.play()
        publish()
    }

    fun saveCheckpoint(positionMs: Long) {
        val id = currentId() ?: return
        checkpoints.saveResumePosition(id, positionMs.coerceAtLeast(0L))
    }

    fun onPrepared(
        slot: FeedSlot,
        mediaId: MediaId,
        callbackToken: Long = slots.getValue(slot).callbackToken,
    ): Boolean {
        val runtime = slots.getValue(slot)
        if (callbackToken != runtime.callbackToken || runtime.item?.id != mediaId || runtime.status != SlotStatus.PREPARING) {
            return false
        }
        runtime.status = SlotStatus.READY
        runtime.error = null
        if (slot == FeedSlot.CURRENT && playWhenReady) runtime.player?.play()
        publish()
        return true
    }

    fun onPrepareFailed(
        slot: FeedSlot,
        mediaId: MediaId,
        reason: String,
        callbackToken: Long = slots.getValue(slot).callbackToken,
    ): Boolean {
        val runtime = slots.getValue(slot)
        if (callbackToken != runtime.callbackToken || runtime.item?.id != mediaId || runtime.status != SlotStatus.PREPARING) {
            return false
        }
        runtime.status = SlotStatus.FAILED
        runtime.error = reason
        if (slot == FeedSlot.CURRENT) runtime.player?.pause()
        publish()
        return true
    }

    fun onCompleted(
        mediaId: MediaId? = currentId(),
        callbackToken: Long = slots.getValue(FeedSlot.CURRENT).callbackToken,
    ): CompletionDisposition {
        val id = currentId() ?: return CompletionDisposition.IGNORED
        val runtime = slots.getValue(FeedSlot.CURRENT)
        if (mediaId != id || callbackToken != runtime.callbackToken || runtime.status != SlotStatus.READY) {
            return CompletionDisposition.IGNORED
        }
        if (completionRecordedFor != id) {
            checkpoints.markCompleted(id)
            completionRecordedFor = id
        }
        if (loop) {
            token += 1
            runtime.status = SlotStatus.PREPARING
            runtime.callbackToken = token
            runtime.player?.prepare(runtime.item ?: return CompletionDisposition.IGNORED, 0L, token)
            publish()
            return CompletionDisposition.LOOP_RELOADING
        } else {
            val next = selectedIndex?.plus(1)
            if (next != null && order?.mediaIds?.getOrNull(next) != null) {
                select(next)
                return CompletionDisposition.ADVANCED
            }
        }
        playWhenReady = false
        runtime.player?.pause()
        publish()
        return CompletionDisposition.ENDED
    }

    fun snapshot(): PlaybackSessionSnapshot = PlaybackSessionSnapshot(
        order = order,
        selectedIndex = selectedIndex,
        current = slots.getValue(FeedSlot.CURRENT).item,
        slots = FeedSlot.entries.map { slot ->
            val runtime = slots.getValue(slot)
            SlotSnapshot(slot, runtime.item, runtime.status, runtime.error)
        },
        looping = loop,
    )

    fun close() {
        token += 1
        completionRecordedFor = null
        playWhenReady = false
        slots.values.forEach { it.player?.release() }
        slots.values.forEach { it.clear() }
        publish()
    }

    internal fun callbackToken(): Long = slots.getValue(FeedSlot.CURRENT).callbackToken

    private fun reconcileSelection() {
        val ids = order?.mediaIds ?: return
        selectedIndex = selectedIndex?.takeIf { ids.getOrNull(it)?.let(items::containsKey) == true }
            ?: ids.indexOfFirst(items::containsKey).takeIf { it >= 0 }
    }

    private fun currentId(): MediaId? = selectedIndex?.let { order?.mediaIds?.getOrNull(it) }

    private fun rebuildSlots(autoPlay: Boolean) {
        playWhenReady = autoPlay
        val selected = selectedIndex
        val ids = order?.mediaIds ?: emptyList()
        val desired = mapOf(
            FeedSlot.PREVIOUS to selected?.minus(1)?.let(ids::getOrNull),
            FeedSlot.CURRENT to selected?.let(ids::getOrNull),
            FeedSlot.NEXT to selected?.plus(1)?.let(ids::getOrNull),
        )
        slots.forEach { (slot, runtime) ->
            val id = desired[slot]
            val item = id?.let(items::get)
            if (runtime.item == item && runtime.status != SlotStatus.FAILED) return@forEach
            runtime.player?.release()
            runtime.clear()
            if (item == null) return@forEach
            runtime.item = item
            runtime.status = SlotStatus.PREPARING
            runtime.callbackToken = token
            runtime.player = playerFactory.create(slot)
            runtime.player?.prepare(item, checkpoints.resumePositionMs(item.id).coerceAtLeast(0L), token)
        }
        if (!playWhenReady) slots.getValue(FeedSlot.CURRENT).player?.pause()
    }

    private fun publish() = onStateChanged(snapshot())

    private class SlotRuntime {
        var item: FeedMedia? = null
        var status: SlotStatus = SlotStatus.EMPTY
        var error: String? = null
        var player: SlotPlayer? = null
        var callbackToken: Long = -1L
        fun clear() { item = null; status = SlotStatus.EMPTY; error = null; player = null; callbackToken = -1L }
    }
}
