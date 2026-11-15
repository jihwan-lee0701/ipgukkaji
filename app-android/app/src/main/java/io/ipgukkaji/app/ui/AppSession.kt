package io.ipgukkaji.app.ui

import android.content.Context
import android.util.Log
import io.ipgukkaji.app.BuildConfig
import io.ipgukkaji.app.data.EntranceCatalogLoader
import io.ipgukkaji.app.data.KakaoNearbyParkingSource
import io.ipgukkaji.app.domain.EntranceCatalog
import io.ipgukkaji.app.domain.EntranceController
import io.ipgukkaji.app.domain.GeoPoint
import io.ipgukkaji.app.domain.UiState
import io.ipgukkaji.app.navi.PleosNaviGateway
import io.ipgukkaji.app.ux.DrivingRestrictionMonitor

/**
 * NaviHelper 연결·컨트롤러를 Activity 수명과 분리한다.
 *
 * 에뮬레이터 확인(2026-11-14, Pleos Connect v2.1.0): 시스템 테마 오버레이 변경으로 Activity 가
 * 재생성되며(changes=0x10000000) NaviHelper 가 해제·재연결되는 것을 관찰했다. 경로 요청 중이면
 * 결과 이벤트를 놓치므로, 연결은 프로세스 범위에 두고 Activity 가 실제로 끝날 때만 해제한다.
 */
class AppSession private constructor(appContext: Context) {
    val navi = PleosNaviGateway(appContext)
    private var view: ((UiState) -> Unit)? = null

    val controller = EntranceController(
        navi = navi,
        initialCatalog = loadCatalog(appContext),
        scheduler = HandlerScheduler(),
        render = { s -> view?.invoke(s) },
        log = { Log.i(TAG, it) },
        nearbySource = KakaoNearbyParkingSource(BuildConfig.KAKAO_REST_API_KEY),
    )
    private val restriction = DrivingRestrictionMonitor(appContext) { controller.setRestricted(it) }

    fun attach(render: (UiState) -> Unit) {
        view = render
        render(controller.state)
    }

    fun detach() {
        view = null
    }

    private fun startAll() {
        restriction.start()
        controller.start()
    }

    private fun stopAll() {
        restriction.stop()
        controller.stop()
    }

    companion object {
        private const val TAG = "Ipgukkaji"
        private var instance: AppSession? = null
        private const val DEBUG_PREFS = "debug_test_facility"
        const val TEST_FACILITY_ID = "test-hospital"

        /** 앱 데이터 로드. release 빌드는 TEST 시설을 빼고, 디버그 빌드에서만 개발 도구로 옮긴 TEST 시설 위치를 적용한다. */
        fun loadCatalog(context: Context): EntranceCatalog {
            val base = EntranceCatalogLoader.load(context)
            if (!BuildConfig.DEBUG) return base.withoutTestData()
            val p = context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
            if (!p.contains("lat")) return base
            val center = GeoPoint(p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble())
            return base.withTestFacilityMovedTo(TEST_FACILITY_ID, center)
        }

        fun saveDebugTestCenter(context: Context, center: GeoPoint) {
            if (!BuildConfig.DEBUG) return
            context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).edit()
                .putFloat("lat", center.lat.toFloat()).putFloat("lon", center.lon.toFloat()).apply()
        }

        fun obtain(context: Context): AppSession =
            instance ?: AppSession(context.applicationContext).also {
                instance = it
                it.startAll()
            }

        fun release() {
            instance?.stopAll()
            instance = null
        }
    }
}
