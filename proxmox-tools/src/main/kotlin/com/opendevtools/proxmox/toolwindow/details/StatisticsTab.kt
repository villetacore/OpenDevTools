package com.opendevtools.proxmox.toolwindow.details

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.opendevtools.core.ui.listRenderer
import com.opendevtools.core.format.Formats
import com.opendevtools.core.ui.Series
import com.opendevtools.core.ui.TimeSeriesChart
import com.opendevtools.proxmox.model.RrdConsolidation
import com.opendevtools.proxmox.model.RrdSeries
import com.opendevtools.proxmox.model.RrdTimeframe
import com.opendevtools.proxmox.service.ProxmoxService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JPanel

/**
 * The RRD graphs Proxmox keeps for every node and guest: CPU, memory, disk I/O and network.
 * The timeframe selector maps one-to-one onto the API's `timeframe` parameter.
 */
class StatisticsTab(project: Project) : DetailTab(project, "Statistics", AllIcons.Actions.Profile) {

    private val timeframe = JComboBox(DefaultComboBoxModel(RrdTimeframe.entries.toTypedArray())).apply {
        renderer = listRenderer("") { it.title }
    }
    private val consolidation =
        JComboBox(DefaultComboBoxModel(RrdConsolidation.entries.toTypedArray())).apply {
            renderer = listRenderer("") { it.title }
        }

    private val cpuChart = TimeSeriesChart("CPU usage") { Formats.percent(it) }
    private val memoryChart = TimeSeriesChart("Memory") { Formats.bytes(it.toLong()) }
    private val diskChart = TimeSeriesChart("Disk I/O") { Formats.bytes(it.toLong()) + "/s" }
    private val networkChart = TimeSeriesChart("Network") { Formats.bytes(it.toLong()) + "/s" }

    private val status = JBLabel().apply { border = JBUI.Borders.empty(2, 8) }

    private var target: DetailTarget = DetailTarget.None

    init {
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            add(JBLabel("Timeframe:"))
            add(timeframe)
            add(JBLabel("Function:"))
            add(consolidation)
            add(status)
        }
        val charts = JPanel(GridLayout(0, 1, 0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(6)
            add(cpuChart)
            add(memoryChart)
            add(diskChart)
            add(networkChart)
        }
        add(controls, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(charts, true), BorderLayout.CENTER)

        val reload = { _: java.awt.event.ActionEvent -> loadData(target) }
        timeframe.addActionListener(reload)
        consolidation.addActionListener(reload)
    }

    override fun supports(target: DetailTarget): Boolean =
        target is DetailTarget.Guest || target is DetailTarget.Node

    override fun loadData(target: DetailTarget) {
        this.target = target
        val frame = timeframe.selectedItem as RrdTimeframe
        val cf = consolidation.selectedItem as RrdConsolidation

        val loader: (() -> RrdSeries)? = when (target) {
            is DetailTarget.Guest -> {
                { ProxmoxService.getInstance().guestRrd(target.guest, frame, cf) }
            }

            is DetailTarget.Node -> {
                { ProxmoxService.getInstance().nodeRrd(target.node.name, frame, cf) }
            }

            else -> null
        }
        if (loader == null) {
            render(RrdSeries.EMPTY)
            return
        }
        status.text = "Loading..."
        load(
            compute = loader,
            onError = { status.text = it },
            onSuccess = {
                status.text = if (it.isEmpty()) "No data" else "${it.samples.size} samples"
                render(it)
            },
        )
    }

    private fun render(series: RrdSeries) {
        // Proxmox reports CPU as a fraction of the whole guest, so the axis is pinned at 100%.
        cpuChart.setData(
            listOf(Series("CPU", series.values(RrdSeries.CPU), TimeSeriesChart.CPU)),
            fixedUpperBound = 1.0,
        )

        val maxMemory = series.latest(RrdSeries.MAX_MEMORY)
        memoryChart.setData(
            listOf(Series("used", series.values(RrdSeries.MEMORY), TimeSeriesChart.MEMORY)),
            fixedUpperBound = maxMemory,
        )

        diskChart.setData(
            listOf(
                Series("read", series.values(RrdSeries.DISK_READ), TimeSeriesChart.DISK_READ),
                Series("write", series.values(RrdSeries.DISK_WRITE), TimeSeriesChart.DISK_WRITE),
            ),
        )

        networkChart.setData(
            listOf(
                Series("in", series.values(RrdSeries.NET_IN), TimeSeriesChart.NET_IN),
                Series("out", series.values(RrdSeries.NET_OUT), TimeSeriesChart.NET_OUT),
            ),
        )
    }
}
