package com.example.ui.components

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.CatchSongUiState
import com.example.ui.theme.LocalBoneTokens
import com.example.util.RestrictedUnlockStep
import com.example.util.SystemHealthStatus

/**
 * Warning banner displayed when an older prototype build (`com.aistudio.catchsong.kxmpzq`)
 * is still installed alongside the production release (`io.github.ahmadzmvd.catchapp`),
 * preventing user confusion in Android's Accessibility -> Installed apps list.
 */
@Composable
fun LegacyPrototypeWarningCard(
    onUninstallLegacyPrototype: () -> Unit
) {
    val tokens = LocalBoneTokens.current
    Surface(
        color = tokens.cardSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, tokens.accentInteractive),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("legacy_prototype_warning_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Duplicate Old Version Detected",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = tokens.accentInteractive
                )
                Text(
                    text = "Remove the old test build so only one Catch entry appears in Accessibility.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    ),
                    color = tokens.textSecondary
                )
            }
            Surface(
                color = tokens.accentInteractive,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onUninstallLegacyPrototype)
                    .testTag("uninstall_legacy_prototype_button")
            ) {
                Text(
                    text = "Uninstall Old",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = tokens.accentOnPrimary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }
    }
}

/**
 * Guided 4-step system setup card with embedded Android 13-16 Restricted Settings
 * unlock flow (`Step 2A -> Step 2B -> Step 2C`) and Store Session self-upgrade trigger.
 */
@Composable
fun SystemSetupWizardCard(
    uiState: CatchSongUiState,
    onRequestAddTile: () -> Unit,
    onOpenAppInfoForRestrictedSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onAutoUnlockViaStoreSession: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onDismissSetupCard: () -> Unit
) {
    val tokens = LocalBoneTokens.current
    val health = uiState.healthStatus

    Surface(
        color = tokens.cardSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.75.dp, tokens.borderHairline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("system_setup_wizard_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SYSTEM PERMISSIONS & SETUP",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.accentInteractive
                    )
                    Text(
                        text = if (health.isFullyOptimized) {
                            "All Recognition Services Active"
                        } else {
                            "Complete 60-Second Recognition Setup"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                        color = tokens.textPrimary
                    )
                }
                if (health.isCoreReady) {
                    IconButton(
                        onClick = onDismissSetupCard,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Collapse setup guide",
                            tint = tokens.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            SetupStepRow(
                stepNumber = "1",
                title = "Quick Settings Tile (\"Catch\")",
                subtitle = if (health.isQuickSettingsTileAdded) {
                    "Added to Quick Settings panel"
                } else {
                    "Add tile to Quick Settings for 1-tap catching"
                },
                isComplete = health.isQuickSettingsTileAdded,
                actionLabel = if (health.isQuickSettingsTileAdded) "Re-Add" else "Add Tile",
                testTag = "setup_add_tile_button",
                onActionClick = onRequestAddTile
            )

            HorizontalDivider(thickness = 0.75.dp, color = tokens.borderHairline)

            SetupStepRow(
                stepNumber = "2",
                title = "Catch Accessibility Service",
                subtitle = if (health.isAccessibilityServiceEnabled) {
                    "Active \u2022 Passively gated until tile is tapped"
                } else {
                    "Taps Circle to Search Music button & saves song title"
                },
                isComplete = health.isAccessibilityServiceEnabled,
                actionLabel = if (health.isAccessibilityServiceEnabled) "Enabled" else "Enable",
                testTag = "setup_enable_accessibility_button",
                onActionClick = onOpenAccessibilitySettings
            )

            if (!health.isAccessibilityServiceEnabled) {
                RestrictedSettingsUnlockCard(
                    health = health,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenAppInfoForRestrictedSettings = onOpenAppInfoForRestrictedSettings,
                    onAutoUnlockViaStoreSession = onAutoUnlockViaStoreSession
                )
            }

            HorizontalDivider(thickness = 0.75.dp, color = tokens.borderHairline)

            SetupStepRow(
                stepNumber = "3",
                title = "Heads-Up Confirmation Banner",
                subtitle = if (health.isNotificationPermissionGranted) {
                    "Enabled \u2022 Alerts you immediately when a song is saved"
                } else {
                    "Required on Android 13+ for 'Song Saved' banner"
                },
                isComplete = health.isNotificationPermissionGranted,
                actionLabel = if (health.isNotificationPermissionGranted) "Granted" else "Allow",
                testTag = "setup_notification_permission_button",
                onActionClick = onRequestNotificationPermission
            )

            HorizontalDivider(thickness = 0.75.dp, color = tokens.borderHairline)

            SetupStepRow(
                stepNumber = "4",
                title = "Background Battery Protection (Unrestricted)",
                subtitle = if (health.isBatteryOptimizationIgnored) {
                    "Unrestricted \u2022 Protected from background limits"
                } else {
                    "Prevents OEM battery limits from suspending tile"
                },
                isComplete = health.isBatteryOptimizationIgnored,
                actionLabel = if (health.isBatteryOptimizationIgnored) "Protected" else "Unrestrict",
                testTag = "setup_battery_unrestricted_button",
                onActionClick = onRequestBatteryUnrestricted
            )
        }
    }
}

@Composable
fun RestrictedSettingsUnlockCard(
    health: SystemHealthStatus,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppInfoForRestrictedSettings: () -> Unit,
    onAutoUnlockViaStoreSession: () -> Unit
) {
    val tokens = LocalBoneTokens.current
    var showRestrictedGuide by remember { mutableStateOf(true) }
    var showOemTips by remember { mutableStateOf(false) }
    val activeUnlockStep = health.currentRestrictedUnlockStep

    Surface(
        color = tokens.backgroundPrimary,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.75.dp, tokens.borderHairline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("restricted_settings_unlock_card")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRestrictedGuide = !showRestrictedGuide },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Security,
                        contentDescription = null,
                        tint = tokens.accentInteractive,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Android 13+ Restricted Settings Unlock (2A -> 2B -> 2C)",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = tokens.textPrimary
                    )
                }
                Icon(
                    imageVector = if (showRestrictedGuide) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = "Toggle guide",
                    tint = tokens.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (showRestrictedGuide) {
                RestrictedUnlockSubstepCard(
                    badge = "2A",
                    isCurrentStep = activeUnlockStep == RestrictedUnlockStep.STEP_2A_TRIGGER_PROMPT,
                    isCompleted = health.hasVisitedAccessibilitySettings,
                    instruction = "Tap Catch in Accessibility; tap Close on 'Denied access' popup.",
                    buttonLabel = "1. Open List",
                    buttonTestTag = "step_2a_accessibility_button",
                    onClick = onOpenAccessibilitySettings
                )

                RestrictedUnlockSubstepCard(
                    badge = "2B",
                    isCurrentStep = activeUnlockStep == RestrictedUnlockStep.STEP_2B_ALLOW_IN_APP_INFO,
                    isCompleted = health.hasVisitedAppInfoSettings,
                    instruction = "In App Info, tap \u22EE (top-right) -> Allow restricted settings.",
                    buttonLabel = "2. App Info (\u22EE)",
                    buttonTestTag = "open_app_info_restricted_button",
                    onClick = onOpenAppInfoForRestrictedSettings
                )

                RestrictedUnlockSubstepCard(
                    badge = "2C",
                    isCurrentStep = activeUnlockStep == RestrictedUnlockStep.STEP_2C_ENABLE_ACCESSIBILITY,
                    isCompleted = health.isAccessibilityServiceEnabled,
                    instruction = "Return to Accessibility -> Installed apps and switch Catch ON.",
                    buttonLabel = "3. Enable Catch",
                    buttonTestTag = "step_2c_enable_button",
                    onClick = onOpenAccessibilitySettings
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = tokens.cardSurface,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.75.dp, tokens.borderHairline),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onAutoUnlockViaStoreSession)
                            .testTag("store_session_auto_unlock_button")
                    ) {
                        Text(
                            text = "Auto-Unlock via Store Session",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = tokens.textPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }

                    Text(
                        text = if (showOemTips) "Hide OEM Tips" else "Xiaomi / Samsung Tips",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = tokens.accentInteractive,
                        modifier = Modifier
                            .clickable { showOemTips = !showOemTips }
                            .padding(4.dp)
                            .testTag("toggle_oem_tips_button")
                    )
                }

                if (showOemTips) {
                    Text(
                        text = "\u2022 Samsung: Turn off Settings -> Security and privacy -> Auto Blocker if blocked.\n" +
                            "\u2022 Xiaomi HyperOS: Scroll down in App Info -> Enable 'Allow restricted settings'.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 10.sp,
                            lineHeight = 13.sp
                        ),
                        color = tokens.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun RestrictedUnlockSubstepCard(
    badge: String,
    isCurrentStep: Boolean,
    isCompleted: Boolean,
    instruction: String,
    buttonLabel: String,
    buttonTestTag: String,
    onClick: () -> Unit
) {
    val tokens = LocalBoneTokens.current
    val borderColor = when {
        isCurrentStep -> tokens.accentInteractive
        isCompleted -> tokens.statusReady
        else -> tokens.borderHairline
    }
    Surface(
        color = tokens.cardSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(if (isCurrentStep) 1.25.dp else 0.75.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = when {
                    isCompleted -> tokens.statusReadyBg
                    isCurrentStep -> tokens.accentInteractive.copy(alpha = 0.14f)
                    else -> tokens.iconBadgeSurface
                },
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = when {
                        isCompleted -> tokens.statusReady
                        isCurrentStep -> tokens.accentInteractive
                        else -> tokens.textSecondary
                    },
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }

            Text(
                text = instruction,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 11.sp,
                    lineHeight = 13.sp
                ),
                color = tokens.textSecondary,
                modifier = Modifier.weight(1f)
            )

            Surface(
                color = if (isCurrentStep) tokens.accentPrimary else tokens.backgroundPrimary,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(0.75.dp, if (isCurrentStep) tokens.accentPrimary else tokens.borderHairline),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClick)
                    .testTag(buttonTestTag)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = buttonLabel,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = if (isCurrentStep) tokens.accentOnPrimary else tokens.textPrimary
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = if (isCurrentStep) tokens.accentOnPrimary else tokens.textPrimary,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupStepRow(
    stepNumber: String,
    title: String,
    subtitle: String,
    isComplete: Boolean,
    actionLabel: String,
    testTag: String,
    onActionClick: () -> Unit
) {
    val tokens = LocalBoneTokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (isComplete) tokens.statusReadyBg else tokens.iconBadgeSurface),
            contentAlignment = Alignment.Center
        ) {
            if (isComplete) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Completed",
                    tint = tokens.statusReady,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Text(
                    text = stepNumber,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textPrimary
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.sp),
                color = tokens.textPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 11.sp,
                    lineHeight = 13.sp
                ),
                color = tokens.textSecondary
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Surface(
            color = if (isComplete) tokens.backgroundPrimary else tokens.accentPrimary,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(0.75.dp, if (isComplete) tokens.borderHairline else tokens.accentPrimary),
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onActionClick)
                .testTag(testTag)
        ) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = if (isComplete) tokens.textSecondary else tokens.accentOnPrimary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}
