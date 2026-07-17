package com.cocakova.charon.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.cocakova.charon.theme.MistGrey

/** One row a [ReadonlyDropdownField] offers. [dim] = the "none" choice, in mist. */
data class DropdownChoice(
    val text: String,
    val dim: Boolean = false,
    val onPick: () -> Unit,
)

/**
 * A pick-from-a-list field: read-only text, trailing caret, the choices in a menu.
 * Every identity/source picker in the app is this shape — the boilerplate
 * (expanded flag, anchor, menu, dismiss-on-pick) lives here once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadonlyDropdownField(
    value: String,
    label: String,
    choices: List<DropdownChoice>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val usable = enabled && choices.isNotEmpty()
    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { if (usable) open = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = usable,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Text(choice.text, color = if (choice.dim) MistGrey else Color.Unspecified)
                    },
                    onClick = { open = false; choice.onPick() },
                )
            }
        }
    }
}
