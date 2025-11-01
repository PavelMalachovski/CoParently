package com.coparently.app.presentation.pairing

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Screen for managing co-parent pairing and invitations.
 */
@Composable
fun PairingScreen(
    onPairSuccess: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "Co-Parent Pairing",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.partnerEmail != null) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Paired with",
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
                        Text("Unpair")
                    }
                }
            }
        } else {
            // Show invitation or send invitation section
            OutlinedTextField(
                value = uiState.invitationEmail,
                onValueChange = viewModel::updateInvitationEmail,
                label = { Text("Partner email") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                Button(
                    onClick = { viewModel.sendInvitation(onPairSuccess) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Send Invitation")
                }
            }
        }

        if (uiState.pendingInvitations.isNotEmpty()) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Pending Invitations",
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
                                text = invitation["fromUserName"] as? String ?: "Unknown",
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
                                    viewModel.acceptInvitation(
                                        invitation["id"] as? String ?: ""
                                    )
                                }
                            ) {
                                Text("Accept")
                            }
                            TextButton(
                                onClick = {
                                    viewModel.rejectInvitation(
                                        invitation["id"] as? String ?: ""
                                    )
                                }
                            ) {
                                Text("Reject")
                            }
                        }
                    }
                }
            }
        }
    }
}

