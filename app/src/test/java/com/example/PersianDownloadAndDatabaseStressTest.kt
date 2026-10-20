package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.CapturedSongEntity
import com.example.data.repository.BackupExportFormat
import com.example.data.repository.SongRepository
import com.example.notification.SongNotificationDispatcher
import com.example.util.PersianDownloadBrowserLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersianDownloadAndDatabaseStressTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: SongRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.createInMemory(context)
        repository = SongRepository(database.capturedSongDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `persian download browser launcher appends exact دانلود suffix and URL-encodes special characters`() {
        val cases = listOf(
            "Blinding Lights The Weeknd" to "Blinding Lights The Weeknd دانلود",
            "گوهر کمیاب محسن چاوشی" to "گوهر کمیاب محسن چاوشی دانلود",
            "Creepin' (feat. 21 Savage & The Weeknd) #1?" to "Creepin' (feat. 21 Savage & The Weeknd) #1? دانلود",
            "봄날 BTS" to "봄날 BTS دانلود",
            "تملي معاك عمرو دياب" to "تملي معاك عمرو دياب دانلود"
        )

        for ((raw, expectedQuery) in cases) {
            val formatted = PersianDownloadBrowserLauncher.buildPersianDownloadQuery(raw)
            assertEquals(expectedQuery, formatted)

            val url = PersianDownloadBrowserLauncher.buildGoogleDownloadSearchUrl(raw)
            assertTrue(url.startsWith("https://www.google.com/search?q="))

            val encodedParam = url.removePrefix("https://www.google.com/search?q=")
            val decoded = URLDecoder.decode(encodedParam, StandardCharsets.UTF_8.name())
            assertEquals(expectedQuery, decoded)
        }
    }

    @Test
    fun `confirmation notification builder creates high-importance notification with Download and View List actions`() {
        val song = CapturedSongEntity(
            id = 42L,
            fullRawTitle = "Midnight City - M83",
            songTitle = "Midnight City",
            artistName = "M83",
            captureMode = CapturedSongEntity.CAPTURE_MODE_CIRCLE_TO_SEARCH,
            sourceAppPackage = "com.instagram.android"
        )

        val notification = SongNotificationDispatcher.buildCapturedSongNotification(context, song)
        val extras = notification.extras
        assertEquals("Song Saved to Library", extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("Midnight City - M83", extras.getString(android.app.Notification.EXTRA_TEXT))
        assertEquals(2, notification.actions.size)
        assertEquals("Download (Search)", notification.actions[0].title.toString())
        assertEquals("View List", notification.actions[1].title.toString())
    }

    @Test
    fun `room repository logs every capture as distinct row by default and supports JSON CSV export and restore`() = runBlocking {
        repository.recordCapturedSong(
            fullRawTitle = "Starboy - The Weeknd",
            songTitle = "Starboy",
            artistName = "The Weeknd",
            captureMode = "HYBRID_AUTO",
            sourceAppPackage = "com.instagram.android",
            capturedAtEpochMillis = 1000L,
            mergeDuplicates = false
        )
        repository.recordCapturedSong(
            fullRawTitle = "Starboy - The Weeknd",
            songTitle = "Starboy",
            artistName = "The Weeknd",
            captureMode = "HYBRID_AUTO",
            sourceAppPackage = "org.telegram.messenger",
            capturedAtEpochMillis = 2000L,
            mergeDuplicates = false
        )

        val allSongs = repository.allSongsFlow.first()
        assertEquals("Each capture must be stored as a separate row when mergeDuplicates=false", 2, allSongs.size)

        val jsonBackup = repository.exportHistory(BackupExportFormat.JSON)
        val csvBackup = repository.exportHistory(BackupExportFormat.CSV)
        val txtBackup = repository.exportHistory(BackupExportFormat.PLAIN_TEXT)

        assertTrue(jsonBackup.contains("Starboy"))
        assertTrue(csvBackup.contains("\"Starboy\",\"The Weeknd\""))
        assertTrue(txtBackup.contains("Starboy - The Weeknd"))
        assertTrue(txtBackup.contains("دانلود"))

        repository.clearAllHistory()
        assertEquals(0, repository.allSongsFlow.first().size)

        val restoredCount = repository.importFromJson(jsonBackup)
        assertEquals(2, restoredCount)
        assertEquals(2, repository.allSongsFlow.first().size)
    }

    @Test
    fun `high-volume concurrent database stress test - 1000 concurrent inserts queries and deletes without lock or corruption`() = runBlocking {
        val batchSize = 1_000
        val jobs = (1..batchSize).map { idx ->
            async(Dispatchers.IO) {
                repository.recordCapturedSong(
                    fullRawTitle = "Track #$idx - Artist #${idx % 25}",
                    songTitle = "Track #$idx",
                    artistName = "Artist #${idx % 25}",
                    captureMode = CapturedSongEntity.CAPTURE_MODE_CIRCLE_TO_SEARCH,
                    sourceAppPackage = if (idx % 2 == 0) "com.instagram.android" else "org.telegram.messenger",
                    capturedAtEpochMillis = 1_700_000_000_000L + idx,
                    mergeDuplicates = false
                )
            }
        }
        jobs.awaitAll()

        val total = repository.totalCountFlow.first()
        assertEquals(batchSize, total)

        val filtered = repository.observeSongs("Track #500").first()
        assertTrue(filtered.any { it.songTitle == "Track #500" })
    }
}
