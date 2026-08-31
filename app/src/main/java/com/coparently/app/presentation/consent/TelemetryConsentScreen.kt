package com.coparently.app.presentation.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R

/**
 * The first-run question about analytics and crash reporting (REL-5).
 *
 * **It is a question, not a notice.** There is no dismiss, no "continue" that quietly means yes,
 * and the two buttons are the two answers — which is what makes it a consent rather than a
 * disclosure. Declining is the *first* of the two and carries no penalty anywhere in the app;
 * everything works identically either way, and the copy says so rather than leaving a parent to
 * wonder what they are giving up.
 *
 * **It runs before sign-in**, because both SDKs would otherwise have collected a session before
 * anyone had been asked. That is also why it is a route of its own rather than a step inside the
 * onboarding wizard: the wizard belongs to an account, and this question is older than the account.
 *
 * The answer is changeable afterwards from Settings → App, which is the other half of what makes
 * this a consent — a decision you cannot revisit is not one.
 *
 * @param onAnswered Called once the answer is stored, to leave this screen.
 * @param viewModel Stores the answer.
 */
@Composable
fun TelemetryConsentScreen(
    onAnswered: () -> Unit,
    viewModel: TelemetryConsentViewModel = hiltViewModel()
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = stringResource(R.string.consent_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.consent_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // What is *not* covered by the answer, said plainly. A parent who reads "we collect
        // usage data" in an app holding their child's medical profile will assume the worst
        // unless the boundary is stated, and the boundary is real: nothing a family types
        // has ever been part of this.
        Text(
            text = stringResource(R.string.consent_never),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.consent_changeable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.size(8.dp))

        // Decline first and as the visually quieter control. The order is deliberate: a consent
        // whose refusal is harder to find than its acceptance is a dark pattern, and this one
        // costs the user nothing.
        OutlinedButton(
            onClick = {
                viewModel.answer(granted = false)
                onAnswered()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.consent_decline))
        }
        Button(
            onClick = {
                viewModel.answer(granted = true)
                onAnswered()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.consent_accept))
        }
    }
}
