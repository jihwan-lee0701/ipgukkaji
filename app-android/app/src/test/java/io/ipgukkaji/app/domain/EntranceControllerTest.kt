package io.ipgukkaji.app.domain

import io.ipgukkaji.app.domain.Fixtures.dest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 가짜 내비(FakeNavi)로 상태 전이만 검증한다. 실제 NaviHelper 연동 검증을 대신하지 않는다.
 */
class EntranceControllerTest {
    private lateinit var navi: FakeNavi
    private lateinit var scheduler: ManualScheduler
    private lateinit var controller: EntranceController
    private val renders = mutableListOf<UiState>()

    private val hospitalDest = dest("가상대학교병원", GeoPoint(37.5003, 127.0002))

    @Before
    fun setUp() {
        navi = FakeNavi()
        scheduler = ManualScheduler()
        renders.clear()
        controller = EntranceController(navi, Fixtures.catalog, scheduler, { renders += it })
    }

    private val screen get() = controller.state.screen
    private val route get() = controller.state.route

    private fun startAt(d: Destination?) {
        navi.destinationToReturn = d
        controller.start()
    }

    // ---- 연결

    @Test
    fun `연결 실패를 표시`() {
        navi.connectResult = IllegalStateException("no service")
        controller.start()
        assertEquals(Screen.ConnectFailed("no service"), screen)
    }

    // ---- 목적지 없음

    @Test
    fun `목적지 응답이 없으면 5초 뒤 목적지 없음`() {
        startAt(null)
        assertEquals(Screen.LoadingDestination, screen)
        scheduler.advance(4_999)
        assertEquals(Screen.LoadingDestination, screen)
        scheduler.advance(1)
        assertEquals(Screen.NoDestination, screen)
    }

    @Test
    fun `0 좌표 목적지는 목적지 없음`() {
        startAt(Fixtures.noDestination)
        assertEquals(Screen.NoDestination, screen)
    }

    // ---- 입구 데이터 없음

    @Test
    fun `등록되지 않은 목적지는 정보 없음 표시, 경로 요청 없음`() {
        startAt(dest("다른병원", GeoPoint(37.6, 127.2)))
        assertEquals(Screen.NotRegistered("다른병원", NoMatchReason.NOT_REGISTERED), screen)
        assertTrue(navi.routeRequests.isEmpty())
    }

    // ---- 정상 흐름

    @Test
    fun `목적지 조회 - 입구 선택 - 경로 요청 - 시작 - 완료`() {
        startAt(hospitalDest)
        val s = screen as Screen.FacilityEntrances
        assertEquals("north", s.entrances.first().entrance.id)

        assertTrue(controller.confirmRouteTo("east"))
        assertEquals(1, navi.routeRequests.size)
        val sent = navi.routeRequests.single()
        assertEquals(Fixtures.east.routeTarget, sent.point)
        assertEquals(Fixtures.east.gate, sent.displayPoint)
        // 요청 성공 ≠ 탐색 완료
        assertTrue(route is RouteStatus.Requested)

        navi.events!!.onRouteStarted(durationS = 600, distanceM = 4200)
        assertEquals(RouteStatus.Started(Fixtures.east, 600, 4200), route)

        navi.events!!.onRoutingCompleted()
        assertEquals(RouteStatus.Completed(Fixtures.east, 600, 4200), route)
    }

    @Test
    fun `사용자 확인 없이는 경로를 바꾸지 않는다`() {
        startAt(hospitalDest)
        navi.events!!.onDestinationChanged()
        scheduler.advance(10_000)
        assertTrue(navi.routeRequests.isEmpty())
    }

    @Test
    fun `방문객 진입 불가 입구는 요청 불가`() {
        startAt(hospitalDest)
        assertFalse(controller.confirmRouteTo("staff"))
        assertTrue(navi.routeRequests.isEmpty())
    }

    // ---- 목적지 변경 / 루프 방지

    @Test
    fun `앱이 바꾼 목적지를 다시 감지해도 재요청하지 않음`() {
        startAt(hospitalDest)
        controller.confirmRouteTo("east")
        navi.events!!.onRouteStarted(600, 4200)
        navi.events!!.onRoutingCompleted()

        // 내비가 목적지 변경 이벤트를 여러 번 보내고, 새 목적지는 우리가 요청한 입구
        navi.destinationToReturn = dest("가상대학교병원 east 입구", Fixtures.east.routeTarget)
        repeat(3) {
            navi.events!!.onDestinationChanged()
            scheduler.advance(400)
        }
        assertEquals(Screen.GuidingOwnEntrance(Fixtures.hospital, Fixtures.east), screen)
        assertEquals(1, navi.routeRequests.size)
        assertTrue(route is RouteStatus.Completed)
    }

    @Test
    fun `목적지 변경 이벤트는 디바운스되어 한 번만 재조회`() {
        startAt(hospitalDest)
        val before = navi.destinationRequests
        repeat(5) { navi.events!!.onDestinationChanged() }
        scheduler.advance(300)
        assertEquals(before + 1, navi.destinationRequests)
    }

    @Test
    fun `사용자가 다른 목적지로 바꾸면 새 목적지로 갱신하고 입구 추적 해제`() {
        startAt(hospitalDest)
        controller.confirmRouteTo("east")
        navi.events!!.onRoutingCompleted()

        navi.destinationToReturn = dest("다른병원", GeoPoint(37.6, 127.2))
        navi.events!!.onDestinationChanged()
        scheduler.advance(300)
        assertEquals(Screen.NotRegistered("다른병원", NoMatchReason.NOT_REGISTERED), screen)
        assertEquals(RouteStatus.Idle, route)
    }

    @Test
    fun `요청 진행 중 옛 목적지가 잠깐 보고돼도 진행 상태 유지`() {
        startAt(hospitalDest)
        controller.confirmRouteTo("east")
        navi.events!!.onDestinationChanged()
        scheduler.advance(300) // 아직 병원 목적지를 돌려줌
        assertTrue(route is RouteStatus.Requested)
        navi.events!!.onRoutingCompleted()
        assertTrue(route is RouteStatus.Completed)
    }

    // ---- 경로 요청 실패

    @Test
    fun `onError 코드별 실패`() {
        startAt(hospitalDest)
        controller.confirmRouteTo("east")
        navi.events!!.onNaviError("CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE")
        assertEquals(RouteStatus.Failed(Fixtures.east, RouteFailure.TOO_SHORT_DISTANCE), route)
        // 실패 후 다시 선택 가능
        assertTrue(controller.confirmRouteTo("north"))
    }

    @Test
    fun `requestRoute 예외는 즉시 실패, 권한 예외 구분`() {
        startAt(hospitalDest)
        navi.throwOnRoute = SecurityException("denied")
        assertFalse(controller.confirmRouteTo("east"))
        assertEquals(RouteStatus.Failed(Fixtures.east, RouteFailure.PERMISSION_DENIED), route)
    }

    @Test
    fun `15초 내 결과 이벤트가 없으면 결과 확인 불가, 늦은 완료는 반영`() {
        startAt(hospitalDest)
        controller.confirmRouteTo("east")
        scheduler.advance(15_000)
        assertEquals(RouteStatus.TimedOut(Fixtures.east), route)
        navi.events!!.onRoutingCompleted()
        assertEquals(RouteStatus.Completed(Fixtures.east, null, null), route)
    }

    @Test
    fun `요청 직후 취소 이벤트는 실패로 단정하지 않음`() {
        startAt(hospitalDest)
        controller.confirmRouteTo("east")
        navi.events!!.onRouteCancelled()
        assertTrue(route is RouteStatus.Requested)
        navi.events!!.onRouteStarted(60, 300)
        navi.events!!.onRouteCancelled()
        assertEquals(RouteStatus.Cancelled(Fixtures.east), route)
    }

    @Test
    fun `진행 중 중복 요청 차단`() {
        startAt(hospitalDest)
        assertTrue(controller.confirmRouteTo("east"))
        assertFalse(controller.confirmRouteTo("north"))
        assertEquals(1, navi.routeRequests.size)
    }

    @Test
    fun `앱 요청과 무관한 내비 이벤트는 무시`() {
        startAt(hospitalDest)
        navi.events!!.onRoutingCompleted()
        navi.events!!.onNaviError("CAUSE_SEARCH_ROUTE")
        assertEquals(RouteStatus.Idle, route)
    }

    // ---- 주행 규제

    @Test
    fun `주행 규제 중에는 경로 변경 요청을 받지 않음`() {
        startAt(hospitalDest)
        controller.setRestricted(true)
        assertFalse(controller.confirmRouteTo("east"))
        assertTrue(navi.routeRequests.isEmpty())
        assertTrue(controller.state.restricted)
    }

    // ---- 근처 시설 확인

    @Test
    fun `근처 시설은 사용자가 예라고 해야 연결`() {
        startAt(dest("병원앞약국", GeoPoint(37.5015, 127.0005)))
        assertTrue(screen is Screen.AskNearby)
        controller.answerNearby(true)
        val s = screen as Screen.FacilityEntrances
        assertEquals(MatchBasis.USER_CONFIRMED_NEARBY, s.basis)
    }

    @Test
    fun `종료 시 내비 해제`() {
        startAt(hospitalDest)
        controller.stop()
        assertTrue(navi.disconnected)
    }
}
