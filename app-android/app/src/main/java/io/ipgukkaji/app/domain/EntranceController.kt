package io.ipgukkaji.app.domain

import kotlin.math.roundToInt

enum class RouteFailure {
    REQUEST_EXCEPTION, PERMISSION_DENIED, LOW_BATTERY, OVER_DISTANCE, TOO_SHORT_DISTANCE, ROUTE_SERVER, UNKNOWN;

    companion object {
        /** NaviErrorCode (NaviHelper 3.0.5 API Reference) → 앱 사유 */
        fun fromNaviCode(code: String): RouteFailure = when (code) {
            "CAUSE_LOW_BATTERY" -> LOW_BATTERY
            "CAUSE_SEARCH_ROUTE_OVER_DISTANCE" -> OVER_DISTANCE
            "CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE" -> TOO_SHORT_DISTANCE
            "CAUSE_SEARCH_ROUTE" -> ROUTE_SERVER
            else -> UNKNOWN
        }
    }
}

/** "요청 보냄"과 "경로 탐색 완료"를 구분한다 */
sealed interface RouteStatus {
    data object Idle : RouteStatus
    data class Requested(val entrance: Entrance) : RouteStatus
    data class Started(val entrance: Entrance, val durationS: Int, val distanceM: Int) : RouteStatus
    data class Completed(val entrance: Entrance, val durationS: Int?, val distanceM: Int?) : RouteStatus
    data class Failed(val entrance: Entrance, val reason: RouteFailure) : RouteStatus
    data class Cancelled(val entrance: Entrance) : RouteStatus
    data class TimedOut(val entrance: Entrance) : RouteStatus
}

/** 결과를 기다리는 중. Started 는 내비가 실제로 안내를 시작한 결과이므로 포함하지 않는다(에뮬레이터 관찰 반영). */
val RouteStatus.inFlight: Boolean get() = this is RouteStatus.Requested

sealed interface Screen {
    data object Connecting : Screen
    data class ConnectFailed(val message: String?) : Screen
    data object LoadingDestination : Screen
    data object NoDestination : Screen
    data class PermissionDenied(val detail: String?) : Screen
    data class NotRegistered(
        val destinationName: String,
        val reason: NoMatchReason,
        val nearby: NearbyState = NearbyState.Idle,
    ) : Screen
    data class AskNearby(val destinationName: String, val facility: Facility, val distanceM: Int) : Screen
    data class FacilityEntrances(
        val facility: Facility,
        val destination: Destination,
        val purposeId: String?,
        val entrances: List<RankedEntrance>,
        val basis: MatchBasis,
    ) : Screen
    data class GuidingOwnEntrance(val facility: Facility, val entrance: Entrance) : Screen
    /** 미등록 목적지에서 실시간 검색한 주변 주차장으로 안내 중 (입구·조건 미확인) */
    data class GuidingNearby(val parking: NearbyParking) : Screen
}

/** 미등록 목적지의 실시간 주변 주차장 검색 상태. 결과는 이 화면 상태에만 있고 목적지가 바뀌면 버린다. */
sealed interface NearbyState {
    data object Idle : NearbyState
    data object Loading : NearbyState
    data class Loaded(val items: List<NearbyParking>, val provider: String) : NearbyState
    data class Failed(val message: String?) : NearbyState
}

data class UiState(val screen: Screen, val route: RouteStatus, val restricted: Boolean)

/**
 * 목적지 조회 → 시설 매칭 → (사용자 확인) 경로 요청 → 결과 이벤트 반영.
 * 메인 스레드에서만 호출된다고 가정한다 (PleosNaviGateway 가 콜백을 메인 스레드로 옮김).
 *
 * 원칙
 * - 경로 변경은 confirmRouteTo() (사용자가 확인 대화상자에서 누른 경우) 에서만 일어난다.
 * - onDestinationChanged 에 대해서는 목적지 재조회만 한다. 어떤 경로 API도 호출하지 않으므로 재요청 루프가 생길 수 없다.
 * - 앱이 바꾼 입구 목적지는 OwnEntrance 로 인식해 다시 추천하지 않는다.
 */
class EntranceController(
    private val navi: NaviGateway,
    initialCatalog: EntranceCatalog,
    private val scheduler: Scheduler,
    private val render: (UiState) -> Unit,
    private val log: (String) -> Unit = {},
    private val destinationTimeoutMs: Long = 5_000,
    private val routeTimeoutMs: Long = 15_000,
    private val changeDebounceMs: Long = 300,
    private val nearbySource: NearbyParkingSource? = null,
) : NaviEvents {

    private var catalog: EntranceCatalog = initialCatalog
    private var matcher = FacilityMatcher(catalog)

    /** 개발용: 테스트 데이터 위치 변경 등으로 카탈로그를 바꾼다. 진행 중인 요청이 있으면 거부한다. */
    fun replaceCatalog(newCatalog: EntranceCatalog): Boolean {
        if (route.inFlight) return false
        catalog = newCatalog
        matcher = FacilityMatcher(newCatalog)
        pendingEntranceId = null
        route = RouteStatus.Idle
        emit()
        return true
    }

    private var screen: Screen = Screen.Connecting
    private var route: RouteStatus = RouteStatus.Idle
    private var restricted = false
    private var connected = false
    private var stopped = false

    private var destination: Destination? = null
    private var purposeId: String? = null
    private var pendingEntranceId: String? = null
    private var confirmedNearbyFacilityId: String? = null
    private var pendingNearby: NearbyParking? = null

    private var destinationTimer: Cancellable? = null
    private var routeTimer: Cancellable? = null
    private var changeTimer: Cancellable? = null

    /** 진단·테스트용 카운터 */
    var routeRequestCount = 0
        private set
    var destinationRequestCount = 0
        private set

    val state: UiState get() = UiState(screen, route, restricted)

    val dataVersion: String? get() = catalog.dataVersion

    // ---------------------------------------------------------------- 사용자/수명주기 입력

    fun start() {
        stopped = false
        connected = false
        show(Screen.Connecting)
        navi.connect(
            events = this,
            onConnected = {
                if (!stopped) {
                    connected = true
                    loadDestination(showLoading = true)
                }
            },
            onFailed = { t ->
                if (!stopped) {
                    connected = false
                    log("NaviHelper 연결 실패: $t")
                    show(Screen.ConnectFailed(t.message))
                }
            },
        )
    }

    fun retry() {
        if (connected) loadDestination(showLoading = true) else start()
    }

    /** 앱이 다시 전면에 왔을 때: 화면을 비우지 않고 목적지만 다시 확인한다 */
    fun refreshDestination() {
        if (connected && !stopped) loadDestination(showLoading = false)
    }

    fun stop() {
        stopped = true
        cancelTimers()
        navi.disconnect()
        connected = false
    }

    fun setRestricted(value: Boolean) {
        if (restricted == value) return
        restricted = value
        emit()
    }

    fun selectPurpose(id: String?) {
        purposeId = id
        val s = screen as? Screen.FacilityEntrances ?: return
        showFacility(s.facility, s.destination, s.basis)
    }

    fun answerNearby(isThisFacility: Boolean) {
        val s = screen as? Screen.AskNearby ?: return
        val d = destination ?: return
        if (isThisFacility) {
            confirmedNearbyFacilityId = s.facility.id
            showFacility(s.facility, d, MatchBasis.USER_CONFIRMED_NEARBY)
        } else {
            show(Screen.NotRegistered(d.name, NoMatchReason.NOT_REGISTERED))
        }
    }

    val nearbyAvailable: Boolean get() = nearbySource?.available == true

    /** 미등록 목적지에서 사용자가 요청할 때만 주변 주차장을 실시간 검색한다 (저장하지 않음). */
    fun searchNearbyParking(radiusM: Int = 500) {
        val src = nearbySource?.takeIf { it.available } ?: return
        val s = screen as? Screen.NotRegistered ?: return
        val d = destination ?: return
        show(s.copy(nearby = NearbyState.Loading))
        src.search(d.point, radiusM) { result ->
            val cur = screen as? Screen.NotRegistered ?: return@search
            if (destination?.point != d.point) return@search // 그 사이 목적지가 바뀌면 결과를 버린다
            val state = result.fold(
                onSuccess = { NearbyState.Loaded(it.take(10), src.providerLabel) },
                onFailure = { NearbyState.Failed(it.message) },
            )
            show(cur.copy(nearby = state))
        }
    }

    /** 사용자가 확인 대화상자에서 고른 주변 주차장으로 차량 내비 경로를 바꾼다. */
    fun confirmRouteToNearby(index: Int): Boolean {
        if (restricted || route.inFlight) return false
        val s = screen as? Screen.NotRegistered ?: return false
        val p = (s.nearby as? NearbyState.Loaded)?.items?.getOrNull(index) ?: return false
        val e = Entrance(
            id = "nearby:$index",
            facilityId = "nearby",
            name = p.name,
            routeTarget = p.point,
            gate = null,
            approach = null,
            visitorAccess = VisitorAccess.UNKNOWN,
            source = Source(SourceType.EXTERNAL_REALTIME, null, null),
            verification = Verification(VerificationStatus.UNVERIFIED_REALTIME),
        )
        pendingEntranceId = null
        pendingNearby = p
        route = RouteStatus.Requested(e)
        routeRequestCount++
        try {
            navi.requestRoute(RouteTarget(p.point, null, p.name, p.address))
        } catch (se: SecurityException) {
            failRoute(e, RouteFailure.PERMISSION_DENIED)
            return false
        } catch (t: Throwable) {
            failRoute(e, RouteFailure.REQUEST_EXCEPTION)
            return false
        }
        if (route.inFlight) armRouteTimer(e)
        emit()
        return true
    }

    /** 앱이 안내 중인 입구 화면에서 같은 시설의 다른 입구를 고르고 싶을 때 */
    fun showOtherEntrances() {
        val s = screen as? Screen.GuidingOwnEntrance ?: return
        val d = destination ?: return
        showFacility(s.facility, d, MatchBasis.OWN_REQUEST)
    }

    /**
     * 사용자가 확인 대화상자에서 [안내 시작]을 눌렀을 때만 호출한다.
     * @return 요청을 내비에 전달했으면 true (경로 탐색 완료를 뜻하지 않음)
     */
    fun confirmRouteTo(entranceId: String): Boolean {
        if (restricted) {
            log("주행 규제 중: 경로 변경 요청을 받지 않음")
            return false
        }
        if (route.inFlight) return false
        val s = screen as? Screen.FacilityEntrances ?: return false
        val e = catalog.entrance(entranceId)
            ?.takeIf { it.facilityId == s.facility.id && it.canRouteTo }
            ?: return false

        pendingEntranceId = e.id
        route = RouteStatus.Requested(e)
        routeRequestCount++
        try {
            navi.requestRoute(RouteTarget.of(s.facility, e))
        } catch (se: SecurityException) {
            failRoute(e, RouteFailure.PERMISSION_DENIED)
            return false
        } catch (t: Throwable) {
            log("requestRoute 예외: $t")
            failRoute(e, RouteFailure.REQUEST_EXCEPTION)
            return false
        }
        if (route.inFlight) armRouteTimer(e)
        emit()
        return true
    }

    // ---------------------------------------------------------------- 내비 이벤트

    override fun onDestination(destination: Destination) {
        if (stopped) return
        destinationTimer?.cancel()
        destinationTimer = null

        if (!destination.isSet && route.inFlight) {
            // 에뮬레이터 확인: 경로 요청 직후 내비가 기존 경로를 취소하면서 빈 목적지를 잠시 보낸다. 결과를 기다리는 중에는 무시.
            log("요청 처리 중 빈 목적지 수신 — 화면 유지")
            return
        }
        if (!destination.isSet) {
            this.destination = null
            resetRouteIfIdle()
            show(Screen.NoDestination)
            return
        }
        this.destination = destination

        pendingNearby?.let { p ->
            if (p.point.distanceTo(destination.point) <= FacilityMatcher.OWN_ENTRANCE_RADIUS_M || destination.name.trim() == p.name) {
                show(Screen.GuidingNearby(p))
                return
            }
        }

        when (val r = matcher.match(destination, pendingEntranceId)) {
            is MatchResult.OwnEntrance -> {
                show(Screen.GuidingOwnEntrance(r.facility, r.entrance))
            }
            is MatchResult.Matched -> {
                resetRouteIfIdle()
                showFacility(r.facility, destination, r.basis)
            }
            is MatchResult.NearbyOnly -> {
                resetRouteIfIdle()
                if (confirmedNearbyFacilityId == r.facility.id) {
                    showFacility(r.facility, destination, MatchBasis.USER_CONFIRMED_NEARBY)
                } else {
                    show(Screen.AskNearby(destination.name, r.facility, r.distanceM.roundToInt()))
                }
            }
            is MatchResult.NoMatch -> {
                resetRouteIfIdle()
                show(Screen.NotRegistered(destination.name, r.reason))
            }
        }
    }

    override fun onDestinationChanged() {
        if (stopped || !connected) return
        // 목적지 재조회만 한다. 경로 API 호출 없음 → 재요청 루프 불가.
        scheduleDestinationRefresh()
    }

    override fun onRouteStarted(durationS: Int, distanceM: Int) {
        val r = route
        val e = when (r) {
            is RouteStatus.Requested -> r.entrance
            is RouteStatus.TimedOut -> r.entrance // 늦게 온 시작 이벤트도 실제 결과로 반영
            else -> null
        }
        if (e != null) {
            // 에뮬레이터 확인(2026-11-14): onRouteStarted 는 경로 거리·시간을 담고 오며(=경로 계산 완료),
            // 이 내비는 onRoutingCompleted 를 보내지 않았다. 따라서 Started 를 "안내 시작"이라는 실제 결과로 보고
            // 더 이상 타임아웃을 걸지 않는다. onRoutingCompleted 가 오면 Completed 로 갱신한다.
            routeTimer?.cancel()
            routeTimer = null
            route = RouteStatus.Started(e, durationS, distanceM)
            emit()
        } else {
            log("onRouteStarted: 앱 요청과 무관하거나 이미 처리됨 (route=$r)")
        }
        // 이 내비는 경로 변경 시 onDestinationChanged 를 보내지 않았으므로, 시작 이벤트 후 목적지를 다시 조회한다.
        scheduleDestinationRefresh()
    }

    override fun onRoutingCompleted() {
        val r = route
        val e = when (r) {
            is RouteStatus.Requested -> r.entrance
            is RouteStatus.Started -> r.entrance
            is RouteStatus.TimedOut -> r.entrance // 늦게 도착한 완료 이벤트도 실제 결과로 반영
            else -> {
                log("onRoutingCompleted: 앱 요청과 무관 (route=$r)")
                return
            }
        }
        routeTimer?.cancel()
        routeTimer = null
        val started = r as? RouteStatus.Started
        route = RouteStatus.Completed(e, started?.durationS, started?.distanceM)
        emit()
        // 에뮬레이터 확인(2026-11-14): 내비 구현에 따라 onDestinationChanged 가 오지 않을 수 있다.
        // 완료 후 목적지를 직접 다시 조회해 "앱이 바꾼 입구"로 인식한다 (조회만 하므로 루프 없음).
        scheduleDestinationRefresh()
    }

    private fun scheduleDestinationRefresh() {
        if (stopped || !connected) return
        changeTimer?.cancel()
        changeTimer = scheduler.schedule(changeDebounceMs) {
            changeTimer = null
            loadDestination(showLoading = false)
        }
    }

    override fun onRouteCancelled() {
        when (val r = route) {
            // [가정 확인 필요] 새 경로 요청 직후 내비가 "기존 경로 취소"를 먼저 보낼 수 있다. 실패로 단정하지 않는다.
            is RouteStatus.Requested -> log("요청 직후 onRouteCancelled 수신 — 기존 경로 취소로 보고 결과 대기")
            is RouteStatus.Started -> {
                routeTimer?.cancel()
                routeTimer = null
                pendingEntranceId = null
                pendingNearby = null
                route = RouteStatus.Cancelled(r.entrance)
                emit()
            }
            is RouteStatus.Completed -> {
                pendingEntranceId = null
                pendingNearby = null
                route = RouteStatus.Cancelled(r.entrance)
                emit()
            }
            else -> Unit
        }
    }

    override fun onNaviError(code: String) {
        val e = when (val r = route) {
            is RouteStatus.Requested -> r.entrance
            is RouteStatus.Started -> r.entrance
            else -> {
                log("앱 요청과 무관한 내비 오류: $code")
                return
            }
        }
        failRoute(e, RouteFailure.fromNaviCode(code))
    }

    // ---------------------------------------------------------------- 내부

    private fun loadDestination(showLoading: Boolean) {
        if (stopped) return
        if (showLoading) show(Screen.LoadingDestination)
        destinationTimer?.cancel()
        destinationTimer = scheduler.schedule(destinationTimeoutMs) {
            destinationTimer = null
            // [가정 A4] 목적지가 없으면 onDestinationInfo 가 오지 않을 수 있다.
            destination = null
            resetRouteIfIdle()
            show(Screen.NoDestination)
        }
        destinationRequestCount++
        try {
            navi.requestDestination()
        } catch (se: SecurityException) {
            destinationTimer?.cancel()
            destinationTimer = null
            show(Screen.PermissionDenied(se.message))
        } catch (t: Throwable) {
            destinationTimer?.cancel()
            destinationTimer = null
            log("getDestinationInfo 예외: $t")
            show(Screen.ConnectFailed(t.message))
        }
    }

    private fun showFacility(f: Facility, d: Destination, basis: MatchBasis) {
        if (purposeId != null && f.purposes.none { it.id == purposeId }) purposeId = null
        val ranked = EntranceRanker.rank(catalog.entrancesOf(f.id), purposeId, d.point)
        show(Screen.FacilityEntrances(f, d, purposeId, ranked, basis))
    }

    /** 목적지가 앱이 고른 입구가 아니게 되면, 진행 중 요청이 없을 때만 입구 추적을 해제한다. */
    private fun resetRouteIfIdle() {
        if (route.inFlight) return
        pendingEntranceId = null
        pendingNearby = null
        route = RouteStatus.Idle
    }

    private fun armRouteTimer(e: Entrance) {
        routeTimer?.cancel()
        routeTimer = scheduler.schedule(routeTimeoutMs) {
            routeTimer = null
            if (route.inFlight) {
                route = RouteStatus.TimedOut(e)
                emit()
            }
        }
    }

    private fun failRoute(e: Entrance, reason: RouteFailure) {
        routeTimer?.cancel()
        routeTimer = null
        pendingEntranceId = null
        pendingNearby = null
        route = RouteStatus.Failed(e, reason)
        emit()
    }

    private fun cancelTimers() {
        destinationTimer?.cancel(); destinationTimer = null
        routeTimer?.cancel(); routeTimer = null
        changeTimer?.cancel(); changeTimer = null
    }

    private fun show(s: Screen) {
        screen = s
        emit()
    }

    private fun emit() = render(state)
}
