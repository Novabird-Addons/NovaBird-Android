package org.novabird.mail.compose

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatEditText
import com.fsck.k9.activity.compose.RichTextEditor
import com.fsck.k9.ui.R as LegacyR

/**
 * The message text of the compose screen (layout overlay message_compose_content.xml) with formatting: bold, italic,
 * underline, strikethrough, two fonts, sizes, headings, quotes and lists. Typing continues the format at the cursor;
 * a format switched on or off without a selection applies to what is typed next. Installs the [NbFormatBar] below
 * the compose content.
 */
class NbRichEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr), RichTextEditor {

    /** Called when the formats at the cursor, the focus or the availability change. */
    var onStateChanged: (() -> Unit)? = null

    var isRichTextAvailable = true
        private set

    /** The formats new text gets. */
    var typingFormats = Formats()
        private set

    // set when the user switched a format without a selection; kept until the cursor moves elsewhere
    private var typingFormatsChosen = false
    private var ignoreChanges = false
    private var edit: EditSnapshot? = null
    private var formatBar: NbFormatBar? = null

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                edit = if (ignoreChanges || s !is Spanned) null else EditSnapshot.of(s, start, count)
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                edit?.inserted = count
            }

            override fun afterTextChanged(editable: Editable) {
                val snapshot = edit ?: return
                edit = null
                if (ignoreChanges) return
                ignoreChanges = true
                try {
                    afterEdit(editable, snapshot)
                } finally {
                    ignoreChanges = false
                }
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (formatBar == null) post(::installFormatBar)
    }

    private fun installFormatBar() {
        val container = rootView.findViewById<ViewGroup>(LegacyR.id.content_container) as? LinearLayout ?: return
        if (formatBar != null) return
        val bar = NbFormatBar(context, this)
        container.addView(bar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        formatBar = bar
        onStateChanged?.invoke()
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // during setup the field isn't ready yet
        @Suppress("SENSELESS_COMPARISON")
        if (text == null) return
        typingFormatsChosen = false
        typingFormats = formatsAtCursor()
        onStateChanged?.invoke()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        onStateChanged?.invoke()
    }

    // RichTextEditor

    override fun hasFormatting(): Boolean = text?.let(RichTextHtml::hasFormatting) == true

    override fun getHtmlFragment(): String = RichTextHtml.toHtml(requireText())

    override fun getPlainText(): String = RichTextHtml.toPlainText(requireText())

    override fun setHtmlFragment(html: String) {
        ignoreChanges = true
        try {
            setText(RichTextHtml.fromHtml(html))
        } finally {
            ignoreChanges = false
        }
    }

    override fun setRichTextAvailable(available: Boolean) {
        if (isRichTextAvailable == available) return
        isRichTextAvailable = available
        onStateChanged?.invoke()
    }

    // Formats at the cursor, for the format bar

    /** The paragraph format at the cursor (null for normal text). */
    fun paragraphAtCursor(): NbParagraphSpan? {
        val text = text ?: return null
        val start = paragraphStart(text, selectionStart.coerceAtLeast(0))
        return RichTextHtml.paragraphSpanAt(text, start)
    }

    private fun formatsAtCursor(): Formats {
        val text = text ?: return Formats()
        val start = selectionStart
        val end = selectionEnd
        if (start < 0) return Formats()
        return when {
            start != end -> RichTextHtml.formatsOf(text, minOf(start, end), maxOf(start, end))
            start > 0 && text[start - 1] != '\n' -> RichTextHtml.formatsOf(text, start - 1, start)
            start < text.length -> RichTextHtml.formatsOf(text, start, start + 1)
            start > 0 -> RichTextHtml.formatsOf(text, start - 1, start)
            else -> Formats()
        }
    }

    // Changing formats

    fun toggle(format: CharFormat) {
        val on = format !in typingFormats.chars
        changeChars(typingFormats.copy(chars = if (on) typingFormats.chars + format else typingFormats.chars - format)) {
            text, start, end ->
            removeChar(text, start, end, format)
            if (on) addSpan(text, format.newSpan(), start, end)
        }
    }

    fun setFont(font: NbFont?) = changeChars(typingFormats.copy(font = font)) { text, start, end ->
        removeSpans(text, start, end, NbFontSpan::class.java)
        if (font != null) addSpan(text, NbFontSpan(font), start, end)
    }

    fun setSize(size: NbSize?) = changeChars(typingFormats.copy(size = size)) { text, start, end ->
        removeSpans(text, start, end, NbSizeSpan::class.java)
        if (size != null) addSpan(text, NbSizeSpan(size), start, end)
    }

    /** Normal (null), heading, subheading or quote for the paragraphs of the selection. */
    fun setBlock(block: NbBlock?) = changeParagraphs { block?.let { NbParagraphSpan(it) } }

    fun toggleList(block: NbBlock) {
        val text = text ?: return
        val allInList = selectedParagraphs(text).all { (start, _) ->
            RichTextHtml.paragraphSpanAt(text, start)?.block == block
        }
        changeParagraphs { current ->
            if (allInList) null else NbParagraphSpan(block, if (current?.isList == true) current.level else 1)
        }
    }

    /** Moves list items one level in (+1) or out (-1); out of the first level ends the list. */
    fun indent(direction: Int) = changeParagraphs { current ->
        when {
            current == null || !current.isList -> current
            current.level + direction < 1 -> null
            else -> NbParagraphSpan(current.block, (current.level + direction).coerceAtMost(NbParagraphSpan.MAX_LEVEL))
        }
    }

    fun clearFormatting() {
        changeChars(Formats()) { text, start, end ->
            CharFormat.entries.forEach { removeChar(text, start, end, it) }
            removeSpans(text, start, end, NbFontSpan::class.java)
            removeSpans(text, start, end, NbSizeSpan::class.java)
        }
        setBlock(null)
    }

    private inline fun changeChars(newTyping: Formats, change: (Editable, Int, Int) -> Unit) {
        val text = text ?: return
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        if (start < 0) return
        if (start == end) {
            typingFormats = newTyping
            typingFormatsChosen = true
        } else {
            ignoreChanges = true
            try {
                change(text, start, end)
            } finally {
                ignoreChanges = false
            }
            typingFormats = formatsAtCursor()
        }
        onStateChanged?.invoke()
    }

    private inline fun changeParagraphs(newSpan: (NbParagraphSpan?) -> NbParagraphSpan?) {
        val text = text ?: return
        if (selectionStart < 0) return
        ignoreChanges = true
        try {
            for ((start, end) in selectedParagraphs(text)) {
                val current = RichTextHtml.paragraphSpanAt(text, start)
                val replacement = newSpan(current)
                if (replacement === current) continue
                current?.let(text::removeSpan)
                replacement?.let { text.setSpan(it, start, end, Spanned.SPAN_PARAGRAPH) }
            }
        } finally {
            ignoreChanges = false
        }
        onStateChanged?.invoke()
    }

    /** Paragraphs touched by the selection, each with its line break. */
    private fun selectedParagraphs(text: Spanned): List<Pair<Int, Int>> {
        val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val end = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
        return paragraphsBetween(text, start, end)
    }

    // Keeping formats while typing

    private fun afterEdit(text: Editable, edit: EditSnapshot) {
        val start = edit.start
        val end = (start + edit.inserted).coerceAtMost(text.length)
        if (end > start) applyTypingFormats(text, start, end)
        val lineBreak = edit.inserted == 1 && edit.removed == 0 && text.getOrNull(start) == '\n'
        var kind = edit.first
        var editEnd = end
        if (lineBreak && edit.inEmptyParagraph && kind?.isList == true) {
            // Enter on an empty list item ends the list instead of adding a line
            text.delete(start, start + 1)
            kind = null
            editEnd = start
        }
        restoreParagraphs(text, edit, kind, editEnd)
        if (lineBreak && kind != null && (kind.block == NbBlock.HEADING || kind.block == NbBlock.SUBHEADING)) {
            // a new empty line after a heading is normal text
            val next = start + 1
            if (next == text.length || text[next] == '\n') {
                RichTextHtml.paragraphSpanAt(text, next)?.let(text::removeSpan)
            }
        }
        typingFormats = formatsAtCursor()
        onStateChanged?.invoke()
    }

    private fun applyTypingFormats(text: Editable, start: Int, end: Int) {
        val typing = typingFormats
        for (format in CharFormat.entries) {
            if (format in typing.chars) {
                addSpan(text, format.newSpan(), start, end)
            } else if (typingFormatsChosen) {
                removeChar(text, start, end, format)
            }
        }
        if (typing.font != null || typingFormatsChosen) {
            removeSpans(text, start, end, NbFontSpan::class.java)
            typing.font?.let { addSpan(text, NbFontSpan(it), start, end) }
        }
        if (typing.size != null || typingFormatsChosen) {
            removeSpans(text, start, end, NbSizeSpan::class.java)
            typing.size?.let { addSpan(text, NbSizeSpan(it), start, end) }
        }
    }

    /**
     * Sets the paragraph formats around an edit again, one span per paragraph: the paragraphs of the edit get the
     * format of the paragraph it started in (a line break in a list item makes a new item, joined lines keep the
     * first format), the paragraphs before and after keep theirs.
     */
    private fun restoreParagraphs(text: Editable, edit: EditSnapshot, kind: Kind?, editEnd: Int) {
        val firstStart = paragraphStart(text, edit.start)
        val lastStart = paragraphStart(text, editEnd)
        val lastEnd = paragraphEnd(text, lastStart)
        val from = if (edit.hasPrevious && firstStart > 0) paragraphStart(text, firstStart - 1) else firstStart
        val nextStart = if (edit.hasNext && lastEnd > lastStart) lastEnd else -1
        val to = if (nextStart >= 0) paragraphEnd(text, nextStart) else lastEnd
        text.getSpans(from, to, NbParagraphSpan::class.java)
            .filter {
                val spanStart = text.getSpanStart(it)
                val spanEnd = text.getSpanEnd(it)
                // an empty paragraph's span moves along with typed text and is left over at its end
                spanStart in from until to || (spanStart < from && spanEnd > from) ||
                    (spanStart == spanEnd && spanStart in from..to)
            }
            .forEach(text::removeSpan)
        if (from < firstStart) setParagraph(text, from, firstStart, edit.previous)
        var paragraphStart = firstStart
        while (true) {
            val paragraphEnd = paragraphEnd(text, paragraphStart)
            setParagraph(text, paragraphStart, paragraphEnd, kind)
            if (paragraphStart >= lastStart || paragraphEnd == paragraphStart) break
            paragraphStart = paragraphEnd
        }
        if (nextStart >= 0) setParagraph(text, nextStart, to, edit.next)
    }

    private fun setParagraph(text: Editable, start: Int, end: Int, kind: Kind?) {
        if (kind == null) return
        if (start > 0 && text[start - 1] != '\n') return
        if (end < text.length && text[end - 1] != '\n') return
        text.setSpan(NbParagraphSpan(kind.block, kind.level), start, end, Spanned.SPAN_PARAGRAPH)
    }

    /** Paragraph format as a value, so it survives the span being changed by an edit. */
    private data class Kind(val block: NbBlock, val level: Int) {
        val isList get() = block == NbBlock.BULLET || block == NbBlock.NUMBER
    }

    /** The paragraph formats around an edit, taken before the text changes. */
    private class EditSnapshot(
        val start: Int,
        val removed: Int,
        val first: Kind?,
        val previous: Kind?,
        val next: Kind?,
        val hasPrevious: Boolean,
        val hasNext: Boolean,
        val inEmptyParagraph: Boolean,
    ) {
        var inserted = 0

        companion object {
            fun of(text: Spanned, start: Int, removed: Int): EditSnapshot {
                val firstStart = paragraphStart(text, start)
                val lastEnd = paragraphEnd(text, start + removed)
                val hasPrevious = firstStart > 0
                // a paragraph after the edited ones; after a final line break that is an empty one
                val hasNext = lastEnd > start + removed && (lastEnd < text.length || text[lastEnd - 1] == '\n')
                val isEmpty = start == text.length || text[start] == '\n'
                return EditSnapshot(
                    start = start,
                    removed = removed,
                    first = kindAt(text, firstStart),
                    previous = if (hasPrevious) kindAt(text, paragraphStart(text, firstStart - 1)) else null,
                    next = if (hasNext) kindAt(text, lastEnd) else null,
                    hasPrevious = hasPrevious,
                    hasNext = hasNext,
                    inEmptyParagraph = removed == 0 && start == firstStart && isEmpty,
                )
            }

            private fun kindAt(text: Spanned, paragraphStart: Int) =
                RichTextHtml.paragraphSpanAt(text, paragraphStart)?.let { Kind(it.block, it.level) }
        }
    }

    private fun requireText(): Editable = checkNotNull(text)

    private companion object {
        fun paragraphStart(text: CharSequence, position: Int): Int {
            var index = position.coerceAtMost(text.length)
            while (index > 0 && text[index - 1] != '\n') index--
            return index
        }

        /** End of the paragraph at [position], after its line break. */
        fun paragraphEnd(text: CharSequence, position: Int): Int {
            val newline = text.indexOf('\n', position.coerceAtMost(text.length))
            return if (newline < 0) text.length else newline + 1
        }

        /** Paragraphs from the one at [start] to the one at [end], each as (start, end after its line break). */
        fun paragraphsBetween(text: CharSequence, start: Int, end: Int): List<Pair<Int, Int>> {
            val result = mutableListOf<Pair<Int, Int>>()
            var paragraphStart = paragraphStart(text, start)
            while (true) {
                val paragraphEnd = paragraphEnd(text, paragraphStart)
                result.add(paragraphStart to paragraphEnd)
                // a selection ending right after a line break doesn't take the next line
                if (paragraphEnd >= text.length || paragraphEnd > end || (paragraphEnd == end && end > start)) break
                paragraphStart = paragraphEnd
            }
            return result
        }

        fun addSpan(text: Editable, span: Any, start: Int, end: Int) {
            if (start >= end) return
            // merge with touching spans of the same format
            var from = start
            var to = end
            val format = RichTextHtml.formatOf(span)
            text.getSpans((start - 1).coerceAtLeast(0), (end + 1).coerceAtMost(text.length), Any::class.java)
                .filter { RichTextHtml.formatOf(it) == format && !RichTextHtml.isComposing(text, it) }
                .filter { text.getSpanStart(it) <= end && text.getSpanEnd(it) >= start }
                .forEach {
                    from = minOf(from, text.getSpanStart(it))
                    to = maxOf(to, text.getSpanEnd(it))
                    text.removeSpan(it)
                }
            text.setSpan(span, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        fun removeChar(text: Editable, start: Int, end: Int, format: CharFormat) =
            removeMatching(text, start, end) { format.matches(it) }

        fun <T> removeSpans(text: Editable, start: Int, end: Int, type: Class<T>) =
            removeMatching(text, start, end) { type.isInstance(it) }

        /** Removes formats from [start, end), keeping the parts of their spans outside. */
        private inline fun removeMatching(text: Editable, start: Int, end: Int, matches: (Any) -> Boolean) {
            splitBoldItalic(text, start, end)
            for (span in text.getSpans(start, end, Any::class.java)) {
                if (!matches(span) || RichTextHtml.isComposing(text, span)) continue
                val spanStart = text.getSpanStart(span)
                val spanEnd = text.getSpanEnd(span)
                if (spanEnd <= start || spanStart >= end) continue
                text.removeSpan(span)
                if (spanStart < start) text.setSpan(copyOf(span), spanStart, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (spanEnd > end) text.setSpan(copyOf(span), end, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        /** Pasted text may carry one span for bold italic; make it two so each can be removed alone. */
        private fun splitBoldItalic(text: Editable, start: Int, end: Int) {
            for (span in text.getSpans(start, end, StyleSpan::class.java)) {
                if (span.style != Typeface.BOLD_ITALIC) continue
                val spanStart = text.getSpanStart(span)
                val spanEnd = text.getSpanEnd(span)
                text.removeSpan(span)
                text.setSpan(StyleSpan(Typeface.BOLD), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(StyleSpan(Typeface.ITALIC), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        private fun copyOf(span: Any): Any = when (span) {
            is NbFontSpan -> NbFontSpan(span.font)
            is NbSizeSpan -> NbSizeSpan(span.size)
            else -> CharFormat.entries.first { it.matches(span) }.newSpan()
        }
    }
}
