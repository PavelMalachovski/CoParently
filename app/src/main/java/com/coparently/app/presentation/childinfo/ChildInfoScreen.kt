package com.coparently.app.presentation.childinfo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.domain.model.Activity
import com.coparently.app.domain.model.ChildInfo
import com.coparently.app.domain.model.EmergencyContact
import com.coparently.app.domain.model.Medication

/**
 * Screen for displaying and managing child information.
 * Shows medications, activities, allergies, and other important data.
 *
 * @param onNavigateBack Callback for navigation back
 * @param onEditClick Callback for edit action
 * @param viewModel ViewModel for managing child info
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildInfoScreen(
    onNavigateBack: () -> Unit,
    onEditClick: (String) -> Unit,
    viewModel: ChildInfoViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentChildInfo by viewModel.currentChildInfo.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Child Information") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (currentChildInfo != null) {
                        IconButton(onClick = { currentChildInfo?.let { onEditClick(it.id) } }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                    }
                    IconButton(onClick = { viewModel.syncChildInfo() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState) {
                is ChildInfoUiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is ChildInfoUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { viewModel.loadChildInfo() }) {
                            Text("Retry")
                        }
                    }
                }
                is ChildInfoUiState.Success -> {
                    if (state.childInfoList.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No child information yet",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { onEditClick("new") }) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Add Child Info")
                            }
                        }
                    } else {
                        currentChildInfo?.let { childInfo ->
                            ChildInfoContent(childInfo = childInfo)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Content displaying detailed child information.
 */
@Composable
fun ChildInfoContent(childInfo: ChildInfo) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Child Name
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = childInfo.childName,
                        style = MaterialTheme.typography.headlineMedium
                    )
                    childInfo.dateOfBirth?.let { dob ->
                        Text(
                            text = "Date of Birth: ${dob.toLocalDate()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Medications
        if (childInfo.medications.isNotEmpty()) {
            item {
                SectionHeader(title = "Medications", icon = Icons.Default.Favorite)
            }
            items(childInfo.medications) { medication ->
                MedicationCard(medication = medication)
            }
        }

        // Activities
        if (childInfo.activities.isNotEmpty()) {
            item {
                SectionHeader(title = "Activities & Classes", icon = Icons.Default.Star)
            }
            items(childInfo.activities) { activity ->
                ActivityCard(activity = activity)
            }
        }

        // Allergies
        if (childInfo.allergies.isNotEmpty()) {
            item {
                SectionHeader(title = "Allergies", icon = Icons.Default.Warning)
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        childInfo.allergies.forEach { allergy ->
                            Text(
                                text = "• $allergy",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        // Medical Notes
        childInfo.medicalNotes?.let { notes ->
            item {
                SectionHeader(title = "Medical Notes", icon = Icons.Default.Info)
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        // Emergency Contacts
        if (childInfo.emergencyContacts.isNotEmpty()) {
            item {
                SectionHeader(title = "Emergency Contacts", icon = Icons.Default.Phone)
            }
            items(childInfo.emergencyContacts) { contact ->
                EmergencyContactCard(contact = contact)
            }
        }

        // School Info
        childInfo.schoolInfo?.let { school ->
            item {
                SectionHeader(title = "School Information", icon = Icons.Default.Place)
            }
            item {
                SchoolInfoCard(schoolInfo = school)
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun MedicationCard(medication: Medication) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = medication.name,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Dosage: ${medication.dosage}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Frequency: ${medication.frequency}",
                style = MaterialTheme.typography.bodyMedium
            )
            medication.notes?.let { notes ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Notes: $notes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ActivityCard(activity: Activity) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = activity.name,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Schedule: ${activity.schedule}",
                style = MaterialTheme.typography.bodyMedium
            )
            activity.location?.let { location ->
                Text(
                    text = "Location: $location",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            activity.contactPerson?.let { contact ->
                Text(
                    text = "Contact: $contact",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            activity.contactPhone?.let { phone ->
                Text(
                    text = "Phone: $phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun EmergencyContactCard(contact: EmergencyContact) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Relationship: ${contact.relationship}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Phone: ${contact.phone}",
                style = MaterialTheme.typography.bodyMedium
            )
            contact.alternatePhone?.let { altPhone ->
                Text(
                    text = "Alt. Phone: $altPhone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SchoolInfoCard(schoolInfo: com.coparently.app.domain.model.SchoolInfo) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = schoolInfo.name,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            schoolInfo.address?.let { address ->
                Text(
                    text = "Address: $address",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            schoolInfo.phone?.let { phone ->
                Text(
                    text = "Phone: $phone",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            schoolInfo.teacherName?.let { teacher ->
                Text(
                    text = "Teacher: $teacher",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            schoolInfo.teacherEmail?.let { email ->
                Text(
                    text = "Email: $email",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            schoolInfo.grade?.let { grade ->
                Text(
                    text = "Grade: $grade",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

