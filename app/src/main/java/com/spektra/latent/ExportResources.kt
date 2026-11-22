package com.spektra.latent

/** CPU budget only; never substitutes a proxy or changes export precision. */
internal object ExportResources {
    fun workers(cpus: Int, ramMiB: Long, lowRam: Boolean, pressure: Boolean, visible: Boolean,
                saver: Boolean, thermal: Int, conservativeBackground: Boolean): Int {
        val capacity = when {
            lowRam || ramMiB < 3_500 || cpus <= 4 -> 2
            ramMiB < 6_000 || cpus <= 6 -> 4
            ramMiB < 9_000 -> 6
            else -> 8
        }
        var limit = minOf(capacity, cpus.coerceAtLeast(1))
        if (!visible) limit = minOf(limit, if (capacity >= 6 && !conservativeBackground) 2 else 1)
        if (saver || thermal >= 2) limit = minOf(limit, if (visible) 2 else 1)
        if (pressure || thermal >= 3) limit = 1
        return limit
    }
}
