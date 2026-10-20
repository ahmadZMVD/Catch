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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.CapturedSongEntity
import com.example.ui.theme.LocalBoneTokens
import com.example.util.PersianDownloadBrowserLauncher

@Composable
fun MinimalistEmptyLibraryCard(
    onAddQuickSettingsTile: () -> Unit,
    onRunSampleCapture: () -> Unit
) {
    val tokens = LocalBoneTokens.current
    Surface(
        color = tokens.cardSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.75.dp, tokens.borderHairline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag("empty_state_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(tokens.iconBadgeSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = tokens.accentInteractive,
                    modifier = Modifier.size(28.dp)
                )
            }

            Text(
                text = stringResource(R.string.empty_state_title),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.textPrimary,
                textAlign = TextAlign.Center
            )

            Text(
                text = stringResource(R.string.empty_state_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = tokens.accentPrimary,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = onAddQuickSettingsTile)
                        .testTag("empty_state_add_tile_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AddCircleOutline,
                            contentDescription = null,
                            tint = tokens.accentOnPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.action_add_qs_tile),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = tokens.accentOnPrimary
                        )
                    }
                }

                Surface(
                    color = tokens.backgroundPrimary,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(0.75.dp, tokens.borderHairline),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = onRunSampleCapture)
                        .testTag("empty_state_demo_capture_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayCircleOutline,
                            contentDescription = null,
                            tint = tokens.textPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Test Capture",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = tokens.textPrimary
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionContextSheet(
    song: CapturedSongEntity,
    onDismiss: () -> Unit,
    onSearchPersianDownload: () -> Unit,
    onCopySongName: () -> Unit,
    onSearchYouTube: () -> Unit,
    onSearchSpotify: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDeleteEntry: () -> Unit
) {
    val tokens = LocalBoneTokens.current
    val downloadQueryPreview = remember(song) {
        PersianDownloadBrowserLauncher.buildPersianDownloadQuery(song)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = tokens.backgroundPrimary,
        contentColor = tokens.textPrimary,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.testTag("song_context_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = song.displayTitle,
                style = MaterialTheme.typography.titleLarge,
                color = tokens.textPrimary
            )
            Text(
                text = "${song.displayArtist} \u2022 Raw: \"${song.fullRawTitle}\"",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textSecondary
            )

            Spacer(modifier = Modifier.height(4.dp))

            ContextSheetActionRow(
                title = "Search & Download (\"$downloadQueryPreview\")",
                subtitle = "Opens default browser with Persian download query",
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                highlight = true,
                testTag = "context_action_persian_download",
                onClick = onSearchPersianDownload
            )

            ContextSheetActionRow(
                title = "Copy Song & Artist Name",
                subtitle = "Copies clean track identifier to clipboard",
                icon = Icons.Outlined.ContentCopy,
                testTag = "context_action_copy",
                onClick = onCopySongName
            )

            ContextSheetActionRow(
                title = if (song.isFavorite) "Remove from Saved Favorites" else "Save to Favorites",
                subtitle = "Pin track for quick filtering",
                icon = if (song.isFavorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                testTag = "context_action_favorite",
                onClick = onToggleFavorite
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = tokens.cardSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.75.dp, tokens.borderHairline),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onSearchYouTube)
                        .testTag("context_action_youtube")
                ) {
                    Box(modifier = Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text("Search YouTube", style = MaterialTheme.typography.labelLarge, color = tokens.textPrimary)
                    }
                }
                Surface(
                    color = tokens.cardSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.75.dp, tokens.borderHairline),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onSearchSpotify)
                        .testTag("context_action_spotify")
                ) {
                    Box(modifier = Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text("Search Spotify", style = MaterialTheme.typography.labelLarge, color = tokens.textPrimary)
                    }
                }
            }

            ContextSheetActionRow(
                title = "Delete Entry",
                subtitle = "Remove this capture from your history list",
                icon = Icons.Outlined.DeleteOutline,
                isDestructive = true,
                testTag = "context_action_delete",
                onClick = onDeleteEntry
            )
        }
    }
}

@Composable
private fun ContextSheetActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    highlight: Boolean = false,
    isDestructive: Boolean = false,
    testTag: String,
    onClick: () -> Unit
) {
    val tokens = LocalBoneTokens.current
    Surface(
        color = if (highlight) tokens.accentPrimary else tokens.cardSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(0.75.dp, if (highlight) tokens.accentPrimary else tokens.borderHairline),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    highlight -> tokens.accentOnPrimary
                    isDestructive -> tokens.accentInteractive
                    else -> tokens.textPrimary
                },
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                    color = when {
                        highlight -> tokens.accentOnPrimary
                        isDestructive -> tokens.accentInteractive
                        else -> tokens.textPrimary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = if (highlight) tokens.accentOnPrimary.copy(alpha = 0.78f) else tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
