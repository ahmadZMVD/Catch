package com.example.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.local.CapturedSongEntity
import com.example.ui.theme.LocalBoneTokens
import com.example.util.AddedTileOutcome
import com.example.util.SystemPermissionHelper
import com.example.util.PersianDownloadBrowserLauncher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Primary song history screen for **Catch**:
 * - Consumes `WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)`
 *   plus a proportional top clearance (`>= 20.dp`, `maxHeight * 0.022f`) for display cutouts.
 * - Top bar contains:
 *   - Left: Title **`Catch`** (`24.sp`, `FontWeight.Bold`).
 *   - Right: Settings icon button (`Icons.Outlined.Settings`).
 * - Bottom bar anchors the `56.dp` circular `"Catch Now"` action above `WindowInsets.navigationBars`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SongHistoryScreen(
    uiState: CatchSongUiState,
    viewModel: CatchViewModel,
    simulatedTopCutoutInset: Dp = 0.dp,
    simulatedBottomNavInset: Dp = 0.dp
) {
    val tokens = LocalBoneTokens.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var selectedSongForContextSheet by remember { mutableStateOf<CapturedSongEntity?>(null) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSystemHealth()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.refreshSystemHealth()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.backgroundPrimary)
    ) {
        // Proportional layout geometry
        val horizontalMargin = (maxWidth * 0.055f).coerceIn(20.dp, 24.dp)
        val extraTopSafetyClearance = (maxHeight * 0.022f).coerceAtLeast(20.dp)
        val bottomSafetyClearance = (maxHeight * 0.025f).coerceAtLeast(24.dp)

        Scaffold(
            containerColor = tokens.backgroundPrimary,
            contentColor = tokens.textPrimary,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.surfaceGlass)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                            )
                        )
                        .padding(
                            top = simulatedTopCutoutInset + extraTopSafetyClearance,
                            start = horizontalMargin,
                            end = horizontalMargin,
                            bottom = 12.dp
                        )
                        .testTag("home_top_header")
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 680.dp)
                                .testTag("top_header_optical_row"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = tokens.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .testTag("app_header_title")
                            )

                            IconButton(
                                onClick = { showSettingsSheet = true },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("open_settings_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Settings,
                                    contentDescription = stringResource(R.string.action_settings),
                                    tint = tokens.textPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(thickness = 0.75.dp, color = tokens.borderHairline)
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 680.dp)
                        .align(Alignment.TopCenter)
                        .testTag("song_history_lazy_column"),
                    contentPadding = PaddingValues(
                        start = horizontalMargin,
                        end = horizontalMargin,
                        top = 12.dp,
                        bottom = 112.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // System Permissions & Setup Card
                    item(key = "setup_wizard_card") {
                        AnimatedVisibility(
                            visible = uiState.isSetupCardExpanded || !uiState.healthStatus.isCoreReady,
                            enter = expandVertically(
                                spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            ) + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            SystemSetupWizardCard(
                                uiState = uiState,
                                onRequestAddTile = {
                                    SystemPermissionHelper.requestAddQuickSettingsTile(context) { outcome ->
                                        when (outcome) {
                                            AddedTileOutcome.ADDED,
                                            AddedTileOutcome.ALREADY_ADDED -> {
                                                viewModel.markTileAdded()
                                                Toast.makeText(
                                                    context,
                                                    "Catch tile is ready in Quick Settings",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            AddedTileOutcome.DISMISSED_OR_UNSUPPORTED -> {
                                                viewModel.markTileAdded()
                                                Toast.makeText(
                                                    context,
                                                    "Pull down Quick Settings -> Tap Edit (Pencil) -> Add 'Catch'",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                onOpenAppInfoForRestrictedSettings = {
                                    SystemPermissionHelper.openAppDetailsSettings(context)
                                },
                                onOpenAccessibilitySettings = {
                                    viewModel.markVisitedAccessibilitySettings()
                                    SystemPermissionHelper.openAccessibilitySettings(context)
                                },
                                onRequestNotificationPermission = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                onRequestBatteryUnrestricted = {
                                    SystemPermissionHelper.requestIgnoreBatteryOptimizations(context)
                                },
                                onDismissSetupCard = {
                                    viewModel.setSetupCardExpanded(false)
                                }
                            )
                        }
                    }

                    if (uiState.totalCount > 0 || uiState.songs.isNotEmpty()) {
                        item(key = "library_section_header") {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp, bottom = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "CAPTURED LIBRARY (${uiState.songs.size})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = tokens.textTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "Tap row to search \"${PersianDownloadBrowserLauncher.PERSIAN_DOWNLOAD_SUFFIX}\"",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = tokens.textTertiary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        IconButton(
                                            onClick = { viewModel.toggleFavoritesOnly() },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .testTag("filter_favorites_chip")
                                        ) {
                                            Icon(
                                                imageVector = if (uiState.favoritesOnly) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                                contentDescription = "Filter saved favorites",
                                                tint = if (uiState.favoritesOnly) tokens.accentInteractive else tokens.textSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.toggleSearchVisibility() },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .testTag("search_toggle_button")
                                        ) {
                                            Icon(
                                                imageVector = if (uiState.isSearchVisible) Icons.Outlined.Close else Icons.Outlined.Search,
                                                contentDescription = "Search songs",
                                                tint = tokens.textSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }

                                AnimatedVisibility(
                                    visible = uiState.isSearchVisible,
                                    enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    OutlinedTextField(
                                        value = uiState.searchQuery,
                                        onValueChange = { viewModel.updateSearchQuery(it) },
                                        singleLine = true,
                                        placeholder = {
                                            Text(
                                                text = stringResource(R.string.search_placeholder),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = tokens.textTertiary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Outlined.Search,
                                                contentDescription = null,
                                                tint = tokens.textSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        trailingIcon = {
                                            if (uiState.searchQuery.isNotEmpty()) {
                                                IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Close,
                                                        contentDescription = "Clear search",
                                                        tint = tokens.textSecondary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = tokens.cardSurface,
                                            unfocusedContainerColor = tokens.cardSurface,
                                            focusedBorderColor = tokens.accentPrimary,
                                            unfocusedBorderColor = tokens.borderHairline,
                                            focusedTextColor = tokens.textPrimary,
                                            unfocusedTextColor = tokens.textPrimary
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("song_search_input")
                                    )
                                }
                            }
                        }

                        items(
                            items = uiState.songs,
                            key = { song -> song.id }
                        ) { song ->
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        viewModel.deleteSong(song)
                                        true
                                    } else {
                                        false
                                    }
                                }
                            )

                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromStartToEnd = false,
                                backgroundContent = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(tokens.accentInteractive.copy(alpha = 0.16f))
                                            .padding(horizontal = 20.dp),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = "Delete song",
                                            tint = tokens.accentInteractive
                                        )
                                    }
                                },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            ) {
                                CapturedSongRowCard(
                                    song = song,
                                    onClickDownloadSearch = {
                                        PersianDownloadBrowserLauncher.launchPersianDownloadSearch(
                                            context = context,
                                            song = song
                                        )
                                    },
                                    onLongClickContext = {
                                        selectedSongForContextSheet = song
                                    }
                                )
                            }
                        }
                    } else {
                        item(key = "empty_library_state") {
                            MinimalistEmptyLibraryCard(
                                onAddQuickSettingsTile = {
                                    SystemPermissionHelper.requestAddQuickSettingsTile(context) { _ ->
                                        viewModel.markTileAdded()
                                    }
                                },
                                onRunSampleCapture = {
                                    viewModel.runDiagnosticSampleCapture()
                                }
                            )
                        }
                    }
                }

                // Undo Delete Banner (positioned above the 56.dp circular button)
                if (uiState.lastDeletedSong != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(
                                start = horizontalMargin,
                                end = horizontalMargin,
                                bottom = simulatedBottomNavInset + bottomSafetyClearance + 68.dp
                            )
                    ) {
                        Surface(
                            color = tokens.accentPrimary,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.testTag("undo_delete_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "Removed \"${uiState.lastDeletedSong.displayTitle}\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = tokens.accentOnPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Text(
                                    text = "Undo",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = tokens.accentInteractive,
                                    modifier = Modifier
                                        .clickable { viewModel.undoDeleteSong() }
                                        .testTag("undo_delete_button")
                                )
                            }
                        }
                    }
                }

                // Compact 56.dp Circular "Catch Now" Button anchored at the bottom above Navigation Bar
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = simulatedBottomNavInset + bottomSafetyClearance)
                ) {
                    BottomCircularCatchNowButton(
                        isActive = uiState.interceptionSession.isActiveWindow,
                        onCatchNow = { viewModel.triggerCatchSongNow() },
                        onCancelListening = { viewModel.cancelActiveListening() }
                    )
                }
            }
        }
    }

    val contextSong = selectedSongForContextSheet
    if (contextSong != null) {
        SongActionContextSheet(
            song = contextSong,
            onDismiss = { selectedSongForContextSheet = null },
            onSearchPersianDownload = {
                PersianDownloadBrowserLauncher.launchPersianDownloadSearch(context, contextSong)
                selectedSongForContextSheet = null
            },
            onCopySongName = {
                PersianDownloadBrowserLauncher.copySongToClipboard(context, contextSong)
                selectedSongForContextSheet = null
            },
            onSearchYouTube = {
                PersianDownloadBrowserLauncher.launchYouTubeSearch(context, contextSong)
                selectedSongForContextSheet = null
            },
            onSearchSpotify = {
                PersianDownloadBrowserLauncher.launchSpotifySearch(context, contextSong)
                selectedSongForContextSheet = null
            },
            onToggleFavorite = {
                viewModel.toggleFavorite(contextSong)
                selectedSongForContextSheet = null
            },
            onDeleteEntry = {
                viewModel.deleteSong(contextSong)
                selectedSongForContextSheet = null
            }
        )
    }

    if (showSettingsSheet) {
        SettingsBottomSheet(
            uiState = uiState,
            onDismiss = {
                viewModel.clearExportPreview()
                showSettingsSheet = false
            },
            onSelectThemeMode = { viewModel.setThemeMode(it) },
            onToggleMergeDuplicates = { viewModel.setMergeDuplicates(it) },
            onToggleTreeDebugger = { viewModel.setTreeDebuggerEnabled(it) },
            onChangeTimeoutSeconds = { viewModel.setActiveWindowTimeoutSeconds(it) },
            onExportFormat = { viewModel.exportHistory(it) },
            onClearExportPreview = { viewModel.clearExportPreview() },
            onImportJson = { json -> viewModel.importBackupJson(json) {} },
            onRunSampleCapture = { viewModel.runDiagnosticSampleCapture() },
            onClearDiagnosticLogs = { viewModel.clearDiagnosticLogs() },
            onClearAllHistory = { viewModel.clearAllHistory() }
        )
    }
}

/**
 * Apple-minimalist `56.dp` Circular `"Catch Now"` Button anchored at the bottom of the Home Screen.
 * Maintains exact `56.dp x 56.dp` layout bounds while rendering a concentric pulse ring via `drawBehind`
 * when active/listening.
 */
@Composable
fun BottomCircularCatchNowButton(
    isActive: Boolean,
    onCatchNow: () -> Unit,
    onCancelListening: () -> Unit
) {
    val tokens = LocalBoneTokens.current
    val pulseTransition = rememberInfiniteTransition(label = "circularCatchPulse")
    val pulseRadiusFactor by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.32f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseRadiusFactor"
    )
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    val ringColor = tokens.accentInteractive

    Surface(
        color = if (isActive) tokens.accentInteractive else tokens.accentPrimary,
        shape = CircleShape,
        border = BorderStroke(1.dp, if (isActive) tokens.accentInteractive else tokens.borderHairline),
        shadowElevation = 6.dp,
        modifier = Modifier
            .size(56.dp)
            .drawBehind {
                if (isActive) {
                    drawCircle(
                        color = ringColor.copy(alpha = pulseAlpha),
                        radius = (size.minDimension / 2f) * pulseRadiusFactor,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
            .clip(CircleShape)
            .clickable {
                if (isActive) onCancelListening() else onCatchNow()
            }
            .semantics {
                contentDescription = if (isActive) {
                    "Stop Circle to Search listening"
                } else {
                    "Catch Now via Circle to Search"
                }
            }
            .testTag("catch_now_button")
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isActive) Icons.Outlined.Close else Icons.Outlined.GraphicEq,
                contentDescription = null,
                tint = tokens.accentOnPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CapturedSongRowCard(
    song: CapturedSongEntity,
    onClickDownloadSearch: () -> Unit,
    onLongClickContext: () -> Unit
) {
    val tokens = LocalBoneTokens.current
    val relativeTime = remember(song.capturedAtEpochMillis) {
        formatRelativeTimestamp(song.capturedAtEpochMillis)
    }
    val sourceLabel = remember(song.sourceAppPackage) {
        SystemPermissionHelper.formatSourceAppName(song.sourceAppPackage)
    }
    val subtitleLine = remember(song.displayArtist, relativeTime, sourceLabel, song.captureCount) {
        buildString {
            append(song.displayArtist)
            append(" \u2022 ")
            append(relativeTime)
            if (!song.sourceAppPackage.isNullOrBlank()) {
                append(" \u2022 ")
                append(sourceLabel)
            }
            if (song.captureCount > 1) {
                append(" \u00D7")
                append(song.captureCount)
            }
        }
    }

    Surface(
        color = tokens.cardSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.75.dp, tokens.borderHairline),
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClickDownloadSearch,
                onLongClick = onLongClickContext
            )
            .semantics {
                contentDescription = "${song.displayTitle} by ${song.displayArtist}. Tap to search download."
            }
            .testTag("song_item_card_${song.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.iconBadgeSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = if (song.isFavorite) tokens.accentInteractive else tokens.textPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = song.displayTitle,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.3).sp
                    ),
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitleLine,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(tokens.iconBadgeSurface),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "\u2197",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = tokens.textPrimary
                )
            }
        }
    }
}

@Composable
private fun SystemSetupWizardCard(
    uiState: CatchSongUiState,
    onRequestAddTile: () -> Unit,
    onOpenAppInfoForRestrictedSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onDismissSetupCard: () -> Unit
) {
    val tokens = LocalBoneTokens.current
    val health = uiState.healthStatus
    var showRestrictedGuide by remember { mutableStateOf(true) }

    Surface(
        color = tokens.cardSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.75.dp, tokens.borderHairline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("system_setup_wizard_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (health.isFullyOptimized) {
                            "All Interception Services Active"
                        } else {
                            "Complete 60-Second Interception Setup"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.textPrimary
                    )
                }
                if (health.isCoreReady) {
                    IconButton(
                        onClick = onDismissSetupCard,
                        modifier = Modifier.size(32.dp)
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
                    "Add tile to your top Quick Settings panel for 1-tap catching inside Instagram or Telegram"
                },
                isComplete = health.isQuickSettingsTileAdded,
                actionLabel = if (health.isQuickSettingsTileAdded) "Re-Add" else "Add Tile",
                testTag = "setup_add_tile_button",
                onActionClick = onRequestAddTile
            )

            HorizontalDivider(thickness = 0.75.dp, color = tokens.borderHairline)

            SetupStepRow(
                stepNumber = "2",
                title = "Google Song Result Interceptor",
                subtitle = if (health.isAccessibilityServiceEnabled) {
                    "Active \u2022 Passively gated until tile is tapped"
                } else {
                    "Automates Circle to Search Music button & captures recognized song title"
                },
                isComplete = health.isAccessibilityServiceEnabled,
                actionLabel = if (health.isAccessibilityServiceEnabled) "Enabled" else "Enable",
                testTag = "setup_enable_accessibility_button",
                onActionClick = onOpenAccessibilitySettings
            )

            if (!health.isAccessibilityServiceEnabled) {
                Surface(
                    color = tokens.backgroundPrimary,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.75.dp, tokens.borderHairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Is Accessibility grayed out? (Android 13+ Restricted Setting)",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontSize = 12.sp,
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
                            Text(
                                text = "1. Tap \"Open App Info\" below.\n" +
                                    "2. Tap the 3-dot menu (\u22EE) in the top-right corner of the App Info screen.\n" +
                                    "3. Tap \"Allow restricted settings\" and verify with Fingerprint/PIN, then tap \"Enable\" above.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                color = tokens.textSecondary
                            )
                            Surface(
                                color = tokens.cardSurface,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(0.75.dp, tokens.borderHairline),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClick = onOpenAppInfoForRestrictedSettings)
                                    .testTag("open_app_info_restricted_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Open App Info (Unlock Restricted Settings)",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = tokens.accentInteractive
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                        contentDescription = null,
                                        tint = tokens.accentInteractive,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 0.75.dp, color = tokens.borderHairline)

            SetupStepRow(
                stepNumber = "3",
                title = "Heads-Up Confirmation Banner",
                subtitle = if (health.isNotificationPermissionGranted) {
                    "Enabled \u2022 Alerts you immediately when a song is saved"
                } else {
                    "Required on Android 13+ to show the 'Song Saved' confirmation banner"
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
                    "Unrestricted \u2022 Protected from background service restrictions"
                } else {
                    "Prevents the system from restricting the tile or interceptor in the background"
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
                .size(28.dp)
                .clip(CircleShape)
                .background(if (isComplete) tokens.statusReadyBg else tokens.iconBadgeSurface),
            contentAlignment = Alignment.Center
        ) {
            if (isComplete) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Completed",
                    tint = tokens.statusReady,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Text(
                    text = stepNumber,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textPrimary
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                color = tokens.textPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = tokens.textSecondary
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Surface(
            color = if (isComplete) tokens.backgroundPrimary else tokens.accentPrimary,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(0.75.dp, if (isComplete) tokens.borderHairline else tokens.accentPrimary),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onActionClick)
                .testTag(testTag)
        ) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = if (isComplete) tokens.textSecondary else tokens.accentOnPrimary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun MinimalistEmptyLibraryCard(
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
private fun SongActionContextSheet(
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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

fun formatRelativeTimestamp(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val diffMs = abs(nowMillis - epochMillis)
    val seconds = diffMs / 1000L
    val minutes = seconds / 60L
    val hours = minutes / 60L
    val days = hours / 24L

    return when {
        seconds < 45L -> "Just now"
        minutes < 60L -> "${minutes}m ago"
        hours < 24L -> "${hours}h ago"
        days == 1L -> "Yesterday"
        days < 7L -> "${days}d ago"
        else -> SimpleDateFormat("MMM d", Locale.US).format(Date(epochMillis))
    }
}
