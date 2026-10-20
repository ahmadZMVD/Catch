package com.example.data.repository

import com.example.data.local.CapturedSongDao
import com.example.data.local.CapturedSongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class BackupExportFormat(val label: String, val extension: String) {
    JSON("JSON Archive", "json"),
    CSV("CSV Spreadsheet", "csv"),
    PLAIN_TEXT("Plain Text List", "txt")
}

class SongRepository(private val dao: CapturedSongDao) {

    val allSongsFlow: Flow<List<CapturedSongEntity>> = dao.observeAllSongs()
    val totalCountFlow: Flow<Int> = dao.observeTotalCount()

    fun observeSongs(searchQuery: String): Flow<List<CapturedSongEntity>> {
        val trimmed = searchQuery.trim()
        return if (trimmed.isEmpty()) {
            dao.observeAllSongs()
        } else {
            dao.observeFilteredSongs(trimmed)
        }
    }

    /**
     * Records a newly captured song into Room.
     * By default (mergeDuplicates = false), every recognition event is stored as a distinct row
     * in chronological order. If mergeDuplicates = true, increments captureCount and updates timestamp.
     */
    suspend fun recordCapturedSong(
        fullRawTitle: String,
        songTitle: String,
        artistName: String,
        captureMode: String,
        sourceAppPackage: String?,
        capturedAtEpochMillis: Long = System.currentTimeMillis(),
        mergeDuplicates: Boolean = false
    ): CapturedSongEntity = withContext(Dispatchers.IO) {
        val cleanRaw = fullRawTitle.trim().ifEmpty {
            listOf(songTitle.trim(), artistName.trim()).filter { it.isNotEmpty() }.joinToString(" - ")
        }
        val cleanTitle = songTitle.trim().ifEmpty { cleanRaw }
        val cleanArtist = artistName.trim()

        if (mergeDuplicates) {
            val existing = dao.findLatestByTitleAndArtist(cleanTitle, cleanArtist)
            if (existing != null) {
                val updated = existing.copy(
                    fullRawTitle = cleanRaw,
                    capturedAtEpochMillis = capturedAtEpochMillis,
                    captureMode = captureMode,
                    sourceAppPackage = sourceAppPackage ?: existing.sourceAppPackage,
                    captureCount = existing.captureCount + 1
                )
                dao.updateSong(updated)
                return@withContext updated
            }
        }

        val entity = CapturedSongEntity(
            fullRawTitle = cleanRaw,
            songTitle = cleanTitle,
            artistName = cleanArtist,
            capturedAtEpochMillis = capturedAtEpochMillis,
            captureMode = captureMode,
            sourceAppPackage = sourceAppPackage,
            isFavorite = false,
            captureCount = 1
        )
        val generatedId = dao.insertSong(entity)
        entity.copy(id = generatedId)
    }

    suspend fun insertSongDirect(song: CapturedSongEntity): Long = withContext(Dispatchers.IO) {
        dao.insertSong(song)
    }

    suspend fun toggleFavorite(song: CapturedSongEntity) = withContext(Dispatchers.IO) {
        dao.updateSong(song.copy(isFavorite = !song.isFavorite))
    }

    suspend fun deleteSong(song: CapturedSongEntity) = withContext(Dispatchers.IO) {
        dao.deleteSong(song)
    }

    suspend fun deleteSongById(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteSongById(id)
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        dao.deleteAllSongs()
    }

    suspend fun exportHistory(format: BackupExportFormat): String = withContext(Dispatchers.IO) {
        val songs = dao.getAllSongsSnapshot()
        when (format) {
            BackupExportFormat.JSON -> exportToJson(songs)
            BackupExportFormat.CSV -> exportToCsv(songs)
            BackupExportFormat.PLAIN_TEXT -> exportToPlainText(songs)
        }
    }

    private fun exportToJson(songs: List<CapturedSongEntity>): String {
        val root = JSONObject()
        root.put("schemaVersion", 1)
        root.put("exportedAtEpochMillis", System.currentTimeMillis())
        root.put("count", songs.size)
        val array = JSONArray()
        for (song in songs) {
            val item = JSONObject().apply {
                put("id", song.id)
                put("fullRawTitle", song.fullRawTitle)
                put("songTitle", song.songTitle)
                put("artistName", song.artistName)
                put("capturedAtEpochMillis", song.capturedAtEpochMillis)
                put("captureMode", song.captureMode)
                put("sourceAppPackage", song.sourceAppPackage ?: JSONObject.NULL)
                put("isFavorite", song.isFavorite)
                put("captureCount", song.captureCount)
            }
            array.put(item)
        }
        root.put("songs", array)
        return root.toString(2)
    }

    private fun exportToCsv(songs: List<CapturedSongEntity>): String {
        val sb = StringBuilder()
        sb.appendLine("id,song_title,artist_name,full_raw_title,captured_at_epoch_ms,capture_mode,source_app,is_favorite,capture_count")
        for (song in songs) {
            sb.append(song.id).append(',')
                .append(escapeCsv(song.songTitle)).append(',')
                .append(escapeCsv(song.artistName)).append(',')
                .append(escapeCsv(song.fullRawTitle)).append(',')
                .append(song.capturedAtEpochMillis).append(',')
                .append(escapeCsv(song.captureMode)).append(',')
                .append(escapeCsv(song.sourceAppPackage ?: "")).append(',')
                .append(song.isFavorite).append(',')
                .append(song.captureCount)
                .appendLine()
        }
        return sb.toString()
    }

    private fun exportToPlainText(songs: List<CapturedSongEntity>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("Catch: Captured Library (${songs.size} tracks)")
        sb.appendLine("--------------------------------------------")
        for ((index, song) in songs.withIndex()) {
            val dateStr = dateFormat.format(Date(song.capturedAtEpochMillis))
            val artistPart = if (song.artistName.isNotBlank()) " - ${song.artistName}" else ""
            sb.appendLine("${index + 1}. ${song.displayTitle}$artistPart [$dateStr] (${song.searchQueryBase} دانلود)")
        }
        return sb.toString()
    }

    suspend fun importFromJson(jsonPayload: String): Int = withContext(Dispatchers.IO) {
        val trimmed = jsonPayload.trim()
        if (trimmed.isEmpty()) return@withContext 0
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val root = JSONObject(trimmed)
            root.optJSONArray("songs") ?: JSONArray()
        }
        val toInsert = mutableListOf<CapturedSongEntity>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val rawTitle = obj.optString("fullRawTitle", "").trim()
            val songTitle = obj.optString("songTitle", rawTitle).trim()
            if (rawTitle.isEmpty() && songTitle.isEmpty()) continue
            val artistName = obj.optString("artistName", "").trim()
            val sourcePkg = if (obj.isNull("sourceAppPackage")) null else obj.optString("sourceAppPackage").takeIf { it.isNotBlank() }
            toInsert.add(
                CapturedSongEntity(
                    id = 0L,
                    fullRawTitle = rawTitle.ifEmpty { songTitle },
                    songTitle = songTitle.ifEmpty { rawTitle },
                    artistName = artistName,
                    capturedAtEpochMillis = obj.optLong("capturedAtEpochMillis", System.currentTimeMillis()),
                    captureMode = obj.optString("captureMode", CapturedSongEntity.CAPTURE_MODE_CIRCLE_TO_SEARCH),
                    sourceAppPackage = sourcePkg,
                    isFavorite = obj.optBoolean("isFavorite", false),
                    captureCount = obj.optInt("captureCount", 1).coerceAtLeast(1)
                )
            )
        }
        if (toInsert.isNotEmpty()) {
            dao.insertSongs(toInsert)
        }
        toInsert.size
    }

    private fun escapeCsv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
