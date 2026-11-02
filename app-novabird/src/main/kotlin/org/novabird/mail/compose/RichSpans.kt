package org.novabird.mail.compose

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan

/** Fonts besides the standard one (no span); [css] is what the HTML says, [family] what the phone shows. */
enum class NbFont(val family: String, val css: String) {
    SERIF("serif", "Georgia, 'Times New Roman', serif"),
    MONO("monospace", "'Courier New', Courier, monospace"),
}

/** Text sizes besides the normal one; [css] is a keyword every mail client knows. */
enum class NbSize(val factor: Float, val css: String) {
    SMALL(0.85f, "small"),
    LARGE(1.25f, "large"),
    HUGE(1.6f, "x-large"),
}

class NbFontSpan(val font: NbFont) : TypefaceSpan(font.family)

class NbSizeSpan(val size: NbSize) : RelativeSizeSpan(size.factor)

/** Paragraph kinds; a paragraph has at most one. */
enum class NbBlock { HEADING, SUBHEADING, QUOTE, BULLET, NUMBER }

/**
 * Marks one paragraph (from its start to after its line break) as heading, quote or list item. List items carry a
 * [level] for nesting; numbers are counted when drawn.
 */
class NbParagraphSpan(val block: NbBlock, val level: Int = 1) : MetricAffectingSpan(), LeadingMarginSpan {
    val isList get() = block == NbBlock.BULLET || block == NbBlock.NUMBER

    override fun updateMeasureState(paint: TextPaint) = apply(paint)

    override fun updateDrawState(paint: TextPaint) = apply(paint)

    private fun apply(paint: TextPaint) {
        val factor = when (block) {
            NbBlock.HEADING -> HEADING_FACTOR
            NbBlock.SUBHEADING -> SUBHEADING_FACTOR
            else -> return
        }
        paint.textSize *= factor
        paint.typeface = Typeface.create(paint.typeface, Typeface.BOLD)
    }

    override fun getLeadingMargin(first: Boolean): Int = when {
        isList -> (level * LIST_INDENT_DP * density).toInt()
        block == NbBlock.QUOTE -> (QUOTE_INDENT_DP * density).toInt()
        else -> 0
    }

    override fun drawLeadingMargin(
        canvas: Canvas,
        paint: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout?,
    ) {
        val spanned = text as? Spanned ?: return
        val savedColor = paint.color
        val savedStyle = paint.style
        when {
            block == NbBlock.QUOTE -> drawQuoteBar(canvas, paint, x, dir, top, bottom)
            !first || spanned.getSpanStart(this) != start -> Unit
            block == NbBlock.BULLET -> drawBullet(canvas, paint, x, dir, top, baseline)
            block == NbBlock.NUMBER -> drawNumber(canvas, paint, x, dir, baseline, spanned)
        }
        paint.color = savedColor
        paint.style = savedStyle
    }

    private fun drawQuoteBar(canvas: Canvas, paint: Paint, x: Int, dir: Int, top: Int, bottom: Int) {
        paint.style = Paint.Style.FILL
        paint.color = paint.color and QUOTE_BAR_ALPHA_MASK
        val left = x + dir * (QUOTE_BAR_OFFSET_DP * density)
        val right = left + dir * (QUOTE_BAR_WIDTH_DP * density)
        canvas.drawRect(minOf(left, right), top.toFloat(), maxOf(left, right), bottom.toFloat(), paint)
    }

    private fun drawBullet(canvas: Canvas, paint: Paint, x: Int, dir: Int, top: Int, baseline: Int) {
        val radius = BULLET_RADIUS_DP * density
        val centerX = x + dir * (getLeadingMargin(true) - BULLET_GAP_DP * density - radius)
        val centerY = (top + baseline) / 2f + radius
        // nested items get an open circle
        paint.style = if (level % 2 == 0) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = density
        canvas.drawCircle(centerX, centerY, radius, paint)
    }

    private fun drawNumber(canvas: Canvas, paint: Paint, x: Int, dir: Int, baseline: Int, text: Spanned) {
        val label = "${numberOf(text)}."
        val width = paint.measureText(label)
        val margin = getLeadingMargin(true)
        val gap = BULLET_GAP_DP * density
        val textX = if (dir > 0) x + margin - gap - width else x - margin + gap
        paint.style = Paint.Style.FILL
        canvas.drawText(label, textX, baseline.toFloat(), paint)
    }

    /** Position in its list: counts the numbered items of the same level right before this one. */
    fun numberOf(text: Spanned): Int {
        var number = 1
        var position = text.getSpanStart(this)
        while (position > 0) {
            val previousStart = text.lastIndexOf('\n', position - 2) + 1
            val previous = RichTextHtml.paragraphSpanAt(text, previousStart)?.takeIf { it.isList } ?: break
            if (previous.level < level) break
            if (previous.level == level) {
                if (previous.block != NbBlock.NUMBER) break
                number++
            }
            position = text.getSpanStart(previous)
        }
        return number
    }

    companion object {
        const val MAX_LEVEL = 4
        private const val HEADING_FACTOR = 1.45f
        private const val SUBHEADING_FACTOR = 1.2f
        private const val LIST_INDENT_DP = 24
        private const val QUOTE_INDENT_DP = 16
        private const val QUOTE_BAR_OFFSET_DP = 2
        private const val QUOTE_BAR_WIDTH_DP = 3
        private const val BULLET_RADIUS_DP = 2.5f
        private const val BULLET_GAP_DP = 8
        private const val QUOTE_BAR_ALPHA_MASK = 0x66FFFFFF
        private val density get() = Resources.getSystem().displayMetrics.density
    }
}
