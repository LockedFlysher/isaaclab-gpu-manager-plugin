package org.jetbrains.plugins.template.gpu

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import kotlin.math.roundToInt

class GpuUsageIcon(private val index: Int) : Icon {

    @Volatile var utilPercent: Int = 0
    @Volatile var memUsedMiB: Int = 0
    @Volatile var memTotalMiB: Int = 0

    private val w = 64
    private val h = 18

    override fun getIconWidth(): Int = w
    override fun getIconHeight(): Int = h

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val bg = JBColor(0xF2F2F2, 0x3C3F41)
            val border = JBColor(0xC9C9C9, 0x5A5D60)
            g2.color = bg
            g2.fillRoundRect(x, y, w, h, 6, 6)
            g2.color = border
            g2.drawRoundRect(x, y, w - 1, h - 1, 6, 6)

            val innerX = x + 20
            val innerW = w - 22
            val padY = 3
            val barH = 5
            val gap = 2

            val util = utilPercent.coerceIn(0, 100)
            val memPct = if (memTotalMiB > 0) ((memUsedMiB.toDouble() / memTotalMiB.toDouble()) * 100.0).roundToInt().coerceIn(0, 100) else 0

            fun colorFor(p: Int): Color = when {
                p < 30 -> JBColor(0x4CAF50, 0x6CC070)
                p < 70 -> JBColor(0xFFB300, 0xD9A441)
                else -> JBColor(0xE53935, 0xE06666)
            }

            // util bar (top)
            g2.color = JBColor(0xDADADA, 0x2B2D30)
            g2.fillRoundRect(innerX, y + padY, innerW, barH, 4, 4)
            g2.color = colorFor(util)
            g2.fillRoundRect(innerX, y + padY, (innerW * util / 100.0).roundToInt().coerceIn(0, innerW), barH, 4, 4)

            // mem bar (bottom)
            val by = y + padY + barH + gap
            g2.color = JBColor(0xDADADA, 0x2B2D30)
            g2.fillRoundRect(innerX, by, innerW, barH, 4, 4)
            g2.color = colorFor(memPct)
            g2.fillRoundRect(innerX, by, (innerW * memPct / 100.0).roundToInt().coerceIn(0, innerW), barH, 4, 4)

            // index label
            val oldFont = g2.font
            g2.font = oldFont.deriveFont(Font.BOLD, 11f)
            g2.color = JBColor(0x404040, 0xD0D0D0)
            val s = index.toString()
            val fm = g2.fontMetrics
            val tx = x + 6
            val ty = y + (h + fm.ascent - fm.descent) / 2
            g2.drawString(s, tx, ty)
            g2.font = oldFont
        } finally {
            g2.dispose()
        }
    }
}

