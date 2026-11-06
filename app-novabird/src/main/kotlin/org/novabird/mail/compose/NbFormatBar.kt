package org.novabird.mail.compose

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import org.novabird.mail.R
import com.google.android.material.R as MaterialR

/**
 * Format bar above the keyboard while the message text has the focus: bold, italic, underline, the two lists, and
 * "Aa" for the rarer formats ([NbFormatPanel]).
 */
class NbFormatBar(context: Context, private val editor: NbRichEditText) : LinearLayout(context) {
    private val dividerPaint = Paint().apply {
        color = context.themeColor(MaterialR.attr.colorOutlineVariant)
        strokeWidth = dp(1).toFloat()
    }

    private val bold = textButton("B", R.string.novabird_format_bold) { editor.toggle(CharFormat.BOLD) }.apply {
        setTypeface(typeface, Typeface.BOLD)
    }
    private val italic = textButton("I", R.string.novabird_format_italic) { editor.toggle(CharFormat.ITALIC) }.apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }
    private val underline = textButton("U", R.string.novabird_format_underline) {
        editor.toggle(CharFormat.UNDERLINE)
    }.apply {
        paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
    }
    private val bullets = iconButton(R.drawable.nb_ic_format_list_bulleted, R.string.novabird_format_bullets) {
        editor.toggleList(NbBlock.BULLET)
    }
    private val numbers = iconButton(R.drawable.nb_ic_format_list_numbered, R.string.novabird_format_numbers) {
        editor.toggleList(NbBlock.NUMBER)
    }
    private val more = TextView(context).apply {
        text = "Aa"
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, MORE_TEXT_SP)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(context.themeColor(MaterialR.attr.colorOnSurface))
        contentDescription = context.getString(R.string.novabird_format_more)
        background = buttonBackground(active = false, radius = dp(MORE_HEIGHT) / 2f, outlined = true)
        setOnClickListener { NbFormatPanel(context, editor).show() }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        setBackgroundColor(context.themeColor(MaterialR.attr.colorSurfaceContainer))
        setPadding(dp(PADDING), dp(PADDING), dp(PADDING), dp(PADDING))
        listOf(bold, italic, underline).forEach { addView(it, LayoutParams(dp(BUTTON), dp(BUTTON))) }
        addView(separator())
        listOf(bullets, numbers).forEach { addView(it, LayoutParams(dp(BUTTON), dp(BUTTON))) }
        addView(View(context), LayoutParams(0, 0, 1f))
        addView(more, LayoutParams(dp(MORE_WIDTH), dp(MORE_HEIGHT)).apply { marginEnd = dp(PADDING) })
        editor.onStateChanged = ::update
        update()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawLine(0f, 0f, width.toFloat(), 0f, dividerPaint)
    }

    private fun update() {
        isVisible = editor.isFocused && editor.isRichTextAvailable
        if (!isVisible) return
        val chars = editor.typingFormats.chars
        setActive(bold, CharFormat.BOLD in chars)
        setActive(italic, CharFormat.ITALIC in chars)
        setActive(underline, CharFormat.UNDERLINE in chars)
        val block = editor.paragraphAtCursor()?.block
        setActive(bullets, block == NbBlock.BULLET)
        setActive(numbers, block == NbBlock.NUMBER)
    }

    private fun setActive(view: View, active: Boolean) {
        if (view.isSelected == active && view.background != null) return
        view.isSelected = active
        view.background = buttonBackground(active, radius = dp(BUTTON_RADIUS).toFloat())
        val color = context.themeColor(
            if (active) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurface,
        )
        when (view) {
            is TextView -> view.setTextColor(color)
            is ImageView -> view.imageTintList = ColorStateList.valueOf(color)
        }
    }

    private fun textButton(label: String, @StringRes description: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, LETTER_TEXT_SP)
        contentDescription = context.getString(description)
        setOnClickListener { onClick() }
    }

    private fun iconButton(@DrawableRes icon: Int, @StringRes description: Int, onClick: () -> Unit) =
        ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = context.getString(description)
            setOnClickListener { onClick() }
        }

    private fun separator() = View(context).apply {
        setBackgroundColor(context.themeColor(MaterialR.attr.colorOutlineVariant))
        layoutParams = LayoutParams(dp(1), dp(SEPARATOR_HEIGHT)).apply {
            marginStart = dp(SEPARATOR_MARGIN)
            marginEnd = dp(SEPARATOR_MARGIN)
        }
    }

    private fun buttonBackground(active: Boolean, radius: Float, outlined: Boolean = false): Drawable {
        val shape = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (active) context.themeColor(MaterialR.attr.colorPrimaryContainer) else 0)
            if (outlined) setStroke(dp(1), context.themeColor(MaterialR.attr.colorOutline))
        }
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(0xFFFFFFFF.toInt())
        }
        val ripple = ColorStateList.valueOf(context.themeColor(android.R.attr.colorControlHighlight))
        return RippleDrawable(ripple, shape, mask)
    }

    private fun dp(value: Int) = context.dp(value)

    private companion object {
        const val BUTTON = 44
        const val BUTTON_RADIUS = 10
        const val PADDING = 4
        const val MORE_WIDTH = 52
        const val MORE_HEIGHT = 40
        const val SEPARATOR_HEIGHT = 24
        const val SEPARATOR_MARGIN = 4
        const val LETTER_TEXT_SP = 18f
        const val MORE_TEXT_SP = 16f
    }
}

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

internal fun Context.themeColor(@AttrRes attr: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attr, value, true)
    return value.data
}
