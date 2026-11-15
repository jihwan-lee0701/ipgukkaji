package io.ipgukkaji.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FreshnessTest {
    private val base = Fixtures.east

    @Test
    fun `마지막 확인일은 공식 안내·원격·현장·경로 확인일 중 최신`() {
        val e = base.copy(
            source = Source(SourceType.OFFICIAL_WEB, "https://example.org", "2026-01-10"),
            verification = Verification(VerificationStatus.FIELD_VERIFIED, fieldCheckedAt = "2026-03-02"),
            remoteChecks = listOf(RemoteCheck("카카오맵 로드뷰", "https://example.org/rv", "2025-08", "2026-02-01", null)),
        )
        assertEquals(LocalDate.of(2026, 3, 2), e.lastConfirmedAt)
    }

    @Test
    fun `183일 이내는 최신, 넘으면 재확인 대상`() {
        val e = base.copy(source = Source(SourceType.OFFICIAL_WEB, "https://example.org", "2026-11-15"))
        assertFalse(Freshness.isStale(e, LocalDate.of(2027, 5, 17))) // 183일
        assertTrue(Freshness.isStale(e, LocalDate.of(2027, 5, 18)))  // 184일
    }

    @Test
    fun `확인일이 없는 테스트 데이터는 경고하지 않음`() {
        assertNull(base.lastConfirmedAt)
        assertFalse(Freshness.isStale(base, LocalDate.of(2030, 1, 1)))
    }
}
