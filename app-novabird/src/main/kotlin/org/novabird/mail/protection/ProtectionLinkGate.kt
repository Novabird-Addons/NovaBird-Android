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
import java.util.Locale
import org.novabird.feature.protection.ProtectionLevel
import org.novabird.mail.R
import com.fsck.k9.ui.R as LegacyR

/**
 * Links of a mail rated "gefährlich" are not opened directly: a dialog names the destination and asks first (like
 * the add-on's "Links in dieser Mail sind gesperrt, bis du sie bestätigst"). The same while the check of the mail has
 * no result yet, or failed: an unknown mail must not count as harmless. Only mail and phone links stay open. In a
 * dangerous mail only web links can be opened after the question; other schemes (`intent:`, `file:`, `smb:` …) start
 * apps or reach files and stay blocked, like `linkpolicy.js` of the add-on. Finds the message's state in the tag the
 * [ProtectionMessageViewHook] set on the message view.
 */
class ProtectionLinkGate : MessageLinkGate {

    override fun shouldIntercept(view: View, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val state = if (scheme in OPEN_SCHEMES) null else findState(view)
        val context = view.context
        val target = uri.host ?: uri.toString()
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.novabird_protect_link_title)
        val shown = when {
            state == null -> null

            state.level == ProtectionLevel.DANGEROUS && scheme !in WEB_SCHEMES ->
                dialog
                    .setMessage(context.getString(R.string.novabird_protect_link_blocked, scheme.orEmpty()))
                    .setPositiveButton(android.R.string.ok, null)

            state.level == ProtectionLevel.DANGEROUS ->
                dialog
                    .setMessage(context.getString(R.string.novabird_protect_link_text, target))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.novabird_protect_link_open) { _, _ -> openUrl(context, uri) }

            !state.isChecked ->
                dialog
                    .setMessage(context.getString(R.string.novabird_protect_link_unchecked, target))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.novabird_protect_link_open) { _, _ -> openUrl(context, uri) }

            else -> null
        }
        shown?.show()
        return shown != null
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

    private companion object {
        val OPEN_SCHEMES = setOf("mailto", "tel")
        val WEB_SCHEMES = setOf("http", "https")
    }
}
