package org.novabird.mail.protection

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.fsck.k9.mailstore.LocalMessage
import com.fsck.k9.mailstore.MessageViewInfo
import com.fsck.k9.ui.MessageViewHook
import com.fsck.k9.ui.messageview.MessageTopView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import net.thunderbird.core.android.account.LegacyAccountDto
import org.novabird.feature.protection.ProtectionLevel
import org.novabird.feature.protection.ProtectionService
import org.novabird.feature.protection.SpamReason
import org.novabird.feature.protection.StoredVerdict
import org.novabird.feature.protection.TrustOutcome
import org.novabird.mail.R
import com.google.android.material.R as MaterialR

/** What the message view knows about the shown message; the link gate reads it from the [MessageTopView]'s tag. */
class ProtectionViewState(val accountUuid: String, val k9Id: Long) {
    @Volatile
    var level: ProtectionLevel = ProtectionLevel.NORMAL

    /** Check and refresh of this message; cancelled when the view shows the next one. */
    var job: Job? = null
}

/**
 * Warning banner above the message (layout overlay `message.xml`, container `nb_protection_container`): checks the
 * opened mail, shows level, score and the reasons on "Warum?", offers "Absender vertrauen" at "Vorsicht" and blocks
 * links at "gefährlich" (see [ProtectionLinkGate]). A failed check shows a grey "Prüfung nicht möglich", never nothing.
 */
class ProtectionMessageViewHook(private val service: ProtectionService) : MessageViewHook {

    override fun onMessageShown(
        messageTopView: MessageTopView,
        account: LegacyAccountDto,
        messageViewInfo: MessageViewInfo,
    ) {
        val container = messageTopView.findViewById<ViewGroup>(R.id.nb_protection_container)
        val message = messageViewInfo.message as? LocalMessage
        if (container == null || message == null) return
        container.removeAllViews()
        container.isVisible = false
        (messageTopView.getTag(R.id.nb_protection_state) as? ProtectionViewState)?.job?.cancel()
        val state = ProtectionViewState(account.uuid, message.databaseId)
        messageTopView.setTag(R.id.nb_protection_state, state)
        val owner = messageTopView.findViewTreeLifecycleOwner()
        if (!service.isEnabled || owner == null) return
        state.job = owner.lifecycleScope.launch {
            val result = runCatching { service.verdictOnOpen(state.accountUuid, state.k9Id) }
            // another message is shown meanwhile
            if (messageTopView.getTag(R.id.nb_protection_state) !== state) return@launch
            result.fold(
                onSuccess = { verdict -> verdict?.let { showFor(container, messageTopView, state, it) } },
                // a failed check must not look like "no warning"
                onFailure = { showErrorBanner(container) },
            )
            // the language model may sharpen the result a little later: show it as soon as it is stored
            var shown = result.getOrNull()?.verdict?.key
            service.revision.drop(1).conflate().collect {
                if (messageTopView.getTag(R.id.nb_protection_state) !== state) return@collect
                val latest = runCatching { service.storedVerdict(state.accountUuid, state.k9Id) }.getOrNull()
                if (latest?.verdict?.key == shown) return@collect
                shown = latest?.verdict?.key
                container.removeAllViews()
                container.isVisible = false
                state.level = ProtectionLevel.NORMAL
                latest?.let { showFor(container, messageTopView, state, it) }
            }
        }
    }

    private fun showBanner(
        container: ViewGroup,
        messageTopView: MessageTopView,
        state: ProtectionViewState,
        verdict: StoredVerdict,
    ) {
        val context = container.context
        val view = LayoutInflater.from(context).inflate(R.layout.nb_protection_banner, container, false)
        val dangerous = verdict.level == ProtectionLevel.DANGEROUS
        val (background, foreground) = if (dangerous) {
            attrColor(context, MaterialR.attr.colorErrorContainer) to
                attrColor(context, MaterialR.attr.colorOnErrorContainer)
        } else {
            ContextCompat.getColor(context, R.color.nb_protect_caution_bg) to
                ContextCompat.getColor(context, R.color.nb_protect_caution_fg)
        }
        view.findViewById<View>(R.id.nb_protect_box).backgroundTintList = ColorStateList.valueOf(background)
        view.findViewById<ImageView>(R.id.nb_protect_icon).imageTintList = ColorStateList.valueOf(foreground)
        val title = view.findViewById<TextView>(R.id.nb_protect_title)
        title.setText(
            if (dangerous) R.string.novabird_protect_banner_dangerous else R.string.novabird_protect_banner_suspicious,
        )
        title.setTextColor(foreground)
        val score = view.findViewById<TextView>(R.id.nb_protect_score)
        score.text = context.getString(R.string.novabird_protect_banner_score, verdict.verdict.score)
        score.setTextColor(foreground)
        val reasons = view.findViewById<LinearLayout>(R.id.nb_protect_reasons)
        val why = view.findViewById<TextView>(R.id.nb_protect_why)
        why.setTextColor(foreground)
        why.setOnClickListener {
            if (reasons.isVisible) {
                reasons.isVisible = false
                why.setText(R.string.novabird_protect_banner_why)
                return@setOnClickListener
            }
            why.setText(R.string.novabird_protect_banner_why_less)
            if (reasons.childCount == 0) loadReasons(messageTopView, verdict, reasons, foreground)
            reasons.isVisible = true
        }
        val trust = view.findViewById<TextView>(R.id.nb_protect_trust)
        val blocked = view.findViewById<TextView>(R.id.nb_protect_blocked)
        blocked.setTextColor(foreground)
        if (dangerous) {
            trust.isVisible = false
            blocked.isVisible = true
            view.findViewById<TextView>(R.id.nb_protect_links).apply {
                isVisible = true
                setTextColor(foreground)
            }
        } else {
            trust.setTextColor(foreground)
            trust.setOnClickListener { confirmTrust(container, messageTopView, state) }
        }
        container.addView(view)
        container.isVisible = true
    }

    private fun loadReasons(messageTopView: MessageTopView, verdict: StoredVerdict, reasons: LinearLayout, color: Int) {
        val owner = messageTopView.findViewTreeLifecycleOwner() ?: return
        owner.lifecycleScope.launch {
            val texts = runCatching { service.reasons(verdict, Locale.getDefault().language) }.getOrDefault(emptyList())
            if (reasons.childCount > 0) return@launch
            for (reason in texts) {
                val line = TextView(reasons.context)
                line.text = reasons.context.getString(R.string.novabird_protect_banner_reason, reason.text)
                line.setTextColor(color)
                line.setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyMedium)
                line.setTextColor(color)
                reasons.addView(line)
            }
        }
    }

    private fun confirmTrust(container: ViewGroup, messageTopView: MessageTopView, state: ProtectionViewState) {
        val context = container.context
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.novabird_protect_trust_title)
            .setMessage(R.string.novabird_protect_trust_text)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.novabird_protect_trust_yes) { _, _ ->
                val owner = messageTopView.findViewTreeLifecycleOwner() ?: return@setPositiveButton
                owner.lifecycleScope.launch {
                    val outcome = runCatching {
                        service.trustSenderOf(state.accountUuid, state.k9Id, Locale.getDefault().language)
                    }.getOrDefault(TrustOutcome.NO_VERDICT)
                    if (outcome == TrustOutcome.TRUSTED) {
                        state.level = ProtectionLevel.NORMAL
                        container.removeAllViews()
                        container.isVisible = false
                        Toast.makeText(context, R.string.novabird_protect_trust_done, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, R.string.novabird_protect_trust_blocked_hint, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    /** Phishing banner for "Vorsicht"/"gefährlich", else the spam banner if the spam rules caught the mail. */
    private fun showFor(
        container: ViewGroup,
        messageTopView: MessageTopView,
        state: ProtectionViewState,
        verdict: StoredVerdict,
    ) {
        if (verdict.level != ProtectionLevel.NORMAL) {
            state.level = verdict.level
            showBanner(container, messageTopView, state, verdict)
        } else if (isSpam(verdict)) {
            showSpamBanner(container, messageTopView, state, verdict)
        }
    }

    private fun isSpam(verdict: StoredVerdict) = verdict.spam != null && service.spamSettings.enabled

    /** Grey banner for a mail the spam rules caught: the reason and "Kein Spam". */
    private fun showSpamBanner(
        container: ViewGroup,
        messageTopView: MessageTopView,
        state: ProtectionViewState,
        verdict: StoredVerdict,
    ) {
        val context = container.context
        val spam = verdict.spam ?: return
        val view = LayoutInflater.from(context).inflate(R.layout.nb_protection_banner, container, false)
        val foreground = attrColor(context, MaterialR.attr.colorOnSurfaceVariant)
        view.findViewById<View>(R.id.nb_protect_box).backgroundTintList =
            ColorStateList.valueOf(attrColor(context, MaterialR.attr.colorSurfaceContainerHigh))
        view.findViewById<ImageView>(R.id.nb_protect_icon).imageTintList = ColorStateList.valueOf(foreground)
        view.findViewById<TextView>(R.id.nb_protect_title).apply {
            setText(R.string.novabird_protect_banner_spam)
            setTextColor(foreground)
        }
        view.findViewById<TextView>(R.id.nb_protect_score).apply {
            text = when (spam.reason) {
                SpamReason.BLOCKED -> context.getString(R.string.novabird_protect_spam_reason_blocked, spam.detail)

                SpamReason.SERVER -> context.getString(R.string.novabird_protect_spam_reason_server)

                SpamReason.AUTH -> context.getString(
                    R.string.novabird_protect_spam_reason_auth,
                    spam.detail.uppercase(),
                )

                SpamReason.PHISHING -> context.getString(R.string.novabird_protect_spam_reason_phishing)

                SpamReason.MONEY -> context.getString(R.string.novabird_protect_spam_reason_money)
            }
            setTextColor(foreground)
        }
        view.findViewById<TextView>(R.id.nb_protect_why).isVisible = false
        view.findViewById<TextView>(R.id.nb_protect_trust).apply {
            setText(R.string.novabird_protect_banner_not_spam)
            setTextColor(foreground)
            setOnClickListener {
                val owner = messageTopView.findViewTreeLifecycleOwner() ?: return@setOnClickListener
                owner.lifecycleScope.launch {
                    val done = runCatching { service.notSpam(state.accountUuid, state.k9Id) }.getOrDefault(false)
                    if (done) {
                        container.removeAllViews()
                        container.isVisible = false
                        Toast.makeText(context, R.string.novabird_protect_not_spam_done, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        container.addView(view)
        container.isVisible = true
    }

    private fun showErrorBanner(container: ViewGroup) {
        val context = container.context
        val view = LayoutInflater.from(context).inflate(R.layout.nb_protection_banner, container, false)
        val foreground = attrColor(context, MaterialR.attr.colorOnSurfaceVariant)
        view.findViewById<View>(R.id.nb_protect_box).backgroundTintList =
            ColorStateList.valueOf(attrColor(context, MaterialR.attr.colorSurfaceContainerHigh))
        view.findViewById<ImageView>(R.id.nb_protect_icon).imageTintList = ColorStateList.valueOf(foreground)
        view.findViewById<TextView>(R.id.nb_protect_title).apply {
            setText(R.string.novabird_protect_banner_error_title)
            setTextColor(foreground)
        }
        view.findViewById<TextView>(R.id.nb_protect_score).apply {
            setText(R.string.novabird_protect_banner_error_text)
            setTextColor(foreground)
        }
        view.findViewById<View>(R.id.nb_protect_footer).isVisible = false
        container.addView(view)
        container.isVisible = true
    }

    private fun attrColor(context: android.content.Context, attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }
}
