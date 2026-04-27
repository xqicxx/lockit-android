package com.lockit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDropUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.domain.model.CredentialType
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.Primary
import com.lockit.ui.theme.TacticalRed
import com.lockit.ui.theme.White

/**
 * Dropdown with preset options.
 * When `editable = true` (default): shows presets + "CUSTOM" option that reveals a text field.
 * When `editable = false`: dropdown-only selection, no custom text input allowed.
 */
@Composable
fun DropdownWithCustomInput(
    label: String,
    presets: List<String>,
    selectedValue: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Enter custom value...",
    error: String? = null,
    editable: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val isInPreset = presets.contains(selectedValue)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        // Dropdown trigger
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (error != null) TacticalRed else MaterialTheme.colorScheme.outline)
                    .clickable { expanded = true }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (selectedValue.isBlank()) "SELECT..." else selectedValue,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 14.sp,
                    color = if (selectedValue.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = "Toggle dropdown",
                    tint = colorScheme.onSurface,
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(colorScheme.surfaceContainerHigh)
                    .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f)),
            ) {
                presets.forEach { preset ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = preset,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 12.sp,
                            )
                        },
                        onClick = {
                            onValueChange(preset)
                            expanded = false
                        },
                    )
                }
                if (editable) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "+ CUSTOM",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 12.sp,
                                color = colorScheme.onSurface,
                            )
                        },
                        onClick = {
                            onValueChange("")
                            expanded = false
                        },
                    )
                }
            }
        }

        // Custom text field shown when no preset is selected (only if editable)
        if (editable && !isInPreset) {
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(4.dp))
            BrutalistTextField(
                value = selectedValue,
                onValueChange = onValueChange,
                label = "CUSTOM",
                placeholder = placeholder,
                error = error,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipGroup(
    label: String,
    options: List<String>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Enter custom value...",
    error: String? = null,
    showCustomInput: Boolean = true,
) {
    val isCustom = selectedValue.isNotBlank() && !options.contains(selectedValue)
    val customDisplayValue = if (isCustom) selectedValue.replace("\u200B", "") else selectedValue

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))

        // Chips - use FlowRow for automatic wrapping
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { option ->
                Chip(
                    text = option.uppercase(),
                    selected = selectedValue == option,
                    onClick = { onSelect(option) },
                )
            }
            if (showCustomInput) {
                Chip(
                    text = "+",
                    selected = isCustom,
                    onClick = {
                        if (isCustom) {
                            onSelect("")
                        } else {
                            onSelect("\u200B")
                        }
                    },
                )
            }
        }

        // Custom text field when custom is active
        if (isCustom) {
            Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
            BrutalistTextField(
                value = customDisplayValue,
                onValueChange = {
                    val newValue = if (it.isBlank()) "\u200B" else it
                    onSelect(newValue)
                },
                label = "CUSTOM",
                placeholder = placeholder,
                error = error,
            )
        }
    }
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .border(1.dp, if (selected) colorScheme.onSurface else colorScheme.outlineVariant.copy(alpha = 0.2f))
            .background(if (selected) colorScheme.onSurface.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
fun CredentialTypeDropdown(
    selectedType: CredentialType,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onTypeSelected: (CredentialType) -> Unit,
    customTypes: List<String> = emptyList(),
    onAddCustomType: (String) -> Unit = {},
) {
    val colorScheme = MaterialTheme.colorScheme
    var showAddDialog by remember { mutableStateOf(false) }
    var customTypeName by remember { mutableStateOf("") }

    Column {
        Text(
            text = "TYPE",
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline)
                    .clickable { onExpandedChange(!expanded) }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedType.displayName,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = "Toggle dropdown",
                    tint = colorScheme.onSurface,
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                modifier = Modifier
                    .background(colorScheme.surfaceContainerHigh)
                    .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f)),
            ) {
                CredentialType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = type.displayName,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 12.sp,
                            )
                        },
                        onClick = { onTypeSelected(type) },
                    )
                }
                if (customTypes.isNotEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "─── CUSTOM ───",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                    customTypes.forEach { typeName ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = typeName,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 12.sp,
                                )
                            },
                            onClick = { onTypeSelected(CredentialType.Custom) },
                        )
                    }
                }
                // Add new type button
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    text = {
                        Text(
                            text = "ADD_TYPE",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 12.sp,
                            color = colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        onExpandedChange(false)
                        showAddDialog = true
                    },
                )
            }
        }
    }

    // Add custom type dialog
    if (showAddDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showAddDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(2.dp, colorScheme.outlineVariant.copy(alpha = 0.2f))
                    .padding(24.dp),
            ) {
                Text(
                    text = "ADD_TYPE",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = colorScheme.onSurface,
                )
                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
                BrutalistTextField(
                    value = customTypeName,
                    onValueChange = { customTypeName = it },
                    label = "TYPE_NAME",
                    placeholder = "e.g. API_KEY_V2",
                )
                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BrutalistButton(
                        text = "CANCEL",
                        onClick = { showAddDialog = false; customTypeName = "" },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                        useMonoFont = true,
                    )
                    BrutalistButton(
                        text = "ADD",
                        onClick = {
                            if (customTypeName.isNotBlank()) {
                                onAddCustomType(customTypeName.uppercase())
                                showAddDialog = false
                                customTypeName = ""
                            }
                        },
                        variant = ButtonVariant.Primary,
                        modifier = Modifier.weight(1f),
                        useMonoFont = true,
                    )
                }
            }
        }
    }
}
