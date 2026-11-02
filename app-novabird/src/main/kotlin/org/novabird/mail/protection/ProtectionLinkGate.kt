package org.novabird.mail.protection

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Browser
import android.view.View
import android.webkit.WebView
import android.widget.Toast
import com.fsck.k9.ui.MessageLinkGate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.novabird.feature.protection.ProtectionLevel
import org.novabird.mail.R
import com.fsck.k9.ui.R as LegacyR

/**
 * Links of a mail rated "gefährlich" are not opened directly: a dialog names the destination and asks first (like the
 * add-on's "Links in dieser Mail sind gesperrt, bis du sie bestätigst"). Finds the message's state in the tag the
 * [ProtectionMessageViewHook] set on the message view.
 */
class ProtectionLinkGate : MessageLinkGate {

    override fun shouldIntercept(webView: WebView, uri: Uri): Boolean {
        val state = if (uri.scheme == "http" || uri.scheme == "https") findState(webView) else null
        if (state == null || state.level != ProtectionLevel.DANGEROUS) return false
        val context = webView.context
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.novabird_protect_link_title)
            .setMessage(context.getString(R.string.novabird_protect_link_text, uri.host ?: uri.toString()))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.novabird_protect_link_open) { _, _ -> openUrl(context, uri) }
            .show()
        return true
    }

    private fun findState(view: View): ProtectionViewState? {
        var current: View? = view
        while (current != null) {
            (current.getTag(R.id.nb_protection_state) as? ProtectionViewState)?.let { return it }
            current = current.parent as? View
        }
        return null
    }

    /** Like `K9WebViewClient.openUrl`. */
    private fun openUrl(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            putExtra(Browser.EXTRA_APPLICATION_ID, context.packageName)
            putExtra(Browser.EXTRA_CREATE_NEW_TAB, true)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, LegacyR.string.error_activity_not_found, Toast.LENGTH_LONG).show()
        }
    }
}
