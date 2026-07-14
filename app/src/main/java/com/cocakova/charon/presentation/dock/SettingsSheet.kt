package com.cocakova.charon.presentation.dock

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cocakova.charon.theme.CharonMono
import com.cocakova.charon.theme.MistGrey
import com.cocakova.charon.theme.ObolGold
import com.cocakova.charon.theme.StyxTeal
import kotlin.math.roundToInt

/**
 * The helm — Charon's settings, raised from the Dock's gear. Everything is backed
 * by the same "charon" prefs the terminal already reads, so the abc/raw accessory
 * key and pinch-zoom stay in perfect agreement with what's set here. Changes apply
 * from the next crossing you step onto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("charon", Context.MODE_PRIVATE)
    var fontSize by remember { mutableFloatStateOf(prefs.getFloat("font_size", 14f)) }
    var rawDefault by remember {
        mutableStateOf(prefs.getString("input_mode", "predictive") == "raw")
    }
    var keepLit by remember { mutableStateOf(prefs.getBoolean("keep_screen_on", false)) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "the helm",
                style = MaterialTheme.typography.titleMedium,
                color = StyxTeal,
            )
            Text(
                "set the trim — it takes hold from your next crossing",
                style = MaterialTheme.typography.bodySmall,
                color = MistGrey,
                modifier = Modifier.padding(top = 2.dp),
            )

            Spacer(Modifier.height(20.dp))

            // ---- Glyph size -------------------------------------------------------
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "glyph size",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (fontSize.roundToInt() != 14) {
                    TextButton(onClick = {
                        fontSize = 14f
                        prefs.edit().putFloat("font_size", 14f).apply()
                    }) { Text("reset", color = MistGrey) }
                }
                Text(
                    "${fontSize.roundToInt()} sp",
                    fontFamily = CharonMono,
                    fontSize = 14.sp,
                    color = StyxTeal,
                    fontWeight = FontWeight.Medium,
                )
            }
            Slider(
                value = fontSize,
                onValueChange = {
                    fontSize = it
                    prefs.edit().putFloat("font_size", it).apply()
                },
                valueRange = 6f..32f,
                colors = SliderDefaults.colors(
                    thumbColor = StyxTeal,
                    activeTrackColor = StyxTeal,
                ),
            )
            Text(
                "pinch the grid to zoom live; around 6–8 sp fits the 80 columns " +
                    "full-screen tools (btop, htop) insist on in portrait",
                style = MaterialTheme.typography.bodySmall,
                color = MistGrey,
            )

            Spacer(Modifier.height(22.dp))

            // ---- Default keyboard personality ------------------------------------
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "keyboard",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (rawDefault) "raw key events — nothing between you and the wire"
                        else "abc — swipe, voice and suggestions stay lit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MistGrey,
                    )
                }
                ModePill("abc", selected = !rawDefault) {
                    rawDefault = false
                    prefs.edit().putString("input_mode", "predictive").apply()
                }
                Spacer(Modifier.width(6.dp))
                ModePill("raw", selected = rawDefault, selectedColor = ObolGold) {
                    rawDefault = true
                    prefs.edit().putString("input_mode", "raw").apply()
                }
            }

            Spacer(Modifier.height(18.dp))

            // ---- Keep the screen lit ----------------------------------------------
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "keep the screen lit at sea",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "no dozing off while a terminal is up",
                        style = MaterialTheme.typography.bodySmall,
                        color = MistGrey,
                    )
                }
                Switch(
                    checked = keepLit,
                    onCheckedChange = {
                        keepLit = it
                        prefs.edit().putBoolean("keep_screen_on", it).apply()
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = StyxTeal),
                )
            }
        }
    }
}

/** A small abc/raw choice pill, matching the accessory row's toggle language. */
@Composable
private fun ModePill(
    label: String,
    selected: Boolean,
    selectedColor: androidx.compose.ui.graphics.Color = StyxTeal,
    onClick: () -> Unit,
) {
    Text(
        label,
        fontFamily = CharonMono,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) selectedColor
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
