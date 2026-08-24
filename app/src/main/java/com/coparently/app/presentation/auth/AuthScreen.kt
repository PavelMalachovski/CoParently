package com.coparently.app.presentation.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.presentation.theme.CoPlanlyColors
import com.coparently.app.presentation.theme.dimensions
import com.coparently.app.utils.findActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Authentication screen for login and registration.
 * Enhanced with modern Material 3 design and animations.
 */
@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    onViewModelReady: ((AuthViewModel) -> Unit)? = null,
    viewModel: AuthViewModel = hiltViewModel()
) {
    // Call the callback when viewModel is ready
    viewModel.onAuthSuccess = onAuthSuccess
    onViewModelReady?.invoke(viewModel)
    val uiState by viewModel.uiState.collectAsState()
    val dims = dimensions()
    val coroutineScope = rememberCoroutineScope()
    // Credential Manager hosts its UI on an Activity and refuses an application context.
    // Compose may hand this composable a ContextWrapper, so unwrap rather than cast.
    val activity = LocalContext.current.findActivity()

    // Gradient background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        CoPlanlyColors.BrandPrimary.copy(alpha = 0.1f),
                        Color.Transparent,
                        CoPlanlyColors.BrandSecondary.copy(alpha = 0.05f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(dims.paddingLarge)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // Logo & Branding Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 40.dp)
            ) {
                // Animated logo with pulse effect
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val pulse by infiniteTransition.animateFloat(
                    initialValue = 0.95f,
                    targetValue = 1.05f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse"
                )

                Icon(
                    imageVector = Icons.Default.ChildCare,
                    contentDescription = stringResource(R.string.auth_cd_logo),
                    modifier = Modifier
                        .size(dims.iconSize * 3.33f) // ~80dp for compact
                        .graphicsLayer {
                            scaleX = pulse
                            scaleY = pulse
                        },
                    tint = CoPlanlyColors.BrandPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "CoPlanly",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Text(
                    text = stringResource(R.string.auth_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // Auth Card with elevated design
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 8.dp
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Title
                    Text(
                        text = if (uiState.isSignInMode) {
                            stringResource(R.string.auth_welcome_back)
                        } else {
                            stringResource(R.string.auth_create_your_account)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = if (uiState.isSignInMode) {
                            stringResource(R.string.auth_sign_in_subtitle)
                        } else {
                            stringResource(R.string.auth_sign_up_subtitle)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Email Field
                    OutlinedTextField(
                        value = uiState.email,
                        onValueChange = viewModel::updateEmail,
                        label = { Text(stringResource(R.string.auth_email_label)) },
                        placeholder = { Text(stringResource(R.string.auth_email_placeholder)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Email,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isLoading,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Password Field
                    var passwordVisible by remember { mutableStateOf(false) }

                    OutlinedTextField(
                        value = uiState.password,
                        onValueChange = viewModel::updatePassword,
                        label = { Text(stringResource(R.string.auth_password_label)) },
                        placeholder = { Text(stringResource(R.string.auth_password_placeholder)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = if (passwordVisible) {
                                        stringResource(R.string.auth_hide_password)
                                    } else {
                                        stringResource(R.string.auth_show_password)
                                    }
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isLoading,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Sign-in only: a fresh account has no password to have forgotten. Needs only
                    // the email field — the reset link goes to the inbox, not through the form.
                    if (uiState.isSignInMode) {
                        TextButton(
                            onClick = { viewModel.sendPasswordReset() },
                            modifier = Modifier.align(Alignment.End),
                            enabled = !uiState.isLoading
                        ) {
                            Text(
                                text = stringResource(R.string.auth_forgot_password_link),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    // Reset-link confirmation. Resolved outside the block for the same reason as
                    // the error card below: the exit animation runs after the value is null.
                    val resetSentTo = uiState.resetEmailSentTo
                    AnimatedVisibility(
                        visible = resetSentTo != null,
                        enter = slideInVertically() + fadeIn(),
                        exit = slideOutVertically() + fadeOut()
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(dims.paddingSmall * 1.5f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Email,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = resetSentTo
                                        ?.let { stringResource(R.string.auth_reset_email_sent, it) }
                                        .orEmpty(),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    // Error Message
                    // Resolved outside the block: AnimatedVisibility still runs its content
                    // while the card animates out, when the error is already null.
                    val errorRes = uiState.error?.messageRes()
                    AnimatedVisibility(
                        visible = errorRes != null,
                        enter = slideInVertically() + fadeIn(),
                        exit = slideOutVertically() + fadeOut()
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(dims.paddingSmall * 1.5f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = errorRes?.let { stringResource(it) }.orEmpty(),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Google Sign-In Button
                    OutlinedButton(
                        onClick = {
                            activity?.let { host ->
                                coroutineScope.launch { viewModel.signInWithGoogle(host) }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(dims.buttonHeight),
                        enabled = !uiState.isLoading,
                        shape = RoundedCornerShape(16.dp),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                            width = 2.dp
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.auth_google_sign_in),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Sign-in only: offering a stored password while creating an account is
                    // nonsense. Deliberately a button rather than a sheet that opens with the
                    // screen - the system UI stays behind an explicit request, which matters
                    // most right after a deliberate sign-out.
                    if (uiState.isSignInMode) {
                        TextButton(
                            onClick = {
                                activity?.let { host ->
                                    coroutineScope.launch { viewModel.signInWithSavedPassword(host) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isLoading
                        ) {
                            Text(
                                text = stringResource(R.string.auth_saved_password_sign_in),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    // Divider with "or"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.auth_divider_or),
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f))
                    }

                    // Primary Action Button
                    Button(
                        onClick = {
                            activity?.let { host ->
                                if (uiState.isSignInMode) {
                                    viewModel.signIn(host, onAuthSuccess)
                                } else {
                                    viewModel.signUp(host, onAuthSuccess)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(dims.buttonHeight),
                        enabled = !uiState.isLoading &&
                                  uiState.email.isNotBlank() &&
                                  uiState.password.isNotBlank(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CoPlanlyColors.BrandPrimary
                        )
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = if (uiState.isSignInMode) {
                                    stringResource(R.string.auth_action_sign_in)
                                } else {
                                    stringResource(R.string.auth_action_create_account)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Toggle Sign In/Sign Up
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (uiState.isSignInMode) {
                        stringResource(R.string.auth_no_account_question)
                    } else {
                        stringResource(R.string.auth_have_account_question)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TextButton(onClick = { viewModel.toggleSignInMode() }) {
                    Text(
                        text = if (uiState.isSignInMode) {
                            stringResource(R.string.auth_action_sign_up)
                        } else {
                            stringResource(R.string.auth_action_sign_in)
                        },
                        fontWeight = FontWeight.Bold,
                        color = CoPlanlyColors.BrandPrimary
                    )
                }
            }
        }
    }
}

