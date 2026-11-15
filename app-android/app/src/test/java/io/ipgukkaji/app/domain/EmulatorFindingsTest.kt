package io.ipgukkaji.app.domain

import io.ipgukkaji.app.domain.Fixtures.dest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-14 Pleos 에뮬레이터에서 관찰한 동작을 반영한 테스트 */
class EmulatorFindingsTest {

    @Test
    fun `onDestinationChanged 없이도 경로 완료 후 목적지를 재조회해 앱이 바꾼 입구로 인식`() {
        val navi = FakeNavi()
        val scheduler = ManualScheduler()
        val c = EntranceController(navi, Fixtures.catalog, scheduler, {})
        navi.destinationToReturn = dest("가상대학교병원", GeoPoint(37.5003, 127.0002))
        c.start()
        c.confirmRouteTo("east")
        navi.destinationToReturn = dest("가상대학교병원 east 입구", Fixtures.east.routeTarget)
        navi.events!!.onRoutingCompleted() // onDestinationChanged 는 오지 않음
        scheduler.advance(300)
        assertEquals(Screen.GuidingOwnEntrance(Fixtures.hospital, Fixtures.east), c.state.screen)
        assertEquals(1, navi.routeRequests.size)
    }

    @Test
    fun `기존 경로 취소 후 onRouteStarted 만 오고 완료 이벤트가 없어도 안내 시작으로 확정, 타임아웃 없음`() {
        val navi = FakeNavi()
        val scheduler = ManualScheduler()
        val c = EntranceController(navi, Fixtures.catalog, scheduler, {})
        navi.destinationToReturn = dest("가상대학교병원", GeoPoint(37.5003, 127.0002))
        c.start()
        c.confirmRouteTo("east")
        navi.events!!.onRouteCancelled() // 에뮬레이터: 기존 경로 취소가 먼저 온다
        navi.events!!.onDestination(Fixtures.noDestination) // 이어서 빈 목적지가 잠시 온다
        assertTrue(c.state.screen is Screen.FacilityEntrances)
        scheduler.advance(8_600)
        navi.destinationToReturn = dest("가상대학교병원 east 입구", Fixtures.east.routeTarget)
        navi.events!!.onRouteStarted(480, 2900)
        scheduler.advance(60_000)
        assertEquals(RouteStatus.Started(Fixtures.east, 480, 2900), c.state.route)
        assertEquals(Screen.GuidingOwnEntrance(Fixtures.hospital, Fixtures.east), c.state.screen)
        // 안내 시작 후에는 다른 입구를 다시 고를 수 있다
        c.showOtherEntrances()
        assertTrue(c.confirmRouteTo("north"))
    }

    @Test
    fun `테스트 시설 이동은 입구 상대 위치를 유지하고 REAL 시설은 거부`() {
        val moved = Fixtures.catalog.withTestFacilityMovedTo("h1", GeoPoint(37.41, 127.10))
        val e = moved.entrance("east")!!
        assertEquals(37.41, moved.facility("h1")!!.center.lat, 1e-9)
        assertEquals(127.10 + 0.0015, e.routeTarget.lon, 1e-9)

        val real = Fixtures.catalog.copy(
            facilities = listOf(Fixtures.hospital.copy(dataKind = DataKind.REAL)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            real.withTestFacilityMovedTo("h1", GeoPoint(0.0, 0.0))
        }
    }

    @Test
    fun `카탈로그 교체는 진행 중 요청이 있으면 거부`() {
        val navi = FakeNavi()
        val c = EntranceController(navi, Fixtures.catalog, ManualScheduler(), {})
        navi.destinationToReturn = dest("가상대학교병원", GeoPoint(37.5003, 127.0002))
        c.start()
        c.confirmRouteTo("east")
        assertTrue(!c.replaceCatalog(Fixtures.catalog))
    }
}
