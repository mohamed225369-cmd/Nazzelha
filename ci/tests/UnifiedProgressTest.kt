package com.clipshelf.app

import org.junit.Assert.*
import org.junit.Test

class UnifiedProgressTest {
    private val v = Stream("137","mp4",1920,1080,30,"avc1","none",0.0,900,"")
    private val a = Stream("140","m4a",0,0,0,"none","aac",0.0,100,"")
    private fun job() = Job(Clip(1,"test","Test","https://youtu.be/abcdefghijk",10,streams=listOf(v,a)),
        Choice(videoId="137",audioId="140"),"Download/Nazzelha/",false)
    @Test fun twoStreamsAndMergeNeverReset() {
        val p=UnifiedProgress(job())
        val values=listOf(
            p.callback("NZP|137|450|900|NA|10|5|downloading")!!.percent!!,
            p.callback("NZP|137|900|900|NA|NA|NA|finished")!!.percent!!,
            p.callback("NZP|140|0|100|NA|10|10|downloading")!!.percent!!,
            p.callback("NZP|140|100|100|NA|NA|NA|finished")!!.percent!!,
            p.callback("[Merger] Merging formats")!!.percent!!,
            p.saving(TransferUpdate(Phase.SAVING,0,"Saving")).percent!!,
            p.saving(TransferUpdate(Phase.SAVING,99,"Saving")).percent!!
        )
        assertEquals(listOf(42,85,85,95,95,95,98), values)
        assertEquals(values.sorted(),values)
    }
    @Test fun unknownBytesDoNotInventCompletion() {
        val p=UnifiedProgress(job())
        assertEquals(0,p.callback("NZP|137|100|NA|NA|NA|NA|downloading")!!.percent)
        assertNull(p.callback("[youtube] Fetching webpage"))
    }
    @Test fun excludesSingleStreamEtaWhenOtherStreamPending() {
        val p=UnifiedProgress(job())
        assertNull(p.callback("NZP|137|100|900|NA|100|8|downloading")!!.metrics!!.etaSeconds)
    }
    @Test fun combinedFileHasSingleTransfer() {
        val combined = v.copy(acodec="aac")
        val j=job().copy(clip=job().clip.copy(streams=listOf(combined)),choice=Choice(videoId="137",combined=true))
        val p=UnifiedProgress(j)
        assertEquals(47,p.callback("NZP|137|450|900|NA|NA|NA|downloading")!!.percent)
        assertEquals(95,p.callback("NZP|137|900|900|NA|NA|NA|finished")!!.percent)
    }
}
