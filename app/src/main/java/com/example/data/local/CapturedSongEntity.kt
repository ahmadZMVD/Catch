package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "captured_songs",
    indices = [
        Index(value = ["capturedAtEpochMillis"]),
        Index(value = ["songTitle", "artistName"])
    ]
)
data class CapturedSongEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val fullRawTitle: String,
    val songTitle: String,
    val artistName: String,
    val capturedAtEpochMillis: Long = System.currentTimeMillis(),
    val captureMode: String = CAPTURE_MODE_CIRCLE_TO_SEARCH,
    val sourceAppPackage: String? = null,
    val isFavorite: Boolean = false,
    val captureCount: Int = 1
) {
    val displayTitle: String
        get() = songTitle.ifBlank { fullRawTitle }

    val displayArtist: String
        get() = artistName.ifBlank { "Identified via Circle to Search" }

    val searchQueryBase: String
        get() = when {
            songTitle.isNotBlank() && artistName.isNotBlank() -> "$songTitle $artistName".trim()
            else -> fullRawTitle.trim()
        }

    companion object {
        const val CAPTURE_MODE_CIRCLE_TO_SEARCH = "CIRCLE_TO_SEARCH_INTERNAL"
    }
}
