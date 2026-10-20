package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.CatchApplication
import com.example.data.local.CapturedSongEntity
import com.example.data.preferences.AppThemeMode
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.preferences.UserPreferencesState
import com.example.data.repository.BackupExportFormat
import com.example.data.repository.SongRepository
import com.example.domain.interception.DiagnosticLogEntry
import com.example.domain.interception.InterceptionSessionState
import com.example.domain.interception.InterceptionStateMachine
import com.example.domain.interception.ParsedNodeSnapshot
import com.example.domain.interception.TreeSnapshotRecord
import com.example.service.CircleToSearchInternalAudioEngine
import com.example.util.SystemHealthStatus
import com.example.util.SystemPermissionHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CatchSongUiState(
    val songs: List<CapturedSongEntity> = emptyList(),
    val totalCount: Int = 0,
    val searchQuery: String = "",
    val isSearchVisible: Boolean = false,
    val favoritesOnly: Boolean = false,
    val preferences: UserPreferencesState = UserPreferencesState(),
    val healthStatus: SystemHealthStatus = SystemHealthStatus(),
    val isSetupCardExpanded: Boolean = false,
    val interceptionSession: InterceptionSessionState = InterceptionSessionState(),
    val diagnosticLogs: List<DiagnosticLogEntry> = emptyList(),
    val treeSnapshots: List<TreeSnapshotRecord> = emptyList(),
    val lastDeletedSong: CapturedSongEntity? = null,
    val exportedPayloadPreview: String? = null,
    val exportedFormatLabel: String? = null,
    val statusBannerMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class CatchViewModel(
    private val appContext: Context,
    private val songRepository: SongRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val stateMachine: InterceptionStateMachine = InterceptionStateMachine.sharedInstance,
    private val ctsEngine: CircleToSearchInternalAudioEngine = CircleToSearchInternalAudioEngine.sharedInstance
) : ViewModel() {

    private val searchQueryFlow = MutableStateFlow("")
    private val isSearchVisibleFlow = MutableStateFlow(false)
    private val favoritesOnlyFlow = MutableStateFlow(false)
    private val setupCardManualExpandFlow = MutableStateFlow<Boolean?>(null)
    private val healthRefreshTrigger = MutableStateFlow(0L)
    private val lastDeletedSongFlow = MutableStateFlow<CapturedSongEntity?>(null)
    private val exportedPreviewFlow = MutableStateFlow<Pair<String, String>?>(null)
    private val bannerMessageFlow = MutableStateFlow<String?>(null)

    private val filteredSongsFlow = combine(
        searchQueryFlow,
        favoritesOnlyFlow
    ) { query, favOnly ->
        query to favOnly
    }.flatMapLatest { (query, favOnly) ->
        songRepository.observeSongs(query).combine(MutableStateFlow(favOnly)) { list, onlyFav ->
            if (onlyFav) list.filter { it.isFavorite } else list
        }
    }

    private val healthStatusFlow = combine(
        preferencesRepository.preferencesFlow,
        healthRefreshTrigger
    ) { prefs, _ ->
        SystemPermissionHelper.evaluateSystemHealth(
            context = appContext,
            isTileAddedFlag = prefs.isTileAddedToQuickSettings,
            hasVisitedAccessibilityFlag = prefs.hasVisitedAccessibilitySettings,
            hasVisitedAppInfoFlag = prefs.hasVisitedAppInfo
        )
    }

    val uiState: StateFlow<CatchSongUiState> = combine(
        filteredSongsFlow,
        songRepository.totalCountFlow,
        preferencesRepository.preferencesFlow,
        healthStatusFlow,
        stateMachine.sessionState
    ) { songs, totalCount, prefs, health, session ->
        Quintuple(songs, totalCount, prefs, health, session)
    }.combine(
        combine(
            searchQueryFlow,
            isSearchVisibleFlow,
            favoritesOnlyFlow,
            setupCardManualExpandFlow,
            stateMachine.diagnosticLogs
        ) { q, sVis, fav, manualExpand, logs ->
            Quintuple(q, sVis, fav, manualExpand, logs)
        }
    ) { part1, part2 ->
        part1 to part2
    }.combine(
        combine(
            stateMachine.treeSnapshots,
            lastDeletedSongFlow,
            exportedPreviewFlow,
            bannerMessageFlow
        ) { snapshots, deleted, exported, msg ->
            Quadruple(snapshots, deleted, exported, msg)
        }
    ) { (part1, part2), part3 ->
        val (songs, totalCount, prefs, health, session) = part1
        val (query, searchVisible, favOnly, manualExpand, logs) = part2
        val (snapshots, deleted, exported, msg) = part3

        val shouldExpandSetup = manualExpand ?: !health.isCoreReady

        CatchSongUiState(
            songs = songs,
            totalCount = totalCount,
            searchQuery = query,
            isSearchVisible = searchVisible,
            favoritesOnly = favOnly,
            preferences = prefs,
            healthStatus = health,
            isSetupCardExpanded = shouldExpandSetup,
            interceptionSession = session,
            diagnosticLogs = logs,
            treeSnapshots = snapshots,
            lastDeletedSong = deleted,
            exportedPayloadPreview = exported?.second,
            exportedFormatLabel = exported?.first,
            statusBannerMessage = msg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = run {
            val initialHealth = SystemPermissionHelper.evaluateSystemHealth(appContext)
            CatchSongUiState(
                healthStatus = initialHealth,
                isSetupCardExpanded = !initialHealth.isCoreReady
            )
        }
    )

    fun refreshSystemHealth() {
        healthRefreshTrigger.value = System.currentTimeMillis()
    }

    fun toggleSetupCardExpanded() {
        val current = uiState.value.isSetupCardExpanded
        setupCardManualExpandFlow.value = !current
    }

    fun setSetupCardExpanded(expanded: Boolean) {
        setupCardManualExpandFlow.value = expanded
    }

    fun toggleSearchVisibility() {
        val next = !isSearchVisibleFlow.value
        isSearchVisibleFlow.value = next
        if (!next) {
            searchQueryFlow.value = ""
        }
    }

    fun updateSearchQuery(query: String) {
        searchQueryFlow.value = query
    }

    fun toggleFavoritesOnly() {
        favoritesOnlyFlow.value = !favoritesOnlyFlow.value
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            preferencesRepository.setThemeMode(mode)
        }
    }

    fun setMergeDuplicates(merge: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setMergeDuplicateCaptures(merge)
        }
    }

    fun setTreeDebuggerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setTreeDebuggerEnabled(enabled)
        }
    }

    fun setActiveWindowTimeoutSeconds(seconds: Int) {
        viewModelScope.launch {
            preferencesRepository.setActiveWindowTimeoutSeconds(seconds)
        }
    }

    fun markTileAdded() {
        viewModelScope.launch {
            preferencesRepository.setTileAddedToQuickSettings(true)
            refreshSystemHealth()
        }
    }

    fun markVisitedAccessibilitySettings() {
        viewModelScope.launch {
            preferencesRepository.setHasVisitedAccessibilitySettings(true)
            refreshSystemHealth()
        }
    }

    fun markVisitedAppInfo() {
        viewModelScope.launch {
            preferencesRepository.setHasVisitedAppInfo(true)
            refreshSystemHealth()
        }
    }

    fun markVisitedAppInfoSettings() = markVisitedAppInfo()

    /**
     * Triggers the Unified Circle to Search (Internal Audio) pipeline from the 56.dp bottom circular button.
     */
    fun triggerCatchSongNow() {
        val prefs = uiState.value.preferences
        val a11yService = com.example.service.CatchAccessibilityService.instance
        if (a11yService != null) {
            a11yService.startCatchSessionFromApp(timeoutSeconds = prefs.activeWindowTimeoutSeconds)
        } else {
            ctsEngine.requestStartFromInAppButton(
                sourceAppPackage = null,
                timeoutSeconds = prefs.activeWindowTimeoutSeconds
            )
        }
    }

    fun cancelActiveListening() {
        ctsEngine.cancelActivePipeline("Cancelled from Home Screen")
    }

    /**
     * Runs a realistic Circle to Search node-tree interception cycle through the real
     * `InterceptionStateMachine` and `GoogleMusicNodeParser` for diagnostics verification.
     */
    fun runDiagnosticSampleCapture() {
        val sampleSongs = listOf(
            Triple("Blinding Lights", "The Weeknd", "com.instagram.android"),
            Triple("Midnight City", "M83", "org.telegram.messenger"),
            Triple("Starboy (feat. Daft Punk)", "The Weeknd", "com.instagram.android"),
            Triple("\u06AF\u0648\u0647\u0631 \u06A9\u0645\u06CC\u0627\u0628", "\u0645\u062D\u0633\u0646 \u0686\u0627\u0648\u0634\u06CC", "org.telegram.messenger"),
            Triple("Veridis Quo", "Daft Punk", "com.google.android.youtube")
        )
        val pick = sampleSongs[(System.currentTimeMillis() % sampleSongs.size).toInt().coerceAtLeast(0)]
        val simulatedTree = ParsedNodeSnapshot(
            packageName = "com.google.android.googlequicksearchbox",
            className = "android.widget.FrameLayout",
            children = listOf(
                ParsedNodeSnapshot(
                    viewIdResourceName = "com.google.android.googlequicksearchbox:id/sound_search_result_title",
                    className = "android.widget.TextView",
                    text = pick.first
                ),
                ParsedNodeSnapshot(
                    viewIdResourceName = "com.google.android.googlequicksearchbox:id/sound_search_result_artist",
                    className = "android.widget.TextView",
                    text = "Song by ${pick.second}"
                )
            )
        )

        stateMachine.armInterception(
            triggerMode = CapturedSongEntity.CAPTURE_MODE_CIRCLE_TO_SEARCH,
            sourceAppPackage = pick.third,
            timeoutMs = 18_000L
        )
        stateMachine.onAccessibilityTreeSnapshot(
            rootSnapshot = simulatedTree,
            recordTreeSnapshot = true
        )
    }

    fun toggleFavorite(song: CapturedSongEntity) {
        viewModelScope.launch {
            songRepository.toggleFavorite(song)
        }
    }

    fun deleteSong(song: CapturedSongEntity) {
        viewModelScope.launch {
            lastDeletedSongFlow.value = song
            songRepository.deleteSong(song)
        }
    }

    fun undoDeleteSong() {
        val deleted = lastDeletedSongFlow.value ?: return
        lastDeletedSongFlow.value = null
        viewModelScope.launch {
            songRepository.insertSongDirect(deleted)
        }
    }

    fun dismissUndoBanner() {
        lastDeletedSongFlow.value = null
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            songRepository.clearAllHistory()
            bannerMessageFlow.value = "Library history cleared"
        }
    }

    fun exportHistory(format: BackupExportFormat) {
        viewModelScope.launch {
            val payload = songRepository.exportHistory(format)
            exportedPreviewFlow.value = format.label to payload
        }
    }

    fun clearExportPreview() {
        exportedPreviewFlow.value = null
    }

    fun importBackupJson(jsonText: String, onComplete: (Int) -> Unit) {
        viewModelScope.launch {
            try {
                val count = songRepository.importFromJson(jsonText)
                bannerMessageFlow.value = "Imported $count songs into library"
                onComplete(count)
            } catch (_: Exception) {
                bannerMessageFlow.value = "Invalid JSON backup format"
                onComplete(0)
            }
        }
    }

    fun clearStatusBannerMessage() {
        bannerMessageFlow.value = null
    }

    fun clearDiagnosticLogs() {
        stateMachine.clearDiagnosticLogs()
    }

    private data class Quintuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    companion object {
        fun provideFactory(application: CatchApplication): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CatchViewModel(
                        appContext = application.applicationContext,
                        songRepository = application.songRepository,
                        preferencesRepository = application.preferencesRepository
                    ) as T
                }
            }
        }
    }
}
