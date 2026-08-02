package com.coparently.app.presentation.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coparently.app.R

/**
 * The message composer.
 *
 * The leading `+` this used to carry is gone. It was captioned "attach" but opened message
 * templates — the August 2026 audit's clearest "icon promises one thing, does another". The
 * templates now live in a labelled chip above this row (see `ChatScreen`), and a real attach
 * button will land with attachments themselves rather than ahead of them.
 *
 * @param onSendMessage Called with the composed text when the user sends
 * @param modifier Modifier for the row
 * @param initialText Seed text for the composer. Keyed on the value so arriving with a new
 *   draft replaces the field, while ordinary recomposition leaves what the user typed alone.
 */
@Composable
fun MessageInput(
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialText: String = ""
) {
    var text by remember(initialText) { mutableStateOf(initialText) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            placeholder = { Text(stringResource(R.string.chat_type_message)) },
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            maxLines = 4
        )

        IconButton(
            onClick = {
                if (text.isNotBlank()) {
                    onSendMessage(text)
                    text = ""
                }
            },
            enabled = text.isNotBlank()
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.chat_send),
                tint = if (text.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Gray
            )
        }
    }
}
