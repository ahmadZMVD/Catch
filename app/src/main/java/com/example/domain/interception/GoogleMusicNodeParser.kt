package com.example.domain.interception

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 3-tier heuristic node extraction engine for `com.google.android.googlequicksearchbox`
 * in Circle to Search audio recognition sessions.
 *
 * Key responsibilities:
 * - Filters out non-song UI chrome and transient listening prompts across system locales.
 * - Enforces time-gated explicit failure detection (`allowFailureDetection` enabled only after 5,500ms).
 * - Locates the Circle to Search audio search button via resource ID, localized label, or bottom-right
 *   geometric icon heuristics.
 */
object GoogleMusicNodeParser {

    const val GOOGLE_QSB_PACKAGE = "com.google.android.googlequicksearchbox"
    const val MAX_TRAVERSAL_DEPTH = 40
    const val MAX_TRAVERSAL_NODES = 300

    private const val EN_DASH = "\u2013"
    private const val EM_DASH = "\u2014"

    private val TRANSIENT_LISTENING_PHRASES = setOf(
        "listening",
        "listening...",
        "listening\u2026",
        "searching for song",
        "searching for song...",
        "searching for song\u2026",
        "searching for a song",
        "searching...",
        "searching\u2026",
        "identifying",
        "identifying song",
        "identifying...",
        "play, sing, or hum",
        "play, sing, or hum a song",
        "sing, hum, or play",
        "sing, hum, or play music",
        "hum, sing, or play",
        "hum or sing",
        "keep holding",
        "keep holding...",
        "hold close to music",
        "finding song",
        "working on it",
        "working on it...",
        "getting results",
        "getting results...",
        "make sure the music is loud enough",
        "در حال گوش دادن",
        "در حال گوش دادن...",
        "جاري الاستماع",
        "جاري الاستماع...",
        "dinleniyor",
        "dinleniyor...",
        "escuchando",
        "escuchando...",
        "écoute en cours",
        "écoute en cours...",
        "hört zu",
        "hört zu...",
        "слушаю",
        "слушаю...",
        "सुन रहा है",
        "正在聆听",
        "正在聆听...",
        "聴いています",
        "듣는 중",
        "듣는 중..."
    )

    private val FAILURE_PHRASES = listOf(
        "couldn't find a match",
        "could not find a match",
        "no match found",
        "can't identify song",
        "cannot identify song",
        "could not identify song",
        "try again",
        "no match",
        "didn't catch that",
        "no song found",
        "no results found",
        "couldn't identify",
        "something went wrong",
        "check your connection",
        "you're offline",
        "network error",
        "نتیجه‌ای یافت نشد",
        "لم يتم العثور على تطابق",
        "eşleşme bulunamadı",
        "no se encontraron coincidencias",
        "aucune correspondance",
        "kein ergebnis gefunden",
        "совпадений не найдено",
        "कोई मिलान नहीं मिला",
        "未找到匹配项",
        "一致する曲が見つかりませんでした",
        "일치하는 항목을 찾을 수 없음"
    )

    private val CHROME_IGNORE_EXACT = setOf(
        "google",
        "search",
        "search...",
        "search\u2026",
        "voice search",
        "sound search",
        "music search",
        "clear",
        "back",
        "close",
        "cancel",
        "dismiss",
        "share",
        "more",
        "more options",
        "sign in",
        "all",
        "images",
        "videos",
        "news",
        "maps",
        "shopping",
        "books",
        "flights",
        "finance",
        "short videos",
        "forums",
        "web",
        "tools",
        "feedback",
        "privacy",
        "terms",
        "settings",
        "hum to search",
        "try again",
        "retry",
        "overview",
        "listen",
        "lyrics",
        "similar songs",
        "other recordings",
        "about",
        "artists",
        "album",
        "youtube",
        "spotify",
        "apple music",
        "deezer",
        "soundhound",
        "youtube music",
        "open",
        "play",
        "preview",
        "save",
        "saved",
        "translate",
        "homework",
        "circle or tap anywhere to search",
        "circle to search",
        "music",
        "song",
        "search a song",
        "find a song",
        "what's this song",
        "what's this song?",
        "what is this song",
        "can't identify song",
        "no match found",
        "couldn't find a match",
        "keep holding",
        "hold close to music",
        "آهنگ",
        "موزیک",
        "جستجوی آهنگ",
        "أغنية",
        "موسيقى",
        "şarkı",
        "canción",
        "música",
        "chanson",
        "musique",
        "lied",
        "musik",
        "песня",
        "मुझसे गाना खोजें",
        "歌曲",
        "音乐",
        "曲",
        "노래"
    )

    private val TITLE_RESOURCE_ID_HINTS = listOf(
        "sound_search_result_title",
        "music_recognition_title",
        "music_result_title",
        "song_title",
        "track_title",
        "matched_song_title",
        "uga_title",
        "knowledge_panel_title",
        "entity_title",
        "header_title",
        "srp_title"
    )

    private val ARTIST_RESOURCE_ID_HINTS = listOf(
        "sound_search_result_artist",
        "sound_search_result_subtitle",
        "music_recognition_subtitle",
        "music_recognition_artist",
        "music_result_subtitle",
        "song_artist",
        "track_artist",
        "matched_song_artist",
        "uga_subtitle",
        "knowledge_panel_subtitle",
        "entity_subtitle",
        "header_subtitle"
    )

    private val SEARCH_BOX_RESOURCE_ID_HINTS = listOf(
        "googleapp_srp_search_box_text",
        "search_box_text",
        "omnient_search_plate_text",
        "search_plate_text",
        "url_bar",
        "search_edit_frame"
    )

    val CIRCLE_TO_SEARCH_MUSIC_BUTTON_HINTS = listOf(
        "search a song",
        "find a song",
        "identify song",
        "identify music",
        "identify a song",
        "music search",
        "sound search",
        "what's this song",
        "audio search",
        "hum to search",
        "music note",
        "song",
        "music",
        "hum",
        "audio",
        "identify",
        "آهنگ",
        "موزیک",
        "جستجوی آهنگ",
        "تشخیص آهنگ",
        "أغنية",
        "موسيقى",
        "şarkı",
        "canción",
        "música",
        "chanson",
        "musique",
        "lied",
        "musik",
        "песня",
        "मुझसे गाना खोजें",
        "歌曲",
        "音乐",
        "曲",
        "노래"
    )

    private val CIRCLE_TO_SEARCH_MUSIC_VIEW_ID_TOKENS = listOf(
        "music",
        "song",
        "audio",
        "sound",
        "hum"
    )

    private val SONG_BY_PREFIXES = listOf(
        "song by ",
        "track by ",
        "single by ",
        "music by ",
        "original song by ",
        "آهنگ از ",
        "ترانه از ",
        "أغنية من ",
        "canción de ",
        "chanson de ",
        "lied von ",
        "песня "
    )

    private data class FlattenedNode(
        val index: Int,
        val depth: Int,
        val viewId: String,
        val className: String,
        val text: String,
        val contentDescription: String,
        val isClickable: Boolean,
        val isHeading: Boolean,
        val boundsCenterX: Int?,
        val boundsCenterY: Int?,
        val boundsWidthPx: Int?,
        val boundsHeightPx: Int?
    ) {
        val displayLabel: String
            get() = text.ifEmpty { contentDescription }
    }

    /**
     * Parses a snapshot of an Accessibility window tree and returns a deterministic [NodeParseOutcome].
     *
     * @param allowCircleToSearchButtonDetection Whether to scan for the Circle to Search audio search button.
     * @param allowFailureDetection Whether explicit failure strings ("No match found", "Couldn't find a match", "Try again")
     *   should return [NodeParseOutcome.RecognitionFailed]. Kept `false` during the initial audio sampling window.
     */
    fun parseTree(
        root: ParsedNodeSnapshot?,
        allowCircleToSearchButtonDetection: Boolean = true,
        allowFailureDetection: Boolean = true,
        screenWidthPx: Int = 1080,
        screenHeightPx: Int = 2340
    ): NodeParseOutcome {
        if (root == null) return NodeParseOutcome.TransientListeningOrEmpty

        val flatNodes = flattenBounded(root)
        if (flatNodes.isEmpty()) return NodeParseOutcome.TransientListeningOrEmpty

        if (allowFailureDetection) {
            for (node in flatNodes) {
                val lowerText = node.displayLabel.lowercase(Locale.ROOT).trim()
                if (lowerText.isNotEmpty() && FAILURE_PHRASES.any { lowerText.contains(it) }) {
                    return NodeParseOutcome.RecognitionFailed(node.displayLabel)
                }
            }
        }

        val tier1Result = extractTier1ByResourceIds(flatNodes)
        if (tier1Result != null) {
            return NodeParseOutcome.SongMatched(tier1Result)
        }

        val tier2Result = extractTier2BySemanticByline(flatNodes)
        if (tier2Result != null) {
            return NodeParseOutcome.SongMatched(tier2Result)
        }

        val tier3Result = extractTier3BySearchPillOrMusicCard(flatNodes)
        if (tier3Result != null) {
            return NodeParseOutcome.SongMatched(tier3Result)
        }

        if (allowCircleToSearchButtonDetection) {
            val ctsButton = detectCircleToSearchMusicButton(
                nodes = flatNodes,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx
            )
            if (ctsButton != null) {
                return ctsButton
            }
        }

        return NodeParseOutcome.TransientListeningOrEmpty
    }

    /**
     * Parses a list of `com.google.android.googlequicksearchbox` window snapshots in priority order.
     */
    fun parseMultipleWindows(
        roots: List<ParsedNodeSnapshot>,
        allowCircleToSearchButtonDetection: Boolean = true,
        allowFailureDetection: Boolean = true,
        screenWidthPx: Int = 1080,
        screenHeightPx: Int = 2340
    ): NodeParseOutcome {
        var foundButton: NodeParseOutcome.CircleToSearchMusicButtonFound? = null
        var foundFailure: NodeParseOutcome.RecognitionFailed? = null

        for (root in roots) {
            val outcome = parseTree(
                root = root,
                allowCircleToSearchButtonDetection = allowCircleToSearchButtonDetection,
                allowFailureDetection = allowFailureDetection,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx
            )
            when (outcome) {
                is NodeParseOutcome.SongMatched -> return outcome
                is NodeParseOutcome.CircleToSearchMusicButtonFound -> {
                    if (foundButton == null) foundButton = outcome
                }
                is NodeParseOutcome.RecognitionFailed -> {
                    if (foundFailure == null) foundFailure = outcome
                }
                is NodeParseOutcome.TransientListeningOrEmpty -> Unit
            }
        }
        return foundButton ?: foundFailure ?: NodeParseOutcome.TransientListeningOrEmpty
    }

    /**
     * Returns true if the given Google Quick Search Box tree shows active audio listening
     * (such as "Listening...", "Play, sing, or hum a song", or audio waveform nodes).
     */
    fun isAudioListeningUiActive(root: ParsedNodeSnapshot?): Boolean {
        if (root == null) return false
        val flat = flattenBounded(root)
        return flat.any { node ->
            isTransientListeningPhrase(node.displayLabel) ||
                node.viewId.contains("sound_search", ignoreCase = true) ||
                node.viewId.contains("music_recognition", ignoreCase = true) ||
                node.viewId.contains("audio_wave", ignoreCase = true)
        }
    }

    private fun flattenBounded(root: ParsedNodeSnapshot): List<FlattenedNode> {
        val result = ArrayList<FlattenedNode>(64)
        val stack = ArrayDeque<Pair<ParsedNodeSnapshot, Int>>()
        val visitedIdentity = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ParsedNodeSnapshot, Boolean>())

        stack.addLast(root to 0)

        while (stack.isNotEmpty() && result.size < MAX_TRAVERSAL_NODES) {
            val (current, depth) = stack.removeLast()
            if (depth > MAX_TRAVERSAL_DEPTH) continue
            if (!visitedIdentity.add(current)) continue

            if (current.isVisibleToUser) {
                val cleanText = sanitizeNodeText(current.text)
                val cleanDesc = sanitizeNodeText(current.contentDescription)
                val cleanId = current.viewIdResourceName?.trim().orEmpty()
                val cleanClass = current.className?.trim().orEmpty()

                if (cleanText.isNotEmpty() || cleanDesc.isNotEmpty() || cleanId.isNotEmpty() || current.isClickable) {
                    result.add(
                        FlattenedNode(
                            index = result.size,
                            depth = depth,
                            viewId = cleanId,
                            className = cleanClass,
                            text = cleanText,
                            contentDescription = cleanDesc,
                            isClickable = current.isClickable,
                            isHeading = current.isHeading,
                            boundsCenterX = current.boundsCenterX,
                            boundsCenterY = current.boundsCenterY,
                            boundsWidthPx = current.boundsWidthPx,
                            boundsHeightPx = current.boundsHeightPx
                        )
                    )
                }
            }

            val children = current.children
            for (i in children.size - 1 downTo 0) {
                stack.addLast(children[i] to (depth + 1))
            }
        }
        return result
    }

    private fun extractTier1ByResourceIds(nodes: List<FlattenedNode>): ExtractedSongCandidate? {
        var titleNode: FlattenedNode? = null
        var artistNode: FlattenedNode? = null

        for (node in nodes) {
            val idLower = node.viewId.lowercase(Locale.ROOT)
            if (idLower.isEmpty()) continue
            val candidateText = node.text.ifEmpty { node.contentDescription }
            if (!isValidCandidateText(candidateText)) continue

            if (titleNode == null && TITLE_RESOURCE_ID_HINTS.any { idLower.endsWith(it) || idLower.contains(it) }) {
                titleNode = node
                continue
            }
            if (artistNode == null && ARTIST_RESOURCE_ID_HINTS.any { idLower.endsWith(it) || idLower.contains(it) }) {
                artistNode = node
            }
        }

        if (titleNode != null) {
            val rawTitleText = titleNode.displayLabel
            val rawArtistText = artistNode?.displayLabel ?: findAdjacentArtistForTitle(nodes, titleNode)
            val cleanedArtist = cleanArtistByline(rawArtistText)
            val cleanedTitle = cleanSongTitle(rawTitleText, cleanedArtist)
            if (cleanedTitle.isNotEmpty() && !isTransientOrChrome(cleanedTitle)) {
                val combinedRaw = if (cleanedArtist.isNotEmpty() && !rawTitleText.contains(cleanedArtist, ignoreCase = true)) {
                    "$cleanedTitle - $cleanedArtist"
                } else {
                    rawTitleText
                }
                return ExtractedSongCandidate(
                    songTitle = cleanedTitle,
                    artistName = cleanedArtist,
                    fullRawTitle = combinedRaw,
                    confidenceTier = 1,
                    matchedByRule = "TIER1_RESOURCE_ID(${titleNode.viewId})"
                )
            }
        }
        return null
    }

    private fun extractTier2BySemanticByline(nodes: List<FlattenedNode>): ExtractedSongCandidate? {
        for (i in nodes.indices) {
            val node = nodes[i]
            val label = node.displayLabel
            if (label.isEmpty()) continue
            val lower = label.lowercase(Locale.ROOT).trim()

            val matchedPrefix = SONG_BY_PREFIXES.firstOrNull { lower.startsWith(it) }
            if (matchedPrefix != null) {
                val extractedArtist = cleanArtistByline(label.substring(matchedPrefix.length))
                val precedingTitle = findPrecedingSongTitle(nodes, i, extractedArtist)
                if (precedingTitle.isNotEmpty()) {
                    val cleanedTitle = cleanSongTitle(precedingTitle, extractedArtist)
                    if (cleanedTitle.isNotEmpty()) {
                        val fullRaw = if (extractedArtist.isNotEmpty()) "$cleanedTitle - $extractedArtist" else cleanedTitle
                        return ExtractedSongCandidate(
                            songTitle = cleanedTitle,
                            artistName = extractedArtist,
                            fullRawTitle = fullRaw,
                            confidenceTier = 2,
                            matchedByRule = "TIER2_SONG_BY_BYLINE"
                        )
                    }
                }
            }

            if (lower.contains("\u2022 song") || lower.contains("\u00b7 song") || lower.startsWith("song \u2022") || lower.startsWith("song \u00b7")) {
                val parts = label.split('\u2022', '\u00b7').map { it.trim() }.filter { it.isNotEmpty() }
                val artistPart = parts.firstOrNull { !it.equals("song", ignoreCase = true) && !it.matches(Regex("^\\d{4}$")) }.orEmpty()
                val cleanedArtist = cleanArtistByline(artistPart)
                val precedingTitle = findPrecedingSongTitle(nodes, i, cleanedArtist)
                if (precedingTitle.isNotEmpty()) {
                    val cleanedTitle = cleanSongTitle(precedingTitle, cleanedArtist)
                    if (cleanedTitle.isNotEmpty()) {
                        val fullRaw = if (cleanedArtist.isNotEmpty()) "$cleanedTitle - $cleanedArtist" else cleanedTitle
                        return ExtractedSongCandidate(
                            songTitle = cleanedTitle,
                            artistName = cleanedArtist,
                            fullRawTitle = fullRaw,
                            confidenceTier = 2,
                            matchedByRule = "TIER2_BULLET_SONG_MARKER"
                        )
                    }
                }
            }
        }
        return null
    }

    private fun extractTier3BySearchPillOrMusicCard(nodes: List<FlattenedNode>): ExtractedSongCandidate? {
        val isListeningStillActive = nodes.any { isTransientListeningPhrase(it.displayLabel) }
        if (isListeningStillActive) return null

        val hasMusicContextTab = nodes.any { n ->
            val l = n.displayLabel.lowercase(Locale.ROOT)
            l == "lyrics" || l == "listen" || l == "other recordings" || l == "similar songs" ||
                n.viewId.contains("sound_search", ignoreCase = true) ||
                n.viewId.contains("music_result", ignoreCase = true)
        }

        for (node in nodes) {
            val isSearchBox = SEARCH_BOX_RESOURCE_ID_HINTS.any { node.viewId.contains(it, ignoreCase = true) } ||
                node.className.endsWith("EditText")
            val text = node.text.ifEmpty { node.contentDescription }
            if (!isValidCandidateText(text)) continue

            val split = splitDelimitedSongAndArtist(text)
            if (split != null && (isSearchBox || hasMusicContextTab || node.isHeading)) {
                val (titlePart, artistPart) = split
                if (!isTransientOrChrome(titlePart) && !isTransientOrChrome(artistPart)) {
                    return ExtractedSongCandidate(
                        songTitle = titlePart,
                        artistName = artistPart,
                        fullRawTitle = "$titlePart - $artistPart",
                        confidenceTier = 3,
                        matchedByRule = "TIER3_DELIMITED_SEARCH_OR_HEADING"
                    )
                }
            }
        }
        return null
    }

    /**
     * 3-tier Circle to Search audio search button detector:
     * 1. Explicit `viewIdResourceName` containing `music`, `song`, `audio`, `sound`, or `hum`.
     * 2. Localized `contentDescription` / `text` matching [CIRCLE_TO_SEARCH_MUSIC_BUTTON_HINTS].
     * 3. Bottom-right geometric icon fallback (`centerX > screenWidth * 0.72` and `centerY > screenHeight * 0.78`,
     *    `width < 220px`, `height < 220px`).
     */
    private fun detectCircleToSearchMusicButton(
        nodes: List<FlattenedNode>,
        screenWidthPx: Int,
        screenHeightPx: Int
    ): NodeParseOutcome.CircleToSearchMusicButtonFound? {
        for (node in nodes) {
            val idLower = node.viewId.lowercase(Locale.ROOT)
            if (idLower.isNotEmpty() && CIRCLE_TO_SEARCH_MUSIC_VIEW_ID_TOKENS.any { idLower.contains(it) }) {
                if (idLower.contains("title") || idLower.contains("artist") || idLower.contains("subtitle")) {
                    continue
                }
                return NodeParseOutcome.CircleToSearchMusicButtonFound(
                    buttonLabel = node.displayLabel.ifEmpty { "Search a song" },
                    viewId = node.viewId.ifEmpty { null },
                    fallbackTapX = node.boundsCenterX,
                    fallbackTapY = node.boundsCenterY
                )
            }
        }

        for (node in nodes) {
            val combinedLabel = "${node.text} ${node.contentDescription}".lowercase(Locale.ROOT).trim()
            if (combinedLabel.isEmpty()) continue
            val matchesLabel = CIRCLE_TO_SEARCH_MUSIC_BUTTON_HINTS.any { hint ->
                combinedLabel == hint || combinedLabel.contains(hint)
            }
            if (matchesLabel && (node.isClickable || node.className.contains("Button") || node.className.contains("Image"))) {
                return NodeParseOutcome.CircleToSearchMusicButtonFound(
                    buttonLabel = node.displayLabel.ifEmpty { "Search a song" },
                    viewId = node.viewId.ifEmpty { null },
                    fallbackTapX = node.boundsCenterX,
                    fallbackTapY = node.boundsCenterY
                )
            }
        }

        val minX = (screenWidthPx * 0.72f).toInt()
        val minY = (screenHeightPx * 0.78f).toInt()

        val bottomRightIcons = nodes.filter { n ->
            val cx = n.boundsCenterX ?: return@filter false
            val cy = n.boundsCenterY ?: return@filter false
            val w = n.boundsWidthPx ?: 120
            val h = n.boundsHeightPx ?: 120
            val isIconClass = n.className.endsWith("ImageButton") ||
                n.className.endsWith("ImageView") ||
                n.className.endsWith("Button") ||
                n.className.endsWith("FrameLayout") ||
                n.className.endsWith("View")
            val labelLower = n.displayLabel.lowercase(Locale.ROOT)
            val isExcludedButton = labelLower.contains("close") ||
                labelLower.contains("dismiss") ||
                labelLower.contains("more") ||
                labelLower.contains("share") ||
                labelLower.contains("translate") ||
                labelLower.contains("lens")

            n.isClickable &&
                isIconClass &&
                !isExcludedButton &&
                cx >= minX &&
                cy >= minY &&
                w in 1..220 &&
                h in 1..220
        }

        val candidateIcon = bottomRightIcons.maxByOrNull { it.boundsCenterX ?: 0 }
        if (candidateIcon != null) {
            return NodeParseOutcome.CircleToSearchMusicButtonFound(
                buttonLabel = candidateIcon.displayLabel.ifEmpty { "Search a song" },
                viewId = candidateIcon.viewId.ifEmpty { null },
                fallbackTapX = candidateIcon.boundsCenterX,
                fallbackTapY = candidateIcon.boundsCenterY
            )
        }

        return null
    }

    private fun findPrecedingSongTitle(
        nodes: List<FlattenedNode>,
        bylineIndex: Int,
        artistName: String
    ): String {
        val start = (bylineIndex - 1)
        val stop = (bylineIndex - 6).coerceAtLeast(0)
        for (idx in start downTo stop) {
            val candidate = nodes[idx].displayLabel
            if (!isValidCandidateText(candidate)) continue
            if (candidate.equals(artistName, ignoreCase = true)) continue
            if (SONG_BY_PREFIXES.any { candidate.lowercase(Locale.ROOT).startsWith(it) }) continue
            return candidate
        }
        return ""
    }

    private fun findAdjacentArtistForTitle(
        nodes: List<FlattenedNode>,
        titleNode: FlattenedNode
    ): String {
        val nextIndex = titleNode.index + 1
        val maxIndex = (titleNode.index + 4).coerceAtMost(nodes.lastIndex)
        for (idx in nextIndex..maxIndex) {
            val candidate = nodes[idx].displayLabel
            if (!isValidCandidateText(candidate)) continue
            if (candidate.equals(titleNode.displayLabel, ignoreCase = true)) continue
            return candidate
        }
        return ""
    }

    private fun splitDelimitedSongAndArtist(raw: String): Pair<String, String>? {
        val delimiters = listOf(" - ", " $EN_DASH ", " $EM_DASH ", " | ")
        for (delim in delimiters) {
            if (raw.contains(delim)) {
                val parts = raw.split(delim, limit = 2).map { it.trim() }
                if (parts.size == 2 && parts[0].length >= 2 && parts[1].length >= 2) {
                    val cleanFirst = cleanSongTitle(parts[0], parts[1])
                    val cleanSecond = cleanArtistByline(parts[1])
                    if (isValidCandidateText(cleanFirst) && isValidCandidateText(cleanSecond)) {
                        return cleanFirst to cleanSecond
                    }
                }
            }
        }
        return null
    }

    internal fun cleanArtistByline(rawArtist: String): String {
        var cleaned = sanitizeNodeText(rawArtist)
        val lower = cleaned.lowercase(Locale.ROOT)
        for (prefix in SONG_BY_PREFIXES) {
            if (lower.startsWith(prefix)) {
                cleaned = cleaned.substring(prefix.length).trim()
                break
            }
        }
        cleaned = cleaned
            .replace(Regex("(?i)\\s*[\u2022\u00b7]\\s*song.*$"), "")
            .replace(Regex("(?i)^song\\s*[\u2022\u00b7]\\s*"), "")
            .trim()
        return if (isTransientOrChrome(cleaned)) "" else cleaned
    }

    internal fun cleanSongTitle(rawTitle: String, artistName: String): String {
        var cleaned = sanitizeNodeText(rawTitle)
            .replace(Regex("(?i)\\s+lyrics$"), "")
            .replace(Regex("(?i)\\s+official\\s+video$"), "")
            .trim()

        if (artistName.isNotBlank()) {
            val suffixHyphen = " - $artistName"
            val suffixEnDash = " $EN_DASH $artistName"
            val suffixEmDash = " $EM_DASH $artistName"
            when {
                cleaned.endsWith(suffixHyphen, ignoreCase = true) ->
                    cleaned = cleaned.dropLast(suffixHyphen.length).trim()
                cleaned.endsWith(suffixEnDash, ignoreCase = true) ->
                    cleaned = cleaned.dropLast(suffixEnDash.length).trim()
                cleaned.endsWith(suffixEmDash, ignoreCase = true) ->
                    cleaned = cleaned.dropLast(suffixEmDash.length).trim()
            }
        }
        return if (isTransientOrChrome(cleaned)) "" else cleaned
    }

    internal fun isValidCandidateText(text: String): Boolean {
        val cleaned = sanitizeNodeText(text)
        if (cleaned.length < 2 || cleaned.length > 120) return false
        if (isTransientOrChrome(cleaned)) return false
        if (cleaned.startsWith("http://", ignoreCase = true) ||
            cleaned.startsWith("https://", ignoreCase = true) ||
            cleaned.startsWith("www.", ignoreCase = true)
        ) {
            return false
        }
        if (cleaned.matches(Regex("^[0-9:\\s/.\\-%]+$"))) return false
        val hasLetterOrDigit = cleaned.any { Character.isLetterOrDigit(it) }
        return hasLetterOrDigit
    }

    internal fun isTransientListeningPhrase(text: String): Boolean {
        val lower = sanitizeNodeText(text).lowercase(Locale.ROOT)
        if (lower.isEmpty()) return false
        return TRANSIENT_LISTENING_PHRASES.any { lower == it || lower.startsWith(it) }
    }

    internal fun isTransientOrChrome(text: String): Boolean {
        val lower = sanitizeNodeText(text).lowercase(Locale.ROOT)
        if (lower.isEmpty()) return true
        if (lower.length < 2) return true
        if (CHROME_IGNORE_EXACT.contains(lower)) return true
        if (isTransientListeningPhrase(lower)) return true
        if (FAILURE_PHRASES.any { lower.contains(it) }) return true
        return false
    }

    internal fun sanitizeNodeText(input: String?): String {
        if (input.isNullOrBlank()) return ""
        return input
            .replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun serializeTreeToJson(root: ParsedNodeSnapshot?, maxNodes: Int = 80): Pair<Int, String> {
        if (root == null) return 0 to "{}"
        var count = 0
        fun toJson(node: ParsedNodeSnapshot, depth: Int): JSONObject {
            count++
            val obj = JSONObject()
            if (!node.viewIdResourceName.isNullOrBlank()) {
                obj.put("id", node.viewIdResourceName.substringAfterLast('/'))
            }
            if (!node.className.isNullOrBlank()) {
                obj.put("cls", node.className.substringAfterLast('.'))
            }
            if (!node.text.isNullOrBlank()) {
                obj.put("text", node.text)
            }
            if (!node.contentDescription.isNullOrBlank()) {
                obj.put("desc", node.contentDescription)
            }
            if (node.isClickable) {
                obj.put("click", true)
            }
            if (node.boundsCenterX != null && node.boundsCenterY != null) {
                obj.put("xy", "${node.boundsCenterX},${node.boundsCenterY}")
            }
            if (depth < 12 && count < maxNodes && node.children.isNotEmpty()) {
                val arr = JSONArray()
                for (child in node.children) {
                    if (count >= maxNodes) break
                    arr.put(toJson(child, depth + 1))
                }
                if (arr.length() > 0) {
                    obj.put("children", arr)
                }
            }
            return obj
        }
        val json = toJson(root, 0).toString(2)
        return count to json
    }
}
