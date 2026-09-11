package com.gios.brightmailbox.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
// androidx.lifecycle.compose, NOT androidx.compose.ui.platform: that one was deprecated in
// lifecycle 2.8 and removed in Compose 1.7, which this BOM (2024.12.01) is past.
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.gios.brightmailbox.auth.Service
import com.gios.brightmailbox.scan.QrAnalyzer
import com.gios.brightmailbox.ui.theme.LocalGrid
import com.gios.brightmailbox.ui.theme.LocalType
import com.gios.brightmailbox.ui.theme.Screen as Frame
import com.gios.brightmailbox.ui.theme.Secondary
import com.gios.brightmailbox.ui.theme.T
import java.util.concurrent.Executors

/**
 * Point the camera at the code from the companion page.
 *
 * **Our own viewfinder, not a library's.** This used `com.journeyapps:zxing-android-embedded`,
 * which launches its own activity with its own layout — a scanner that looked like a different
 * app, in the middle of signing in to this one. The decoder is now [QrAnalyzer], lifted from Roll,
 * and the screen around it is ours.
 *
 * The camera settings are Roll's, for its reasons:
 *
 * - **1280×720**, not more. A code is found from its three finder squares, and at 720p a code
 *   filling a third of the frame is still about forty pixels across the smallest one. The cost of
 *   a bigger frame is not the decode, it is that the analyzer copies the whole Y plane per frame.
 * - **KEEP_ONLY_LATEST**. `TRY_HARDER` sometimes takes longer than a frame interval, and with the
 *   default back-pressure that stalls the *preview* — which reads as the viewfinder breaking when
 *   you point it at something busy. The next frame is 33 ms away and the code has not moved.
 * - **YUV_420_888**, because the analyzer reads the Y plane directly.
 */
@Composable
fun ScanScreen(vm: MailboxViewModel, service: Service) {
    val g = LocalGrid.current
    val t = LocalType.current
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    /*
     * The analyzer fires per frame, so it will read the same code twenty times in the second it
     * takes to look away. One latch, and every later frame is dropped — without it the sign-in
     * runs repeatedly and the toast from a rejected code stutters.
     */
    var handled by remember { mutableStateOf(false) }

    // One thread, shut down with the screen. A leaked executor keeps the process awake.
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    Frame {
        TopBar("SCAN")
        Spacer(Modifier.height(g * 0.6f))

        if (!granted) {
            Spacer(Modifier.height(g * 2f))
            T("The camera is off for this app.", t.copy)
            Spacer(Modifier.height(g * 0.8f))
            T(
                "Android asks once. If the question did not appear, turn the camera on for " +
                    "Mailbox in the phone's app settings, or go back and type the password.",
                t.detail,
                Secondary,
            )
            Spacer(Modifier.weight(1f))
        } else {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val view = PreviewView(ctx).apply {
                            // COMPATIBLE, not PERFORMANCE: a TextureView survives being drawn
                            // inside a Compose hierarchy that moves it, where a SurfaceView
                            // punches a hole and leaves the chrome above it black.
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            val provider = runCatching { future.get() }.getOrNull()
                                ?: return@addListener
                            val preview = Preview.Builder().build()
                                .also { it.setSurfaceProvider(view.surfaceProvider) }
                            val analysis = ImageAnalysis.Builder()
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setAspectRatioStrategy(
                                            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY,
                                        )
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(1280, 720),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                            ),
                                        )
                                        .build(),
                                )
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                .build()
                                .also { unit ->
                                    unit.setAnalyzer(
                                        executor,
                                        QrAnalyzer { text ->
                                            if (handled) return@QrAnalyzer
                                            handled = true
                                            view.post {
                                                // Back to the form first: a rejected code has to
                                                // land somewhere that can show why, and a good
                                                // one is on its way to the first sync anyway.
                                                vm.go(Screen.Password(service))
                                                vm.signInFromQr(text)
                                            }
                                        },
                                    )
                                }
                            runCatching {
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    owner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis,
                                )
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        view
                    },
                )
            }
            Spacer(Modifier.height(g * 0.7f))
            T("Point at the code on the computer.", t.detail, Secondary)
        }

        Spacer(Modifier.height(g * 0.7f))
        ActionBar(left = "BACK" to { vm.go(Screen.Password(service)) }, right = null)
        Spacer(Modifier.height(g * 0.4f))
    }
}
