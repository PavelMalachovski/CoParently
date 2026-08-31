package com.coparently.app.presentation.parentingplan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.data.repository.ParentingPlanPair

/**
 * One question, both answers, and the tick that turns them into an agreement (MON-5).
 *
 * **Your answer is editable and theirs is not, in the same sheet.** That is the point of showing
 * them together: a parent should be able to see that they cannot write the other's half, not
 * merely be prevented from it by a rule they never meet.
 *
 * @param questionId The catalogue id being answered.
 * @param plan Both halves as they stand.
 * @param coParentName What to call the other parent, already resolved through `ParentNames`.
 * @param onSaveAnswer Records this parent's answer.
 * @param onAgree Ticks agreement with the co-parent's wording, or unticks it when given null.
 * @param onDismiss Closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
// One callback per action the sheet offers, plus both halves and the name to label the other
// with. Collapsing them into a state object would hide which of the two halves is editable,
// which is the one thing this screen exists to make visible.
@Suppress("LongParameterList")
fun PlanQuestionSheet(
    questionId: String,
    plan: ParentingPlanPair,
    coParentName: String,
    onSaveAnswer: (String) -> Unit,
    onAgree: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val prompt = PlanStrings.questionPrompt(questionId) ?: return
    val theirAnswer = plan.theirs?.answerTo(questionId)
    // Seeded once per question rather than tracked: re-seeding on every emission would take the
    // cursor away mid-sentence when the co-parent's own write arrives through the listener.
    var draft by remember(questionId) { mutableStateOf(plan.yours.answers[questionId].orEmpty()) }
    val agreed = theirAnswer != null && plan.yours.agreedTo[questionId] == theirAnswer

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = stringResource(prompt), style = MaterialTheme.typography.titleMedium)

            YourAnswerField(draft = draft, onDraftChange = { draft = it })

            TheirAnswer(theirAnswer = theirAnswer, coParentName = coParentName)

            if (theirAnswer != null) {
                AgreementRow(
                    agreed = agreed,
                    onToggle = { onAgree(if (agreed) null else theirAnswer) }
                )
            }

            Button(
                onClick = {
                    onSaveAnswer(draft)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.parenting_plan_save))
            }
        }
    }
}

/** The half this parent may edit, labelled in the theme's primary so the pair is obvious. */
@Composable
private fun YourAnswerField(draft: String, onDraftChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.parenting_plan_your_answer),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            placeholder = { Text(stringResource(R.string.parenting_plan_answer_hint)) }
        )
    }
}

@Composable
private fun TheirAnswer(theirAnswer: String?, coParentName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.parenting_plan_their_answer, coParentName),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = theirAnswer
                    ?: stringResource(R.string.parenting_plan_their_answer_missing, coParentName),
                style = MaterialTheme.typography.bodyMedium,
                color = if (theirAnswer == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
private fun AgreementRow(agreed: Boolean, onToggle: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = agreed, onCheckedChange = { onToggle() })
            Text(
                text = stringResource(R.string.parenting_plan_agree),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            text = stringResource(R.string.parenting_plan_agree_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
