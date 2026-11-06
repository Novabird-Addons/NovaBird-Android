package org.novabird.mail.compose

import android.app.Application
import android.view.ContextThemeWrapper
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NbRichEditTextTest {
    private val editor = NbRichEditText(
        ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            com.google.android.material.R.style.Theme_Material3_DayNight,
        ),
    )

    private fun type(value: String) {
        editor.text!!.insert(editor.selectionStart, value)
    }

    private fun html() = editor.htmlFragment.removePrefix("<div dir=\"auto\">").removeSuffix("</div>")

    @Test
    fun `should format what is typed after switching a format on and off`() {
        type("Hallo ")
        editor.toggle(CharFormat.BOLD)
        type("Welt")
        editor.toggle(CharFormat.BOLD)
        type("!")

        assertThat(html()).isEqualTo("Hallo <b>Welt</b>!")
    }

    @Test
    fun `should continue a format at the cursor`() {
        type("ab")
        editor.setSelection(0, 2)
        editor.toggle(CharFormat.ITALIC)
        editor.setSelection(2)
        type("c")

        assertThat(html()).isEqualTo("<i>abc</i>")
    }

    @Test
    fun `should remove a format from part of a word`() {
        type("abcd")
        editor.setSelection(0, 4)
        editor.toggle(CharFormat.BOLD)
        editor.setSelection(1, 3)
        editor.toggle(CharFormat.BOLD)

        assertThat(html()).isEqualTo("<b>a</b>bc<b>d</b>")
    }

    @Test
    fun `should start a new list item on enter`() {
        type("eins")
        editor.toggleList(NbBlock.BULLET)
        type("\n")
        type("zwei")

        assertThat(editor.plainText).isEqualTo("• eins\n• zwei")
        assertThat(html()).isEqualTo("<ul><li>eins</li><li>zwei</li></ul>")
    }

    @Test
    fun `should end a list on enter in an empty item`() {
        type("eins")
        editor.toggleList(NbBlock.NUMBER)
        type("\n")
        type("\n")
        type("Gruß")

        assertThat(editor.text.toString()).isEqualTo("eins\nGruß")
        assertThat(editor.plainText).isEqualTo("1. eins\nGruß")
    }

    @Test
    fun `should split a list item in the middle`() {
        type("einszwei")
        editor.toggleList(NbBlock.NUMBER)
        editor.setSelection(4)
        type("\n")

        assertThat(editor.plainText).isEqualTo("1. eins\n2. zwei")
    }

    @Test
    fun `should nest list items`() {
        type("a\nb\nc")
        editor.setSelection(0, 5)
        editor.toggleList(NbBlock.BULLET)
        editor.setSelection(2)
        editor.indent(1)

        assertThat(editor.plainText).isEqualTo("• a\n  • b\n• c")
        editor.indent(-1)
        editor.indent(-1)
        assertThat(editor.plainText).isEqualTo("• a\nb\n• c")
    }

    @Test
    fun `should write normal text after a heading`() {
        editor.setBlock(NbBlock.HEADING)
        type("Titel")
        type("\n")
        type("Text")

        assertThat(html()).isEqualTo("<h2>Titel</h2>Text")
    }

    @Test
    fun `should keep the formats of lines around an edit`() {
        type("a\nb\nc")
        editor.setSelection(2)
        editor.setBlock(NbBlock.QUOTE)
        editor.setSelection(5)
        type("d")
        editor.setSelection(0)
        type("x")

        assertThat(editor.plainText).isEqualTo("xa\n> b\ncd")
    }

    @Test
    fun `should only count as formatted with formats`() {
        type("nur Text")
        assertThat(editor.hasFormatting()).isFalse()

        editor.setSelection(0, 3)
        editor.toggle(CharFormat.UNDERLINE)
        assertThat(editor.hasFormatting()).isTrue()
    }

    @Test
    fun `should load a formatted draft`() {
        editor.setHtmlFragment("<div dir=\"auto\"><b>fett</b><ul><li>eins</li></ul></div>")

        assertThat(editor.plainText).isEqualTo("fett\n• eins")
        assertThat(html()).isEqualTo("<b>fett</b><ul><li>eins</li></ul>")
    }
}
