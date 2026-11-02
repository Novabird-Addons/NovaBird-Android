package org.novabird.mail.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.HorizontalScrollView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.fsck.k9.CoreResourceProvider
import com.fsck.k9.activity.MessageHomeActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.SearchAccount
import net.thunderbird.feature.search.legacy.serialization.LocalMessageSearchSerializer
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.novabird.mail.R
import com.google.android.material.R as MaterialR

/**
 * Row of inboxes above the quick filters (layout overlay message_list_fragment.xml): "All accounts" (the unified inbox)
 * and the inbox of each account. Only with two or more accounts, and only on an inbox.
 */
class AccountInboxesView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    HorizontalScrollView(context, attrs), KoinComponent {

    private val accountManager: LegacyAccountDtoManager by inject()
    private val generalSettingsManager: GeneralSettingsManager by inject()
    private val coreResourceProvider: CoreResourceProvider by inject()

    private val group = ChipGroup(context).apply {
        isSingleLine = true
        chipSpacingHorizontal = dp(CHIP_SPACING)
    }

    init {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        setPadding(dp(PADDING_HORIZONTAL), dp(PADDING_TOP), dp(PADDING_HORIZONTAL), 0)
        addView(group)
        isVisible = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (group.childCount == 0) build()
    }

    private fun build() {
        val accounts = accountManager.getAccounts().filter { it.inboxFolderId != null }
        val searchBytes = listArguments()?.getByteArray(ARG_SEARCH)
        if (accounts.size < 2 || searchBytes == null) return
        val search = LocalMessageSearchSerializer.deserialize(searchBytes)
        val unified = search.id == SearchAccount.UNIFIED_FOLDERS
        val current = accounts.firstOrNull { isInboxOf(search, it) }
        // other folders and searches get no row
        if (!unified && current == null) return

        if (unified || generalSettingsManager.getConfig().display.inboxSettings.isShowUnifiedInbox) {
            group.addView(
                inboxChip(context.getString(R.string.novabird_accounts_all), null, checked = unified) {
                    if (!unified) show(unifiedSearch())
                },
            )
        }
        for (account in accounts) {
            val chip = inboxChip(account.displayName, account.chipColor, checked = account == current) {
                if (account != current) show(inboxSearch(account))
            }
            group.addView(chip)
            // keep the current inbox in view
            if (account == current) post { scrollTo(chip.left - paddingLeft, 0) }
        }
        isVisible = true
    }

    private fun isInboxOf(search: LocalMessageSearch, account: LegacyAccountDto) =
        search.accountUuids == setOf(account.uuid) && search.folderIds == listOf(account.inboxFolderId)

    private fun listArguments() = try {
        FragmentManager.findFragment<Fragment>(this).arguments
    } catch (_: IllegalStateException) {
        null
    }

    private fun unifiedSearch(): LocalMessageSearch = SearchAccount.createUnifiedFoldersSearch(
        title = coreResourceProvider.searchUnifiedFoldersTitle(),
        detail = coreResourceProvider.searchUnifiedFoldersDetail(),
    ).relatedSearch

    private fun inboxSearch(account: LegacyAccountDto) = LocalMessageSearch().apply {
        addAllowedFolder(requireNotNull(account.inboxFolderId))
        addAccountUuid(account.uuid)
    }

    private fun show(search: LocalMessageSearch) {
        context.startActivity(MessageHomeActivity.intentDisplaySearch(context, search, false, false, true))
    }

    private fun inboxChip(label: String, accountColor: Int?, checked: Boolean, onClick: () -> Unit) =
        Chip(context, null, MaterialR.attr.chipStyle).apply {
            text = label
            isCheckable = true
            isChecked = checked
            isCheckedIconVisible = false
            if (accountColor != null) {
                setChipIconResource(R.drawable.nb_account_dot)
                chipIconTint = ColorStateList.valueOf(accountColor)
                chipIconSize = dp(DOT_SIZE).toFloat()
            }
            chipBackgroundColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(color(MaterialR.attr.colorPrimaryContainer), color(MaterialR.attr.colorSurfaceContainerLow)),
            )
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(color(MaterialR.attr.colorOnPrimaryContainer), color(MaterialR.attr.colorOnSurface)),
                ),
            )
            setOnClickListener {
                // the chip shows the current inbox; a tap opens the other one instead of toggling
                isChecked = checked
                onClick()
            }
        }

    private fun color(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        // argument key of LegacyMessageListFragment (see InboxHeaderView)
        const val ARG_SEARCH = "searchObject"

        const val PADDING_HORIZONTAL = 12
        const val PADDING_TOP = 2
        const val CHIP_SPACING = 8
        const val DOT_SIZE = 10
    }
}
