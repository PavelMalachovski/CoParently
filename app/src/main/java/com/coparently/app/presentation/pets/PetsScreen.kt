package com.coparently.app.presentation.pets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.Pet
import com.coparently.app.presentation.common.GroupLabel
import com.coparently.app.presentation.common.SectionGroup
import com.coparently.app.presentation.common.SectionRow
import com.coparently.app.presentation.common.labelRes

/**
 * The pets list. Unlike the child screen — which shows only the first record, a documented
 * limitation — a family can have several pets, so this is a genuine list: one row per pet,
 * tapping opens the editor, the FAB adds a new one.
 *
 * @param onNavigateBack Callback for navigation back
 * @param onEditPet Opens the editor for a pet id (or "new")
 * @param viewModel ViewModel for managing pets
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetsScreen(
    onNavigateBack: () -> Unit,
    onEditPet: (String) -> Unit,
    viewModel: PetsViewModel = hiltViewModel()
) {
    val haptic = LocalHapticFeedback.current
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pets_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNavigateBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.pets_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.syncPets()
                    }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.pets_sync)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            val state = uiState
            if (state is PetsUiState.Success && state.pets.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onEditPet("new")
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.pets_add)) }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState) {
                is PetsUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is PetsUiState.Error -> {
                    ErrorState(message = state.message, onRetry = viewModel::loadPets)
                }
                is PetsUiState.Success -> {
                    if (state.pets.isEmpty()) {
                        EmptyState(onAdd = { onEditPet("new") })
                    } else {
                        PetsList(pets = state.pets, onEditPet = onEditPet)
                    }
                }
            }
        }
    }
}

/** One row per pet: name as the title, species (and breed when known) as the summary line. */
@Composable
private fun PetsList(pets: List<Pet>, onEditPet: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 88.dp)
    ) {
        item {
            Column {
                GroupLabel(stringResource(R.string.pets_group_label))
                SectionGroup {
                    pets.forEachIndexed { index, pet ->
                        val species = stringResource(pet.species.labelRes())
                        SectionRow(
                            icon = Icons.Default.Pets,
                            title = pet.name,
                            supporting = pet.breed?.takeIf { it.isNotBlank() }
                                ?.let { "$species · $it" } ?: species,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onEditPet(pet.id)
                            }
                        )
                        if (index != pets.lastIndex) Divider()
                    }
                }
            }
        }
    }
}

/** Shown when the family has no pets yet. */
@Composable
private fun BoxScope.EmptyState(onAdd: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Pets,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.pets_empty_state),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onAdd()
        }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.pets_add))
        }
    }
}

/** Shown when the pets flow fails; the retry re-collects it from scratch. */
@Composable
private fun BoxScope.ErrorState(message: String, onRetry: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onRetry()
        }) {
            Text(stringResource(R.string.pets_retry))
        }
    }
}
