package com.clipshelf.app

import org.junit.Test
import org.junit.Assert.*

class EngineRecoveryPolicyTest {
    private val stale = "WARNING: Your yt-dlp version (2025.11.12) is older than 90 days!"
    private val extractor = "ERROR: [youtube] Unable to extract signature; please update yt-dlp"
    @Test fun staleWarningAloneNotFailure() { assertFalse(EngineRecoveryPolicy.shouldUpdate(stale)) }
    @Test fun staleAnd403CanRecover() { assertTrue(EngineRecoveryPolicy.shouldUpdate("$stale\nERROR: unable to download video data: HTTP Error 403: Forbidden")) }
    @Test fun extractorFailureCanRecover() { assertTrue(EngineRecoveryPolicy.shouldUpdate(extractor)) }
    @Test fun noUpdateOnUnavailableVideo() { assertFalse(EngineRecoveryPolicy.shouldUpdate("$stale\nERROR: Private video")) }
    @Test fun noUpdateOnTimeout() { assertFalse(EngineRecoveryPolicy.shouldUpdate("$stale\nERROR: Connection timed out")) }
    @Test fun noUpdateOnRateLimit() { assertFalse(EngineRecoveryPolicy.shouldUpdate("$stale\nERROR: HTTP Error 429: Too Many Requests")) }
    @Test fun noUpdateOnMissingFormat() { assertFalse(EngineRecoveryPolicy.shouldUpdate("$stale\nERROR: Requested format is not available")) }
    @Test fun noUpdateOnStorageError() { assertFalse(EngineRecoveryPolicy.shouldUpdate("$stale\nERROR: No space left on device")) }
    @Test fun normalSuccessDoesNotUpdate() {
        var updates = 0
        assertEquals("ok", EngineRecoveryPolicy.once({ false }, { updates++; true }, {}) { "ok" })
        assertEquals(0, updates)
    }
    @Test fun updateAndRetryOnce() {
        var attempts = 0; var updates = 0; var notices = 0
        val result = EngineRecoveryPolicy.once({ false }, { updates++; true }, { notices++ }) {
            if (++attempts == 1) throw EngineCommandFailure(extractor)
            "saved"
        }
        assertEquals("saved", result); assertEquals(2, attempts); assertEquals(1, updates); assertEquals(1, notices)
    }
    @Test fun secondFailureDoesNotLoop() {
        var attempts = 0; var updates = 0
        try { EngineRecoveryPolicy.once({ false }, { updates++; true }, {}) { attempts++; throw EngineCommandFailure(extractor) }; fail() }
        catch (e: EngineCommandFailure) { assertTrue(e.message.orEmpty().contains("retry failed")) }
        assertEquals(2, attempts); assertEquals(1, updates)
    }
    @Test fun noRetryWhenNoUpdateInstalled() {
        var attempts = 0
        try { EngineRecoveryPolicy.once({ false }, { false }, {}) { attempts++; throw EngineCommandFailure(extractor) }; fail() }
        catch (e: EngineCommandFailure) { assertTrue(e.message.orEmpty().contains("No newer engine")) }
        assertEquals(1, attempts)
    }
    @Test fun cancellationAfterUpdatePreventsRetry() {
        var canceled = false; var attempts = 0
        try { EngineRecoveryPolicy.once({ canceled }, { canceled = true; true }, {}) { attempts++; throw EngineCommandFailure(extractor) }; fail() }
        catch (_: InterruptedException) {}
        assertEquals(1, attempts)
    }
    @Test fun failedUpdatePreservesRealError() {
        var attempts = 0
        try { EngineRecoveryPolicy.once({ false }, { throw IllegalStateException("checksum mismatch") }, {}) { attempts++; throw EngineCommandFailure(extractor) }; fail() }
        catch (e: EngineCommandFailure) {
            assertTrue(e.message.orEmpty().contains(extractor)); assertTrue(e.message.orEmpty().contains("checksum mismatch"))
        }
        assertEquals(1, attempts)
    }
    @Test fun summaryPrefersActualError() { assertEquals("ERROR: HTTP Error 403", EngineRecoveryPolicy.summary("$stale\nERROR: HTTP Error 403")) }
}
