package org.fedorahosted.freeotp.ui.tokenlist

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import org.fedorahosted.freeotp.data.OtpToken
import java.util.Collections

/**
 * Manages long-press drag-and-drop reordering over a [LazyGridState] and commits swapped
 * `(sourceId, targetId)` pairs to Room once the drag gesture ends.
 */
@Stable
class ReorderableGridState(
    val gridState: LazyGridState,
    private val onCommitMovePairs: (List<Pair<Long, Long>>) -> Unit,
) {
    var displayedTokens by mutableStateOf<List<OtpToken>>(emptyList())
        private set

    var draggedTokenId by mutableStateOf<Long?>(null)
        private set

    var dragOffset by mutableStateOf(Offset.Zero)
        private set

    private val movePairs = mutableListOf<Pair<Long, Long>>()

    fun syncExternalTokens(externalTokens: List<OtpToken>) {
        if (draggedTokenId == null) {
            displayedTokens = externalTokens
        }
    }

    fun startDragAtPoint(touchPointInGrid: Offset): Boolean {
        if (displayedTokens.size <= 1) return false
        val hitItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            touchPointInGrid.x >= info.offset.x &&
                touchPointInGrid.x <= info.offset.x + info.size.width &&
                touchPointInGrid.y >= info.offset.y &&
                touchPointInGrid.y <= info.offset.y + info.size.height
        } ?: return false

        val tokenId = hitItem.key as? Long ?: return false
        movePairs.clear()
        draggedTokenId = tokenId
        dragOffset = Offset.Zero
        return true
    }

    fun updateDrag(delta: Offset) {
        val currentDraggedId = draggedTokenId ?: return
        val nextOffset = dragOffset + delta
        dragOffset = nextOffset

        val currentItemInfo: LazyGridItemInfo = gridState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == currentDraggedId }
            ?: return

        val centerX = currentItemInfo.offset.x + currentItemInfo.size.width / 2f + nextOffset.x
        val centerY = currentItemInfo.offset.y + currentItemInfo.size.height / 2f + nextOffset.y

        val targetInfo = gridState.layoutInfo.visibleItemsInfo.firstOrNull { candidate ->
            candidate.key != currentDraggedId &&
                centerX >= candidate.offset.x &&
                centerX <= candidate.offset.x + candidate.size.width &&
                centerY >= candidate.offset.y &&
                centerY <= candidate.offset.y + candidate.size.height
        } ?: return

        val targetId = targetInfo.key as? Long ?: return
        val working = displayedTokens.toMutableList()
        val fromIndex = working.indexOfFirst { it.id == currentDraggedId }
        val toIndex = working.indexOfFirst { it.id == targetId }
        if (fromIndex == -1 || toIndex == -1 || fromIndex == toIndex) return

        Collections.swap(working, fromIndex, toIndex)
        displayedTokens = working
        movePairs.add(currentDraggedId to targetId)

        // Compensate dragOffset for the layout shift so the card stays anchored under the finger
        dragOffset += Offset(
            x = (currentItemInfo.offset.x - targetInfo.offset.x).toFloat(),
            y = (currentItemInfo.offset.y - targetInfo.offset.y).toFloat(),
        )
    }

    fun finishDrag() {
        val committedPairs = movePairs.toList()
        movePairs.clear()
        draggedTokenId = null
        dragOffset = Offset.Zero
        if (committedPairs.isNotEmpty()) {
            onCommitMovePairs(committedPairs)
        }
    }
}

@Composable
fun rememberReorderableGridState(
    tokens: List<OtpToken>,
    gridState: LazyGridState,
    onCommitMovePairs: (List<Pair<Long, Long>>) -> Unit,
): ReorderableGridState {
    val currentOnCommit by rememberUpdatedState(onCommitMovePairs)
    val state = remember(gridState) {
        ReorderableGridState(
            gridState = gridState,
            onCommitMovePairs = { pairs -> currentOnCommit(pairs) },
        )
    }
    state.syncExternalTokens(tokens)
    LaunchedEffect(tokens) {
        state.syncExternalTokens(tokens)
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
