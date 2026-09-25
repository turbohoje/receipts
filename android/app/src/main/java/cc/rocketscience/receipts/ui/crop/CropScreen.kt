package cc.rocketscience.receipts.ui.crop

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import cc.rocketscience.receipts.image.ImagePipeline
import cc.rocketscience.receipts.image.NormalizedRect
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Rectangular crop, written directly in Compose rather than pulled in as a dependency: a
 * receipt only ever needs a rectangle, and the maintained cropper libraries are Activity-based
 * and drag in AppCompat, which this Compose-only app has no theme for.
 *
 * The crop rect is kept in normalised 0..1 coordinates of the bitmap. The displayed box is
 * locked to the bitmap's aspect ratio, so view space and image space differ only by a scale
 * factor and there is no letterboxing arithmetic to get wrong.
 */
@Composable
fun CropScreen(
    pipeline: ImagePipeline,
    tempName: String,
    onCropped: (String) -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bitmap by produceState<Bitmap?>(initialValue = null, tempName) {
        value = pipeline.loadNormalized(tempName)
    }
    var crop by remember { mutableStateOf(NormalizedRect.Inset) }
    var saving by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { crop = NormalizedRect.Full }) {
                Text("Select all", color = Color.White)
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val current = bitmap
            if (current == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                val aspect = current.width.toFloat() / current.height.toFloat()
                Box(
                    Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f)
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = current.asImageBitmap(),
                        contentDescription = "Captured receipt",
                        modifier = Modifier.fillMaxSize(),
                    )
                    CropOverlay(crop = crop, onCropChange = { crop = it })
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) {
                Text("Retake")
            }
            Button(
                onClick = {
                    val source = bitmap ?: return@Button
                    if (saving) return@Button
                    saving = true
                    scope.launch {
                        val stored = pipeline.writeCropped(source, crop, tempName)
                        saving = false
                        if (stored != null) onCropped(stored)
                    }
                },
                enabled = bitmap != null && !saving,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (saving) "Saving…" else "Use photo")
            }
        }
    }
}

internal const val MIN_SIDE = 0.08f

/** Touch target for a corner. Deliberately far bigger than the drawn handle. */
private val HANDLE_TOUCH = 44.dp

/** Drawn corner bracket: arm length and stroke. */
private val BRACKET_ARM = 22.dp
private val BRACKET_STROKE = 4.dp

@Composable
private fun CropOverlay(
    crop: NormalizedRect,
    onCropChange: (NormalizedRect) -> Unit,
) {
    val density = LocalDensity.current
    val touchPx = with(density) { HANDLE_TOUCH.toPx() }
    val armPx = with(density) { BRACKET_ARM.toPx() }
    val strokePx = with(density) { BRACKET_STROKE.toPx() }

    // The gesture lambdas below live inside pointerInput(Unit), which is never restarted, so
    // they would otherwise close over the crop value from first composition and every drag
    // would be computed from the original rectangle. Read it through a State instead.
    val currentCrop = rememberUpdatedState(crop)
    val onChange = rememberUpdatedState(onCropChange)
    var grabbed by remember { mutableStateOf(Grab.NONE) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        grabbed = grabFor(
                            point = start,
                            crop = currentCrop.value,
                            width = size.width,
                            height = size.height,
                            touchRadius = touchPx,
                        )
                    },
                    onDragEnd = { grabbed = Grab.NONE },
                    onDragCancel = { grabbed = Grab.NONE },
                ) { _, drag ->
                    if (grabbed == Grab.NONE) return@detectDragGestures
                    val dx = drag.x / size.width
                    val dy = drag.y / size.height
                    onChange.value(applyGrab(currentCrop.value, grabbed, dx, dy))
                }
            }
    ) {
        drawCropChrome(crop, armPx, strokePx)
    }
}

internal enum class Grab { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

/**
 * Picks what the touch grabbed. Corners win over the body, and the nearest corner wins over a
 * merely-close one, so overlapping touch targets on a small crop still behave predictably.
 */
internal fun grabFor(
    point: Offset,
    crop: NormalizedRect,
    width: Int,
    height: Int,
    touchRadius: Float,
): Grab {
    val corners = listOf(
        Grab.TOP_LEFT to Offset(crop.left * width, crop.top * height),
        Grab.TOP_RIGHT to Offset(crop.right * width, crop.top * height),
        Grab.BOTTOM_LEFT to Offset(crop.left * width, crop.bottom * height),
        Grab.BOTTOM_RIGHT to Offset(crop.right * width, crop.bottom * height),
    )

    val nearest = corners
        .map { (grab, centre) -> grab to hypot(point.x - centre.x, point.y - centre.y) }
        .filter { it.second <= touchRadius }
        .minByOrNull { it.second }
    if (nearest != null) return nearest.first

    val inside = point.x in (crop.left * width)..(crop.right * width) &&
        point.y in (crop.top * height)..(crop.bottom * height)
    return if (inside) Grab.MOVE else Grab.NONE
}

internal fun applyGrab(crop: NormalizedRect, grab: Grab, dx: Float, dy: Float): NormalizedRect =
    when (grab) {
        Grab.NONE -> crop
        Grab.TOP_LEFT -> crop.copy(
            left = (crop.left + dx).coerceIn(0f, crop.right - MIN_SIDE),
            top = (crop.top + dy).coerceIn(0f, crop.bottom - MIN_SIDE),
        )
        Grab.TOP_RIGHT -> crop.copy(
            right = (crop.right + dx).coerceIn(crop.left + MIN_SIDE, 1f),
            top = (crop.top + dy).coerceIn(0f, crop.bottom - MIN_SIDE),
        )
        Grab.BOTTOM_LEFT -> crop.copy(
            left = (crop.left + dx).coerceIn(0f, crop.right - MIN_SIDE),
            bottom = (crop.bottom + dy).coerceIn(crop.top + MIN_SIDE, 1f),
        )
        Grab.BOTTOM_RIGHT -> crop.copy(
            right = (crop.right + dx).coerceIn(crop.left + MIN_SIDE, 1f),
            bottom = (crop.bottom + dy).coerceIn(crop.top + MIN_SIDE, 1f),
        )
        // Moving translates the whole rect, clamped so it cannot leave the image or resize.
        Grab.MOVE -> {
            val w = crop.right - crop.left
            val h = crop.bottom - crop.top
            val left = (crop.left + dx).coerceIn(0f, 1f - w)
            val top = (crop.top + dy).coerceIn(0f, 1f - h)
            NormalizedRect(left, top, left + w, top + h)
        }
    }

private fun DrawScope.drawCropChrome(
    crop: NormalizedRect,
    armPx: Float,
    strokePx: Float,
) {
    val l = crop.left * size.width
    val t = crop.top * size.height
    val r = crop.right * size.width
    val b = crop.bottom * size.height
    val shade = Color.Black.copy(alpha = 0.55f)

    // Dim everything outside the selection.
    drawRect(shade, topLeft = Offset(0f, 0f), size = Size(size.width, t))
    drawRect(shade, topLeft = Offset(0f, b), size = Size(size.width, size.height - b))
    drawRect(shade, topLeft = Offset(0f, t), size = Size(l, b - t))
    drawRect(shade, topLeft = Offset(r, t), size = Size(size.width - r, b - t))

    // Selection border, thin so the brackets read as the grabbable part.
    drawRect(
        color = Color.White.copy(alpha = 0.9f),
        topLeft = Offset(l, t),
        size = Size(r - l, b - t),
        style = Stroke(width = strokePx / 2f),
    )

    // Rule-of-thirds guides: handy for lining the crop up with a receipt's edges.
    val thirdX = (r - l) / 3f
    val thirdY = (b - t) / 3f
    val guide = Color.White.copy(alpha = 0.3f)
    for (i in 1..2) {
        drawLine(guide, Offset(l + thirdX * i, t), Offset(l + thirdX * i, b), strokeWidth = 1.5f)
        drawLine(guide, Offset(l, t + thirdY * i), Offset(r, t + thirdY * i), strokeWidth = 1.5f)
    }

    // Corner brackets, drawn inside the selection so they never fall off the image edge.
    // Arms shrink if the crop gets smaller than two arms wide, so they never cross over.
    val arm = minOf(armPx, (r - l) / 2.5f, (b - t) / 2.5f)
    val corners = listOf(
        Triple(Offset(l, t), 1f, 1f),
        Triple(Offset(r, t), -1f, 1f),
        Triple(Offset(l, b), 1f, -1f),
        Triple(Offset(r, b), -1f, -1f),
    )
    corners.forEach { (c, sx, sy) ->
        drawLine(
            Color.White,
            start = Offset(c.x, c.y),
            end = Offset(c.x + arm * sx, c.y),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )
        drawLine(
            Color.White,
            start = Offset(c.x, c.y),
            end = Offset(c.x, c.y + arm * sy),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )
    }
}
