package com.opendevtools.core.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.GeneralPath
import javax.swing.JComponent

/** One named line of a chart. [values] may contain nulls where the server reported no sample. */
class Series(val label: String, val values: List<Double?>, val color: Color)

/**
 * A compact area chart for the RRD statistics Proxmox serves. Drawing this directly avoids
 * pulling a charting library into a plugin that needs exactly one chart shape.
 */
class TimeSeriesChart(
    private val title: String,
    private val formatValue: (Double) -> String,
) : JComponent() {

    private var series: List<Series> = emptyList()
    private var upperBound: Double? = null

    init {
        preferredSize = Dimension(JBUI.scale(320), JBUI.scale(140))
        minimumSize = Dimension(JBUI.scale(160), JBUI.scale(100))
        isOpaque = false
    }

    /** [fixedUpperBound] pins the axis, which is what makes a percentage chart readable. */
    fun setData(data: List<Series>, fixedUpperBound: Double? = null) {
        series = data
        upperBound = fixedUpperBound
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val insets = JBUI.insets(20, 8, 18, 8)
            val plotX = insets.left
            val plotY = insets.top
            val plotWidth = width - insets.left - insets.right
            val plotHeight = height - insets.top - insets.bottom
            if (plotWidth <= 0 || plotHeight <= 0) return

            g2.color = UIUtil.getLabelForeground()
            g2.font = JBUI.Fonts.smallFont()
            g2.drawString(title, plotX, plotY - JBUI.scale(6))

            val samples = series.flatMap { it.values }.filterNotNull()
            val max = upperBound ?: samples.maxOrNull()?.takeIf { it > 0 } ?: 1.0

            drawGrid(g2, plotX, plotY, plotWidth, plotHeight, max)

            if (samples.isEmpty()) {
                g2.color = UIUtil.getInactiveTextColor()
                g2.drawString("no data", plotX + plotWidth / 2 - JBUI.scale(20), plotY + plotHeight / 2)
                return
            }

            series.forEach { drawSeries(g2, it, plotX, plotY, plotWidth, plotHeight, max) }
            drawLegend(g2, plotX, plotY + plotHeight + JBUI.scale(13))
        } finally {
            g2.dispose()
        }
    }

    private fun drawGrid(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int, max: Double) {
        g2.color = JBColor.border()
        g2.stroke = BasicStroke(1f)
        g2.drawLine(x, y + h, x + w, y + h)
        for (i in 1..GRID_LINES) {
            val lineY = y + h - h * i / GRID_LINES
            g2.color = JBColor(Color(0, 0, 0, 20), Color(255, 255, 255, 20))
            g2.drawLine(x, lineY, x + w, lineY)
        }
        g2.color = UIUtil.getInactiveTextColor()
        g2.font = JBUI.Fonts.miniFont()
        g2.drawString(formatValue(max), x + JBUI.scale(2), y + JBUI.scale(9))
    }

    private fun drawSeries(g2: Graphics2D, item: Series, x: Int, y: Int, w: Int, h: Int, max: Double) {
        val points = item.values
        if (points.size < 2) return
        val step = w.toDouble() / (points.size - 1)

        val line = GeneralPath()
        val area = GeneralPath()
        var started = false
        points.forEachIndexed { index, raw ->
            val value = raw ?: return@forEachIndexed
            val px = x + step * index
            val py = y + h - (value / max).coerceIn(0.0, 1.0) * h
            if (!started) {
                line.moveTo(px, py)
                area.moveTo(px, (y + h).toDouble())
                area.lineTo(px, py)
                started = true
            } else {
                line.lineTo(px, py)
                area.lineTo(px, py)
            }
        }
        if (!started) return
        area.lineTo(x + w.toDouble(), (y + h).toDouble())
        area.closePath()

        val previousComposite = g2.composite
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f)
        g2.color = item.color
        g2.fill(area)
        g2.composite = previousComposite

        g2.color = item.color
        g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(line)
    }

    private fun drawLegend(g2: Graphics2D, x: Int, y: Int) {
        g2.font = JBUI.Fonts.miniFont()
        var offset = x
        for (item in series) {
            val last = item.values.lastOrNull { it != null }
            val text = item.label + (last?.let { " " + formatValue(it) } ?: "")
            g2.color = item.color
            g2.fillRect(offset, y - JBUI.scale(7), JBUI.scale(7), JBUI.scale(7))
            offset += JBUI.scale(10)
            g2.color = UIUtil.getInactiveTextColor()
            g2.drawString(text, offset, y)
            offset += g2.fontMetrics.stringWidth(text) + JBUI.scale(12)
        }
    }

    companion object {
        private const val GRID_LINES = 4

        val CPU: Color = JBColor(0x3B82F6, 0x60A5FA)
        val MEMORY: Color = JBColor(0x8B5CF6, 0xA78BFA)
        val DISK_READ: Color = JBColor(0x059669, 0x34D399)
        val DISK_WRITE: Color = JBColor(0xDC2626, 0xF87171)
        val NET_IN: Color = JBColor(0x0891B2, 0x22D3EE)
        val NET_OUT: Color = JBColor(0xD97706, 0xFBBF24)
    }
}
