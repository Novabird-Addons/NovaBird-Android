package com.fsck.k9.activity.compose;


/**
 * NovaBird: implemented by a message text view that can format text (bold, lists, ...). MessageCompose sends the
 * formatting as HTML and switches an "automatic" message format to HTML once the text is formatted.
 */
public interface RichTextEditor {
    /** True if the text carries formatting that plain text would lose. */
    boolean hasFormatting();

    /** The text as an HTML fragment. */
    String getHtmlFragment();

    /** The text for the plain text part, with list markers written out. */
    String getPlainText();

    /** Replaces the text by the given HTML fragment, e.g. from a draft. */
    void setHtmlFragment(String html);

    /** False while the message has to be sent as plain text; the editor then offers no formatting. */
    void setRichTextAvailable(boolean available);
}
