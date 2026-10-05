package cc.rocketscience.receipts.ui.reports

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cc.rocketscience.receipts.reorder.ReportOrder
import cc.rocketscience.receipts.reorder.ReportOrder.moved

/**
 * Drag-to-reorder for a short list rendered as a plain column.
 *
 * Deliberately not a LazyColumn while reordering: the reports list is short, and measuring a
 * lazy layout mid-drag is where this kind of code usually goes wrong. Every row is the same
 * height, so the target index is pure arithmetic — and that arithmetic lives in [ReportOrder],
 * tested, rather than in here.
 *
 * The drag lambdas read the live order through [rememberUpdatedState]. `pointerInput(Unit)` is
 * never restarted, so without that they would close over the list from first composition and
 * every drag would be computed against a stale order — the exact bug `CropScreen` shipped with.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    keyOf: (T) -> String,
    rowHeight: androidx.compose.ui.unit.Dp,
    onReordered: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (item: T, isDragging: Boolean, handle: Modifier) -> Unit,
) {
    val current = rememberUpdatedState(items)
    val onDrop = rememberUpdatedState(onReordered)
    val heightPx = with(LocalDensity.current) { rowHeight.toPx() }

    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    androidx.compose.foundation.layout.Column(modifier) {
        items.forEachIndexed { index, item ->
            val isDragging = index == draggingIndex
            Box(
                Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    .alpha(if (isDragging) 0.92f else 1f)
            ) {
                row(
                    item,
                    isDragging,
                    Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                draggingIndex = current.value.indexOfFirst {
                                    keyOf(it) == keyOf(item)
                                }
                                dragOffset = 0f
                            },
                            onDragEnd = {
                                val from = draggingIndex
                                if (from >= 0) {
                                    val to = ReportOrder.targetIndex(
                                        fromIndex = from,
                                        offsetY = dragOffset,
                                        itemHeight = heightPx,
                                        count = current.value.size,
                                    )
                                    if (to != from) {
                                        onDrop.value(
                                            current.value.moved(from, to).map(keyOf)
                                        )
                                    }
                                }
                                draggingIndex = -1
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                draggingIndex = -1
                                dragOffset = 0f
                            },
                        ) { _, drag ->
                            dragOffset += drag.y
                        }
                    },
                )
            }
        }
    }
}
