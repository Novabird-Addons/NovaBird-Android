package org.novabird.mail.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.novabird.feature.ai.AiVectorBackend
import org.novabird.feature.ai.local.LocalEmbedder
import org.novabird.feature.ai.ui.AiSettingsActivity
import org.novabird.feature.protection.ProtectionSettings
import org.novabird.mail.R
import org.novabird.mail.logo.SenderLogoSettings
import org.novabird.mail.protection.ProtectionSettingsActivity
import com.google.android.material.R as MaterialR

/**
 * NovaBird block at the top of the settings (layout overlay fragment_settings_list.xml): semantic search with the
 * status of the Helper (green: connected, orange: not reachable, with the note that the phone computes for now) and
 * the switch for sender logos, and the phishing protection with its state.
 */
class SettingsHeaderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs), KoinComponent {

    private val backend: AiVectorBackend by inject()
    private val localEmbedder: LocalEmbedder by inject()
    private val logoSettings: SenderLogoSettings by inject()
    private val protectionSettings: ProtectionSettings by inject()

    private val statusIcon: ImageView
    private val statusText: TextView
    private val hint: TextView
    private val logoSwitch: MaterialSwitch
    private val protectionStatus: TextView

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.nb_settings_header, this, true)
        statusIcon = findViewById(R.id.nb_search_status_icon)
        statusText = findViewById(R.id.nb_search_status)
        hint = findViewById(R.id.nb_search_hint)
        logoSwitch = findViewById(R.id.nb_logo_switch)
        protectionStatus = findViewById(R.id.nb_protect_status)

        findViewById<LinearLayout>(R.id.nb_protect_row).setOnClickListener {
            context.startActivity(Intent(context, ProtectionSettingsActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.nb_search_row).setOnClickListener {
            context.startActivity(Intent(context, AiSettingsActivity::class.java))
        }
        logoSwitch.isChecked = logoSettings.isEnabled
        findViewById<LinearLayout>(R.id.nb_logo_row).setOnClickListener {
            logoSwitch.isChecked = !logoSwitch.isChecked
            logoSettings.setEnabled(context, logoSwitch.isChecked)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val owner = findViewTreeLifecycleOwner() ?: return
        // checked again on every return to the settings (e.g. after pairing in the search settings)
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) { refresh() }
        }
    }

    private suspend fun refresh() {
        val protectionText = if (protectionSettings.enabled) {
            R.string.novabird_settings_protect_on
        } else {
            R.string.novabird_settings_protect_off
        }
        protectionStatus.setText(protectionText)
        if (!backend.isHelperPaired()) {
            lastConnected = null
            show(
                icon = R.drawable.nb_ic_sparkle,
                color = attrColor(MaterialR.attr.colorOnSurfaceVariant),
                status = context.getString(
                    if (localEmbedder.isAvailable()) {
                        R.string.novabird_settings_search_phone
                    } else {
                        R.string.novabird_settings_search_off
                    },
                ),
                hintText = null,
            )
            return
        }
        // keep the last known color while checking (no orange flash on every visit)
        when (lastConnected) {
            true -> showConnected(context.getString(R.string.novabird_settings_search_checking))

            false -> showUnreachable()

            null -> show(
                icon = R.drawable.nb_ic_sparkle,
                color = attrColor(MaterialR.attr.colorOnSurfaceVariant),
                status = context.getString(R.string.novabird_settings_search_checking),
                hintText = null,
            )
        }
        val result = backend.helperStatus()
        lastConnected = result.isSuccess
        result.fold(
            onSuccess = {
                showConnected(context.getString(R.string.novabird_settings_search_connected, it.deviceName))
            },
            onFailure = { showUnreachable() },
        )
    }

    private fun showConnected(status: String) {
        show(R.drawable.nb_ic_status_ok, ContextCompat.getColor(context, R.color.nb_status_ok), status, hintText = null)
    }

    private fun showUnreachable() {
        val hintRes = if (localEmbedder.isAvailable()) {
            R.string.novabird_settings_search_local_now
        } else {
            R.string.novabird_settings_search_words_only
        }
        show(
            icon = R.drawable.nb_ic_status_warn,
            color = ContextCompat.getColor(context, R.color.nb_status_warn),
            status = context.getString(R.string.novabird_settings_search_unreachable),
            hintText = context.getString(hintRes),
        )
    }

    private fun show(@DrawableRes icon: Int, color: Int, status: String, hintText: String?) {
        statusIcon.setImageResource(icon)
        statusIcon.imageTintList = ColorStateList.valueOf(color)
        statusText.text = status
        hint.isVisible = hintText != null
        hint.text = hintText
    }

    private fun attrColor(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private companion object {
        /** Result of the last Helper check (null: not checked yet or no Helper). */
        @Volatile
        var lastConnected: Boolean? = null
    }
}
