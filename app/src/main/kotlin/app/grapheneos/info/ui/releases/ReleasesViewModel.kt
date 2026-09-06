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
import org.grapheneos.tls.ModernTLSSocketFactory
import org.json.JSONArray
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

const val TAG = "ReleasesViewModel"

class ReleasesViewModel(
    private val application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val tlsSocketFactory = ModernTLSSocketFactory()
    private val _uiState = MutableStateFlow(ReleasesUiState(savedStateHandle))
    val uiState: StateFlow<ReleasesUiState> = _uiState.asStateFlow()

    init {
        updateChangelog(
            useCaches = true,
            showSnackbarError = {},
            scrollChangelogLazyListTo = {},
            countAsInitialScroll = false,
            onFinishedUpdating = {},
        )
    }

    fun updateChangelog(
        useCaches: Boolean,
        showSnackbarError: suspend (message: String) -> Unit,
        scrollChangelogLazyListTo: (scrollTo: Int) -> Unit,
        countAsInitialScroll: Boolean = true,
        onFinishedUpdating: () -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val device = Build.DEVICE?.lowercase() ?: "akita"
                var parsedEntries: Map<String, String>? = null

                // 1. Try fetching READMEs from GitHub repos
                val readmeCandidates = listOf(
                    "https://raw.githubusercontent.com/rhythmcreative/lineageos-$device-ota/main/README.md",
                    "https://raw.githubusercontent.com/rhythmcreative/lineage-build-scripts/main/README.md",
                    "https://raw.githubusercontent.com/rhythmcreative/Info/main/README.md"
                )

                for (readmeUrl in readmeCandidates) {
                    try {
                        val text = fetchUrl(readmeUrl, useCaches)
                        if (text != null) {
                            val entries = extractEntriesFromReadme(text)
                            if (!entries.isNullOrEmpty()) {
                                parsedEntries = entries
                                break
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Could not fetch or parse $readmeUrl: ${e.message}")
                    }
                }

                // 2. Fallback to GitHub Releases API if no README changes section found
                if (parsedEntries.isNullOrEmpty()) {
                    val releasesApiCandidates = listOf(
                        "https://api.github.com/repos/rhythmcreative/lineageos-$device-ota/releases",
                        "https://api.github.com/repos/rhythmcreative/lineageos-akita-ota/releases"
                    )

                    for (apiUrl in releasesApiCandidates) {
                        try {
                            val jsonText = fetchUrl(apiUrl, useCaches)
                            if (jsonText != null) {
                                val entries = extractEntriesFromReleasesJson(jsonText)
                                if (entries.isNotEmpty()) {
                                    parsedEntries = entries
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Could not fetch releases from $apiUrl: ${e.message}")
                        }
                    }
                }

                if (!parsedEntries.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        _uiState.value.entries.filterKeys {
                            !parsedEntries.keys.contains(it)
                        }.forEach {
                            _uiState.value.entries.remove(it.key)
                        }
                        _uiState.value.entries.putAll(parsedEntries)
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
                onFinishedUpdating()
            }
        }
    }

    private fun fetchUrl(urlString: String, useCaches: Boolean): String? {
        val url = URL(urlString)
        val connection = url.openConnection() as? HttpsURLConnection ?: return null
        return try {
            connection.apply {
                sslSocketFactory = tlsSocketFactory
                connectTimeout = 10_000
                readTimeout = 30_000
                this.useCaches = useCaches
                setRequestProperty("User-Agent", "LineageOS-Info-App")
                setRequestProperty("Accept", "text/plain, application/json, */*")
            }
            connection.connect()
            if (connection.responseCode in 200..299) {
                BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Extracts changes from README markdown starting from "# Changes" or "## Changes" downwards.
     */
    private fun extractEntriesFromReadme(markdown: String): Map<String, String>? {
        val changesRegex = Regex("""(?im)^#{1,4}\s*changes\b.*$""")
        val match = changesRegex.find(markdown) ?: return null

        val changesText = markdown.substring(match.range.last + 1).trim()
        if (changesText.isBlank()) return null

        val subHeadingRegex = Regex("""(?m)^#{2,4}\s+(.+)$""")
        val subMatches = subHeadingRegex.findAll(changesText).toList()

        val entries = mutableMapOf<String, String>()

        if (subMatches.isNotEmpty()) {
            for (i in subMatches.indices) {
                val currentMatch = subMatches[i]
                val title = currentMatch.groupValues[1].trim()
                val startIndex = currentMatch.range.last + 1
                val endIndex = if (i + 1 < subMatches.size) subMatches[i + 1].range.first else changesText.length
                val sectionBody = changesText.substring(startIndex, endIndex).trim()

                val sortKey = String.format("%06d", subMatches.size - i)
                val xmlEntry = formatMarkdownSectionToXml(sortKey, title, sectionBody)
                entries[sortKey] = xmlEntry
            }
        } else {
            val sortKey = "000001"
            val xmlEntry = formatMarkdownSectionToXml(sortKey, "Changes", changesText)
            entries[sortKey] = xmlEntry
        }

        return entries
    }

    /**
     * Fallback to GitHub Releases JSON
     */
    private fun extractEntriesFromReleasesJson(jsonString: String): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val releaseObj = jsonArray.getJSONObject(i)
                val tagName = releaseObj.optString("tag_name", "")
                val name = releaseObj.optString("name", tagName).ifBlank { tagName }
                var body = releaseObj.optString("body", "")

                val changesRegex = Regex("""(?im)^#{1,4}\s*changes\b.*$""")
                val match = changesRegex.find(body)
                if (match != null) {
                    body = body.substring(match.range.last + 1).trim()
                }

                val sortKey = String.format("%06d", jsonArray.length() - i)
                val xmlEntry = formatMarkdownSectionToXml(sortKey, name, body)
                entries[sortKey] = xmlEntry
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing releases JSON", e)
        }
        return entries
    }

    /**
     * Converts a markdown block into an XML entry string understood by Changelog.kt.
     */
    private fun formatMarkdownSectionToXml(id: String, title: String, markdown: String): String {
        val escapedTitle = escapeXml(title)
        val sb = StringBuilder()
        sb.append("<title>").append(escapedTitle).append("</title>")
        sb.append("<content><div>")

        val lines = markdown.lines()
        var inList = false

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("---") || line.startsWith("***")) {
                if (inList) {
                    sb.append("</ul>")
                    inList = false
                }
                continue
            }

            // Subheadings within a section (e.g. ### Notes or #### Fixed)
            if (line.startsWith("#### ") || line.startsWith("### ")) {
                if (inList) {
                    sb.append("</ul>")
                    inList = false
                }
                val headingText = line.replace(Regex("""^#+\s*"""), "")
                sb.append("<h3>").append(convertInlineMarkdownToXml(headingText)).append("</h3>")
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
        var result = escapeXml(text)
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
