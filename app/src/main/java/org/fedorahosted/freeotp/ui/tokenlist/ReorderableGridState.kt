package org.fedorahosted.freeotp.ui.tokenlist

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.data.OtpToken
import java.util.Collections

private const val PENDING_COMMIT_GRACE_MS = 1_500L
private const val SWAP_HYSTERESIS_X = 0.15f
private const val SWAP_HYSTERESIS_Y = 0.20f

/**
 * Manages smooth long-press drag-and-drop reordering over a [LazyGridState]:
 * - Tracks the dragged card's viewport-space position directly so layout/scroll shifts never
 *   cause visual coordinate jumps.
 * - Anchors [LazyGridState.firstVisibleItemIndex] via [LazyGridState.requestScrollToItem] when
 *   swapping across the top visible slot so dragging to index `0` never jumps the viewport while
 *   preserving `Modifier.animateItem` placement animations on displaced neighbors.
 * - Animates the released card smoothly into its target slot via a critically-damped spring and
 *   suppresses stale pre-commit Room emissions until `movePairs` finishes persisting.
 */
@Stable
class ReorderableGridState(
    val gridState: LazyGridState,
    private val scope: CoroutineScope,
    private val hapticFeedback: HapticFeedback?,
    private val horizontalPaddingPx: Float,
    private val edgeThresholdPx: Float,
    private val maxAutoScrollSpeedPxPerFrame: Float,
    private val onCommitMovePairs: (List<Pair<Long, Long>>) -> Unit,
) {
    var displayedTokens by mutableStateOf<List<OtpToken>>(emptyList())
        private set

    var draggedTokenId by mutableStateOf<Long?>(null)
        private set

    var settlingTokenId by mutableStateOf<Long?>(null)
        private set

    private var draggedVisualTopLeft by mutableStateOf(Offset.Zero)
    private var draggedItemSize: IntSize = IntSize.Zero
    private var lastKnownSlotOffset: Offset = Offset.Zero

    private val settlingAnimatable = Animatable(Offset.Zero, Offset.VectorConverter)
    private var settleJob: Job? = null

    private val movePairs = mutableListOf<Pair<Long, Long>>()
    private var pendingCommittedIds: List<Long>? = null
    private var pendingCommitUptimeMs: Long = 0L

    fun isItemManipulated(tokenId: Long): Boolean {
        return tokenId == draggedTokenId || tokenId == settlingTokenId
    }

    fun translationFor(tokenId: Long): Offset {
        return when (tokenId) {
            draggedTokenId -> {
                val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == tokenId }
                val slotOffset = if (info != null) {
                    Offset(info.offset.x.toFloat(), info.offset.y.toFloat()).also {
                        lastKnownSlotOffset = it
                    }
                } else {
                    lastKnownSlotOffset
                }
                draggedVisualTopLeft - slotOffset
            }
            settlingTokenId -> settlingAnimatable.value
            else -> Offset.Zero
        }
    }

    fun syncExternalTokens(externalTokens: List<OtpToken>) {
        val activeDragged = draggedTokenId
        if (activeDragged != null) {
            // If the dragged token disappeared externally (e.g. filter/lock), abort drag cleanly
            if (externalTokens.none { it.id == activeDragged }) {
                cancelDragImmediate()
                displayedTokens = externalTokens
            } else {
                val byId = externalTokens.associateBy { it.id }
                displayedTokens = displayedTokens.mapNotNull { byId[it.id] ?: it }
            }
            return
        }

        val expectedIds = pendingCommittedIds
        if (expectedIds != null) {
            val incomingIds = externalTokens.map { it.id }
            val elapsed = SystemClock.uptimeMillis() - pendingCommitUptimeMs
            val tokenSetChanged = incomingIds.size != expectedIds.size ||
                incomingIds.toSet() != expectedIds.toSet()
            if (incomingIds == expectedIds || tokenSetChanged || elapsed > PENDING_COMMIT_GRACE_MS) {
                pendingCommittedIds = null
                displayedTokens = externalTokens
            } else {
                // Keep optimistic post-drop order while refreshing token payloads (e.g. HOTP counter)
                val byId = externalTokens.associateBy { it.id }
                displayedTokens = displayedTokens.mapNotNull { byId[it.id] }
            }
            return
        }

        displayedTokens = externalTokens
    }

    fun startDragAtPoint(touchPointInGrid: Offset): Boolean {
        if (displayedTokens.size <= 1) return false
        val layoutInfo = gridState.layoutInfo
        val contentX = touchPointInGrid.x - horizontalPaddingPx
        val contentY = touchPointInGrid.y + layoutInfo.viewportStartOffset
        val hitItem = layoutInfo.visibleItemsInfo.firstOrNull { info ->
            contentX >= info.offset.x &&
                contentX <= info.offset.x + info.size.width &&
                contentY >= info.offset.y &&
                contentY <= info.offset.y + info.size.height
        } ?: return false

        val tokenId = hitItem.key as? Long ?: return false
        settleJob?.cancel()
        settleJob = null
        settlingTokenId = null

        movePairs.clear()
        draggedItemSize = hitItem.size
        val initialSlot = Offset(hitItem.offset.x.toFloat(), hitItem.offset.y.toFloat())
        lastKnownSlotOffset = initialSlot
        draggedVisualTopLeft = clampToViewport(initialSlot)
        draggedTokenId = tokenId
        hapticFeedback?.performHapticFeedback(HapticFeedbackType.LongPress)
        return true
    }

    fun updateDrag(delta: Offset) {
        if (draggedTokenId == null) return
        draggedVisualTopLeft = clampToViewport(draggedVisualTopLeft + delta)
        trySwapWithHoveredTarget()
    }

    internal fun onLayoutInfoUpdatedDuringDrag() {
        if (draggedTokenId != null) {
            trySwapWithHoveredTarget()
        }
    }

    internal val shouldAutoScroll: Boolean by derivedStateOf {
        draggedTokenId != null && computeAutoScrollDeltaPx() != 0f
    }

    internal suspend fun runAutoScrollLoop() {
        var lastFrameNanos = 0L
        while (draggedTokenId != null) {
            val rawDelta = computeAutoScrollDeltaPx()
            if (rawDelta == 0f) break

            val frameTimeNanos = withFrameNanos { it }
            val dtScale = if (lastFrameNanos == 0L) {
                1f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 16_666_667f).coerceIn(0.25f, 2.5f)
            }
            lastFrameNanos = frameTimeNanos

            val consumed = gridState.scrollBy(rawDelta * dtScale)
            if (consumed == 0f) break

            lastKnownSlotOffset = Offset(
                x = lastKnownSlotOffset.x,
                y = lastKnownSlotOffset.y - consumed,
            )
            trySwapWithHoveredTarget()
        }
    }

    fun finishDrag() {
        val releasedId = draggedTokenId ?: return
        val releaseTranslation = translationFor(releasedId)
        val committedPairs = movePairs.toList()
        movePairs.clear()

        if (committedPairs.isNotEmpty()) {
            pendingCommittedIds = displayedTokens.map { it.id }
            pendingCommitUptimeMs = SystemClock.uptimeMillis()
            onCommitMovePairs(committedPairs)
        }

        draggedTokenId = null
        settlingTokenId = releasedId
        settleJob?.cancel()
        settleJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                settlingAnimatable.snapTo(releaseTranslation)
                settlingAnimatable.animateTo(
                    targetValue = Offset.Zero,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
            } finally {
                if (settlingTokenId == releasedId) {
                    settlingTokenId = null
                }
            }
        }
    }

    private fun cancelDragImmediate() {
        movePairs.clear()
        draggedTokenId = null
        settleJob?.cancel()
        settleJob = null
        settlingTokenId = null
    }

    private fun clampToViewport(candidateTopLeft: Offset): Offset {
        val layoutInfo = gridState.layoutInfo
        val viewportSize = layoutInfo.viewportSize
        if (viewportSize.width <= 0 || viewportSize.height <= 0) {
            return candidateTopLeft
        }
        val contentWidth = (viewportSize.width - (horizontalPaddingPx * 2f).toInt()).coerceAtLeast(0)
        val maxX = (contentWidth - draggedItemSize.width).coerceAtLeast(0).toFloat()
        val minY = layoutInfo.viewportStartOffset.toFloat()
        val maxY = (layoutInfo.viewportEndOffset - draggedItemSize.height).coerceAtLeast(0).toFloat()
        return Offset(
            x = candidateTopLeft.x.coerceIn(0f, maxX),
            y = candidateTopLeft.y.coerceIn(minY, maxOf(minY, maxY)),
        )
    }

    private fun computeAutoScrollDeltaPx(): Float {
        val layoutInfo = gridState.layoutInfo
        val viewportStart = layoutInfo.viewportStartOffset.toFloat()
        val viewportEnd = layoutInfo.viewportEndOffset.toFloat()
        if (viewportEnd <= viewportStart || edgeThresholdPx <= 0f) return 0f

        val topY = draggedVisualTopLeft.y
        val bottomY = topY + draggedItemSize.height

        val topZoneEnd = viewportStart + edgeThresholdPx
        if (topY < topZoneEnd && gridState.canScrollBackward) {
            val fraction = ((topZoneEnd - topY) / edgeThresholdPx).coerceIn(0f, 1f)
            return -maxAutoScrollSpeedPxPerFrame * fraction
        }

        val bottomZoneStart = viewportEnd - edgeThresholdPx
        if (bottomY > bottomZoneStart && gridState.canScrollForward) {
            val fraction = ((bottomY - bottomZoneStart) / edgeThresholdPx).coerceIn(0f, 1f)
            return maxAutoScrollSpeedPxPerFrame * fraction
        }

        return 0f
    }

    private fun trySwapWithHoveredTarget(): Boolean {
        val currentDraggedId = draggedTokenId ?: return false
        val fromIndex = displayedTokens.indexOfFirst { it.id == currentDraggedId }
        if (fromIndex == -1) return false

        val visibleItems = gridState.layoutInfo.visibleItemsInfo
        val laidOutDragged = visibleItems.firstOrNull { it.key == currentDraggedId }
        if (laidOutDragged != null) {
            draggedItemSize = laidOutDragged.size
            lastKnownSlotOffset = Offset(
                x = laidOutDragged.offset.x.toFloat(),
                y = laidOutDragged.offset.y.toFloat(),
            )
            // Wait until LazyVerticalGrid measures the previous swap before evaluating another swap
            if (laidOutDragged.index != fromIndex) {
                return false
            }
        }

        val centerX = draggedVisualTopLeft.x + draggedItemSize.width / 2f
        val centerY = draggedVisualTopLeft.y + draggedItemSize.height / 2f

        val targetInfo = visibleItems.firstOrNull { candidate ->
            if (candidate.key == currentDraggedId) return@firstOrNull false
            val marginX = candidate.size.width * SWAP_HYSTERESIS_X
            val marginY = candidate.size.height * SWAP_HYSTERESIS_Y
            centerX >= candidate.offset.x + marginX &&
                centerX <= candidate.offset.x + candidate.size.width - marginX &&
                centerY >= candidate.offset.y + marginY &&
                centerY <= candidate.offset.y + candidate.size.height - marginY
        } ?: return false

        val targetId = targetInfo.key as? Long ?: return false
        val toIndex = displayedTokens.indexOfFirst { it.id == targetId }
        if (toIndex == -1 || toIndex == fromIndex) return false

        val firstVisibleIndex = gridState.firstVisibleItemIndex
        val firstVisibleOffset = gridState.firstVisibleItemScrollOffset

        val working = displayedTokens.toMutableList()
        val step = if (toIndex > fromIndex) 1 else -1
        var cursor = fromIndex
        while (cursor != toIndex) {
            val next = cursor + step
            val firstId = working[cursor].id
            val secondId = working[next].id
            Collections.swap(working, cursor, next)
            movePairs.add(firstId to secondId)
            cursor = next
        }
        displayedTokens = working

        // Prevent LazyGridScrollPosition from jumping to follow the old firstVisibleItem key
        // when dragging into or out of the top visible slot (such as index 0).
        if (minOf(fromIndex, toIndex) <= firstVisibleIndex) {
            gridState.requestScrollToItem(
                index = firstVisibleIndex,
                scrollOffset = firstVisibleOffset,
            )
        }

        hapticFeedback?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        return true
    }
}

@Composable
fun rememberReorderableGridState(
    tokens: List<OtpToken>,
    gridState: LazyGridState,
    onCommitMovePairs: (List<Pair<Long, Long>>) -> Unit,
): ReorderableGridState {
    val currentOnCommit by rememberUpdatedState(onCommitMovePairs)
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val horizontalPaddingPx = with(density) { 12.dp.toPx() }
    val edgeThresholdPx = with(density) { 64.dp.toPx() }
    val maxAutoScrollPx = with(density) { 14.dp.toPx() }

    val state = remember(gridState) {
        ReorderableGridState(
            gridState = gridState,
            scope = scope,
            hapticFeedback = hapticFeedback,
            horizontalPaddingPx = horizontalPaddingPx,
            edgeThresholdPx = edgeThresholdPx,
            maxAutoScrollSpeedPxPerFrame = maxAutoScrollPx,
            onCommitMovePairs = { pairs -> currentOnCommit(pairs) },
        )
    }

    LaunchedEffect(tokens) {
        state.syncExternalTokens(tokens)
    }

    // Initialize synchronously on first composition when displayedTokens is still empty
    if (state.displayedTokens.isEmpty() && tokens.isNotEmpty() && state.draggedTokenId == null) {
        state.syncExternalTokens(tokens)
    }

    LaunchedEffect(state.draggedTokenId) {
        if (state.draggedTokenId != null) {
            snapshotFlow { gridState.layoutInfo }.collect {
                state.onLayoutInfoUpdatedDuringDrag()
            }
        }
    }

    LaunchedEffect(state.shouldAutoScroll) {
        if (state.shouldAutoScroll) {
            state.runAutoScrollLoop()
        }
    }

    return state
}

fun Modifier.reorderableTokenGrid(reorderState: ReorderableGridState): Modifier {
    return pointerInput(reorderState) {
        detectDragGesturesAfterLongPress(
            onDragStart = { startOffset ->
                reorderState.startDragAtPoint(startOffset)
            },
            onDragCancel = {
                reorderState.finishDrag()
            },
            onDragEnd = {
                reorderState.finishDrag()
            },
            onDrag = { change, dragAmount ->
                if (reorderState.draggedTokenId != null) {
                    change.consume()
                    reorderState.updateDrag(dragAmount)
                }
            },
        )
    }
}
