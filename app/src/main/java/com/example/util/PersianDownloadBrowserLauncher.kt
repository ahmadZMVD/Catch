package com.example.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.data.local.CapturedSongEntity
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Formats and launches browser download searches using the query pattern:
 * `[Song Title] [Artist Name] \u062F\u0627\u0646\u0644\u0648\u062F`
 */
object PersianDownloadBrowserLauncher {

    // Unicode escape for Persian download keyword (U+062F U+0627 U+0646 U+0644 U+0648 U+062F)
    // to prevent UTF-8/Latin-1 mojibake across source control bridges.
    const val PERSIAN_DOWNLOAD_SUFFIX = "\u062F\u0627\u0646\u0644\u0648\u062F"
    private const val GOOGLE_SEARCH_BASE_URL = "https://www.google.com/search?q="
    private const val YOUTUBE_SEARCH_BASE_URL = "https://www.youtube.com/results?search_query="
    private const val SPOTIFY_SEARCH_BASE_URL = "https://open.spotify.com/search/"

    fun buildPersianDownloadQuery(rawSongQuery: String): String {
        val cleaned = rawSongQuery
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return PERSIAN_DOWNLOAD_SUFFIX
        return if (cleaned.endsWith(PERSIAN_DOWNLOAD_SUFFIX)) {
            cleaned
        } else {
            "$cleaned $PERSIAN_DOWNLOAD_SUFFIX"
        }
    }

    fun buildPersianDownloadQuery(song: CapturedSongEntity): String {
        val base = when {
            song.songTitle.isNotBlank() && song.artistName.isNotBlank() &&
                !song.songTitle.contains(song.artistName, ignoreCase = true) -> {
                "${song.songTitle.trim()} ${song.artistName.trim()}"
            }
            song.fullRawTitle.isNotBlank() -> song.fullRawTitle.trim()
            else -> song.songTitle.trim()
        }
        return buildPersianDownloadQuery(base)
    }

    fun buildGoogleDownloadSearchUrl(rawSongQuery: String): String {
        val fullQuery = buildPersianDownloadQuery(rawSongQuery)
        val encoded = urlEncodeUtf8(fullQuery)
        return "$GOOGLE_SEARCH_BASE_URL$encoded"
    }

    fun buildGoogleDownloadSearchUrl(song: CapturedSongEntity): String {
        val fullQuery = buildPersianDownloadQuery(song)
        val encoded = urlEncodeUtf8(fullQuery)
        return "$GOOGLE_SEARCH_BASE_URL$encoded"
    }

    fun urlEncodeUtf8(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    fun launchPersianDownloadSearch(context: Context, song: CapturedSongEntity): Boolean {
        val url = buildGoogleDownloadSearchUrl(song)
        return openUrlInDefaultBrowser(context, url)
    }

    fun launchPersianDownloadSearch(context: Context, rawSongQuery: String): Boolean {
        val url = buildGoogleDownloadSearchUrl(rawSongQuery)
        return openUrlInDefaultBrowser(context, url)
    }

    fun launchYouTubeSearch(context: Context, song: CapturedSongEntity): Boolean {
        val query = urlEncodeUtf8(song.searchQueryBase)
        return openUrlInDefaultBrowser(context, "$YOUTUBE_SEARCH_BASE_URL$query")
    }

    fun launchSpotifySearch(context: Context, song: CapturedSongEntity): Boolean {
        val query = urlEncodeUtf8(song.searchQueryBase).replace("+", "%20")
        return openUrlInDefaultBrowser(context, "$SPOTIFY_SEARCH_BASE_URL$query")
    }

    fun copySongToClipboard(context: Context, song: CapturedSongEntity) {
        val text = if (song.artistName.isNotBlank()) {
            "${song.displayTitle} - ${song.artistName}"
        } else {
            song.displayTitle
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Captured Song", text))
        Toast.makeText(context, "Copied \"$text\"", Toast.LENGTH_SHORT).show()
    }

    private fun openUrlInDefaultBrowser(context: Context, url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            Toast.makeText(context, "No browser installed to open search", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
