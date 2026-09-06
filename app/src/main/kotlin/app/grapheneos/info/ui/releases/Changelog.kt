package app.grapheneos.info.ui.releases

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.grapheneos.info.ui.reusablecomposables.ClickableText
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
fun ChannelBadge(channel: String, modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()
    val (label, containerColor, contentColor, borderColor) = when (channel.lowercase()) {
        "stable", "estable" -> Quadruple(
            "ESTABLE",
            if (isDark) Color(0xFF14381C) else Color(0xFFE8F5E9),
            if (isDark) Color(0xFF81C784) else Color(0xFF1B5E20),
            if (isDark) Color(0xFF2E7D32) else Color(0xFF81C784)
        )
        "beta" -> Quadruple(
            "BETA",
            if (isDark) Color(0xFF38260F) else Color(0xFFFFF3E0),
            if (isDark) Color(0xFFFFB74D) else Color(0xFFB25E00),
            if (isDark) Color(0xFFF57C00) else Color(0xFFFFB74D)
        )
        "alpha" -> Quadruple(
            "ALPHA",
            if (isDark) Color(0xFF2E1A3D) else Color(0xFFF3E5F5),
            if (isDark) Color(0xFFCE93D8) else Color(0xFF6A1B9A),
            if (isDark) Color(0xFF8E24AA) else Color(0xFFCE93D8)
        )
        else -> Quadruple(
            channel.uppercase(),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.outline
        )
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = contentColor
        )
    }
}

@Composable
fun Changelog(modifier: Modifier = Modifier, entry: String) {
    val localUriHandler = LocalUriHandler.current

    SelectionContainer {
        ElevatedCard(modifier) {
            Column(Modifier.padding(16.dp)) {
                val factory = DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()

                val document: Document = builder.parse(InputSource(StringReader("<entry>$entry</entry>")))
                document.documentElement.normalize()

                val channel = document.getElementsByTagName("channel").item(0)?.textContent?.trim() ?: ""
                val titleNode = document.getElementsByTagName("title").item(0)
                val titleUrl = titleNode?.attributes?.getNamedItem("url")?.nodeValue
                    ?: titleNode?.attributes?.getNamedItem("href")?.nodeValue
                val titleText = titleNode?.textContent?.trim() ?: ""

                if (titleText.isNotEmpty() || channel.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (titleText.isNotEmpty()) {
                            Text(
                                text = titleText,
                                style = typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clickable {
                                        titleUrl?.let { url ->
                                            val fullUrl = if (url.startsWith('/')) "https://github.com/rhythmcreative$url" else url
                                            try {
                                                localUriHandler.openUri(fullUrl)
                                            } catch (_: Exception) {}
                                        }
                                    }
                            )
                        }
                        if (channel.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            ChannelBadge(channel = channel)
                        }
                    }
                }

                NodeToComposable(
                    node = document.documentElement,
                    modifier = Modifier,
                    style = LocalTextStyle.current,
                    builder = AnnotatedString.Builder(),
                    localUriHandler = localUriHandler,
                    skipTitle = titleText.isNotEmpty()
                )
            }
        }
    }
}

@Composable
private fun NodeToComposable(
    node: Node,
    modifier: Modifier,
    style: TextStyle,
    builder: AnnotatedString.Builder,
    localUriHandler: UriHandler,
    skipTitle: Boolean = false
) {
    val attributes = node.attributes

    // Push annotations and modify modifier and/or style
    for (a in 0 until attributes.length) {
        val attribute = attributes.item(a)

        when (attribute.nodeName) {
            "href" -> {
                val hrefValue = attribute.nodeValue
                val home = "https://github.com/rhythmcreative"
                val url = if (hrefValue.startsWith('/')) {
                    "$home$hrefValue"
                } else if (hrefValue.startsWith('#')) {
                    "$home$hrefValue"
                } else {
                    hrefValue
                }
                builder.apply {
                    pushLink(LinkAnnotation.Url(url))
                    pushStringAnnotation("URL", url)
                    pushStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold))
                }
            }

            "aria-label" -> {
                // Only VerbatimTtsAnnotation is available so we can't use the text contained for TTS
            }

            else -> {
            }
        }
    }

    ParseChildren(node, modifier, style, builder, localUriHandler, skipTitle)

    // Pop annotations, modifier and style don't carry over so no need to do anything for those
    for (a in 0 until attributes.length) {
        val attribute = attributes.item(a)

        when (attribute.nodeName) {
            "href" -> {
                builder.apply {
                    pop()
                    pop()
                    pop()
                }
            }

            "aria-label" -> {
            }

            else -> {
            }
        }
    }
}

@Composable
private fun ParseChildren(
    node: Node,
    modifier: Modifier,
    style: TextStyle,
    builder: AnnotatedString.Builder,
    localUriHandler: UriHandler,
    skipTitle: Boolean = false
) {
    val children = node.childNodes

    for (i in 0 until children.length) {
        val child = children.item(i)

        when (child.nodeType) {
            Node.ELEMENT_NODE -> {
                when (child.nodeName) {
                    "channel" -> {
                        // Handled in card header
                    }
                    "title" -> {
                        if (skipTitle) {
                            // Already rendered in header with ChannelBadge
                            continue
                        }
                        val titleUrl = child.attributes?.getNamedItem("url")?.nodeValue
                            ?: child.attributes?.getNamedItem("href")?.nodeValue

                        val annotatedStringBuilder = AnnotatedString.Builder()

                        NodeToComposable(
                            child,
                            modifier,
                            style,
                            annotatedStringBuilder,
                            localUriHandler
                        )

                        val annotatedString = annotatedStringBuilder.toAnnotatedString()

                        ClickableText(
                            text = annotatedString,
                            modifier = modifier.semantics { heading() },
                            onClick = { offset ->
                                val targetUrl = annotatedString
                                    .getStringAnnotations("URL", offset, offset).firstOrNull()?.item
                                    ?: titleUrl
                                targetUrl?.let { url ->
                                    val fullUrl = if (url.startsWith('/')) "https://github.com/rhythmcreative$url" else url
                                    try {
                                        localUriHandler.openUri(fullUrl)
                                    } catch (_: Exception) {}
                                }
                            },
                            style = typography.titleLarge,
                        )
                    }

                    "content" -> NodeToComposable(
                        child,
                        modifier,
                        style,
                        AnnotatedString.Builder(),
                        localUriHandler
                    )

                    "div" -> NodeToComposable(
                        child,
                        modifier,
                        style,
                        AnnotatedString.Builder(),
                        localUriHandler
                    )

                    "p" -> {
                        val annotatedStringBuilder = AnnotatedString.Builder()

                        NodeToComposable(
                            child,
                            modifier,
                            style,
                            annotatedStringBuilder,
                            localUriHandler,
                        )

                        val annotatedString = annotatedStringBuilder.toAnnotatedString()

                        val text = annotatedString.text.trim()
                        val likelyHeading =
                            text == "Tags:" ||
                            text == "Etiquetas:" ||
                            text.startsWith("Changes since the") ||
                            text.startsWith("Changes since") ||
                            text.startsWith("Changes in") ||
                            text == "Changes:" ||
                            text.startsWith("Cambios desde") ||
                            text.startsWith("Cambios en") ||
                            text == "Cambios:" ||
                            text == "Notes:" ||
                            text == "Notas:"

                        ClickableText(
                            text = annotatedString,
                            onClick = { offset ->
                                annotatedString
                                    .getStringAnnotations("URL", offset, offset).firstOrNull()
                                    ?.let { annotation ->
                                        try {
                                            localUriHandler.openUri(annotation.item)
                                        } catch (_: Exception) {}
                                    }
                            },
                            modifier = if (likelyHeading) {
                                modifier.padding(top = 16.dp, bottom = 12.dp)
                            } else {
                                modifier.padding(top = 24.dp)
                            },
                            style = if (likelyHeading) {
                                typography.titleMedium
                            } else {
                                style
                            },
                        )
                    }

                    "a" -> {
                        NodeToComposable(
                            child,
                            modifier,
                            style,
                            builder,
                            localUriHandler
                        )
                    }

                    "ul" -> {
                        NodeToComposable(
                            child,
                            modifier,
                            style,
                            AnnotatedString.Builder(),
                            localUriHandler
                        )
                    }

                    "li" -> {
                        val annotatedStringBuilder = AnnotatedString.Builder()

                        NodeToComposable(
                            child,
                            modifier,
                            style,
                            annotatedStringBuilder,
                            localUriHandler
                        )

                        val annotatedString = annotatedStringBuilder.toAnnotatedString()

                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                when (child.parentNode.nodeName) {
                                    "ul" -> "  •  "
                                    "ol" -> "  $i  "
                                    else -> ""
                                }
                            )

                            ClickableText(
                                text = annotatedString,
                                onClick = { offset ->
                                    annotatedString
                                        .getStringAnnotations("URL", offset, offset).firstOrNull()
                                        ?.let { annotation ->
                                            try {
                                                localUriHandler.openUri(annotation.item)
                                            } catch (_: Exception) {}
                                        }
                                }
                            )
                        }
                    }

                    "b", "strong" -> {
                        builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            NodeToComposable(
                                child,
                                modifier,
                                style.copy(fontWeight = FontWeight.Bold),
                                builder,
                                localUriHandler,
                            )
                        }
                    }

                    "i", "em" -> {
                        builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            NodeToComposable(
                                child,
                                modifier,
                                style.copy(fontStyle = FontStyle.Italic),
                                builder,
                                localUriHandler
                            )
                        }
                    }

                    "span" -> {
                        NodeToComposable(
                            child,
                            modifier,
                            style,
                            builder,
                            localUriHandler
                        )
                    }

                    "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        val annotatedStringBuilder = AnnotatedString.Builder()

                        NodeToComposable(
                            child,
                            modifier,
                            style,
                            annotatedStringBuilder,
                            localUriHandler,
                        )

                        val annotatedString = annotatedStringBuilder.toAnnotatedString()
                        val text = annotatedString.text.trim()
                        val likelyHeading =
                            text == "Tags:" ||
                            text == "Etiquetas:" ||
                            text.startsWith("Changes since the") ||
                            text.startsWith("Changes since") ||
                            text.startsWith("Changes in") ||
                            text == "Changes:" ||
                            text.startsWith("Cambios desde") ||
                            text.startsWith("Cambios en") ||
                            text == "Cambios:" ||
                            text == "Notes:" ||
                            text == "Notas:"

                        ClickableText(
                            text = annotatedString,
                            onClick = { offset ->
                                annotatedString
                                    .getStringAnnotations("URL", offset, offset).firstOrNull()
                                    ?.let { annotation ->
                                        try {
                                            localUriHandler.openUri(annotation.item)
                                        } catch (_: Exception) {}
                                    }
                            },
                            modifier = if (likelyHeading) {
                                modifier.padding(top = 16.dp, bottom = 12.dp)
                            } else {
                                modifier.padding(vertical = 16.dp)
                            },
                            style = if (likelyHeading) {
                                typography.titleMedium
                            } else {
                                when (child.nodeName) {
                                    "h1" -> style.copy(
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                    )

                                    "h2" -> style.copy(
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                    )

                                    "h3" -> style.copy(
                                        fontSize = 18.72.sp,
                                        fontWeight = FontWeight.Bold,
                                    )

                                    "h4" -> style.copy(
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                    )

                                    "h5" -> style.copy(
                                        fontSize = 13.28.sp,
                                        fontWeight = FontWeight.Bold,
                                    )

                                    "h6" -> style.copy(
                                        fontSize = 10.72.sp,
                                        fontWeight = FontWeight.Bold,
                                    )

                                    else -> style
                                }
                            },
                        )
                    }

                    else -> {
                        NodeToComposable(
                            child,
                            modifier,
                            style,
                            builder,
                            localUriHandler
                        )
                    }
                }
            }

            Node.TEXT_NODE -> {
                val textContent = child.textContent
                if (!textContent.isNullOrEmpty()) {
                    builder.apply {
                        append(textContent)
                    }
                }
            }
        }
    }
}
