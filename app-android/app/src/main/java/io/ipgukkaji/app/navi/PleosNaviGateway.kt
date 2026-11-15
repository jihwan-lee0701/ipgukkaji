package io.ipgukkaji.app.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.ipgukkaji.app.domain.Destination
import io.ipgukkaji.app.domain.GeoPoint
import io.ipgukkaji.app.domain.NaviEvents
import io.ipgukkaji.app.domain.NaviGateway
import io.ipgukkaji.app.domain.RouteTarget

/**
 * NaviHelper SDK v3.0.5 어댑터.
 * 시그니처 근거: NaviHelper API Reference 및 AAR(javap) 대조 (2026-11-14).
 * - initialize(onConnected, onFailed) → onConnected 에서 addListener
 * - getDestinationInfo() 결과는 onDestinationInfo 콜백 (권한 NAVI_ROUTE)
 * - requestRoute(RouteInfo) (권한 NAVI_ROUTE_SEARCH) — 호출 성공 ≠ 경로 탐색 완료
 * 콜백 스레드가 문서에 명시되어 있지 않으므로 모두 메인 스레드로 옮긴다.
 */
class PleosNaviGateway(context: Context) : NaviGateway {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var events: NaviEvents? = null
    private var pendingLocation: ((GeoPoint) -> Unit)? = null

    /** 개발용 도구에서만 사용: 내비의 현재 차량 위치 1회 조회 (권한 NAVI_ROUTE) */
    fun requestCurrentLocation(callback: (GeoPoint) -> Unit) {
        val h = helper ?: throw IllegalStateException("NaviHelper not connected")
        pendingLocation = callback
        Log.i(TAG, "getCurrentLocationInfo()")
        h.getCurrentLocationInfo()
    }

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val d = destinationInfo.toDomain()
            Log.i(TAG, "onDestinationInfo name='${d.name}' poi=${d.poiProvider}/${d.poiId} " +
                "route=${d.point} display=${d.displayPoint}")
            main.post { events?.onDestination(d) }
        }

        override fun onDestinationChanged() {
            Log.i(TAG, "onDestinationChanged")
            main.post { events?.onDestinationChanged() }
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.i(TAG, "onRouteStarted name='${routeStartInfo.name}' ${routeStartInfo.distance}m ${routeStartInfo.duration}s")
            val dur = routeStartInfo.duration
            val dist = routeStartInfo.distance
            main.post { events?.onRouteStarted(dur, dist) }
        }

        override fun onRoutingCompleted() {
            Log.i(TAG, "onRoutingCompleted")
            main.post { events?.onRoutingCompleted() }
        }

        // API Reference 에는 없지만 SDK 인터페이스에 있는 콜백. 에뮬레이터 내비는 onRouteStarted 직후 이것을 보냈다.
        // 의미가 문서로 확인되지 않아 동작에 쓰지 않고 기록만 한다.
        override fun onRouteEnded() {
            Log.i(TAG, "onRouteEnded (undocumented, log only)")
        }

        override fun onRouteCancelled() {
            Log.i(TAG, "onRouteCancelled")
            main.post { events?.onRouteCancelled() }
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val p = GeoPoint(currentLocationInfo.latitude, currentLocationInfo.longitude)
            main.post {
                val cb = pendingLocation ?: return@post
                pendingLocation = null
                Log.i(TAG, "onCurrentLocationInfo $p '${currentLocationInfo.address}'")
                cb(p)
            }
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "onError $errorCode")
            val code = errorCode.name
            main.post { events?.onNaviError(code) }
        }
    }

    override fun connect(events: NaviEvents, onConnected: () -> Unit, onFailed: (Throwable) -> Unit) {
        this.events = events
        val h = NaviHelper(appContext)
        helper = h
        h.initialize(
            onConnected = {
                main.post {
                    if (helper === h) {
                        h.addListener(listener)
                        Log.i(TAG, "NaviHelper connected")
                        onConnected()
                    }
                }
            },
            onFailed = { t ->
                Log.e(TAG, "NaviHelper initialize failed", t)
                main.post { if (helper === h) onFailed(t) }
            },
        )
    }

    override fun requestDestination() {
        val h = helper ?: throw IllegalStateException("NaviHelper not connected")
        Log.i(TAG, "getDestinationInfo()")
        h.getDestinationInfo()
    }

    override fun requestRoute(target: RouteTarget) {
        val h = helper ?: throw IllegalStateException("NaviHelper not connected")
        // [가정 A3] poiId 없이 좌표만으로 경로 요청이 가능한지 에뮬레이터에서 확인 필요.
        val info = RouteInfo(
            latitude = target.point.lat,
            longitude = target.point.lon,
            displayLatitude = target.displayPoint?.lat,
            displayLongitude = target.displayPoint?.lon,
            poiName = target.poiName,
            poiId = "",
            address = target.address,
            poiSubId = "",
            routeOption = RouteOption.RECOMMENDED,
        )
        Log.i(TAG, "requestRoute $info")
        h.requestRoute(info)
    }

    override fun disconnect() {
        val h = helper ?: return
        helper = null
        events = null
        main.removeCallbacksAndMessages(null)
        runCatching { h.removeListener(listener) }
        runCatching { h.release() }
        Log.i(TAG, "NaviHelper released")
    }

    private fun DestinationInfo.toDomain(): Destination {
        val dLat = displayLatitude
        val dLon = displayLongitude
        return Destination(
        name = name,
        address = address,
        point = GeoPoint(latitude, longitude),
        displayPoint = if (dLat != null && dLon != null) GeoPoint(dLat, dLon) else null,
        poiId = poiId,
        poiProvider = poiProvider,
        remainDistanceM = remainDistance,
        remainTimeS = remainTime,
        )
    }

    companion object {
        const val TAG = "Ipgukkaji/Navi"
    }
}
