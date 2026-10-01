package com.opendevtools.proxmox.model

/** Time window of an RRD query; the same set of choices the web UI's graph selector offers. */
enum class RrdTimeframe(val api: String, val title: String) {
    HOUR("hour", "Last hour"),
    DAY("day", "Last day"),
    WEEK("week", "Last week"),
    MONTH("month", "Last month"),
    YEAR("year", "Last year"),
}

/** RRD consolidation function: an average smooths, a maximum keeps spikes visible. */
enum class RrdConsolidation(val api: String, val title: String) {
    AVERAGE("AVERAGE", "Average"),
    MAX("MAX", "Maximum"),
}

/**
 * A parsed `rrddata` response: a list of samples, each a map of metric name to value.
 * Values are nullable because RRD leaves gaps where nothing was recorded.
 */
class RrdSeries(val samples: List<Map<String, Double?>>) {

    fun values(metric: String): List<Double?> = samples.map { it[metric] }

    fun latest(metric: String): Double? = samples.lastOrNull { it[metric] != null }?.get(metric)

    fun isEmpty(): Boolean = samples.isEmpty()

    companion object {
        val EMPTY = RrdSeries(emptyList())

        /** Metric names Proxmox uses in node and guest RRD payloads. */
        const val CPU = "cpu"
        const val MAX_CPU = "maxcpu"
        const val MEMORY = "mem"
        const val MAX_MEMORY = "maxmem"
        const val DISK = "disk"
        const val MAX_DISK = "maxdisk"
        const val DISK_READ = "diskread"
        const val DISK_WRITE = "diskwrite"
        const val NET_IN = "netin"
        const val NET_OUT = "netout"
        const val LOAD = "loadavg"
        const val IO_WAIT = "iowait"
        const val ROOT_USED = "rootused"
        const val ROOT_TOTAL = "roottotal"
        const val SWAP_USED = "swapused"
        const val SWAP_TOTAL = "swaptotal"
    }
}
