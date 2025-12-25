package org.jetbrains.plugins.template.gpu

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * FlowLayout that supports wrapping components to the next line based on the available width.
 *
 * Adapted from common Swing WrapLayout patterns.
 */
class WrapLayout(align: Int = FlowLayout.LEFT, hgap: Int = 6, vgap: Int = 4) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)
    override fun minimumLayoutSize(target: Container): Dimension {
        val minimum = layoutSize(target, false)
        minimum.width -= hgap + 1
        return minimum
    }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            val targetWidth = target.size.width
            val hgap = hgap
            val vgap = vgap
            val insets = target.insets
            val maxWidth = if (targetWidth > 0) targetWidth else {
                // If we don't know yet, try to use the viewport width when inside a scrollpane.
                val sp = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target) as? JScrollPane
                sp?.viewport?.width ?: Int.MAX_VALUE
            }

            val availableWidth = maxWidth - (insets.left + insets.right + hgap * 2)
            var dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            val count = target.componentCount
            for (i in 0 until count) {
                val m = target.getComponent(i)
                if (!m.isVisible) continue
                val d = if (preferred) m.preferredSize else m.minimumSize

                if (rowWidth == 0) {
                    rowWidth = d.width
                    rowHeight = d.height
                } else if (rowWidth + hgap + d.width <= availableWidth) {
                    rowWidth += hgap + d.width
                    rowHeight = maxOf(rowHeight, d.height)
                } else {
                    dim.width = maxOf(dim.width, rowWidth)
                    dim.height += rowHeight + vgap
                    rowWidth = d.width
                    rowHeight = d.height
                }
            }

            dim.width = maxOf(dim.width, rowWidth)
            dim.height += rowHeight
            dim.width += insets.left + insets.right + hgap * 2
            dim.height += insets.top + insets.bottom + vgap * 2
            return dim
        }
    }
}

