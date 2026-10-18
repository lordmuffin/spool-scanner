package com.bambu.nfc.ui.qr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bambu.nfc.data.qr.CameraQrScanner
import com.bambu.nfc.ui.scan.NotSpoolmanCode
import com.bambu.nfc.ui.scan.ScanViewModel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executors

@EntryPoint
@InstallIn(SingletonComponent::class)
interface QrScannerEntryPoint {
    fun cameraQrScanner(): CameraQrScanner
}

/**
 * Full-screen QR camera. Decoded payloads go to [CameraQrScanner]; ScanViewModel decides what
 * to do with them and closes this screen (state → null) after the first Spoolman code.
 * NFC reader mode stays on underneath, so a tag tap also closes it.
 */
@Composable
fun QrScannerScreen(state: ScanViewModel.QrCamera, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    // Back from the app's settings page with the permission granted.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasCameraPermission(context) }
    BackHandler(onBack = onClose)

    var torchOn by remember { mutableStateOf(false) }
    val hasFlash = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraPreview(torchOn = torchOn)
            Viewfinder()
        } else {
            PermissionRationale(
                denied = denied,
                onRequest = { launcher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { openAppSettings(context) },
            )
        }

        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onClose, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                Icon(Icons.Default.Close, "Close scanner")
            }
            if (granted && hasFlash) {
                IconButton(onClick = { torchOn = !torchOn }, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                    Icon(if (torchOn) Icons.Default.FlashlightOff else Icons.Default.FlashlightOn, if (torchOn) "Torch off" else "Torch on")
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.notice?.let {
                NotSpoolmanCode(it)
                Spacer(Modifier.height(12.dp))
            }
            if (granted) {
                Text(
                    "Fit the label's QR code in the box, about 15 cm away. Pinch to zoom, tap to focus.",
                    color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CameraPreview(torchOn: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sink = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, QrScannerEntryPoint::class.java).cameraQrScanner()
    }
    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS) // preview is always on
            setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // The QR on a 62x29 mm label is ~12 mm: analyse at up to 1080p, not the 640x480 default.
            setImageAnalysisResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    )
                    .build()
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val analyzer = QrAnalyzer(sink::submit)
        controller.setImageAnalysisAnalyzer(executor, analyzer)
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            analyzer.close()
            executor.shutdown()
        }
    }
    LaunchedEffect(torchOn) { controller.enableTorch(torchOn) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                // A controller-backed PreviewView handles tap-to-focus and pinch-to-zoom itself.
                this.controller = controller
            }
        },
    )
}

/** Dims everything but a centred square and outlines it. */
@Composable
private fun Viewfinder() {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawBehind {
                val side = size.minDimension * 0.65f
                val topLeft = Offset((size.width - side) / 2, (size.height - side) / 2)
                val radius = CornerRadius(24.dp.toPx())
                drawRect(Color.Black.copy(alpha = 0.55f))
                drawRoundRect(Color.Transparent, topLeft, ComposeSize(side, side), radius, blendMode = BlendMode.Clear)
                drawRoundRect(Color.White, topLeft, ComposeSize(side, side), radius, style = Stroke(3.dp.toPx()))
            }
    )
}

@Composable
private fun PermissionRationale(denied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Camera access needed", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Spool Scanner uses the camera only to read the QR code on a spool label. Nothing is recorded or uploaded.",
            color = Color.White, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Allow camera") }
        if (denied) {
            Spacer(Modifier.height(8.dp))
            Text(
                "If Android no longer asks, allow it in the app's settings.",
                color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onOpenSettings) { Text("Open app settings", color = Color.White) }
        }
    }
}

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
