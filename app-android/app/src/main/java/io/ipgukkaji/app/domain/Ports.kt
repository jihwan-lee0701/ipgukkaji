package io.ipgukkaji.app.domain

/** 차량 내비 이벤트 (NaviHelperEventListener 중 이 앱이 쓰는 것만) */
interface NaviEvents {
    fun onDestination(destination: Destination)
    fun onDestinationChanged()
    fun onRouteStarted(durationS: Int, distanceM: Int)
    fun onRoutingCompleted()
    fun onRouteCancelled()
    /** NaviErrorCode.name */
    fun onNaviError(code: String)
}

/**
 * 차량 내비 연동 경계. 실제 구현은 navi/PleosNaviGateway(NaviHelper SDK).
 * 단위 테스트에서는 가짜 구현을 쓴다 — 가짜 구현의 결과를 "SDK 연동 완료"로 보고하지 않는다.
 */
interface NaviGateway {
    fun connect(events: NaviEvents, onConnected: () -> Unit, onFailed: (Throwable) -> Unit)
    /** 결과는 NaviEvents.onDestination 으로 비동기 전달 */
    fun requestDestination()
    /** 호출이 예외 없이 끝나도 "요청 전달"일 뿐, 경로 탐색 완료가 아니다 */
    fun requestRoute(target: RouteTarget)
    fun disconnect()
}

/** 외부 실시간 검색으로 찾은 주변 주차장 (예: 카카오 로컬 API). 화면 표시에만 쓰고 저장하지 않는다. */
data class NearbyParking(
    val name: String,
    val address: String,
    val point: GeoPoint,
    val distanceM: Int?,
)

/**
 * 등록되지 않은 목적지용 보조 검색. 결과는 호출한 화면에서만 쓰고 저장·캐싱하지 않는다
 * (카카오 로컬 API 운영정책: 저장·캐싱 제한, 실시간 호출만).
 */
interface NearbyParkingSource {
    /** API 키가 없는 등 사용할 수 없으면 false → 기능을 숨긴다 */
    val available: Boolean
    val providerLabel: String
    fun search(center: GeoPoint, radiusM: Int, callback: (Result<List<NearbyParking>>) -> Unit)
}

fun interface Cancellable {
    fun cancel()
}

interface Scheduler {
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}
