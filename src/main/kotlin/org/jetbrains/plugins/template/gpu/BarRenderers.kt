package org.jetbrains.plugins.template.gpu

import com.intellij.ui.JBColor
import java.awt.*
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

class PercentBarRenderer(
    private val barColor: Color = JBColor( Color(67,160,71), Color(76,175,80) ),
) : JComponent(), TableCellRenderer {
    private var percent: Int = 0
    private var text: String = ""
    init { isOpaque = true }
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
    ): Component {
        percent = when (value) {
            is Number -> value.toInt().coerceIn(0, 100)
            is String -> value.toIntOrNull()?.coerceIn(0, 100) ?: 0
            else -> 0
        }
        text = "$percent%"
        background = if (isSelected) table.selectionBackground else table.background
        foreground = table.foreground
        return this
    }
    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val w = fm.stringWidth(text) + 24
        val h = super.getPreferredSize().height.coerceAtLeast(fm.height + 6)
        return Dimension(w, h)
    }
    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        val w = width
        val h = height
        // background
        g2.color = background
        g2.fillRect(0, 0, w, h)
        // bar
        val bw = (w * percent / 100.0).toInt()
        g2.color = barColor
        g2.fillRect(0, 0, bw, h - 1)
        // border
        g2.color = JBColor.border()
        g2.drawRect(0, 0, w - 1, h - 1)
        // text
        g2.color = JBColor.foreground()
        val fm = g2.fontMetrics
        val tx = (w - fm.stringWidth(text)) / 2
        val ty = (h + fm.ascent - fm.descent) / 2
        g2.drawString(text, tx, ty)
    }
}

class MemoryBarRenderer(
    private val barColor: Color = JBColor( Color(30,136,229), Color(33,150,243) ),
) : JComponent(), TableCellRenderer {
    private var used: Int = 0
    private var total: Int = 0
    private var text: String = ""
    init { isOpaque = true }
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
    ): Component {
        when (value) {
            is Pair<*, *> -> {
                used = (value.first as? Number)?.toInt() ?: 0
                total = (value.second as? Number)?.toInt() ?: 0
            }
            is String -> {
                // Fallback parse "123 / 456 MiB" or "1.2 / 4.0 GB"
                val nums = Regex("\\d+").findAll(value).map { it.value.toInt() }.toList()
                used = nums.getOrNull(0) ?: 0
                total = nums.getOrNull(1) ?: 0
            }
            else -> { used = 0; total = 0 }
        }
        if (total <= 0) total = 1
        val usedGb = used / 1024.0
        val totalGb = total / 1024.0
        text = String.format("%.1f / %.1f GB", usedGb, totalGb)
        background = if (isSelected) table.selectionBackground else table.background
        foreground = table.foreground
        return this
    }
    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val w = fm.stringWidth(text) + 32
        val h = super.getPreferredSize().height.coerceAtLeast(fm.height + 6)
        return Dimension(w, h)
    }
    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        val w = width
        val h = height
        g2.color = background
        g2.fillRect(0, 0, w, h)
        val percent = (used * 100.0 / total).coerceIn(0.0, 100.0)
        val bw = (w * percent / 100.0).toInt()
        g2.color = barColor
        g2.fillRect(0, 0, bw, h - 1)
        g2.color = JBColor.border()
        g2.drawRect(0, 0, w - 1, h - 1)
        g2.color = JBColor.foreground()
        val fm = g2.fontMetrics
        val tx = (w - fm.stringWidth(text)) / 2
        val ty = (h + fm.ascent - fm.descent) / 2
        g2.drawString(text, tx, ty)
    }
}
