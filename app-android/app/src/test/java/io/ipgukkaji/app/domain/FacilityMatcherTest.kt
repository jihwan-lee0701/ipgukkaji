package io.ipgukkaji.app.domain

import io.ipgukkaji.app.domain.Fixtures.catalog
import io.ipgukkaji.app.domain.Fixtures.dest
import io.ipgukkaji.app.domain.Fixtures.hospitalCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FacilityMatcherTest {
    private val matcher = FacilityMatcher(catalog)

    @Test
    fun `POI ID가 같으면 연결`() {
        val r = matcher.match(dest("아무 이름", GeoPoint(37.5010, 127.0010), "POI-H1", "42dot"), null)
        assertEquals(MatchResult.Matched(Fixtures.hospital, MatchBasis.POI_ID), r)
    }

    @Test
    fun `이름과 거리가 모두 맞으면 연결 - 공백과 별칭 허용`() {
        val r = matcher.match(dest("가상대병원 본관", GeoPoint(37.5020, 127.0010)), null)
        assertEquals(MatchResult.Matched(Fixtures.hospital, MatchBasis.NAME_AND_DISTANCE), r)
        val r2 = matcher.match(dest("가상 대학교 병원(본원)", hospitalCenter), null)
        assertEquals(MatchResult.Matched(Fixtures.hospital, MatchBasis.NAME_AND_DISTANCE), r2)
    }

    @Test
    fun `이름만 같고 멀리 있으면 연결하지 않음 - 분원 오연결 방지`() {
        val r = matcher.match(dest("가상대학교병원 별관센터", Fixtures.branchLocation), null)
        assertEquals(MatchResult.NoMatch(NoMatchReason.NAME_MATCH_BUT_FAR), r)
    }

    @Test
    fun `제외 키워드가 있으면 가까워도 이름 일치로 보지 않음`() {
        val r = matcher.match(dest("분당가상대학교병원", hospitalCenter), null)
        assertTrue(r is MatchResult.NearbyOnly)
    }

    @Test
    fun `가깝지만 이름이 다르면 묻기만 함`() {
        val r = matcher.match(dest("병원앞약국", GeoPoint(37.5015, 127.0005)), null)
        assertTrue(r is MatchResult.NearbyOnly)
    }

    @Test
    fun `분당서울대병원 등록 시 서울대병원 본원 목적지는 연결하지 않음`() {
        val snubh = Fixtures.hospital.copy(
            id = "snubh", dataKind = DataKind.REAL, officialName = "분당서울대학교병원",
            aliases = listOf("분당서울대병원"), excludeKeywords = emptyList(),
            center = GeoPoint(37.3493053, 127.1241745), matchRadiusM = 500.0, naviPoiIds = emptyList(),
        )
        val m = FacilityMatcher(EntranceCatalog(listOf(snubh), emptyList()))
        // 본원(종로구) 이름은 "분당서울대학교병원"을 포함하지 않고 거리도 멀다
        assertEquals(
            MatchResult.NoMatch(NoMatchReason.NOT_REGISTERED),
            m.match(dest("서울대학교병원", GeoPoint(37.5796, 126.9990)), null),
        )
        assertEquals(
            MatchResult.Matched(snubh, MatchBasis.NAME_AND_DISTANCE),
            m.match(dest("분당서울대학교병원", GeoPoint(37.3496, 127.1238)), null),
        )
        assertEquals(
            MatchResult.Matched(snubh, MatchBasis.NAME_AND_DISTANCE),
            m.match(dest("분당서울대병원 장례식장", GeoPoint(37.3475, 127.1241)), null),
        )
    }

    @Test
    fun `등록되지 않은 목적지`() {
        val r = matcher.match(dest("다른병원", GeoPoint(37.6, 127.2)), null)
        assertEquals(MatchResult.NoMatch(NoMatchReason.NOT_REGISTERED), r)
    }

    @Test
    fun `앱이 요청한 입구가 목적지면 OwnEntrance - 좌표 또는 이름`() {
        val byPoint = matcher.match(dest("내비가 붙인 이름", GeoPoint(37.50002, 127.00152)), "east")
        assertEquals(MatchResult.OwnEntrance(Fixtures.hospital, Fixtures.east), byPoint)
        // 내비가 좌표를 도로로 60m 옮겨도 우리가 보낸 poiName 이 돌아오면 같은 요청으로 본다
        val byName = matcher.match(dest("가상대학교병원 east 입구", GeoPoint(37.5000, 127.0022)), "east")
        assertEquals(MatchResult.OwnEntrance(Fixtures.hospital, Fixtures.east), byName)
    }
}
