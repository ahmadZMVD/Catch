package com.example

import com.example.domain.interception.GoogleMusicNodeParser
import com.example.domain.interception.NodeParseOutcome
import com.example.domain.interception.ParsedNodeSnapshot
import com.example.util.PersianDownloadBrowserLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.random.Random

class InterceptionParserAndFuzzTest {

    @Test
    fun `tier 1 - extracts song title and artist from explicit sound search resource IDs`() {
        val tree = ParsedNodeSnapshot(
            packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
            children = listOf(
                ParsedNodeSnapshot(
                    viewIdResourceName = "com.google.android.googlequicksearchbox:id/sound_search_result_title",
                    text = "Blinding Lights"
                ),
                ParsedNodeSnapshot(
                    viewIdResourceName = "com.google.android.googlequicksearchbox:id/sound_search_result_artist",
                    text = "The Weeknd"
                )
            )
        )

        val outcome = GoogleMusicNodeParser.parseTree(tree)
        assertTrue(outcome is NodeParseOutcome.SongMatched)
        val matched = (outcome as NodeParseOutcome.SongMatched).candidate
        assertEquals("Blinding Lights", matched.songTitle)
        assertEquals("The Weeknd", matched.artistName)
        assertEquals("Blinding Lights - The Weeknd", matched.fullRawTitle)
        assertEquals(1, matched.confidenceTier)
    }

    @Test
    fun `tier 2 - extracts Persian RTL and English songs from byline without resource IDs`() {
        val persianTree = ParsedNodeSnapshot(
            packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
            children = listOf(
                ParsedNodeSnapshot(text = "گوهر کمیاب"),
                ParsedNodeSnapshot(text = "آهنگ از محسن چاوشی")
            )
        )
        val persianOutcome = GoogleMusicNodeParser.parseTree(persianTree)
        assertTrue(persianOutcome is NodeParseOutcome.SongMatched)
        assertEquals("گوهر کمیاب", (persianOutcome as NodeParseOutcome.SongMatched).candidate.songTitle)
        assertEquals("محسن چاوشی", persianOutcome.candidate.artistName)
    }

    @Test
    fun `detects Circle to Search Music button even when viewId is stripped via positional ImageButton heuristic`() {
        val strippedTree = ParsedNodeSnapshot(
            packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
            children = listOf(
                ParsedNodeSnapshot(text = "Circle or tap anywhere to search"),
                ParsedNodeSnapshot(text = "Translate", isClickable = true),
                ParsedNodeSnapshot(
                    className = "android.widget.ImageButton",
                    isClickable = true,
                    boundsCenterX = 912,
                    boundsCenterY = 2115,
                    boundsWidthPx = 144,
                    boundsHeightPx = 144
                )
            )
        )

        val outcome = GoogleMusicNodeParser.parseTree(strippedTree, allowCircleToSearchButtonDetection = true)
        assertTrue(outcome is NodeParseOutcome.CircleToSearchMusicButtonFound)
        val btn = outcome as NodeParseOutcome.CircleToSearchMusicButtonFound
        assertEquals(912, btn.fallbackTapX)
        assertEquals(2115, btn.fallbackTapY)
    }

    /**
     * Evaluates 500 Google overlay node hierarchies (including transient `"Listening..."`,
     * `"Google"`, `"Search"`, `"Translate"`, `"Try again"`, RTL Persian/Arabic titles, and 3-tier
     * song result cards) to verify zero false positives during the listening phase and accurate
     * extraction on result cards, alongside URL-encoded query verification for
     * `https://www.google.com/search?q=<Song>+<Artist>+%D8%AF%D8%A7%D9%86%D9%84%D9%88%D8%AF`.
     */
    @Test
    fun `evaluates 500 diverse Google QSB hierarchies and verifies URL-encoded Persian download query`() {
        val rng = Random(2026L)
        val transientPrompts = listOf(
            "Listening...",
            "Listening",
            "Sing, hum, or play",
            "Hum or sing",
            "Play, sing, or hum a song",
            "Keep holding...",
            "Searching for song...",
            "Google",
            "Search",
            "Translate",
            "Homework",
            "Share",
            "در حال گوش دادن..."
        )
        val failurePrompts = listOf(
            "Couldn't find a match",
            "No match found",
            "Can't identify song",
            "Try again",
            "No match",
            "Didn't catch that",
            "نتیجه‌ای یافت نشد"
        )
        val songPairs = listOf(
            "Blinding Lights" to "The Weeknd",
            "Starboy (feat. Daft Punk)" to "The Weeknd",
            "گوهر کمیاب" to "محسن چاوشی",
            "تملي معاك" to "عمرو دياب",
            "봄날 (Spring Day)" to "BTS",
            "Creepin' (with The Weeknd & 21 Savage)" to "Metro Boomin",
            "Veridis Quo" to "Daft Punk",
            "با من صنما" to "همایون شجریان"
        )

        // Verify Persian download URL encoding (`https://www.google.com/search?q=<Song>+<Artist>+%D8%AF%D8%A7%D9%86%D9%84%D9%88%D8%AF`)
        val sampleUrl = PersianDownloadBrowserLauncher.buildGoogleDownloadSearchUrl("Blinding Lights The Weeknd")
        assertEquals(
            "https://www.google.com/search?q=Blinding+Lights+The+Weeknd+%D8%AF%D8%A7%D9%86%D9%84%D9%88%D8%AF",
            sampleUrl
        )
        for ((title, artist) in songPairs) {
            val rawBase = "$title $artist"
            val encodedUrl = PersianDownloadBrowserLauncher.buildGoogleDownloadSearchUrl(rawBase)
            assertTrue(
                "URL must end with +%D8%AF%D8%A7%D9%86%D9%84%D9%88%D8%AF: $encodedUrl",
                encodedUrl.endsWith("+%D8%AF%D8%A7%D9%86%D9%84%D9%88%D8%AF")
            )
            val decoded = URLDecoder.decode(
                encodedUrl.removePrefix("https://www.google.com/search?q="),
                StandardCharsets.UTF_8.name()
            )
            assertEquals("$rawBase دانلود", decoded)
        }

        var totalVerified = 0
        val startNanos = System.nanoTime()

        for (i in 0 until 500) {
            when (i % 5) {
                0 -> {
                    // Archetype 0: Transient listening / Chrome pills ("Listening...", "Google", "Search", "Translate", "Try again" during grace period)
                    val prompt = transientPrompts[i % transientPrompts.size]
                    val tree = ParsedNodeSnapshot(
                        packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                        children = listOf(
                            ParsedNodeSnapshot(className = "android.widget.ProgressBar"),
                            ParsedNodeSnapshot(text = prompt),
                            ParsedNodeSnapshot(text = "Google"),
                            ParsedNodeSnapshot(text = "Search"),
                            ParsedNodeSnapshot(text = "Translate"),
                            ParsedNodeSnapshot(text = "Try again"),
                            ParsedNodeSnapshot(text = "0:0${i % 9} / 0:15")
                        )
                    )
                    // During the listening grace period (`allowFailureDetection = false`), even if "Try again" is present,
                    // it MUST return TransientListeningOrEmpty and 0 false positives!
                    val outcomeDuringGrace = GoogleMusicNodeParser.parseTree(
                        root = tree,
                        allowCircleToSearchButtonDetection = false,
                        allowFailureDetection = false
                    )
                    assertEquals(
                        "Transient tree #$i ('$prompt') must NEVER produce a false-positive song or premature failure during listening",
                        NodeParseOutcome.TransientListeningOrEmpty,
                        outcomeDuringGrace
                    )
                    totalVerified++
                }

                1 -> {
                    // Archetype 1: Explicit Failure hierarchy after 5,500ms ("Couldn't find a match", "No match found", "Try again")
                    val failText = failurePrompts[i % failurePrompts.size]
                    val tree = ParsedNodeSnapshot(
                        packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                        children = listOf(
                            ParsedNodeSnapshot(text = failText),
                            ParsedNodeSnapshot(text = "Try again", isClickable = true)
                        )
                    )
                    val outcome = GoogleMusicNodeParser.parseTree(
                        root = tree,
                        allowCircleToSearchButtonDetection = false,
                        allowFailureDetection = true
                    )
                    assertTrue(
                        "Failure tree #$i ('$failText') must be classified as RecognitionFailed when allowFailureDetection=true",
                        outcome is NodeParseOutcome.RecognitionFailed
                    )
                    totalVerified++
                }

                2 -> {
                    // Archetype 2: Circle to Search overlay variants (with ID, with label, or stripped-ID positional)
                    val variant = (i / 5) % 3
                    val tree = when (variant) {
                        0 -> ParsedNodeSnapshot(
                            packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                            children = listOf(
                                ParsedNodeSnapshot(
                                    viewIdResourceName = "com.google.android.googlequicksearchbox:id/omnient_music_search_button",
                                    isClickable = true,
                                    boundsCenterX = 900,
                                    boundsCenterY = 2100
                                )
                            )
                        )
                        1 -> ParsedNodeSnapshot(
                            packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                            children = listOf(
                                ParsedNodeSnapshot(
                                    contentDescription = "Search a song",
                                    isClickable = true,
                                    boundsCenterX = 905,
                                    boundsCenterY = 2105
                                )
                            )
                        )
                        else -> ParsedNodeSnapshot(
                            packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                            children = listOf(
                                ParsedNodeSnapshot(text = "Circle or tap anywhere to search"),
                                ParsedNodeSnapshot(
                                    className = "android.widget.ImageButton",
                                    isClickable = true,
                                    boundsCenterX = 910,
                                    boundsCenterY = 2110,
                                    boundsWidthPx = 144,
                                    boundsHeightPx = 144
                                )
                            )
                        )
                    }
                    val outcome = GoogleMusicNodeParser.parseTree(tree, allowCircleToSearchButtonDetection = true)
                    assertTrue(
                        "CtS button variant #$variant (iteration $i) must detect Music button",
                        outcome is NodeParseOutcome.CircleToSearchMusicButtonFound
                    )
                    totalVerified++
                }

                3 -> {
                    // Archetype 3: Matched Song + Artist across Tier 1, Tier 2, and Tier 3 topologies
                    val (expectedTitle, expectedArtist) = songPairs[i % songPairs.size]
                    val topology = (i / 5) % 3
                    val tree = when (topology) {
                        0 -> ParsedNodeSnapshot(
                            packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                            children = listOf(
                                ParsedNodeSnapshot(
                                    viewIdResourceName = "com.google.android.googlequicksearchbox:id/sound_search_result_title",
                                    text = expectedTitle
                                ),
                                ParsedNodeSnapshot(
                                    viewIdResourceName = "com.google.android.googlequicksearchbox:id/sound_search_result_artist",
                                    text = expectedArtist
                                )
                            )
                        )
                        1 -> ParsedNodeSnapshot(
                            packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                            children = listOf(
                                ParsedNodeSnapshot(text = expectedTitle),
                                ParsedNodeSnapshot(text = "Song by $expectedArtist")
                            )
                        )
                        else -> ParsedNodeSnapshot(
                            packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                            children = listOf(
                                ParsedNodeSnapshot(
                                    viewIdResourceName = "com.google.android.googlequicksearchbox:id/omnient_search_plate_text",
                                    text = "$expectedTitle - $expectedArtist"
                                )
                            )
                        )
                    }
                    val outcome = GoogleMusicNodeParser.parseTree(tree)
                    assertTrue("Song tree #$i (topology $topology) must match", outcome is NodeParseOutcome.SongMatched)
                    val matched = (outcome as NodeParseOutcome.SongMatched).candidate
                    assertEquals(expectedTitle, matched.songTitle)
                    assertEquals(expectedArtist, matched.artistName)
                    totalVerified++
                }

                4 -> {
                    // Archetype 4: Deeply nested malformed / null-text / emoji / system noise trees
                    var cursor = ParsedNodeSnapshot(
                        packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                        text = if (rng.nextBoolean()) null else "   \n\t  "
                    )
                    for (depth in 0 until 24) {
                        cursor = ParsedNodeSnapshot(
                            packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                            className = "android.view.ViewGroup",
                            text = if (depth % 3 == 0) "🎵" else null,
                            contentDescription = if (depth % 4 == 0) "Close" else null,
                            children = listOf(cursor)
                        )
                    }
                    val outcome = GoogleMusicNodeParser.parseTree(cursor, allowCircleToSearchButtonDetection = false)
                    assertNotNull(outcome)
                    assertTrue(
                        "Malformed tree #$i must never false-positive as a song",
                        outcome !is NodeParseOutcome.SongMatched
                    )
                    totalVerified++
                }
            }
        }

        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0
        val avgMs = elapsedMs / 500.0
        assertEquals(500, totalVerified)
        assertTrue("Average parse time ($avgMs ms) must be < 8ms", avgMs < 8.0)
    }
}
