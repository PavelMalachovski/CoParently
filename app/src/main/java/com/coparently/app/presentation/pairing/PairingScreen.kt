package com.coparently.app.presentation.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import com.coparently.app.R
import com.coparently.app.presentation.common.ConfirmationDialog
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Screen for managing co-parent pairing and invitations.
 * Stateless composable that receives navigation callbacks and ViewModel.
 *
 * @param onNavigateBack Navigation callback to go back
 * @param viewModel ViewModel for pairing operations
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onNavigateBack: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // QR Scanner launcher
    val qrScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                val data = result.data
                val invitationId = data?.getStringExtra(QRScannerActivity.EXTRA_INVITATION_ID)

                if (invitationId != null) {
                    viewModel.acceptInvitation(invitationId)
                }
            }
            android.app.Activity.RESULT_CANCELED -> {
                val message = result.data?.getStringExtra(QRScannerActivity.EXTRA_MESSAGE)
                if (message != null) {
                    viewModel.showError(message)
                }
            }
        }
    }

    // State for confirmation dialog
    var showConfirmDialog by remember { mutableStateOf(false) }
    var invitationToAccept by remember { mutableStateOf<Map<String, Any?>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pairing_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.pairing_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(24.dp)
        ) {

        if (uiState.partnerEmail != null) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.pairing_paired_with_label),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = uiState.partnerEmail ?: "",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.removePairing() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.pairing_unpair_button))
                    }
                }
            }
        } else {
            // Show invitation or send invitation section
            OutlinedTextField(
                value = uiState.invitationEmail,
                onValueChange = viewModel::updateInvitationEmail,
                label = { Text(stringResource(R.string.pairing_partner_email_label)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading,
                isError = uiState.emailError != null,
                supportingText = if (uiState.emailError != null) {
                    { Text(
                        text = uiState.emailError ?: "",
                        color = MaterialTheme.colorScheme.error
                    ) }
                } else null,
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                // Email invitation button
                Button(
                    onClick = { viewModel.sendInvitation(onNavigateBack) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.pairing_send_email_invitation))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // QR code sharing button
                OutlinedButton(
                    onClick = { viewModel.generateQRCode() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCode,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(stringResource(R.string.pairing_share_qr_code))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // QR code scanning button
                OutlinedButton(
                    onClick = {
                        val intent = android.content.Intent(
                            context,
                            QRScannerActivity::class.java
                        )
                        qrScannerLauncher.launch(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(stringResource(R.string.pairing_scan_qr_code))
                }
            }
        }

        if (uiState.pendingInvitations.isNotEmpty()) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.pairing_pending_invitations_title),
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            uiState.pendingInvitations.forEach { invitation ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = invitation["fromUserName"] as? String
                                    ?: stringResource(R.string.pairing_unknown_sender),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = invitation["fromUserEmail"] as? String ?: "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row {
                            TextButton(
                                onClick = {
                                    invitationToAccept = invitation
                                    showConfirmDialog = true
                                }
                            ) {
                                Text(stringResource(R.string.pairing_accept_button))
                            }
                            TextButton(
                                onClick = {
                                    viewModel.rejectInvitation(
                                        invitation["id"] as? String ?: ""
                                    )
                                }
                            ) {
                                Text(stringResource(R.string.pairing_reject_button))
                            }
                        }
                    }
                }
            }
        }

        // QR Code sharing dialog
        uiState.qrCodeBitmap?.let { qrBitmap ->
            if (uiState.showQRCodeDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissQRCodeDialog() },
                    title = {
                        Text(
                            text = stringResource(R.string.pairing_qr_dialog_title),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    },
                    text = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.pairing_qr_dialog_message),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )

                            Card(
                                modifier = Modifier.size(256.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = qrBitmap.asImageBitmap(),
                                        contentDescription = stringResource(
                                            R.string.pairing_qr_code_content_description
                                        ),
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            // Countdown timer for QR code expiration
                            var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }

                            LaunchedEffect(uiState.qrCodeExpirationTime) {
                                while (uiState.qrCodeExpirationTime != null && uiState.showQRCodeDialog) {
                                    currentTime = System.currentTimeMillis()
                                    delay(1000) // Update every second
                                }
                            }

                            val timeRemaining = uiState.qrCodeExpirationTime?.let { expiration ->
                                (expiration - currentTime).coerceAtLeast(0)
                            } ?: 0

                            val isExpired = timeRemaining <= 0

                            if (isExpired && uiState.qrCodeExpirationTime != null) {
                                // Show expired message and regenerate button
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.pairing_qr_expired),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Button(
                                        onClick = { viewModel.regenerateQRCode() }
                                    ) {
                                        Text(stringResource(R.string.pairing_generate_new_qr_code))
                                    }
                                }
                            } else {
                                val hours = TimeUnit.MILLISECONDS.toHours(timeRemaining)
                                val minutes = TimeUnit.MILLISECONDS.toMinutes(timeRemaining % TimeUnit.HOURS.toMillis(1))
                                val seconds = TimeUnit.MILLISECONDS.toSeconds(timeRemaining % TimeUnit.MINUTES.toMillis(1))

                                val timeText = when {
                                    hours > 0 -> stringResource(
                                        R.string.pairing_time_remaining_hours, hours, minutes
                                    )
                                    minutes > 0 -> stringResource(
                                        R.string.pairing_time_remaining_minutes, minutes, seconds
                                    )
                                    else -> stringResource(
                                        R.string.pairing_time_remaining_seconds, seconds
                                    )
                                }

                                Text(
                                    text = stringResource(R.string.pairing_expires_in, timeText),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when {
                                        hours < 1 -> MaterialTheme.colorScheme.error
                                        hours < 6 -> MaterialTheme.colorScheme.errorContainer
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.dismissQRCodeDialog() }) {
                            Text(stringResource(R.string.pairing_close))
                        }
                    }
                )
            }
        }

        // Confirmation dialog for accepting pairing
        if (showConfirmDialog && invitationToAccept != null) {
            val fromName = invitationToAccept?.get("fromUserName") as? String
                ?: stringResource(R.string.pairing_unknown_user)
            val fromEmail = invitationToAccept?.get("fromUserEmail") as? String ?: ""

            ConfirmationDialog(
                title = stringResource(R.string.pairing_confirm_title),
                message = stringResource(R.string.pairing_confirm_message, fromName, fromEmail),
                confirmText = stringResource(R.string.pairing_accept_pairing),
                dismissText = stringResource(R.string.pairing_cancel),
                onConfirm = {
                    invitationToAccept?.get("id")?.let { invId ->
                        viewModel.acceptInvitation(invId as String)
                    }
                    showConfirmDialog = false
                    invitationToAccept = null
                },
                onDismiss = {
                    showConfirmDialog = false
                    invitationToAccept = null
                }
            )
        }
    }
    }
}


