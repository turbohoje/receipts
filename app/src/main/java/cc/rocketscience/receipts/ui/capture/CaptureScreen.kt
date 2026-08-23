package cc.rocketscience.receipts.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import cc.rocketscience.receipts.image.ImagePipeline
import java.util.concurrent.Executor

@Composable
fun CaptureScreen(
    pipeline: ImagePipeline,
    onCaptured: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var denied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        denied = !granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            CameraPreview(pipeline = pipeline, onCaptured = onCaptured, onBack = onBack)
        } else {
            PermissionNeeded(
                denied = denied,
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun CameraPreview(
    pipeline: ImagePipeline,
    onCaptured: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor: Executor = remember { ContextCompat.getMainExecutor(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    var flashOn by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    // Bind and unbind in one effect, holding the provider in a local val.
    //
    // The previous version kept it in a mutableStateOf and released it from a
    // DisposableEffect(provider). Because `provider` was a state delegate, onDispose read its
    // value at dispose time rather than at creation time — so the moment it flipped from null
    // to the real provider, the key changed, the old effect disposed, and it unbound the
    // camera that this coroutine was still in the middle of binding. The camera opened and
    // immediately closed, leaving a black preview.
    LaunchedEffect(Unit) {
        val cameraProvider = runCatching { ProcessCameraProvider.awaitInstance(context) }
            .getOrElse {
                error = "Could not open the camera: ${it.message}"
                return@LaunchedEffect
            }
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
            )
            // Hold the binding for as long as this screen is composed.
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Could not open the camera: ${e.message}"
        } finally {
            cameraProvider.unbindAll()
        }
    }

    imageCapture.flashMode =
        if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }
    }

    Column(
        Modifier.fillMaxSize().padding(bottom = 32.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        error?.let {
            Surface(color = Color.Black.copy(alpha = 0.6f)) {
                Text(it, color = Color.White, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Button(onClick = { flashOn = !flashOn }) {
                Text(if (flashOn) "Flash on" else "Flash off")
            }

            // Shutter
            Surface(
                shape = CircleShape,
                color = if (capturing) Color.Gray else Color.White,
                modifier = Modifier.size(76.dp),
            ) {
                IconButton(
                    onClick = {
                        if (capturing) return@IconButton
                        capturing = true
                        val target = pipeline.newTempFile()
                        imageCapture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(target).build(),
                            executor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(
                                    output: ImageCapture.OutputFileResults,
                                ) {
                                    capturing = false
                                    onCaptured(target.name)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    capturing = false
                                    target.delete()
                                    error = "Capture failed: ${exception.message}"
                                }
                            },
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {}
            }

            Spacer(Modifier.size(76.dp))
        }
    }
}

@Composable
private fun PermissionNeeded(denied: Boolean, onRequest: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (denied) "Camera access is off" else "Camera access needed",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (denied) {
                "Receipts needs the camera to photograph a receipt. You can still add one " +
                    "with \"Choose from photos\" instead."
            } else {
                "Receipts uses the camera only to photograph receipts."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(24.dp))
        if (!denied) {
            Button(onClick = onRequest) { Text("Allow camera") }
            Spacer(Modifier.height(8.dp))
        }
        Button(onClick = onBack) { Text("Go back") }
    }
}
