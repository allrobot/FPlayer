package io.github.fplayer.feature.feed

/** Gesture phase from the Feed contract. Values are stable for UI and accessibility tests. */
enum class FeedGesturePhase { IDLE, TRACKING, DIRECTION_LOCKED, SETTLING, ACTIVE }

enum class FeedGestureDirection { NONE, VERTICAL, HORIZONTAL }

enum class FeedPagingOutcome { NONE, PAGE_CHANGED, OPEN_GRID, BOUNDARY }

data class FeedPagingSnapshot(
    val pageIndex: Int,
    val pageCount: Int,
    val phase: FeedGesturePhase,
    val direction: FeedGestureDirection,
    val offsetPx: Float,
    val settleTarget: Int?,
    val outcome: FeedPagingOutcome,
)

data class FeedPagingConfig(
    val touchSlopPx: Float = 8f,
    val directionRatio: Float = 1.25f,
    val pageThresholdFraction: Float = 0.18f,
    val velocityThresholdPxPerSecond: Float = 1100f,
    val velocityMinimumDistancePx: Float = 24f,
    val horizontalThresholdFraction: Float = 0.20f,
    val boundaryDamping: Float = 0.35f,
    val settleCompletionFraction: Float = 0.95f,
) {
    init {
        require(touchSlopPx >= 0f)
        require(directionRatio >= 1f)
        require(pageThresholdFraction in 0f..1f)
        require(velocityThresholdPxPerSecond > 0f)
        require(velocityMinimumDistancePx >= 0f)
        require(horizontalThresholdFraction in 0f..1f)
        require(boundaryDamping in 0f..1f)
        require(settleCompletionFraction in 0f..1f)
    }
}

/**
 * Deterministic Feed paging contract. It never starts playback; callers promote only a settled
 * target to the active PlaybackSession item.
 */
class FeedPagingStateMachine(
    pageCount: Int,
    private val pageExtentPx: Float,
    private val config: FeedPagingConfig = FeedPagingConfig(),
) {
    private var pageCountValue = pageCount.coerceAtLeast(0)
    private var pageIndexValue = 0
    private var phaseValue = FeedGesturePhase.IDLE
    private var directionValue = FeedGestureDirection.NONE
    private var offsetValue = 0f
    private var settleTargetValue: Int? = null
    private var outcomeValue = FeedPagingOutcome.NONE
    private var activePageValue: Int? = if (pageCountValue > 0) 0 else null

    init { require(pageExtentPx > 0f) }

    fun setPageCount(count: Int, pageIndex: Int = pageIndexValue) {
        pageCountValue = count.coerceAtLeast(0)
        pageIndexValue = pageIndex.coerceIn(0, (pageCountValue - 1).coerceAtLeast(0))
        activePageValue = pageIndexValue.takeIf { pageCountValue > 0 }
        reset()
    }

    fun onDown() {
        if (phaseValue == FeedGesturePhase.SETTLING) return
        phaseValue = FeedGesturePhase.TRACKING
        directionValue = FeedGestureDirection.NONE
        offsetValue = 0f
        settleTargetValue = null
        outcomeValue = FeedPagingOutcome.NONE
    }

    /** Pass cumulative displacement from the initial pointer down. */
    fun onMove(dxPx: Float, dyPx: Float) {
        if (phaseValue != FeedGesturePhase.TRACKING && phaseValue != FeedGesturePhase.DIRECTION_LOCKED) return
        val absX = kotlin.math.abs(dxPx)
        val absY = kotlin.math.abs(dyPx)
        if (directionValue == FeedGestureDirection.NONE) {
            if (maxOf(absX, absY) <= config.touchSlopPx) return
            directionValue = when {
                absY >= config.directionRatio * absX -> FeedGestureDirection.VERTICAL
                absX >= config.directionRatio * absY -> FeedGestureDirection.HORIZONTAL
                else -> return
            }
            phaseValue = FeedGesturePhase.DIRECTION_LOCKED
        }
        offsetValue = when (directionValue) {
            FeedGestureDirection.VERTICAL -> verticalOffset(dyPx)
            FeedGestureDirection.HORIZONTAL -> dxPx
            FeedGestureDirection.NONE -> 0f
        }
    }

    fun onUp(velocityX: Float = 0f, velocityY: Float = 0f) {
        if (phaseValue != FeedGesturePhase.DIRECTION_LOCKED) {
            reset()
            return
        }
        when (directionValue) {
            FeedGestureDirection.HORIZONTAL -> finishHorizontal(offsetValue, velocityX)
            FeedGestureDirection.VERTICAL -> finishVertical(offsetValue, velocityY)
            FeedGestureDirection.NONE -> reset()
        }
    }

    /** Feed animation calls this with 0..1 progress toward the selected target. */
    fun onSettleProgress(progress: Float) {
        if (phaseValue != FeedGesturePhase.SETTLING) return
        val bounded = progress.coerceIn(0f, 1f)
        offsetValue = if (settleTargetValue == pageIndexValue) 0f else -pageExtentPx * (1f - bounded)
        if (bounded >= config.settleCompletionFraction) {
            val target = settleTargetValue
            if (target != null && target != pageIndexValue) {
                pageIndexValue = target
                activePageValue = target
                outcomeValue = FeedPagingOutcome.PAGE_CHANGED
            }
            phaseValue = FeedGesturePhase.ACTIVE
            offsetValue = 0f
            settleTargetValue = null
            directionValue = FeedGestureDirection.NONE
        }
    }

    fun snapshot(): FeedPagingSnapshot = FeedPagingSnapshot(
        pageIndexValue,
        pageCountValue,
        phaseValue,
        directionValue,
        offsetValue,
        settleTargetValue,
        outcomeValue,
    )

    fun consumeOutcome(): FeedPagingOutcome = outcomeValue.also { outcomeValue = FeedPagingOutcome.NONE }

    private fun verticalOffset(dyPx: Float): Float {
        val movingPastStart = pageIndexValue == 0 && dyPx > 0f
        val movingPastEnd = pageIndexValue == pageCountValue - 1 && dyPx < 0f
        return if (movingPastStart || movingPastEnd) dyPx * config.boundaryDamping else dyPx
    }

    private fun finishVertical(offset: Float, velocity: Float) {
        val distanceTrigger = kotlin.math.abs(offset) >= pageExtentPx * config.pageThresholdFraction
        val speedTrigger = kotlin.math.abs(offset) >= config.velocityMinimumDistancePx && kotlin.math.abs(velocity) >= config.velocityThresholdPxPerSecond
        val direction = when {
            offset < 0f || velocity < -config.velocityThresholdPxPerSecond -> 1
            offset > 0f || velocity > config.velocityThresholdPxPerSecond -> -1
            else -> 0
        }
        val target = (pageIndexValue + direction).takeIf { it in 0 until pageCountValue }
        if ((distanceTrigger || speedTrigger) && target != null) beginSettle(target)
        else {
            if (distanceTrigger || speedTrigger) outcomeValue = FeedPagingOutcome.BOUNDARY
            beginSettle(pageIndexValue)
        }
    }

    private fun finishHorizontal(offset: Float, velocity: Float) {
        val distanceTrigger = -offset >= pageExtentPx * config.horizontalThresholdFraction
        val speedTrigger = -offset >= config.velocityMinimumDistancePx && velocity <= -config.velocityThresholdPxPerSecond
        if (distanceTrigger || speedTrigger) {
            phaseValue = FeedGesturePhase.ACTIVE
            outcomeValue = FeedPagingOutcome.OPEN_GRID
        } else beginSettle(pageIndexValue)
    }

    private fun beginSettle(target: Int) {
        settleTargetValue = target
        phaseValue = FeedGesturePhase.SETTLING
    }

    private fun reset() {
        phaseValue = if (pageCountValue > 0) FeedGesturePhase.ACTIVE else FeedGesturePhase.IDLE
        directionValue = FeedGestureDirection.NONE
        offsetValue = 0f
        settleTargetValue = null
    }
}
