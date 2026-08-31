package com.coparently.app.presentation.parentingplan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.data.repository.ParentingPlanPair
import com.coparently.app.domain.parentingplan.ParentingPlanCatalogue
import com.coparently.app.domain.parentingplan.ParentingPlanComparison
import com.coparently.app.domain.parentingplan.PlanQuestionStatus
import com.coparently.app.presentation.common.GroupLabel
import com.coparently.app.presentation.common.ParentNames
import com.coparently.app.presentation.common.PillChip
import com.coparently.app.presentation.common.SectionGroup
import com.coparently.app.presentation.common.SectionRow
import com.coparently.app.presentation.common.rememberParentNames

/**
 * The parenting plan: a question list, each parent's answer beside it, and where they agree
 * (MON-5).
 *
 * **Two halves that are never edited together.** Tapping a question opens a sheet showing your
 * answer (editable) beside the co-parent's (not), which is the same preview-then-edit anatomy the
 * calendar uses and the only one that makes "you cannot write their half" visible rather than
 * merely true.
 *
 * The screen says outright that this is not the Ministry of Justice's form. It covers the areas
 * the law and that form name, in this project's wording, and a parent who takes it to a mediator
 * has to know which of those two things they are holding.
 *
 * @param onNavigateBack Pops back to wherever the plan was opened from.
 * @param viewModel Supplies both halves and records this parent's edits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentingPlanScreen(
    onNavigateBack: () -> Unit,
    viewModel: ParentingPlanViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val parents by viewModel.parents.collectAsState()
    val names = rememberParentNames(parents)
    var openQuestionId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.parenting_plan_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.parenting_plan_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is ParentingPlanUiState.Loading -> LoadingBody(Modifier.padding(padding))
            is ParentingPlanUiState.NoCoParent -> NoCoParentBody(Modifier.padding(padding))
            is ParentingPlanUiState.Ready -> {
                PlanBody(
                    plan = state.plan,
                    names = names,
                    coParentUid = state.coParentUid,
                    onOpenQuestion = { openQuestionId = it },
                    modifier = Modifier.padding(padding)
                )
                openQuestionId?.let { questionId ->
                    PlanQuestionSheet(
                        questionId = questionId,
                        plan = state.plan,
                        coParentName = names.labelForUid(state.coParentUid),
                        onSaveAnswer = { viewModel.answer(questionId, it) },
                        onAgree = { viewModel.agree(questionId, it) },
                        onDismiss = { openQuestionId = null }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingBody(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NoCoParentBody(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.parenting_plan_needs_coparent_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.parenting_plan_needs_coparent_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PlanBody(
    plan: ParentingPlanPair,
    names: ParentNames,
    coParentUid: String,
    onOpenQuestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val total = ParentingPlanCatalogue.questions.size
    val agreed = remember(plan) { ParentingPlanComparison.agreedCount(plan.yours, plan.theirs) }
    val answered = remember(plan) { ParentingPlanComparison.answeredByYou(plan.yours) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "header") {
            PlanHeader(agreed = agreed, answered = answered, total = total)
        }
        items(ParentingPlanCatalogue.sections, key = { it.id }) { section ->
            Column {
                PlanStrings.sectionTitle(section.id)?.let { GroupLabel(stringResource(it)) }
                SectionGroup {
                    section.questions.forEachIndexed { index, question ->
                        if (index > 0) Divider()
                        QuestionRow(
                            questionId = question.id,
                            plan = plan,
                            names = names,
                            coParentUid = coParentUid,
                            onClick = { onOpenQuestion(question.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanHeader(agreed: Int, answered: Int, total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.parenting_plan_progress_agreed, agreed, total),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = stringResource(R.string.parenting_plan_progress_answered, answered, total),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.parenting_plan_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.parenting_plan_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QuestionRow(
    questionId: String,
    plan: ParentingPlanPair,
    names: ParentNames,
    coParentUid: String,
    onClick: () -> Unit
) {
    val prompt = PlanStrings.questionPrompt(questionId) ?: return
    val status = ParentingPlanComparison.statusOf(questionId, plan.yours, plan.theirs)
    val yourAnswer = plan.yours.answerTo(questionId)

    SectionRow(
        title = stringResource(prompt),
        supporting = yourAnswer ?: stringResource(R.string.parenting_plan_not_answered),
        onClick = onClick,
        trailing = { StatusChip(status, names.labelForUid(coParentUid)) }
    )
}

/**
 * The one-word answer to "where does this question stand".
 *
 * `AGREED` is the only state that gets a colour of its own, and it is the theme's primary rather
 * than a parent hue: agreement belongs to the pair, and pink or blue here would read as one
 * parent's, which is the colour rule `ParentColors` exists to keep.
 */
@Composable
private fun StatusChip(status: PlanQuestionStatus, coParentName: String) {
    val label = when (status) {
        PlanQuestionStatus.UNANSWERED -> stringResource(R.string.parenting_plan_status_unanswered)
        PlanQuestionStatus.ONLY_YOURS ->
            stringResource(R.string.parenting_plan_status_only_yours, coParentName)
        PlanQuestionStatus.ONLY_THEIRS -> stringResource(R.string.parenting_plan_status_only_theirs)
        PlanQuestionStatus.OPEN -> stringResource(R.string.parenting_plan_status_open)
        PlanQuestionStatus.AGREED -> stringResource(R.string.parenting_plan_status_agreed)
    }
    PillChip(
        label = label,
        icon = if (status == PlanQuestionStatus.AGREED) Icons.Default.CheckCircle else null,
        contentColor = if (status == PlanQuestionStatus.AGREED) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}
