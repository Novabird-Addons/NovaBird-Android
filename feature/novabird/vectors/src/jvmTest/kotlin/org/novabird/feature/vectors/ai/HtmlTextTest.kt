package org.novabird.feature.vectors.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class HtmlTextTest {
    @Test
    fun `html becomes text with line breaks and entities`() {
        val html = "<html><head><style>p{}</style></head><body><p>Hallo&nbsp;Anna,</p><div>Preis: 5&euro; &amp; mehr" +
            "<br>Zeile&#33;</div><!-- x --></body></html>"
        assertThat(HtmlText.toText(html).trim()).isEqualTo("Hallo\u00A0Anna,\nPreis: 5€ & mehr\nZeile!")
    }

    @Test
    fun `quoted blocks are left out for only the new part`() {
        val html =
            "<div>Passt!</div><blockquote type=\"cite\"><div>alt <blockquote type=cite>älter</blockquote></div>" +
                "</blockquote><p>Gruß</p><div class=\"gmail_quote\"><div>Gmail alt</div></div>" +
                "<div id=\"divRplyFwdMsg\">Von: X</div><div>Outlook alt</div>"
        assertThat(HtmlText.toText(html, onlyNew = true).trim()).isEqualTo("Passt!\nGruß")
        assertThat(HtmlText.toText(html).contains("älter")).isEqualTo(true)
    }
}
