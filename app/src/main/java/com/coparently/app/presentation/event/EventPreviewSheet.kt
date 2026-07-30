package com.coparently.app.presentation.event

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.coparently.app.R
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.theme.CoPlanlyColors
import java.time.format.DateTimeFormatter

/**
 * Read-only preview of an event shown before the editor: tapping an event in
 * the calendar opens this sheet; the full editor is one tap further (Edit).
 *
 * @param event Event (or expanded occurrence) to preview
 * @param onEdit Open the full editor for this event
 * @param onDelete Delete the event
 * @param onDismiss Close the sheet without action
 */
@Suppress("LongMethod") // linear declarative layout, no logic to extract
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventPreviewSheet(
    event: Event,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val parentColor = when (event.parentOwner) {
        "mom" -> CoPlanlyColors.MomPink
        "dad" -> CoPlanlyColors.DadBlue
        else -> MaterialTheme.colorScheme.tertiary
    }
    val parentLabel = when (event.parentOwner) {
        "mom" -> stringResource(R.string.event_preview_mom)
        "dad" -> stringResource(R.string.event_preview_dad)
        else -> event.parentOwner
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(parentColor, CircleShape)
                )
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    // Long titles used to wrap mid-word and push the sheet's actions down.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }

            PreviewRow(icon = Icons.Default.Schedule) {
                val dateText = event.startDateTime
                    .format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
                val timeText = buildString {
                    append(event.startDateTime.format(DateTimeFormatter.ofPattern("HH:mm")))
                    event.endDateTime?.let {
                        append(" – ")
                        append(it.format(DateTimeFormatter.ofPattern("HH:mm")))
                    }
                }
                Text(
                    text = "$dateText · $timeText",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            PreviewRow(icon = Icons.Default.Person) {
                Text(text = parentLabel, style = MaterialTheme.typography.bodyMedium)
            }

            event.description?.takeIf { it.isNotBlank() }?.let { description ->
                PreviewRow(icon = Icons.Default.Description) {
                    Text(text = description, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (event.isPrivate) {
                PreviewRow(icon = Icons.Default.Lock) {
                    Text(
                        text = stringResource(R.string.event_preview_private),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            event.imageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = "Event photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(MaterialTheme.shapes.medium)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.event_preview_delete),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                Button(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.event_preview_edit),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Box(modifier = Modifier.padding(start = 10.dp)) {
            content()
        }
    }
}
