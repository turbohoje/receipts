package cc.rocketscience.receipts.ui.receipt

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import cc.rocketscience.receipts.image.ImagePipeline
import cc.rocketscience.receipts.ui.ConfirmDialog
import cc.rocketscience.receipts.ui.ImageSourceSheet
import kotlinx.coroutines.launch
import java.io.File
import cc.rocketscience.receipts.ui.formatDate
import cc.rocketscience.receipts.ui.pickedDateToStoredMillis
import cc.rocketscience.receipts.ui.storedMillisToPickerMillis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptEditScreen(
    factory: ViewModelProvider.Factory,
    pipeline: ImagePipeline,
    onDone: () -> Unit,
    onTakePhoto: () -> Unit,
    onImageReady: (String) -> Unit,
) {
    val vm: ReceiptEditViewModel = viewModel(factory = factory)
    val scope = rememberCoroutineScope()

    var showDatePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSourceSheet by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            pipeline.importFromUri(uri)?.let(onImageReady)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "Add receipt" else "Edit receipt") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!vm.isNew) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete receipt")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // imePadding shrinks the scroll viewport when the keyboard opens, which is
                // what lets the text field scroll itself into view above it. Applied outside
                // verticalScroll so the viewport (not the content) is what shrinks.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ReceiptImage(
                file = vm.imageFile?.let { pipeline.imageFileOrNull(it) },
                onTap = { fullScreen = true },
                onAddOrReplace = { showSourceSheet = true },
                hasImage = vm.imageFile != null,
            )

            OutlinedTextField(
                value = vm.description,
                onValueChange = vm::onDescriptionChange,
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            OutlinedTextField(
                value = vm.amountText,
                onValueChange = vm::onAmountChange,
                label = { Text("Amount (${vm.currency.currencyCode})") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = vm.amountInvalid,
                supportingText = if (vm.amountInvalid) {
                    { Text("Enter an amount, e.g. 24.50") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
            )

            OutlinedTextField(
                value = formatDate(vm.date),
                onValueChange = {},
                label = { Text("Date") },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { showDatePicker = true }) { Text("Change") }
                },
            )

            Button(
                onClick = { vm.save(onDone) },
                enabled = vm.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = storedMillisToPickerMillis(vm.date),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let {
                            vm.onDateChange(pickedDateToStoredMillis(it))
                        }
                        showDatePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showSourceSheet) {
        ImageSourceSheet(
            title = if (vm.imageFile == null) "Add a photo" else "Replace the photo",
            onTakePhoto = onTakePhoto,
            onChooseFromPhotos = {
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onNoPhoto = { showSourceSheet = false },
            onDismiss = { showSourceSheet = false },
        )
    }

    val viewing = vm.imageFile?.let { pipeline.imageFileOrNull(it) }
    if (fullScreen && viewing != null) {
        FullScreenImage(file = viewing, onDismiss = { fullScreen = false })
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete receipt?",
            body = "Delete \"${vm.description.ifBlank { "this receipt" }}\"? " +
                "This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = { vm.delete(onDone) },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Thumbnail plus the add/replace affordance, above the fields. */
@Composable
private fun ReceiptImage(
    file: File?,
    hasImage: Boolean,
    onTap: () -> Unit,
    onAddOrReplace: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (file != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = file,
                    contentDescription = "Receipt photo. Tap or pinch to zoom.",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onTap)
                        // Pinching here hands off to the full-screen viewer rather than
                        // zooming in place, which would fight the vertical scroll.
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                if (zoom > 1.02f) onTap()
                            }
                        },
                )
                // Makes it discoverable that the image does something when touched.
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .padding(6.dp)
                        .size(18.dp),
                )
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onAddOrReplace),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (hasImage) "Photo missing from storage" else "No photo — tap to add one",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onAddOrReplace) {
            Text(if (file != null) "Replace photo" else "Add a photo")
        }
    }
}

/**
 * Full-screen viewer: pinch to zoom, drag to pan, double-tap to toggle.
 *
 * Note there is deliberately no tap-to-dismiss here. It was tried, and a pinch gets reported
 * as a tap often enough that the viewer closed instead of zooming — hence the explicit close
 * button.
 */
@Composable
private fun FullScreenImage(file: File, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var viewport by remember { mutableStateOf(IntSize.Zero) }

        // Panning is only meaningful once zoomed in, and must not let the image be dragged
        // off screen: the limit is however much of it currently overflows the viewport.
        fun clamp(candidate: Offset, atScale: Float): Offset {
            if (atScale <= 1f) return Offset.Zero
            val maxX = (viewport.width * (atScale - 1f) / 2f).coerceAtLeast(0f)
            val maxY = (viewport.height * (atScale - 1f) / 2f).coerceAtLeast(0f)
            return Offset(
                candidate.x.coerceIn(-maxX, maxX),
                candidate.y.coerceIn(-maxY, maxY),
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { viewport = it },
        ) {
            AsyncImage(
                model = file,
                contentDescription = "Receipt photo. Pinch to zoom.",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { tap ->
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    // Bring the tapped point to the centre. Scaling happens
                                    // about the centre, so a point p lands at
                                    // centre + (p - centre) * s + t; solving for p' == centre
                                    // gives t = (centre - p) * s.
                                    scale = DOUBLE_TAP_SCALE
                                    val centre = Offset(
                                        viewport.width / 2f,
                                        viewport.height / 2f,
                                    )
                                    offset = clamp(
                                        (centre - tap) * DOUBLE_TAP_SCALE,
                                        DOUBLE_TAP_SCALE,
                                    )
                                }
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, MAX_SCALE)
                            offset = clamp(offset + pan, next)
                            scale = next
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }

            if (scale > 1f) {
                Text(
                    "%.1f×".format(scale),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(16.dp),
                )
            }
        }
    }
}

private const val MAX_SCALE = 8f
private const val DOUBLE_TAP_SCALE = 3f
