package com.coparently.app.presentation.pairing

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.coparently.app.R
import com.coparently.app.domain.pairing.PairingUri
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Live camera preview that reports the first pairing code it sees.
 *
 * Requests the camera permission itself (contextually, when the scanner is
 * opened) and falls back to a rationale screen — with a link to the app's
 * system settings once the permission has been permanently denied.
 *
 * @param onCodeScanned Invoked once with a validated 6-character code.
 */
@Composable
fun QrScannerScreen(onCodeScanned: (String) -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasCameraPermission(context)) }
    var permanentlyDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            permanentlyDenied = !shouldShowCameraRationale(context)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (hasPermission) {
        CameraPreview(onCodeScanned = onCodeScanned)
    } else {
        CameraPermissionRationale(
            permanentlyDenied = permanentlyDenied,
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = { context.startActivity(appSettingsIntent(context)) }
        )
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * True once the user has denied the permission without checking "don't ask
 * again" — i.e. asking again may still succeed. False means either the
 * permission was just permanently denied, or [context] isn't an [Activity]
 * (defensively treated as "not permanent" so we never strand the user on a
 * dead-end settings screen for the wrong reason).
 */
private fun shouldShowCameraRationale(context: Context): Boolean =
    (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ?: true

private fun appSettingsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )

/** Explains why the camera is needed, and offers the right next step for the current denial state. */
@Composable
private fun CameraPermissionRationale(
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.pairing_camera_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(
                if (permanentlyDenied) {
                    R.string.pairing_camera_permission_denied_message
                } else {
                    R.string.pairing_camera_permission_message
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        if (permanentlyDenied) {
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.pairing_open_app_settings))
            }
        } else {
            Button(onClick = onRequestPermission) {
                Text(stringResource(R.string.pairing_grant_permission))
            }
        }
    }
}

/**
 * Bundles everything a single scanning session needs so the CameraX/ML Kit
 * setup functions below stay under the project's parameter-count limit.
 *
 * @param delivered Claimed exactly once (via [AtomicBoolean.compareAndSet]) by
 * the frame that first decodes a valid code, so [onCodeScanned] cannot fire
 * twice even if two in-flight frames both resolve successfully.
 * @param cameraProviderRef Set once the provider is ready, so it can be
 * unbound explicitly when the screen leaves composition instead of relying
 * solely on CameraX's own lifecycle observation.
 * @param disposed Set once the screen leaves composition. [ProcessCameraProvider.getInstance]
 * resolves asynchronously, so a back-press that lands before it completes must stop the
 * pending listener from calling `bindToLifecycle` against an already-destroyed lifecycle —
 * CameraX throws in that case rather than no-op'ing.
 */
private class ScannerSession(
    val executor: ExecutorService,
    val scanner: BarcodeScanner,
    val delivered: AtomicBoolean,
    val cameraProviderRef: AtomicReference<ProcessCameraProvider?>,
    val disposed: AtomicBoolean,
    val onCodeScanned: (String) -> Unit
)

/** Hosts the CameraX preview via [AndroidView] and overlays the scanning hint. */
@Composable
private fun CameraPreview(onCodeScanned: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val session = remember {
        ScannerSession(
            executor = Executors.newSingleThreadExecutor(),
            scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            ),
            delivered = AtomicBoolean(false),
            cameraProviderRef = AtomicReference(null),
            disposed = AtomicBoolean(false),
            onCodeScanned = onCodeScanned
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            session.disposed.set(true)
            session.cameraProviderRef.get()?.unbindAll()
            session.executor.shutdown()
            session.scanner.close()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext -> createPreviewView(viewContext, lifecycleOwner, session) }
        )
        Text(
            text = stringResource(R.string.pairing_qr_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp)
        )
    }
}

/** Creates the [PreviewView] and, once the camera is ready, binds preview + analysis to [lifecycleOwner]. */
private fun createPreviewView(
    viewContext: Context,
    lifecycleOwner: LifecycleOwner,
    session: ScannerSession
): PreviewView {
    val previewView = PreviewView(viewContext)
    val providerFuture = ProcessCameraProvider.getInstance(viewContext)
    providerFuture.addListener(
        {
            if (session.disposed.get()) return@addListener
            val provider = providerFuture.get()
            session.cameraProviderRef.set(provider)
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = buildAnalysis(session)
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        },
        ContextCompat.getMainExecutor(viewContext)
    )
    return previewView
}

private fun buildAnalysis(session: ScannerSession): ImageAnalysis {
    val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
    analysis.setAnalyzer(session.executor) { imageProxy -> analyzeFrame(imageProxy, session) }
    return analysis
}

/**
 * Analyses a single frame. [imageProxy] is closed on every path — immediately when there
 * is nothing to analyze, once ML Kit's task completes on the happy path, and in the catch
 * below when handing the frame to ML Kit throws synchronously.
 *
 * That last path matters: with [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST] CameraX will not
 * deliver another frame until the current proxy is closed, so a single unclosed proxy
 * stalls the analyzer permanently and the user is left staring at a frozen preview with no
 * error. [InputImage.fromMediaImage] rejects unsupported formats and a closed [BarcodeScanner]
 * throws from `process`, both synchronously — neither reaches `addOnCompleteListener`.
 */
@OptIn(ExperimentalGetImage::class)
private fun analyzeFrame(imageProxy: ImageProxy, session: ScannerSession) {
    if (session.delivered.get()) {
        imageProxy.close()
        return
    }
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    try {
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        session.scanner.process(input)
            .addOnSuccessListener { barcodes -> handleBarcodes(barcodes, session) }
            .addOnCompleteListener { imageProxy.close() }
    } catch (
        // Narrow on purpose: only the synchronous hand-off is guarded, and the frame is
        // dropped rather than the scanner being torn down, so the next frame retries.
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        Log.w(TAG, "Dropping a frame the barcode scanner rejected", e)
        imageProxy.close()
    }
}

private const val TAG = "QrScannerScreen"

/** Delivers the first valid pairing code found, claiming [ScannerSession.delivered] atomically so it fires once. */
private fun handleBarcodes(barcodes: List<Barcode>, session: ScannerSession) {
    barcodes.asSequence()
        .mapNotNull { it.rawValue }
        .mapNotNull { PairingUri.extractCode(it) }
        .firstOrNull()
        ?.let { code ->
            if (session.delivered.compareAndSet(false, true)) {
                session.onCodeScanned(code)
            }
        }
}
