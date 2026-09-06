package app.grapheneos.info.ui.releases

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.grapheneos.info.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

const val TAG = "ReleasesViewModel"

class ReleasesViewModel(
    private val application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ReleasesUiState(savedStateHandle))
    val uiState: StateFlow<ReleasesUiState> = _uiState.asStateFlow()

    private val _selectedChannel = MutableStateFlow("all")
    val selectedChannel: StateFlow<String> = _selectedChannel.asStateFlow()

    private val _deviceChannel = MutableStateFlow("alpha")
    val deviceChannel: StateFlow<String> = _deviceChannel.asStateFlow()

    init {
        updateChangelog(
            useCaches = false,
            showSnackbarError = {},
            scrollChangelogLazyListTo = {},
            countAsInitialScroll = false,
            onFinishedUpdating = {},
        )
    }

    fun setChannelFilter(channel: String) {
        _selectedChannel.value = channel
    }

    fun updateChangelog(
        useCaches: Boolean,
        showSnackbarError: suspend (message: String) -> Unit,
        scrollChangelogLazyListTo: (scrollTo: Int) -> Unit,
        countAsInitialScroll: Boolean = true,
        onFinishedUpdating: () -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.value.isLoading.value = true
            }
            try {
                val device = Build.DEVICE?.lowercase() ?: "akita"
                val model = if (!Build.MODEL.isNullOrBlank()) Build.MODEL else "Pixel 8a"

                val parsedEntries = mutableMapOf<String, String>()
                val parsedChannelMap = mutableMapOf<String, String>()

                // 1. Fetch channel JSON files directly from GitHub with cache-busting
                val channels = listOf("stable", "beta", "alpha")
                val channelBuilds = mutableMapOf<String, MutableSet<String>>()
                val channelOtaData = mutableMapOf<String, JSONArray>()

                for (ch in channels) {
                    channelBuilds[ch] = mutableSetOf()
                    val apiContentsUrl = "https://api.github.com/repos/rhythmcreative/lineageos-$device-ota/contents/$device-$ch.json"
                    val rawUrl = "https://raw.githubusercontent.com/rhythmcreative/lineageos-$device-ota/main/$device-$ch.json"
                    val jsonText = fetchUrl(apiContentsUrl, useCaches = false, acceptHeader = "application/vnd.github.raw")
                        ?: fetchUrl(rawUrl, useCaches = false)
                    if (!jsonText.isNullOrBlank()) {
                        try {
                            val arr = JSONArray(jsonText)
                            channelOtaData[ch] = arr
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val dt = obj.optLong("datetime", 0L).toString()
                                if (dt != "0") channelBuilds[ch]?.add(dt)
                                val files = obj.optJSONArray("files")
                                if (files != null) {
                                    for (j in 0 until files.length()) {
                                        val f = files.getJSONObject(j)
                                        val fn = f.optString("filename", "")
                                        if (fn.isNotEmpty()) channelBuilds[ch]?.add(fn)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Error parsing channel $ch JSON: ${e.message}")
                        }
                    }
                }

                // Also check default $device.json (fallback for stable)
                val defaultApiUrl = "https://api.github.com/repos/rhythmcreative/lineageos-$device-ota/contents/$device.json"
                val defaultRawUrl = "https://raw.githubusercontent.com/rhythmcreative/lineageos-$device-ota/main/$device.json"
                val defaultJsonText = fetchUrl(defaultApiUrl, useCaches = false, acceptHeader = "application/vnd.github.raw")
                    ?: fetchUrl(defaultRawUrl, useCaches = false)
                if (!defaultJsonText.isNullOrBlank()) {
                    try {
                        val arr = JSONArray(defaultJsonText)
                        if (channelOtaData["stable"] == null || channelOtaData["stable"]!!.length() == 0) {
                            channelOtaData["stable"] = arr
                        }
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val dt = obj.optLong("datetime", 0L).toString()
                            if (dt != "0") channelBuilds["stable"]?.add(dt)
                            val files = obj.optJSONArray("files")
                            if (files != null) {
                                for (j in 0 until files.length()) {
                                    val f = files.getJSONObject(j)
                                    val fn = f.optString("filename", "")
                                    if (fn.isNotEmpty()) channelBuilds["stable"]?.add(fn)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Error parsing default $device.json: ${e.message}")
                    }
                }

                // Detect device channel from current build timestamp / properties
                val deviceUtc = getDeviceBuildUtc()
                var detectedChannel = "alpha"
                if (channelBuilds["stable"]?.contains(deviceUtc) == true) {
                    detectedChannel = "stable"
                } else if (channelBuilds["beta"]?.contains(deviceUtc) == true) {
                    detectedChannel = "beta"
                } else if (channelBuilds["alpha"]?.contains(deviceUtc) == true) {
                    detectedChannel = "alpha"
                } else {
                    val sysChannel = getSystemProperty("lineage.updater.channel", "")
                    if (sysChannel.isNotBlank()) {
                        detectedChannel = sysChannel.lowercase()
                    }
                }

                withContext(Dispatchers.Main) {
                    _deviceChannel.value = detectedChannel
                }

                // 2. Fetch live GitHub Releases API as primary source of real-time truth
                val releasesApiCandidates = listOf(
                    "https://api.github.com/repos/rhythmcreative/lineageos-$device-ota/releases",
                    "https://api.github.com/repos/rhythmcreative/lineageos-akita-ota/releases"
                )

                var releasesFromApi: Map<String, Pair<String, String>>? = null
                for (apiUrl in releasesApiCandidates) {
                    try {
                        val jsonText = fetchUrl(apiUrl, useCaches)
                        if (!jsonText.isNullOrBlank()) {
                            val entries = extractEntriesFromReleasesJson(
                                jsonText,
                                device,
                                model,
                                channelBuilds,
                                detectedChannel
                            )
                            if (entries.isNotEmpty()) {
                                releasesFromApi = entries
                                break
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Could not fetch releases from $apiUrl: ${e.message}")
                    }
                }

                if (!releasesFromApi.isNullOrEmpty()) {
                    for ((key, pair) in releasesFromApi) {
                        parsedEntries[key] = pair.first
                        parsedChannelMap[key] = pair.second
                    }
                } else {
                    // 3. Fallback: Parse README and channel JSONs
                    val readmeCandidates = listOf(
                        "https://raw.githubusercontent.com/rhythmcreative/lineageos-$device-ota/main/README.md",
                        "https://raw.githubusercontent.com/rhythmcreative/lineage-build-scripts/main/README.md",
                        "https://raw.githubusercontent.com/rhythmcreative/Info/main/README.md"
                    )

                    for (readmeUrl in readmeCandidates) {
                        try {
                            val text = fetchUrl(readmeUrl, useCaches)
                            if (text != null) {
                                val entries = extractEntriesFromReadme(text, device, model, detectedChannel)
                                if (!entries.isNullOrEmpty()) {
                                    for ((key, pair) in entries) {
                                        parsedEntries[key] = pair.first
                                        parsedChannelMap[key] = pair.second
                                    }
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Could not fetch or parse $readmeUrl: ${e.message}")
                        }
                    }

                    // 4. Fallback if still empty: Construct entries from channel JSON files
                    if (parsedEntries.isEmpty()) {
                        var index = 1
                        for (ch in channels) {
                            val arr = channelOtaData[ch] ?: continue
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val dt = obj.optLong("datetime", 0L)
                                val version = obj.optString("version", "24.0")
                                val files = obj.optJSONArray("files")
                                val firstFile = if (files != null && files.length() > 0) files.getJSONObject(0) else null
                                val dlUrl = firstFile?.optString("url", "") ?: ""
                                val fn = firstFile?.optString("filename", "lineage-$version") ?: "LineageOS"
                                val patchLevel = firstFile?.optString("os_patch_level", "") ?: ""

                                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dt * 1000))
                                val title = "$dateStr (${ch.replaceFirstChar { it.uppercase() }})"
                                val tag = dateStr
                                val deviceLabel = "$model, $device, LineageOS $version"
                                val body = "Build ${ch.uppercase()} para $model ($device).\n- Archivo: $fn" + if (patchLevel.isNotEmpty()) "\n- Parche de seguridad: $patchLevel" else ""

                                val sortKey = String.format("%06d", index++)
                                val xml = formatMarkdownSectionToXml(
                                    title = title,
                                    tag = tag,
                                    channel = ch,
                                    deviceLabel = deviceLabel,
                                    releaseUrl = dlUrl.ifBlank { "https://github.com/rhythmcreative/lineageos-$device-ota/releases" },
                                    prevTag = null,
                                    markdown = body
                                )
                                parsedEntries[sortKey] = xml
                                parsedChannelMap[sortKey] = ch
                            }
                        }
                    }
                }

                if (parsedEntries.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        _uiState.value.entries.clear()
                        _uiState.value.entries.putAll(parsedEntries)
                        _uiState.value.channelMap.clear()
                        _uiState.value.channelMap.putAll(parsedChannelMap)
                    }

                    if (countAsInitialScroll && !uiState.value.didInitialScroll) {
                        _uiState.value.didInitialScroll = true
                        scrollChangelogLazyListTo(0)
                    }
                }
            } catch (e: SocketTimeoutException) {
                val errorMessage =
                    application.getString(R.string.update_changelog_socket_timeout_exception_snackbar_message)
                Log.e(TAG, errorMessage, e)
                viewModelScope.launch {
                    showSnackbarError("$errorMessage: $e")
                }
            } catch (e: IOException) {
                val errorMessage =
                    application.getString(R.string.update_changelog_io_exception_snackbar_message)
                Log.e(TAG, errorMessage, e)
                viewModelScope.launch {
                    showSnackbarError("$errorMessage: $e")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating changelog", e)
                viewModelScope.launch {
                    showSnackbarError("Error: ${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _uiState.value.isLoading.value = false
                }
                onFinishedUpdating()
            }
        }
    }

    private fun getDeviceBuildUtc(): String {
        val propUtc = getSystemProperty("ro.build.date.utc", "")
        if (propUtc.isNotBlank()) return propUtc
        return (Build.TIME / 1000).toString()
    }

    private fun getSystemProperty(key: String, def: String = ""): String {
        return try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java, String::class.java)
            getMethod.invoke(null, key, def) as String
        } catch (_: Exception) {
            def
        }
    }

    private fun fetchUrl(
        urlString: String,
        useCaches: Boolean,
        acceptHeader: String = "application/vnd.github+json, text/plain, application/json, */*"
    ): String? {
        val targetUrl = if (!useCaches) {
            val delimiter = if (urlString.contains("?")) "&" else "?"
            "$urlString${delimiter}_t=${System.currentTimeMillis()}"
        } else {
            urlString
        }

        val url = try {
            URL(targetUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Malformed URL: $targetUrl", e)
            return null
        }

        val connection = url.openConnection() as? HttpsURLConnection ?: return null
        return try {
            connection.apply {
                connectTimeout = 8_000
                readTimeout = 15_000
                this.useCaches = useCaches
                setRequestProperty("User-Agent", "LineageOS-Info-App")
                setRequestProperty("Accept", acceptHeader)
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
            }
            connection.connect()
            if (connection.responseCode in 200..299) {
                BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            } else {
                Log.w(TAG, "HTTP ${connection.responseCode} for $targetUrl")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch $targetUrl: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Extracts changes from GitHub Releases API JSON
     */
    private fun extractEntriesFromReleasesJson(
        jsonString: String,
        device: String,
        model: String,
        channelBuilds: Map<String, Set<String>>,
        defaultChannel: String
    ): Map<String, Pair<String, String>> {
        val entries = mutableMapOf<String, Pair<String, String>>()
        try {
            val jsonArray = JSONArray(jsonString)
            data class ReleaseInfo(
                val name: String,
                val tagName: String,
                val htmlUrl: String,
                val body: String,
                val channel: String,
                val publishedAt: String
            )
            val releases = mutableListOf<ReleaseInfo>()
            for (i in 0 until jsonArray.length()) {
                val releaseObj = jsonArray.getJSONObject(i)
                val tagName = releaseObj.optString("tag_name", "")
                val name = releaseObj.optString("name", tagName).ifBlank { tagName }
                val htmlUrl = releaseObj.optString("html_url", "")
                val publishedAt = releaseObj.optString("published_at", "")
                var body = releaseObj.optString("body", "")

                val assetsArr = releaseObj.optJSONArray("assets")
                var detectedRelChannel = ""
                if (assetsArr != null) {
                    for (a in 0 until assetsArr.length()) {
                        val assetObj = assetsArr.getJSONObject(a)
                        val assetName = assetObj.optString("name", "")
                        for (ch in listOf("stable", "beta", "alpha")) {
                            if (channelBuilds[ch]?.contains(assetName) == true) {
                                detectedRelChannel = ch
                                break
                            }
                        }
                        if (detectedRelChannel.isNotEmpty()) break
                    }
                }

                if (detectedRelChannel.isEmpty()) {
                    val combinedName = "$tagName $name".lowercase()
                    detectedRelChannel = when {
                        combinedName.contains("stable") || combinedName.contains("estable") -> "stable"
                        combinedName.contains("beta") -> "beta"
                        combinedName.contains("alpha") -> "alpha"
                        releaseObj.optBoolean("prerelease", false) -> "beta"
                        else -> defaultChannel
                    }
                }

                val changesRegex = Regex("""(?im)^#{1,4}\s*changes\b.*$""")
                val match = changesRegex.find(body)
                if (match != null) {
                    body = body.substring(match.range.last + 1).trim()
                }
                releases.add(ReleaseInfo(name, tagName, htmlUrl, body, detectedRelChannel, publishedAt))
            }

            val dateRegex = Regex("""\b(20\d\d[-.]?\d\d[-.]?\d\d(?:[-.]?\d+)?)\b""")

            for (i in releases.indices) {
                val (name, tagName, htmlUrl, body, ch) = releases[i]
                val tag = dateRegex.find(tagName)?.value ?: dateRegex.find(name)?.value ?: tagName.ifBlank { name }
                val prevTag = if (i + 1 < releases.size) {
                    val nextRelease = releases[i + 1]
                    dateRegex.find(nextRelease.tagName)?.value ?: dateRegex.find(nextRelease.name)?.value ?: nextRelease.tagName
                } else null

                val specificReleaseUrl = if (htmlUrl.isNotBlank()) htmlUrl else getReleaseUrlForTag(device, tagName.ifBlank { tag })
                val deviceLabel = "$model, $device, LineageOS 24.0"
                val sortKey = String.format("%06d", releases.size - i)
                val xmlEntry = formatMarkdownSectionToXml(
                    title = tag,
                    tag = tag,
                    channel = ch,
                    deviceLabel = deviceLabel,
                    releaseUrl = specificReleaseUrl,
                    prevTag = prevTag,
                    markdown = body
                )
                entries[sortKey] = Pair(xmlEntry, ch)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing releases JSON", e)
        }
        return entries
    }

    /**
     * Extracts changes from README markdown starting from "# Changes" or "## Changes" downwards.
     */
    private fun extractEntriesFromReadme(
        markdown: String,
        device: String,
        model: String,
        defaultChannel: String
    ): Map<String, Pair<String, String>>? {
        val changesRegex = Regex("""(?im)^(#{1,4})\s*changes\b.*$""")
        val match = changesRegex.find(markdown) ?: return null
        val headerDepth = match.groupValues[1].length

        val textAfterChanges = markdown.substring(match.range.last + 1)
        val nonChangesRegex = Regex("""(?im)^#{1,$headerDepth}\s+.*$|^#{1,4}\s*(disclaimer|about|license|contributing|notes|credits|donate|contact)\b.*$""")
        val endMatch = nonChangesRegex.find(textAfterChanges)
        val changesText = if (endMatch != null) {
            textAfterChanges.substring(0, endMatch.range.first).trim()
        } else {
            textAfterChanges.trim()
        }
        if (changesText.isBlank()) return null

        val subHeadingRegex = Regex("""(?m)^#{2,4}\s+(.+)$""")
        val rawSubMatches = subHeadingRegex.findAll(changesText).toList()
        val subMatches = rawSubMatches.filter {
            !it.groupValues[1].trim().matches(Regex("""(?i)^(disclaimer|about|license|contributing|notes|credits|donate|contact)\b.*"""))
        }

        val entries = mutableMapOf<String, Pair<String, String>>()
        val dateRegex = Regex("""\b(20\d\d[-.]?\d\d[-.]?\d\d(?:[-.]?\d+)?)\b""")

        if (subMatches.isNotEmpty()) {
            val parsedSections = mutableListOf<Triple<String, String, String>>()
            for (i in subMatches.indices) {
                val currentMatch = subMatches[i]
                val rawTitle = currentMatch.groupValues[1].trim()
                val tag = dateRegex.find(rawTitle)?.value ?: rawTitle
                val startIndex = currentMatch.range.last + 1
                val endIndex = if (i + 1 < subMatches.size) subMatches[i + 1].range.first else changesText.length
                val sectionBody = changesText.substring(startIndex, endIndex).trim()
                parsedSections.add(Triple(rawTitle, tag, sectionBody))
            }

            for (i in parsedSections.indices) {
                val (rawTitle, tag, sectionBody) = parsedSections[i]
                val prevTag = if (i + 1 < parsedSections.size) parsedSections[i + 1].second else null
                val romVersion = Regex("""(LineageOS\s*[\d.]+)""", RegexOption.IGNORE_CASE).find(rawTitle)?.value ?: "LineageOS 24.0"
                val deviceLabel = "$model, $device, $romVersion"

                val sectionChannel = when {
                    rawTitle.contains("alpha", ignoreCase = true) -> "alpha"
                    rawTitle.contains("beta", ignoreCase = true) -> "beta"
                    rawTitle.contains("stable", ignoreCase = true) || rawTitle.contains("estable", ignoreCase = true) -> "stable"
                    else -> defaultChannel
                }

                val specificReleaseUrl = getReleaseUrlForTag(device, tag)
                val sortKey = String.format("%06d", parsedSections.size - i)
                val xmlEntry = formatMarkdownSectionToXml(
                    title = tag,
                    tag = tag,
                    channel = sectionChannel,
                    deviceLabel = deviceLabel,
                    releaseUrl = specificReleaseUrl,
                    prevTag = prevTag,
                    markdown = sectionBody
                )
                entries[sortKey] = Pair(xmlEntry, sectionChannel)
            }
        } else {
            val sortKey = "000001"
            val deviceLabel = "$model, $device, LineageOS 24.0"
            val defaultReleaseUrl = "https://github.com/rhythmcreative/lineageos-$device-ota/releases"
            val xmlEntry = formatMarkdownSectionToXml(
                title = "Changes",
                tag = "Current",
                channel = defaultChannel,
                deviceLabel = deviceLabel,
                releaseUrl = defaultReleaseUrl,
                prevTag = null,
                markdown = changesText
            )
            entries[sortKey] = Pair(xmlEntry, defaultChannel)
        }

        return entries
    }

    private fun getReleaseUrlForTag(device: String, tag: String): String {
        val trimmedTag = tag.trim().replace('.', '-')
        val gitTag = when {
            trimmedTag.startsWith("$device-", ignoreCase = true) -> trimmedTag
            trimmedTag.matches(Regex("""^20\d\d[-_]?\d\d[-_]?\d\d.*""")) -> "$device-$trimmedTag"
            trimmedTag.equals("Current", ignoreCase = true) -> ""
            else -> "$device-$trimmedTag"
        }
        return if (gitTag.isNotEmpty()) {
            "https://github.com/rhythmcreative/lineageos-$device-ota/releases/tag/$gitTag"
        } else {
            "https://github.com/rhythmcreative/lineageos-$device-ota/releases"
        }
    }

    /**
     * Converts a markdown block into an XML entry string understood by Changelog.kt.
     */
    private fun formatMarkdownSectionToXml(
        title: String,
        tag: String,
        channel: String,
        deviceLabel: String,
        releaseUrl: String,
        prevTag: String?,
        markdown: String
    ): String {
        val escapedTitle = escapeXml(title)
        val escapedReleaseUrl = escapeXml(releaseUrl)
        val escapedChannel = escapeXml(channel.lowercase())
        val sb = StringBuilder()
        sb.append("<channel>").append(escapedChannel).append("</channel>")
        sb.append("<title url=\"").append(escapedReleaseUrl).append("\">").append(escapedTitle).append("</title>")
        sb.append("<content><div>")

        val hasTagsAlready = markdown.contains("Tags:", ignoreCase = true) || markdown.contains("Etiquetas:", ignoreCase = true)
        if (!hasTagsAlready) {
            sb.append("<p>Tags:</p>")
            sb.append("<ul><li><a href=\"").append(escapedReleaseUrl).append("\">").append(escapeXml(tag)).append("</a> (").append(escapeXml(deviceLabel)).append(")</li></ul>")
            if (!prevTag.isNullOrBlank()) {
                sb.append("<p>Changes since the ").append(escapeXml(prevTag)).append(" release:</p>")
            } else {
                sb.append("<p>Changes in this release:</p>")
            }
        }

        val lines = markdown.lines()
        var inList = false

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("---") || line.startsWith("***") ||
                line.matches(Regex("""^</?(div|center|p|section|article|span)[^>]*>$""", RegexOption.IGNORE_CASE)) ||
                line.startsWith("<!--")
            ) {
                if (inList) {
                    sb.append("</ul>")
                    inList = false
                }
                continue
            }

            // Subheadings within a section
            if (line.startsWith("#### ") || line.startsWith("### ") || line.startsWith("## ")) {
                if (inList) {
                    sb.append("</ul>")
                    inList = false
                }
                val headingText = line.replace(Regex("""^#+\s*"""), "").trim().trimEnd(':')
                sb.append("<p>").append(convertInlineMarkdownToXml(headingText)).append(":</p>")
                continue
            }

            // Bullet points: - item or * item
            if (line.startsWith("- ") || line.startsWith("* ")) {
                if (!inList) {
                    sb.append("<ul>")
                    inList = true
                }
                val itemText = line.substring(2).trim()
                sb.append("<li>").append(convertInlineMarkdownToXml(itemText)).append("</li>")
            } else {
                if (inList) {
                    sb.append("</ul>")
                    inList = false
                }
                sb.append("<p>").append(convertInlineMarkdownToXml(line)).append("</p>")
            }
        }

        if (inList) {
            sb.append("</ul>")
        }

        sb.append("</div></content>")
        return sb.toString()
    }

    private fun convertInlineMarkdownToXml(text: String): String {
        val stripped = text.replace(Regex("""<[^>]*>"""), "")
        var result = escapeXml(stripped)
        // Convert [text](url) to <a href="url">text</a>
        result = result.replace(Regex("""\[([^\]]+)\]\(([^)]+)\)""")) { m ->
            val linkText = m.groupValues[1]
            val url = m.groupValues[2]
            "<a href=\"$url\">$linkText</a>"
        }
        // Bold: **text** or __text__
        result = result.replace(Regex("""\*\*(.+?)\*\*""")) { "<b>${it.groupValues[1]}</b>" }
        result = result.replace(Regex("""__(.+?)__""")) { "<b>${it.groupValues[1]}</b>" }
        // Inline code: `text`
        result = result.replace(Regex("""`([^`]+)`""")) { "<b>${it.groupValues[1]}</b>" }
        // Italic: *text*
        result = result.replace(Regex("""(?<!\*)\*([^*]+)\*(?!\*)""")) { "<i>${it.groupValues[1]}</i>" }
        return result
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
