package org.novabird.mail.compose

import android.app.Application
import android.text.SpannableStringBuilder
import android.text.Spanned
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RichTextHtmlTest {

    @Test
    fun `should write plain lines like Thunderbird`() {
        val text = SpannableStringBuilder("Hallo\n\nViele Grüße")

        assertThat(RichTextHtml.toHtml(text)).isEqualTo("<div dir=\"auto\">Hallo<br><br>Viele Grüße</div>")
        assertThat(RichTextHtml.hasFormatting(text)).isFalse()
    }

    @Test
    fun `should write character formats nested`() {
        val text = SpannableStringBuilder("Hallo schöne Welt")
        text.setSpan(CharFormat.BOLD.newSpan(), 6, 17, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(CharFormat.ITALIC.newSpan(), 13, 17, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(NbFontSpan(NbFont.SERIF), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        assertThat(RichTextHtml.hasFormatting(text)).isTrue()
        assertThat(RichTextHtml.toHtml(text)).isEqualTo(
            "<div dir=\"auto\"><span style=\"font-family: Georgia, 'Times New Roman', serif\">Hallo</span> " +
                "<b>schöne </b><b><i>Welt</i></b></div>",
        )
    }

    @Test
    fun `should write lists, headings and quotes`() {
        val text = paragraphs(
            "Punkte" to null,
            "Raum" to NbParagraphSpan(NbBlock.BULLET),
            "Beamer" to NbParagraphSpan(NbBlock.BULLET),
            "Kabel" to NbParagraphSpan(NbBlock.BULLET, level = 2),
            "Erstens" to NbParagraphSpan(NbBlock.NUMBER),
            "Titel" to NbParagraphSpan(NbBlock.HEADING),
            "Zitat eins" to NbParagraphSpan(NbBlock.QUOTE),
            "Zitat zwei" to NbParagraphSpan(NbBlock.QUOTE),
            "Ende" to null,
        )

        assertThat(RichTextHtml.toHtml(text)).isEqualTo(
            "<div dir=\"auto\">Punkte<ul><li>Raum</li><li>Beamer<ul><li>Kabel</li></ul></li></ul>" +
                "<ol><li>Erstens</li></ol><h2>Titel</h2><blockquote>Zitat eins<br>Zitat zwei</blockquote>Ende</div>",
        )
    }

    @Test
    fun `should keep an empty line before a list`() {
        val text = paragraphs("Hallo" to null, "" to null, "eins" to NbParagraphSpan(NbBlock.BULLET))

        assertThat(RichTextHtml.toHtml(text)).isEqualTo("<div dir=\"auto\">Hallo<br><br><ul><li>eins</li></ul></div>")
    }

    @Test
    fun `should write list markers into the plain text`() {
        val text = paragraphs(
            "Liste" to null,
            "a" to NbParagraphSpan(NbBlock.NUMBER),
            "b" to NbParagraphSpan(NbBlock.NUMBER),
            "c" to NbParagraphSpan(NbBlock.BULLET, level = 2),
            "d" to NbParagraphSpan(NbBlock.NUMBER),
            "gesagt" to NbParagraphSpan(NbBlock.QUOTE),
        )

        assertThat(RichTextHtml.toPlainText(text)).isEqualTo("Liste\n1. a\n2. b\n  • c\n3. d\n> gesagt")
    }

    @Test
    fun `should make links clickable`() {
        val text = SpannableStringBuilder("siehe https://example.com")
        text.setSpan(CharFormat.BOLD.newSpan(), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        assertThat(RichTextHtml.toHtml(text)).contains("<a href=\"https://example.com\">https://example.com</a>")
    }

    @Test
    fun `should read its own HTML back`() {
        val text = paragraphs(
            "Hallo Alice," to null,
            "" to null,
            "Punkte" to NbParagraphSpan(NbBlock.SUBHEADING),
            "Raum" to NbParagraphSpan(NbBlock.BULLET),
            "Kabel" to NbParagraphSpan(NbBlock.NUMBER, level = 2),
            "Beamer" to NbParagraphSpan(NbBlock.BULLET),
            "Zitat" to NbParagraphSpan(NbBlock.QUOTE),
            "Viele  Grüße" to null,
        )
        text.setSpan(CharFormat.UNDERLINE.newSpan(), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(NbSizeSpan(NbSize.LARGE), 6, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(NbFontSpan(NbFont.MONO), 6, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val html = RichTextHtml.toHtml(text)

        val read = RichTextHtml.fromHtml(html)

        assertThat(read.toString()).isEqualTo(text.toString())
        assertThat(RichTextHtml.toHtml(read)).isEqualTo(html)
    }

    @Test
    fun `should read HTML of other mail programs`() {
        val html = "<div>Hallo,</div><div><br></div><div>das ist <strong>wichtig</strong>.</div>" +
            "<ol><li>eins</li><li>zwei</li></ol><p style=\"margin:0\">Gruß</p>"

        val read = RichTextHtml.fromHtml(html)

        assertThat(read.toString()).isEqualTo("Hallo,\n\ndas ist wichtig.\neins\nzwei\nGruß")
        assertThat(RichTextHtml.toPlainText(read)).isEqualTo("Hallo,\n\ndas ist wichtig.\n1. eins\n2. zwei\nGruß")
    }

    @Test
    fun `should read old plain drafts without formatting`() {
        val read = RichTextHtml.fromHtml("<div dir=\"auto\">eins<br>zwei&nbsp; drei</div>")

        assertThat(read.toString()).isEqualTo("eins\nzwei  drei")
        assertThat(RichTextHtml.hasFormatting(read)).isFalse()
    }

    private fun paragraphs(vararg lines: Pair<String, NbParagraphSpan?>): SpannableStringBuilder {
        val text = SpannableStringBuilder()
        lines.forEachIndexed { index, (line, span) ->
            val start = text.length
            text.append(line)
            if (index < lines.lastIndex) text.append('\n')
            span?.let { text.setSpan(it, start, text.length, Spanned.SPAN_PARAGRAPH) }
        }
        return text
    }
}
