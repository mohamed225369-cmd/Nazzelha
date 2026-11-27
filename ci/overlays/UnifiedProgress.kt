package com.clipshelf.app

/** Progress for one output, not an individual video/audio stream. */
internal class UnifiedProgress(private val job: Job) {
    companion object {
        const val TEMPLATE = "download:NZP|%(info.format_id)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s|%(progress.status)s"
    }
    private val plan = MediaPlanner.plan(job.clip, job.choice)
    private val streams = listOfNotNull(plan?.video, plan?.audio)
    private val ids = streams.map { it.id }.ifEmpty { listOfNotNull(job.choice.videoId, job.choice.audioId).distinct() }
    private val weights: List<Double> = streams.map { MediaSizes.stream(it,job.clip.duration).bytes?.toDouble() ?: 0.0 }
        .let { if (it.isNotEmpty() && it.all { n -> n > 0 }) it else List(maxOf(1, ids.size)) { 1.0 } }
    private val fractions = DoubleArray(weights.size)
    private var last = 0
    private var activeIndex = 0
    private var activeFormat: String? = null
    fun callback(line: String): TransferUpdate? {
        val t = line.trim()
        if (listOf("[Merger]", "[ExtractAudio]", "[VideoConvertor]", "[VideoRemuxer]", "[Fixup").any { t.startsWith(it) }) {
            return TransferUpdate(Phase.PROCESSING, maxOf(last, 95).also { last = it },
                if (job.choice.mode == Mode.VIDEO && !job.choice.combined) "Combining audio and video" else "Processing media")
        }
        if (!t.startsWith("NZP|")) return null
        val a = t.split('|')
        if (a.size != 8) return null
        val format = a[1]
        val known = ids.indexOf(format)
        val index = if (known >= 0) known.coerceIn(fractions.indices) else {
            if (activeFormat != null && format != activeFormat && fractions[activeIndex] >= 1.0)
                activeIndex = minOf(activeIndex + 1, fractions.lastIndex)
            activeIndex
        }
        activeIndex = index
        activeFormat = format
        val total = a[3].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
            ?: a[4].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        val downloaded = a[2].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        val done = a[7] == "finished"
        if (done) fractions[index] = 1.0
        else if (total != null && downloaded != null)
            fractions[index] = maxOf(fractions[index], (downloaded / total).coerceIn(0.0, 0.999))
        val percent = (fractions.indices.sumOf { fractions[it] * weights[it] } / weights.sum() * 95).toInt().coerceIn(0,95)
        last = maxOf(last,percent)
        val speed = a[5].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { MediaSizes.bytes(it.toLong()) + "/s" }
        // Stream ETA is not an output ETA until all remaining streams are known.
        val eta = a[6].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && fractions.indices.all { i -> i == index || fractions[i] >= 1.0 } }?.toLong()
        return TransferUpdate(Phase.DOWNLOADING, last, "Downloading", TransferMetrics(speed,eta,null))
    }
    fun saving(update: TransferUpdate): TransferUpdate {
        last = maxOf(last, 95 + ((update.percent ?: 0) * 4 / 100))
        return update.copy(percent = last, message = "Saving")
    }
}
