package com.coparently.app.presentation.pairing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.remote.firebase.CoParentPairingService
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.coparently.app.presentation.theme.CoPlanlyTheme
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch

/**
 * Activity for scanning QR codes to accept co-parent pairing invitations.
 * Uses ML Kit for barcode scanning and handles camera permissions.
 */
class QRScannerActivity : AppCompatActivity() {

    private lateinit var barcodeScanner: BarcodeScanner

    // Camera permission launcher
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, scanner will start automatically in composable
            Log.d("QRScannerActivity", "Camera permission granted")
        } else {
            // Permission denied, show message and finish
            Log.w("QRScannerActivity", "Camera permission denied")
            finishWithResult(false, "Camera permission required for QR scanning")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize barcode scanner
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        setContent {
            CoPlanlyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    QRScannerScreen(
                        onQRCodeScanned = { qrContent ->
                            handleQRCodeScanned(qrContent)
                        },
                        onPermissionRequest = {
                            requestCameraPermission()
                        },
                        onBackPressed = {
                            finishWithResult(false, "Scanning cancelled")
                        }
                    )
                }
            }
        }
    }

    private fun requestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
                Log.d("QRScannerActivity", "Camera permission already granted")
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                // Show rationale and request permission
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                // Request permission directly
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun handleQRCodeScanned(qrContent: String) {
        Log.d("QRScannerActivity", "QR Code scanned: $qrContent")

        try {
            // Parse QR code content
            val qrData = parseQRCodeContent(qrContent)
            if (qrData != null) {
                // Valid QR code, process the invitation
                processInvitation(qrData)
            } else {
                finishWithResult(false, "Invalid QR code format")
            }
        } catch (e: Exception) {
            Log.e("QRScannerActivity", "Error processing QR code", e)
            finishWithResult(false, "Error processing QR code: ${e.message}")
        }
    }

    private fun parseQRCodeContent(content: String): QRInvitationData? {
        return try {
            // Expected format: {"type":"coparent_invitation","invitationId":"...","inviterName":"...","inviterEmail":"...","timestamp":...}
            val jsonContent = content.trim()

            // Simple JSON parsing (in production, use proper JSON library)
            if (!jsonContent.contains("\"type\":\"coparent_invitation\"")) {
                return null
            }

            val invitationId = extractJsonValue(jsonContent, "invitationId")
            val inviterName = extractJsonValue(jsonContent, "inviterName")
            val inviterEmail = extractJsonValue(jsonContent, "inviterEmail")
            val timestamp = extractJsonValue(jsonContent, "timestamp")?.toLongOrNull()

            if (invitationId != null && inviterName != null && inviterEmail != null && timestamp != null) {
                QRInvitationData(invitationId, inviterName, inviterEmail, timestamp)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("QRScannerActivity", "Error parsing QR content", e)
            null
        }
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val keyPattern = "\"$key\":\""
        val keyIndex = json.indexOf(keyPattern)
        if (keyIndex == -1) return null

        val valueStart = keyIndex + keyPattern.length
        val valueEnd = json.indexOf("\"", valueStart)
        return if (valueEnd != -1) {
            json.substring(valueStart, valueEnd)
        } else {
            null
        }
    }

    private fun processInvitation(qrData: QRInvitationData) {
        // Check if invitation is expired (24 hours)
        val currentTime = System.currentTimeMillis()
        val expiryTime = qrData.timestamp + (24 * 60 * 60 * 1000) // 24 hours in milliseconds

        if (currentTime > expiryTime) {
            finishWithResult(false, "QR code has expired. Please ask your partner to generate a new one.")
            return
        }

        // Return success with invitation data
        val resultIntent = Intent().apply {
            putExtra(EXTRA_INVITATION_ID, qrData.invitationId)
            putExtra(EXTRA_INVITER_NAME, qrData.inviterName)
            putExtra(EXTRA_INVITER_EMAIL, qrData.inviterEmail)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun finishWithResult(success: Boolean, message: String? = null) {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_SUCCESS, success)
            message?.let { putExtra(EXTRA_MESSAGE, it) }
        }
        setResult(if (success) RESULT_OK else RESULT_CANCELED, resultIntent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        barcodeScanner.close()
    }

    companion object {
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_INVITATION_ID = "invitation_id"
        const val EXTRA_INVITER_NAME = "inviter_name"
        const val EXTRA_INVITER_EMAIL = "inviter_email"
    }
}

private data class QRInvitationData(
    val invitationId: String,
    val inviterName: String,
    val inviterEmail: String,
    val timestamp: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRScannerScreen(
    onQRCodeScanned: (String) -> Unit,
    onPermissionRequest: () -> Unit,
    onBackPressed: () -> Unit,
    viewModel: QRScannerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            onPermissionRequest()
        }
    }

    // Check permission status periodically
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            hasCameraPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan QR Code") },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            when {
                !hasCameraPermission -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = "Camera permission required",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Please grant camera permission to scan QR codes for co-parent invitations.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = onPermissionRequest) {
                            Text("Grant Permission")
                        }
                    }
                }
                uiState.isScanning -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Processing QR code...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = "Position QR code within the camera view",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Make sure the QR code is well lit and fits within the frame.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )

                        // Placeholder for camera preview
                            Card(
                                modifier = Modifier
                                    .size(280.dp)
                                    .padding(16.dp)
                            ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Camera Preview",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (uiState.errorMessage != null) {
                            Text(
                                text = uiState.errorMessage ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * ViewModel for QR Scanner Activity.
 * Handles the logic for processing scanned QR codes.
 */
@dagger.hilt.android.lifecycle.HiltViewModel
class QRScannerViewModel @Inject constructor(
    private val pairingService: CoParentPairingService,
    private val firebaseAuthService: FirebaseAuthService
) : ViewModel() {

    private val _uiState = MutableStateFlow(QRScannerUiState())
    val uiState: StateFlow<QRScannerUiState> = _uiState.asStateFlow()

    fun processQRCode(invitationId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true, errorMessage = null)

            try {
                val currentUser = firebaseAuthService.getCurrentUser()
                if (currentUser == null) {
                    _uiState.value = _uiState.value.copy(
                        isScanning = false,
                        errorMessage = "User not authenticated. Please sign in."
                    )
                    return@launch
                }

                // Accept the invitation
                val result = pairingService.acceptInvitation(invitationId, currentUser.uid)
                result.fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(
                            isScanning = false,
                            isSuccess = true
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isScanning = false,
                            errorMessage = error.message ?: "Failed to accept invitation"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    errorMessage = "Error processing QR code: ${e.message}"
                )
            }
        }
    }

    fun resetState() {
        _uiState.value = QRScannerUiState()
    }
}

data class QRScannerUiState(
    val isScanning: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null
)
