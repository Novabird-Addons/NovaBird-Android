package org.novabird.mail.compose

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.fsck.k9.message.html.HtmlConverter
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Character formats of the editor, in the order their tags nest in HTML. */
enum class CharFormat(internal val open: String, internal val close: String) {
    BOLD("<b>", "</b>"),
    ITALIC("<i>", "</i>"),
    UNDERLINE("<u>", "</u>"),
    STRIKE("<s>", "</s>"),
    ;

    fun matches(span: Any): Boolean = when (this) {
        BOLD -> span is StyleSpan && (span.style == Typeface.BOLD || span.style == Typeface.BOLD_ITALIC)
        ITALIC -> span is StyleSpan && (span.style == Typeface.ITALIC || span.style == Typeface.BOLD_ITALIC)
        UNDERLINE -> span is UnderlineSpan
        STRIKE -> span is StrikethroughSpan
    }

    fun newSpan(): Any = when (this) {
        BOLD -> StyleSpan(Typeface.BOLD)
        ITALIC -> StyleSpan(Typeface.ITALIC)
        UNDERLINE -> UnderlineSpan()
        STRIKE -> StrikethroughSpan()
    }
}

/** The formats of one piece of text. */
data class Formats(
    val chars: Set<CharFormat> = emptySet(),
    val font: NbFont? = null,
    val size: NbSize? = null,
)

/**
 * Converts the editor's text to the HTML of the message and back. The HTML is a fragment like the one Thunderbird
 * makes from plain text (`<div dir="auto">…<br>…</div>`, links made clickable), with b/i/u/s, font and size spans,
 * h2/h3 headings, blockquote and nested ul/ol lists.
 */
object RichTextHtml {

    fun hasFormatting(text: Spanned): Boolean =
        text.getSpans(0, text.length, Any::class.java).any { span ->
            !isComposing(text, span) && (
                span is NbParagraphSpan ||
                    (text.getSpanEnd(span) > text.getSpanStart(span) && formatOf(span) != null)
                )
        }

    fun toHtml(text: Spanned): String {
        val html = StringBuilder(DIV_START)
        val paragraphs = paragraphsOf(text)
        val lists = ArrayDeque<NbParagraphSpan>()
        var inQuote = false
        paragraphs.forEachIndexed { index, (start, end) ->
            val span = paragraphSpanAt(text, start)
            val block = span?.block
            if (block != NbBlock.QUOTE && inQuote) {
                html.append("</blockquote>")
                inQuote = false
            }
            if (span == null || !span.isList) closeLists(html, lists, 0)
            when {
                span != null && span.isList -> {
                    openListItem(html, lists, span)
                    appendInline(html, text, start, end)
                }
                block == NbBlock.QUOTE -> {
                    html.append(if (inQuote) "<br>" else "<blockquote>")
                    inQuote = true
                    appendInline(html, text, start, end)
                }
                block == NbBlock.HEADING || block == NbBlock.SUBHEADING -> {
                    val tag = if (block == NbBlock.HEADING) "h2" else "h3"
                    html.append("<$tag>")
                    appendInline(html, text, start, end)
                    html.append("</$tag>")
                }
                else -> {
                    appendInline(html, text, start, end)
                    val next = paragraphs.getOrNull(index + 1)
                    val nextIsPlain = next != null && paragraphSpanAt(text, next.first) == null
                    // a line break between plain lines; an empty line before a block keeps its space
                    if (nextIsPlain || (next != null && start == end)) html.append("<br>")
                }
            }
        }
        closeLists(html, lists, 0)
        if (inQuote) html.append("</blockquote>")
        return html.append(DIV_END).toString()
    }

    fun toPlainText(text: Spanned): String = paragraphsOf(text).joinToString("\n") { (start, end) ->
        val span = paragraphSpanAt(text, start)
        val line = text.substring(start, end)
        when (span?.block) {
            NbBlock.BULLET -> "  ".repeat(span.level - 1) + "• " + line
            NbBlock.NUMBER -> "  ".repeat(span.level - 1) + "${span.numberOf(text)}. " + line
            NbBlock.QUOTE -> "> $line"
            else -> line
        }
    }

    fun fromHtml(html: String): Spannable {
        val builder = SpannableStringBuilder()
        HtmlReader(builder).read(Jsoup.parseBodyFragment(html).body())
        while (builder.isNotEmpty() && builder.last() == '\n') builder.delete(builder.length - 1, builder.length)
        return builder
    }

    /** Paragraphs as (start, end) without their line break; an empty text has one empty paragraph. */
    fun paragraphsOf(text: CharSequence): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (true) {
            val newline = text.indexOf('\n', start)
            if (newline < 0) {
                result.add(start to text.length)
                return result
            }
            result.add(start to newline)
            start = newline + 1
        }
    }

    fun paragraphSpanAt(text: Spanned, paragraphStart: Int): NbParagraphSpan? =
        text.getSpans(paragraphStart, paragraphStart, NbParagraphSpan::class.java)
            .firstOrNull { text.getSpanStart(it) == paragraphStart }

    fun isComposing(text: Spanned, span: Any) = text.getSpanFlags(span) and Spanned.SPAN_COMPOSING != 0

    fun formatOf(span: Any): Any? = when (span) {
        is NbFontSpan -> span.font
        is NbSizeSpan -> span.size
        else -> CharFormat.entries.firstOrNull { it.matches(span) }
    }

    /** The formats that cover all of [start, end). */
    fun formatsOf(text: Spanned, start: Int, end: Int): Formats {
        val spans = text.getSpans(start, end, CharacterStyle::class.java).filter {
            !isComposing(text, it) && text.getSpanStart(it) <= start && text.getSpanEnd(it) >= end &&
                text.getSpanEnd(it) > text.getSpanStart(it)
        }
        return Formats(
            chars = CharFormat.entries.filter { format -> spans.any { format.matches(it) } }.toSet(),
            font = spans.filterIsInstance<NbFontSpan>().lastOrNull()?.font,
            size = spans.filterIsInstance<NbSizeSpan>().lastOrNull()?.size,
        )
    }

    private fun openListItem(html: StringBuilder, lists: ArrayDeque<NbParagraphSpan>, span: NbParagraphSpan) {
        val level = span.level.coerceIn(1, NbParagraphSpan.MAX_LEVEL)
        closeLists(html, lists, level)
        val current = lists.lastOrNull()
        if (current != null && lists.size == level && current.block != span.block) {
            closeLists(html, lists, level - 1)
        }
        if (lists.size == level) {
            html.append("</li><li>")
            return
        }
        while (lists.size < level) {
            html.append(if (span.block == NbBlock.NUMBER) "<ol>" else "<ul>")
            lists.addLast(span)
            html.append(if (lists.size < level) "<li style=\"list-style: none\">" else "<li>")
        }
    }

    /** Closes open lists until [keep] levels are left. */
    private fun closeLists(html: StringBuilder, lists: ArrayDeque<NbParagraphSpan>, keep: Int) {
        while (lists.size > keep) {
            val list = lists.removeLast()
            html.append(if (list.block == NbBlock.NUMBER) "</li></ol>" else "</li></ul>")
        }
    }

    private fun appendInline(html: StringBuilder, text: Spanned, start: Int, end: Int) {
        // runs of equal formats, merged where only other spans (spelling, links) change
        val runs = mutableListOf<Pair<Formats, StringBuilder>>()
        var position = start
        while (position < end) {
            val next = text.nextSpanTransition(position, end, CharacterStyle::class.java)
            val formats = formatsOf(text, position, next)
            val last = runs.lastOrNull()
            if (last != null && last.first == formats) {
                last.second.append(text, position, next)
            } else {
                runs.add(formats to StringBuilder().append(text, position, next))
            }
            position = next
        }
        runs.forEachIndexed { index, (formats, run) ->
            val opening = StringBuilder()
            val closing = StringBuilder()
            formats.font?.let {
                opening.append("<span style=\"font-family: ${it.css}\">")
                closing.insert(0, "</span>")
            }
            formats.size?.let {
                opening.append("<span style=\"font-size: ${it.css}\">")
                closing.insert(0, "</span>")
            }
            for (format in CharFormat.entries) {
                if (format in formats.chars) {
                    opening.append(format.open)
                    closing.insert(0, format.close)
                }
            }
            html.append(opening).append(plainToHtml(run.toString(), lineStart = index == 0)).append(closing)
        }
    }

    /** HTML of a piece of text without line breaks, as Thunderbird writes plain text (links, spaces). */
    private fun plainToHtml(text: String, lineStart: Boolean): String {
        // Thunderbird keeps a space at the start of a text visible; inside a line one space is enough
        if (!lineStart && text.startsWith(' ')) return " " + plainToHtml(text.substring(1), lineStart = true)
        return HtmlConverter.textToHtmlFragment(text).removePrefix(DIV_START).removeSuffix(DIV_END)
    }

    private const val DIV_START = "<div dir=\"auto\">"
    private const val DIV_END = "</div>"
}

/** Reads HTML into the editor's spans; tolerant of HTML from other mail programs. */
private class HtmlReader(private val text: SpannableStringBuilder) {
    private var listLevel = 0
    private val listKinds = ArrayDeque<NbBlock>()

    fun read(node: Node) {
        when (node) {
            is TextNode -> appendText(node.wholeText)
            is Element -> readElement(node)
        }
    }

    private fun readChildren(element: Element) = element.childNodes().forEach(::read)

    private fun readElement(element: Element) {
        when (element.normalName()) {
            "br" -> text.append('\n')
            "b", "strong" -> inline(element, CharFormat.BOLD.newSpan())
            "i", "em" -> inline(element, CharFormat.ITALIC.newSpan())
            "u", "ins" -> inline(element, CharFormat.UNDERLINE.newSpan())
            "s", "strike", "del" -> inline(element, CharFormat.STRIKE.newSpan())
            "span", "font" -> styled(element)
            "h1", "h2" -> block(element, NbBlock.HEADING)
            "h3", "h4", "h5", "h6" -> block(element, NbBlock.SUBHEADING)
            "blockquote" -> block(element, NbBlock.QUOTE)
            "ul", "ol" -> list(element, if (element.normalName() == "ol") NbBlock.NUMBER else NbBlock.BULLET)
            "li" -> block(element, listKinds.lastOrNull() ?: NbBlock.BULLET, listLevel.coerceIn(1, MAX_LEVEL))
            "div", "p" -> {
                startParagraph()
                readChildren(element)
                startParagraph()
            }
            "head", "style", "script", "title" -> Unit
            else -> readChildren(element)
        }
    }

    private fun appendText(raw: String) {
        var value = WHITESPACE.replace(raw, " ").replace(' ', ' ')
        // spaces at the start of a line come from the HTML source, not from the text
        if (text.isEmpty() || text.last() == '\n') value = value.trimStart(' ')
        text.append(value)
    }

    private fun inline(element: Element, span: Any) {
        val start = text.length
        readChildren(element)
        if (text.length > start) text.setSpan(span, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun styled(element: Element) {
        val style = element.attr("style").lowercase()
        val family = (FONT_FAMILY.find(style)?.groupValues?.get(1) ?: element.attr("face")).lowercase()
        val font = when {
            "courier" in family || "monospace" in family -> NbFont.MONO
            "georgia" in family || "times" in family || SERIF.containsMatchIn(family) -> NbFont.SERIF
            else -> null
        }
        val size = when (FONT_SIZE.find(style)?.groupValues?.get(1)?.trim()) {
            "small", "x-small", "xx-small", "smaller" -> NbSize.SMALL
            "large", "larger" -> NbSize.LARGE
            "x-large", "xx-large", "xxx-large" -> NbSize.HUGE
            else -> null
        }
        val start = text.length
        readChildren(element)
        if (text.length > start) {
            font?.let { text.setSpan(NbFontSpan(it), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            size?.let { text.setSpan(NbSizeSpan(it), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
    }

    private fun list(element: Element, kind: NbBlock) {
        startParagraph()
        listLevel++
        listKinds.addLast(kind)
        readChildren(element)
        listKinds.removeLast()
        listLevel--
    }

    /** The paragraphs read inside [element] get [block], unless a nested element gave them one already. */
    private fun block(element: Element, block: NbBlock, level: Int = 1) {
        startParagraph()
        val start = text.length
        readChildren(element)
        startParagraph()
        if (text.length == start) text.append('\n')
        var paragraphStart = start
        while (paragraphStart < text.length) {
            val paragraphEnd = text.indexOf('\n', paragraphStart).let { if (it < 0) text.length else it + 1 }
            if (RichTextHtml.paragraphSpanAt(text, paragraphStart) == null) {
                text.setSpan(NbParagraphSpan(block, level), paragraphStart, paragraphEnd, Spanned.SPAN_PARAGRAPH)
            }
            paragraphStart = paragraphEnd
        }
    }

    /** Ends the current line unless it is empty. */
    private fun startParagraph() {
        if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
    }

    companion object {
        private val WHITESPACE = Regex("[ \\t\\r\\n]+")
        private val FONT_FAMILY = Regex("font-family\\s*:([^;]*)")
        private val FONT_SIZE = Regex("font-size\\s*:([^;]*)")
        private val SERIF = Regex("(^|[ ,'\"])serif")
        private const val MAX_LEVEL = NbParagraphSpan.MAX_LEVEL
    }
}
