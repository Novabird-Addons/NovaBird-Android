package org.novabird.mail.compose

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.novabird.mail.R

/**
 * The rarer formats behind "Aa": strikethrough, indent, clear formatting, paragraph kind, font and size. Shown as a
 * sheet in place of the keyboard; changes apply right away to the selection or to what is typed next.
 */
class NbFormatPanel(private val context: Context, private val editor: NbRichEditText) {
    private val inputMethodManager = context.getSystemService(InputMethodManager::class.java)

    fun show() {
        inputMethodManager?.hideSoftInputFromWindow(editor.windowToken, 0)
        val dialog = BottomSheetDialog(context)
        val updates = mutableListOf<() -> Unit>()
        dialog.setContentView(content(updates))
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        // keep the text readable above the sheet
        dialog.window?.setDimAmount(DIM_AMOUNT)
        val barUpdate = editor.onStateChanged
        editor.onStateChanged = {
            barUpdate?.invoke()
            updates.forEach { it() }
        }
        dialog.setOnDismissListener {
            editor.onStateChanged = barUpdate
            editor.showKeyboardWhenFocused()
        }
        dialog.show()
    }

    private fun content(updates: MutableList<() -> Unit>) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(PADDING), dp(PADDING_TOP), dp(PADDING), dp(PADDING))

        addView(toolRow(updates))

        section(R.string.novabird_format_paragraph)
        addView(
            chips(
                updates,
                listOf(
                    Choice(R.string.novabird_format_paragraph_normal, null),
                    Choice(R.string.novabird_format_heading, NbBlock.HEADING, bold = true),
                    Choice(R.string.novabird_format_subheading, NbBlock.SUBHEADING, bold = true),
                    Choice(R.string.novabird_format_quote, NbBlock.QUOTE, italic = true),
                ),
                current = { editor.paragraphAtCursor()?.block?.takeUnless { it == NbBlock.BULLET || it == NbBlock.NUMBER } },
                apply = editor::setBlock,
            ),
        )

        section(R.string.novabird_format_font)
        addView(
            chips(
                updates,
                listOf(
                    Choice(R.string.novabird_format_font_standard, null),
                    Choice(R.string.novabird_format_font_serif, NbFont.SERIF, typeface = Typeface.SERIF),
                    Choice(R.string.novabird_format_font_mono, NbFont.MONO, typeface = Typeface.MONOSPACE),
                ),
                current = { editor.typingFormats.font },
                apply = editor::setFont,
            ),
        )

        section(R.string.novabird_format_size)
        addView(
            chips(
                updates,
                listOf(
                    Choice(R.string.novabird_format_size_small, NbSize.SMALL),
                    Choice(R.string.novabird_format_size_normal, null),
                    Choice(R.string.novabird_format_size_large, NbSize.LARGE),
                    Choice(R.string.novabird_format_size_huge, NbSize.HUGE),
                ),
                current = { editor.typingFormats.size },
                apply = editor::setSize,
            ),
        )
    }

    /** Strikethrough, indent out and in, clear formatting. */
    private fun toolRow(updates: MutableList<() -> Unit>) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = dp(TOOL_RADIUS).toFloat()
            setColor(context.themeColor(MaterialR.attr.colorSurfaceContainerLowest))
            setStroke(dp(1), context.themeColor(MaterialR.attr.colorOutlineVariant))
        }
        val strike = TextView(context).apply {
            text = "S"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TOOL_TEXT_SP)
            paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            contentDescription = context.getString(R.string.novabird_format_strike)
            setOnClickListener { editor.toggle(CharFormat.STRIKE) }
        }
        updates += {
            val active = CharFormat.STRIKE in editor.typingFormats.chars
            strike.background = if (active) activeBackground() else null
            strike.setTextColor(
                context.themeColor(
                    if (active) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurface,
                ),
            )
        }
        addView(strike, LinearLayout.LayoutParams(0, dp(TOOL_HEIGHT), 1f))
        val outdent = tool(R.drawable.nb_ic_format_indent_decrease, R.string.novabird_format_indent_less) {
            editor.indent(-1)
        }
        val indent = tool(R.drawable.nb_ic_format_indent_increase, R.string.novabird_format_indent_more) {
            editor.indent(1)
        }
        updates += {
            val inList = editor.paragraphAtCursor()?.isList == true
            listOf(outdent, indent).forEach {
                it.isEnabled = inList
                it.alpha = if (inList) 1f else DISABLED_ALPHA
            }
        }
        addView(outdent, LinearLayout.LayoutParams(0, dp(TOOL_HEIGHT), 1f))
        addView(indent, LinearLayout.LayoutParams(0, dp(TOOL_HEIGHT), 1f))
        addView(
            tool(R.drawable.nb_ic_format_clear, R.string.novabird_format_clear) { editor.clearFormatting() },
            LinearLayout.LayoutParams(0, dp(TOOL_HEIGHT), 1f),
        )
        updates.forEach { it() }
    }

    private fun tool(@DrawableRes icon: Int, @StringRes description: Int, onClick: () -> Unit) =
        ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER
            imageTintList = ColorStateList.valueOf(context.themeColor(MaterialR.attr.colorOnSurface))
            contentDescription = context.getString(description)
            setOnClickListener { onClick() }
        }

    private fun activeBackground() = GradientDrawable().apply {
        cornerRadius = dp(TOOL_RADIUS).toFloat()
        setColor(context.themeColor(MaterialR.attr.colorPrimaryContainer))
    }

    private fun LinearLayout.section(@StringRes label: Int) {
        addView(
            TextView(context).apply {
                text = context.getString(label)
                isAllCaps = true
                setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_TEXT_SP)
                letterSpacing = LABEL_LETTER_SPACING
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.themeColor(MaterialR.attr.colorOnSurfaceVariant))
                setPadding(0, dp(SECTION_TOP), 0, dp(SECTION_BOTTOM))
            },
        )
    }

    private class Choice<T>(
        @StringRes val label: Int,
        val value: T?,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val typeface: Typeface? = null,
    )

    private fun <T> chips(
        updates: MutableList<() -> Unit>,
        choices: List<Choice<T>>,
        current: () -> T?,
        apply: (T?) -> Unit,
    ): View {
        val group = ChipGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
            chipSpacingHorizontal = dp(CHIP_SPACING)
        }
        val chips = choices.map { choice ->
            Chip(context, null, MaterialR.attr.chipStyle).apply {
                id = View.generateViewId()
                text = context.getString(choice.label)
                isCheckable = true
                isCheckedIconVisible = false
                val style = when {
                    choice.bold -> Typeface.BOLD
                    choice.italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                typeface = Typeface.create(choice.typeface ?: typeface, style)
                chipBackgroundColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(
                        context.themeColor(MaterialR.attr.colorPrimaryContainer),
                        context.themeColor(MaterialR.attr.colorSurfaceContainerLow),
                    ),
                )
                group.addView(this)
            }
        }
        var updating = false
        val update = {
            updating = true
            val value = current()
            chips.zip(choices).forEach { (chip, choice) -> chip.isChecked = choice.value == value }
            updating = false
        }
        update()
        updates += update
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            if (updating) return@setOnCheckedStateChangeListener
            val index = chips.indexOfFirst { it.id == checkedIds.firstOrNull() }
            if (index >= 0) apply(choices[index].value)
        }
        return group
    }

    private fun dp(value: Int) = context.dp(value)

    private companion object {
        const val PADDING = 16
        const val PADDING_TOP = 8
        const val TOOL_HEIGHT = 48
        const val TOOL_RADIUS = 12
        const val TOOL_TEXT_SP = 18f
        const val LABEL_TEXT_SP = 12f
        const val LABEL_LETTER_SPACING = 0.04f
        const val SECTION_TOP = 14
        const val SECTION_BOTTOM = 2
        const val CHIP_SPACING = 8
        const val DISABLED_ALPHA = 0.38f
        const val DIM_AMOUNT = 0.1f
    }
}
