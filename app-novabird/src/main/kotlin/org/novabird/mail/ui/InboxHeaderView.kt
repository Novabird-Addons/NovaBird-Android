package org.novabird.mail.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.HorizontalScrollView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.fsck.k9.activity.MessageHomeActivity
import com.fsck.k9.ui.messagelist.MessageListFragmentBridgeContract
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.serialization.LocalMessageSearchSerializer
import org.novabird.feature.ai.ui.AiNewsActivity
import org.novabird.mail.R
import com.fsck.k9.ui.R as MessageListR
import com.google.android.material.R as MaterialR
import org.novabird.feature.ai.R as AiR

/**
 * Bar above the message list of the NovaBird design: "What's new?" and quick filters (all, unread, starred, with
 * attachment). Placed by the layout overlay message_list_fragment.xml. It reads the list's search from the arguments
 * of the message list fragment and shows a filtered list as a new search of the same list.
 */
class InboxHeaderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    HorizontalScrollView(context, attrs) {

    private val group = ChipGroup(context).apply {
        isSingleLine = true
        chipSpacingHorizontal = dp(CHIP_SPACING)
    }

    init {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        setPadding(dp(PADDING_HORIZONTAL), dp(PADDING_TOP), dp(PADDING_HORIZONTAL), dp(PADDING_BOTTOM))
        addView(group)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (group.childCount == 0) build()
    }

    private fun build() {
        val fragment = listFragment()
        val arguments = fragment?.arguments
        val searchBytes = arguments?.getByteArray(ARG_SEARCH)
        // threads and other lists without a search of their own get no bar
        if (searchBytes == null || arguments.getBoolean(ARG_IS_THREAD_DISPLAY)) {
            isVisible = false
            return
        }
        val active = InboxFilters.activeFilter(LocalMessageSearchSerializer.deserialize(searchBytes))

        group.addView(newsChip())
        group.addView(
            filterChip(context.getString(R.string.novabird_filter_all), checked = active == null) {
                showAll()
            },
        )
        for (filter in InboxFilter.entries) {
            group.addView(
                filterChip(context.getString(filter.label), checked = active == filter) {
                    if (active != filter) show(InboxFilters.filtered(searchBytes, filter))
                },
            )
        }
        if (active != null) {
            // keep the chosen filter in view, and let "back" lead to the whole list
            post {
                group.getChildAt(InboxFilter.entries.indexOf(active) + 2)?.let { scrollTo(it.left - paddingLeft, 0) }
            }
            registerBackToAll(fragment)
        }
    }

    private fun listFragment(): Fragment? = try {
        FragmentManager.findFragment(this)
    } catch (_: IllegalStateException) {
        null
    }

    private fun registerBackToAll(fragment: Fragment) {
        val activity = context as? ComponentActivity ?: return
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val drawer = activity.findViewById<DrawerLayout>(MessageListR.id.navigation_drawer_layout)
                val searchOpen = (fragment as? MessageListFragmentBridgeContract)?.isSearchViewCollapsed() == false
                val drawerOpen = drawer?.isDrawerOpen(GravityCompat.START) == true
                // the drawer, the search field and an open message go first (handled by the activity)
                val activityFirst = !isShown || searchOpen || drawerOpen
                val base = InboxFilters.lastBase
                if (base == null || activityFirst) {
                    isEnabled = false
                    activity.onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                } else {
                    show(LocalMessageSearchSerializer.deserialize(base))
                }
            }
        }
        activity.onBackPressedDispatcher.addCallback(fragment.viewLifecycleOwner, callback)
    }

    private fun showAll() {
        val base = InboxFilters.lastBase
        if (base != null) {
            show(LocalMessageSearchSerializer.deserialize(base))
        } else {
            // the unfiltered list isn't known any more (app restarted): back is the way out
            (context as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    private fun show(search: LocalMessageSearch) {
        context.startActivity(
            MessageHomeActivity.intentDisplaySearch(context, search, false, false, false),
        )
    }

    private fun newsChip() = Chip(context).apply {
        text = context.getString(AiR.string.novabird_ai_menu_news)
        setChipIconResource(R.drawable.nb_ic_sparkle)
        chipIconTint = ColorStateList.valueOf(color(MaterialR.attr.colorOnTertiaryContainer))
        chipBackgroundColor = ColorStateList.valueOf(color(MaterialR.attr.colorTertiaryContainer))
        setTextColor(color(MaterialR.attr.colorOnTertiaryContainer))
        chipStrokeWidth = 0f
        setOnClickListener { context.startActivity(Intent(context, AiNewsActivity::class.java)) }
    }

    private fun filterChip(label: String, checked: Boolean, onClick: () -> Unit) =
        Chip(context, null, MaterialR.attr.chipStyle).apply {
            text = label
            isCheckable = true
            isChecked = checked
            isCheckedIconVisible = false
            chipBackgroundColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(color(MaterialR.attr.colorOnSurface), color(MaterialR.attr.colorSurfaceContainerLow)),
            )
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(color(MaterialR.attr.colorSurface), color(MaterialR.attr.colorOnSurface)),
                ),
            )
            setOnClickListener {
                // the chip shows the list's filter; a tap opens the other list instead of toggling
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
        // argument keys of LegacyMessageListFragment
        const val ARG_SEARCH = "searchObject"
        const val ARG_IS_THREAD_DISPLAY = "isThreadedDisplay"

        const val PADDING_HORIZONTAL = 12
        const val PADDING_TOP = 2
        const val PADDING_BOTTOM = 6
        const val CHIP_SPACING = 8
    }
}
