package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.preferences.AppThemeMode
import com.example.data.repository.BackupExportFormat
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalBoneTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    uiState: CatchSongUiState,
    onDismiss: () -> Unit,
    onSelectThemeMode: (AppThemeMode) -> Unit,
    onToggleMergeDuplicates: (Boolean) -> Unit,
    onToggleTreeDebugger: (Boolean) -> Unit,
    onChangeTimeoutSeconds: (Int) -> Unit,
    onExportFormat: (BackupExportFormat) -> Unit,
    onClearExportPreview: () -> Unit,
    onImportJson: (String) -> Unit,
    onRunSampleCapture: () -> Unit,
    onClearDiagnosticLogs: () -> Unit,
    onClearAllHistory: () -> Unit
) {
    val tokens = LocalBoneTokens.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showImportField by remember { mutableStateOf(false) }
    var importJsonInput by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = tokens.backgroundPrimary,
        contentColor = tokens.textPrimary,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.testTag("settings_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Settings & Diagnostics",
                style = MaterialTheme.typography.titleLarge,
                color = tokens.textPrimary
            )

            // 1. APPEARANCE (Bone Light | Obsidian Dark | System)
            SectionHeader("APPEARANCE")
            Surface(
                color = tokens.cardSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(0.75.dp, tokens.borderHairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_theme_selector")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Color Palette",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                        color = tokens.textPrimary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppThemeMode.entries.forEach { mode ->
                            val selected = uiState.preferences.themeMode == mode
                            Surface(
                                color = if (selected) tokens.accentPrimary else tokens.backgroundPrimary,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    width = if (selected) 1.dp else 0.75.dp,
                                    color = if (selected) tokens.accentPrimary else tokens.borderHairline
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectThemeMode(mode) }
                                    .testTag("theme_pill_${mode.storageKey}")
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = mode.displayLabel,
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                        ),
                                        color = if (selected) tokens.accentOnPrimary else tokens.textSecondary,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Circle to Search (Internal Audio) Engine Status
            SectionHeader("RECOGNITION ENGINE")
            Surface(
                color = tokens.cardSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(0.75.dp, tokens.borderHairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("single_engine_cts_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Circle to Search (Internal Audio)",
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                            color = tokens.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Unified pipeline for both the Quick Settings Tile and the bottom Catch Now button. Waits for shade collapse, invokes Circle to Search, clicks the Music button, and captures internal device audio.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                            color = tokens.textSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(tokens.accentPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Active Engine",
                            tint = tokens.accentOnPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // 3. Capture Behavior & Smart Timeout
            SectionHeader("INTERCEPTION SAFEGUARDS")
            Surface(
                color = tokens.cardSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(0.75.dp, tokens.borderHairline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Merge Duplicate Captures",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                                color = tokens.textPrimary
                            )
                            Text(
                                text = "Off: Every captured song is logged as a separate chronological entry.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                color = tokens.textSecondary
                            )
                        }
                        Switch(
                            checked = uiState.preferences.mergeDuplicateCaptures,
                            onCheckedChange = onToggleMergeDuplicates,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = tokens.accentOnPrimary,
                                checkedTrackColor = tokens.accentPrimary
                            ),
                            modifier = Modifier.testTag("switch_merge_duplicates")
                        )
                    }

                    HorizontalDivider(thickness = 0.75.dp, color = tokens.borderHairline)

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Active Interception Window",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                                color = tokens.textPrimary
                            )
                            Text(
                                text = "${uiState.preferences.activeWindowTimeoutSeconds}s timeout",
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.accentInteractive
                            )
                        }
                        Text(
                            text = "Automatically disarms Accessibility listener if no song is matched, preventing unintended scraping during normal Google searches.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                            color = tokens.textSecondary
                        )
                        Slider(
                            value = uiState.preferences.activeWindowTimeoutSeconds.toFloat(),
                            onValueChange = { onChangeTimeoutSeconds(it.toInt()) },
                            valueRange = 8f..30f,
                            steps = 10,
                            colors = SliderDefaults.colors(
                                thumbColor = tokens.accentPrimary,
                                activeTrackColor = tokens.accentPrimary,
                                inactiveTrackColor = tokens.borderHairline
                            )
                        )
                    }
                }
            }

            // 4. On-Device Backup & Export (JSON / CSV / Plain Text)
            SectionHeader("LIBRARY BACKUP & EXPORT")
            Surface(
                color = tokens.cardSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(0.75.dp, tokens.borderHairline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Export ${uiState.totalCount} captured tracks or restore a JSON backup:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BackupExportFormat.entries.forEach { format ->
                            Surface(
                                color = tokens.backgroundPrimary,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(0.75.dp, tokens.borderHairline),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onExportFormat(format) }
                                    .testTag("export_btn_${format.extension}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(vertical = 10.dp, horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.FileUpload,
                                        contentDescription = null,
                                        tint = tokens.textPrimary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = format.extension.uppercase(Locale.ROOT),
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = tokens.textPrimary
                                    )
                                }
                            }
                        }
                    }

                    if (uiState.exportedPayloadPreview != null) {
                        Surface(
                            color = tokens.backgroundPrimary,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(0.75.dp, tokens.borderHairline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = uiState.exportedFormatLabel ?: "Exported Data",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = tokens.accentInteractive
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable {
                                                copyText(context, uiState.exportedPayloadPreview)
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.ContentCopy,
                                                contentDescription = "Copy export",
                                                tint = tokens.textPrimary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Copy", style = MaterialTheme.typography.labelSmall, color = tokens.textPrimary)
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable {
                                                shareText(context, uiState.exportedPayloadPreview)
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Share,
                                                contentDescription = "Share export",
                                                tint = tokens.textPrimary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Share", style = MaterialTheme.typography.labelSmall, color = tokens.textPrimary)
                                        }
                                    }
                                }
                                Text(
                                    text = uiState.exportedPayloadPreview.take(600),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = tokens.textSecondary,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = tokens.backgroundPrimary,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(0.75.dp, tokens.borderHairline),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showImportField = !showImportField }
                                .testTag("toggle_import_json_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FileDownload,
                                    contentDescription = null,
                                    tint = tokens.textPrimary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Import JSON Backup",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = tokens.textPrimary
                                )
                            }
                        }

                        if (uiState.totalCount > 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onClearAllHistory() }
                                    .padding(8.dp)
                                    .testTag("clear_all_history_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Clear All",
                                    tint = tokens.accentInteractive,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Clear All",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = tokens.accentInteractive
                                )
                            }
                        }
                    }

                    if (showImportField) {
                        OutlinedTextField(
                            value = importJsonInput,
                            onValueChange = { importJsonInput = it },
                            placeholder = {
                                Text(
                                    "Paste JSON backup array or object here…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = tokens.textTertiary
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = tokens.accentPrimary,
                                unfocusedBorderColor = tokens.borderHairline,
                                focusedTextColor = tokens.textPrimary,
                                unfocusedTextColor = tokens.textPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 88.dp)
                                .testTag("import_json_input")
                        )
                        Surface(
                            color = tokens.accentPrimary,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (importJsonInput.isNotBlank()) {
                                        onImportJson(importJsonInput)
                                        importJsonInput = ""
                                        showImportField = false
                                    }
                                }
                                .testTag("confirm_import_json_button")
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Restore Songs into Library",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = tokens.accentOnPrimary
                                )
                            }
                        }
                    }
                }
            }

            // 5. Self-Healing Tree Snapshot Debugger & Live Verification
            SectionHeader("TREE SNAPSHOT DEBUGGER & DIAGNOSTICS")
            Surface(
                color = tokens.cardSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(0.75.dp, tokens.borderHairline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Tree Snapshot Debugger",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                                color = tokens.textPrimary
                            )
                            Text(
                                text = "Records anonymized Google QSB AccessibilityNodeInfo structure during song capture.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                color = tokens.textSecondary
                            )
                        }
                        Switch(
                            checked = uiState.preferences.treeDebuggerEnabled,
                            onCheckedChange = onToggleTreeDebugger,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = tokens.accentOnPrimary,
                                checkedTrackColor = tokens.accentPrimary
                            )
                        )
                    }

                    Surface(
                        color = tokens.accentPrimary,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onRunSampleCapture() }
                            .testTag("simulate_capture_button")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.GraphicEq,
                                contentDescription = null,
                                tint = tokens.accentOnPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Test Interception Pipeline (Simulate Google Match)",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = tokens.accentOnPrimary
                            )
                        }
                    }

                    if (uiState.diagnosticLogs.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "RECENT INTERCEPTION TELEMETRY (${uiState.diagnosticLogs.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.textTertiary
                            )
                            Text(
                                text = "Clear",
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.accentInteractive,
                                modifier = Modifier.clickable { onClearDiagnosticLogs() }
                            )
                        }
                        val timeFormatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
                        Surface(
                            color = tokens.backgroundPrimary,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(0.75.dp, tokens.borderHairline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                uiState.diagnosticLogs.take(6).forEach { entry ->
                                    val t = timeFormatter.format(Date(entry.timestampEpochMillis))
                                    Text(
                                        text = "[$t] ${entry.tag}: ${entry.message}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = JetBrainsMonoFamily,
                                            fontSize = 10.sp
                                        ),
                                        color = tokens.textSecondary
                                    )
                                }
                            }
                        }
                    }

                    if (uiState.treeSnapshots.isNotEmpty()) {
                        val latestSnap = uiState.treeSnapshots.first()
                        Text(
                            text = "LATEST GOOGLE QSB NODE TREE SNAPSHOT",
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.textTertiary
                        )
                        Surface(
                            color = tokens.backgroundPrimary,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(0.75.dp, tokens.borderHairline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = latestSnap.serializedTreeJson,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 10.sp
                                ),
                                color = tokens.textSecondary,
                                modifier = Modifier.padding(10.dp),
                                maxLines = 10,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    val tokens = LocalBoneTokens.current
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = tokens.textTertiary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Catch Backup", text))
    Toast.makeText(context, "Copied backup to clipboard", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    try {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Catch Library Backup")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Export Catch Library"))
    } catch (_: Exception) {
    }
}
