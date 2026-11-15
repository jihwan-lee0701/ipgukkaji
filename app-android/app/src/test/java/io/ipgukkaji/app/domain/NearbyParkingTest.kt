package io.ipgukkaji.app.domain

import io.ipgukkaji.app.data.KakaoNearbyParkingSource
import io.ipgukkaji.app.domain.Fixtures.dest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyParkingTest {
    private class FakeSource(override val available: Boolean = true) : NearbyParkingSource {
        override val providerLabel = "테스트"
        var calls = 0
        var pending: ((Result<List<NearbyParking>>) -> Unit)? = null
        override fun search(center: GeoPoint, radiusM: Int, callback: (Result<List<NearbyParking>>) -> Unit) {
            calls++; pending = callback
        }
    }

    private val lot = NearbyParking("다른병원 주차장", "가상 주소", GeoPoint(37.6003, 127.2002), 40)
    private val other = dest("다른병원", GeoPoint(37.6, 127.2))

    private fun setup(src: FakeSource): Pair<FakeNavi, EntranceController> {
        val navi = FakeNavi()
        val c = EntranceController(navi, Fixtures.catalog, ManualScheduler(), {}, nearbySource = src)
        navi.destinationToReturn = other
        c.start()
        return navi to c
    }

    @Test
    fun `미등록 목적지에서 사용자가 요청할 때만 검색하고 결과를 화면 상태로만 보여줌`() {
        val src = FakeSource()
        val (_, c) = setup(src)
        assertEquals(0, src.calls) // 자동 검색하지 않음
        c.searchNearbyParking()
        assertEquals(NearbyState.Loading, (c.state.screen as Screen.NotRegistered).nearby)
        src.pending!!(Result.success(listOf(lot)))
        val s = c.state.screen as Screen.NotRegistered
        assertEquals(NearbyState.Loaded(listOf(lot), "테스트"), s.nearby)
    }

    @Test
    fun `키가 없으면 기능을 숨기고 호출하지 않음`() {
        val src = FakeSource(available = false)
        val (_, c) = setup(src)
        assertFalse(c.nearbyAvailable)
        c.searchNearbyParking()
        assertEquals(0, src.calls)
    }

    @Test
    fun `목적지가 바뀐 뒤 도착한 결과는 버림`() {
        val src = FakeSource()
        val (navi, c) = setup(src)
        c.searchNearbyParking()
        navi.events!!.onDestination(dest("또다른곳", GeoPoint(37.7, 127.3)))
        src.pending!!(Result.success(listOf(lot)))
        assertEquals(NearbyState.Idle, (c.state.screen as Screen.NotRegistered).nearby)
    }

    @Test
    fun `고른 주차장으로 경로 요청 후 같은 목적지가 돌아오면 안내 중으로 인식, 재요청 없음`() {
        val src = FakeSource()
        val (navi, c) = setup(src)
        c.searchNearbyParking(); src.pending!!(Result.success(listOf(lot)))
        assertTrue(c.confirmRouteToNearby(0))
        assertEquals(lot.point, navi.routeRequests.single().point)
        navi.events!!.onRouteStarted(300, 1200)
        navi.destinationToReturn = dest("다른병원 주차장", lot.point)
        navi.events!!.onDestinationChanged()
        c.refreshDestination()
        assertEquals(Screen.GuidingNearby(lot), c.state.screen)
        assertEquals(1, navi.routeRequests.size)
    }

    @Test
    fun `주행 규제 중에는 주변 주차장으로도 경로를 바꾸지 않음`() {
        val src = FakeSource()
        val (navi, c) = setup(src)
        c.searchNearbyParking(); src.pending!!(Result.success(listOf(lot)))
        c.setRestricted(true)
        assertFalse(c.confirmRouteToNearby(0))
        assertTrue(navi.routeRequests.isEmpty())
    }

    @Test
    fun `카카오 응답 파싱 - 좌표는 x=경도 y=위도`() {
        val body = """{"documents":[{"place_name":"분당서울대학교병원 주차장","road_address_name":"경기 성남시 분당구 구미로173번길 82","address_name":"","x":"127.1253","y":"37.3466","distance":"35"}],"meta":{}}"""
        val r = KakaoNearbyParkingSource.parse(body)
        assertEquals(1, r.size)
        assertEquals(GeoPoint(37.3466, 127.1253), r[0].point)
        assertEquals(35, r[0].distanceM)
    }
}
