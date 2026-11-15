package io.ipgukkaji.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.ipgukkaji.app.BuildConfig
import io.ipgukkaji.app.data.EntranceCatalogLoader
import io.ipgukkaji.app.domain.DataKind
import io.ipgukkaji.app.domain.Entrance
import io.ipgukkaji.app.domain.EntranceController
import io.ipgukkaji.app.domain.Freshness
import io.ipgukkaji.app.domain.GeoPoint
import java.time.LocalDate
import io.ipgukkaji.app.domain.MatchBasis
import io.ipgukkaji.app.domain.NearbyState
import io.ipgukkaji.app.domain.NoMatchReason
import io.ipgukkaji.app.domain.PhotoLicense
import io.ipgukkaji.app.domain.RankedEntrance
import io.ipgukkaji.app.domain.RouteFailure
import io.ipgukkaji.app.domain.RouteStatus
import io.ipgukkaji.app.domain.RouteTarget
import io.ipgukkaji.app.domain.Screen
import io.ipgukkaji.app.domain.SourceType
import io.ipgukkaji.app.domain.UiState
import io.ipgukkaji.app.domain.VerificationStatus
import io.ipgukkaji.app.domain.VisitorAccess
import io.ipgukkaji.app.domain.inFlight
import kotlin.math.roundToInt

/**
 * 화면: 연결/목적지 상태 → 입구 목록(S2) → 입구 상세(S3) → 경로 결과 바(S4), 주행 중 한 줄 상태(D1).
 * UI는 EntranceController 의 UiState 를 그대로 그린다.
 */
class MainActivity : Activity() {

    private lateinit var session: AppSession
    private val controller: EntranceController get() = session.controller

    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var statusBar: TextView

    private var detailEntranceId: String? = null
    private var confirmDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        // NaviHelper 문서: 앱 생성 시 initialize, 종료 시 release.
        // 연결은 AppSession(프로세스 범위)에 두어 Activity 재생성·백그라운드 중에도 결과 이벤트를 받는다.
        session = AppSession.obtain(this)
        session.attach(::render)
    }

    override fun onRestart() {
        super.onRestart()
        controller.refreshDestination()
    }

    override fun onDestroy() {
        confirmDialog?.dismiss()
        confirmDialog = null
        session.detach()
        if (isFinishing) AppSession.release()
        super.onDestroy()
    }

    /**
     * 디버그 빌드 전용 개발 도구: 테스트(가상) 시설을 내비 현재 위치 약 1km 북쪽으로 옮기고,
     * 그 시설 중심을 실제 NaviHelper requestRoute 로 목적지 설정한다. 실제 병원 데이터와 무관하다.
     */
    private fun debugSetTestDestination() {
        runCatching {
            session.navi.requestCurrentLocation { here ->
                val center = GeoPoint(here.lat + 0.009, here.lon)
                AppSession.saveDebugTestCenter(this, center)
                val moved = AppSession.loadCatalog(this)
                if (!controller.replaceCatalog(moved)) return@requestCurrentLocation
                val f = moved.facility("test-hospital") ?: return@requestCurrentLocation
                Log.i(TAG, "DEBUG 테스트 시설 이동 → ${f.center}, 목적지 설정 요청")
                session.navi.requestRoute(RouteTarget(f.center, null, f.officialName, f.address))
                // 이 내비 구현은 onDestinationChanged 를 보내지 않을 수 있어 잠시 후 직접 재조회
                content.postDelayed({ controller.refreshDestination() }, 5_000)
            }
        }.onFailure { Log.e(TAG, "DEBUG 도구 실패", it) }
    }

    private fun debugTools() {
        if (!BuildConfig.DEBUG) return
        content.addView(makeButton("[개발용] 테스트 시설을 현재 위치 근처로 옮기고 목적지로 설정") {
            debugSetTestDestination()
        }.withTopMargin(32))
    }

    // ------------------------------------------------------------------ 렌더링

    private fun render(s: UiState) {
        if (s.restricted) {
            confirmDialog?.dismiss()
            confirmDialog = null
            detailEntranceId = null
            renderRestricted(s)
            return
        }
        content.removeAllViews()
        // 입구 목록 화면을 벗어나면 보던 상세를 잊는다 (안내 중 "다른 입구 보기"가 목록으로 가도록)
        if (s.screen !is Screen.FacilityEntrances) detailEntranceId = null
        when (val sc = s.screen) {
            Screen.Connecting -> message("차량 내비에 연결하는 중…")
            is Screen.ConnectFailed -> {
                message("차량 내비에 연결할 수 없습니다.")
                sc.message?.let { small(it) }
                button("다시 시도") { controller.retry() }
            }
            Screen.LoadingDestination -> message("차량 내비 목적지를 확인하는 중…")
            Screen.NoDestination -> {
                message("설정된 목적지가 없습니다.\n차량 내비에서 목적지를 먼저 설정한 뒤 다시 확인하세요.")
                button("다시 확인") { controller.retry() }
                debugTools()
            }
            is Screen.PermissionDenied -> {
                message("차량 내비 정보에 접근할 권한이 없습니다.")
                sc.detail?.let { small(it) }
            }
            is Screen.NotRegistered -> {
                title(sc.destinationName.ifBlank { "현재 목적지" })
                message("이 목적지에는 등록된 입구 정보가 없습니다.\n차량 내비 안내를 따라 주세요.")
                if (sc.reason == NoMatchReason.NAME_MATCH_BUT_FAR) {
                    small("이름이 비슷한 등록 시설이 있지만 위치가 달라 연결하지 않았습니다.")
                }
                button("다시 확인") { controller.retry() }
                renderNearby(sc)
                debugTools()
            }
            is Screen.GuidingNearby -> {
                title(sc.parking.name)
                message("'${sc.parking.name}'(으)로 안내 중입니다.")
                small("실시간 검색 결과(정보 제공: 카카오) — 주차장 대표 위치 기준이며 진입구·이용 조건은 확인되지 않았습니다.")
            }
            is Screen.AskNearby -> {
                title(sc.destinationName.ifBlank { "현재 목적지" })
                message("목적지에서 약 ${sc.distanceM}m 거리에 등록 시설이 있습니다.\n'${sc.facility.officialName}'에 가시나요?")
                row(
                    makeButton("예, 이 병원입니다") { controller.answerNearby(true) },
                    makeButton("아니요") { controller.answerNearby(false) },
                )
            }
            is Screen.FacilityEntrances -> renderFacility(sc, s.route)
            is Screen.GuidingOwnEntrance -> {
                if (sc.facility.dataKind == DataKind.TEST) testBanner()
                title(sc.facility.officialName)
                message("'${sc.entrance.name}'(으)로 안내 중입니다.")
                sc.entrance.approach?.let { body("진입 방향: $it") }
                button("다른 입구 보기") { controller.showOtherEntrances() }
            }
        }
        renderRouteStatus(s.route)
    }

    private fun renderFacility(sc: Screen.FacilityEntrances, route: RouteStatus) {
        val detail = detailEntranceId?.let { id -> sc.entrances.firstOrNull { it.entrance.id == id } }
        if (detail != null) {
            renderDetail(sc, detail, route)
            return
        }
        detailEntranceId = null
        if (sc.facility.dataKind == DataKind.TEST) testBanner()
        title(sc.facility.officialName)
        if (sc.basis == MatchBasis.USER_CONFIRMED_NEARBY) small("목적지 '${sc.destination.name}' 근처 시설로 직접 확인한 결과입니다.")

        if (sc.facility.purposes.isNotEmpty()) {
            body("방문 목적")
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chips.addView(chip("전체", sc.purposeId == null) { controller.selectPurpose(null) })
            sc.facility.purposes.forEach { p ->
                chips.addView(chip(p.label, sc.purposeId == p.id) { controller.selectPurpose(p.id) })
            }
            content.addView(HorizontalScrollView(this).apply { addView(chips) })
        }

        if (sc.entrances.isEmpty()) {
            message("등록된 입구가 없습니다.")
            return
        }
        sc.entrances.forEach { content.addView(entranceCard(it)) }
        session.controller.dataVersion?.let { small("입구 데이터 버전 $it") }
    }

    private fun renderDetail(sc: Screen.FacilityEntrances, r: RankedEntrance, route: RouteStatus) {
        val e = r.entrance
        if (sc.facility.dataKind == DataKind.TEST) testBanner()
        title(e.name)
        small(sc.facility.officialName)

        photoView(e)?.let { content.addView(it) } ?: small("등록된 입구 사진이 없습니다.")

        body("출입: ${accessLabel(e.visitorAccess)}")
        e.conditions.forEach { body("· $it") }
        e.approach?.let { body("진입 방향: $it") }
        e.heightLimitText?.let { body("높이 제한(표지·공식 안내 원문): $it") }

        if (Freshness.isStale(e, LocalDate.now())) {
            body("⚠ 마지막 확인 후 6개월이 지났습니다. 현재 안내와 다를 수 있습니다.")
        }
        e.lastConfirmedAt?.let { small("마지막 확인: $it") }
        small("검증 상태: ${verificationLabel(e.verification.status)}")
        e.verification.fieldCheckedAt?.let { small("현장 확인일: $it") }
        e.verification.routeCheckedAt?.let {
            small("경로 확인일: $it (${e.verification.routeCheckedFrom.joinToString("/")} 방향)")
        }
        small("출처: ${sourceLabel(e.source.type)}${e.source.url?.let { " · $it" } ?: ""}${e.source.retrievedAt?.let { " · $it 확인" } ?: ""}")
        e.remoteChecks.forEach { rc ->
            small("${rc.provider} 확인 ${rc.checkedAt}${rc.imageDate?.let { " (영상 촬영 $it 기준 — 현재와 다를 수 있음)" } ?: ""}")
        }
        e.coordinateSource?.let { small("좌표 출처: $it") }

        when {
            e.visitorAccess == VisitorAccess.NOT_ALLOWED -> message("방문객이 진입할 수 없는 입구입니다.")
            r.sameAsCurrentDestination -> message("현재 차량 내비 안내와 같은 입구입니다. 경로를 바꾸지 않아도 됩니다.")
            route.inFlight -> message("다른 경로 요청을 처리하는 중입니다.")
            r.selectable -> button("이 입구로 안내") { askConfirm(sc, e) }
        }
        button("입구 목록으로") {
            detailEntranceId = null
            render(controller.state)
        }
    }

    /** 미등록 목적지: 사용자가 누를 때만 카카오 로컬 API로 주변 주차장을 실시간 검색 (저장하지 않음) */
    private fun renderNearby(sc: Screen.NotRegistered) {
        if (!controller.nearbyAvailable) return
        when (val n = sc.nearby) {
            NearbyState.Idle -> button("주변 주차장 찾기 (실시간 검색)") { controller.searchNearbyParking() }
            NearbyState.Loading -> body("주변 주차장을 찾는 중…")
            is NearbyState.Failed -> {
                body("주변 주차장을 불러오지 못했습니다.")
                button("다시 찾기") { controller.searchNearbyParking() }
            }
            is NearbyState.Loaded -> {
                body("목적지 반경 500m 주차장 (정보 제공: ${n.provider})")
                small("주차장 대표 위치입니다. 진입구 위치와 이용 조건은 확인되지 않았습니다. 검색 결과는 저장하지 않습니다.")
                if (n.items.isEmpty()) small("검색된 주차장이 없습니다.")
                n.items.forEachIndexed { i, p ->
                    val label = p.name + (p.distanceM?.let { " · ${it}m" } ?: "")
                    button(label) { askConfirmNearby(i, p.name) }
                }
            }
        }
    }

    private fun askConfirmNearby(index: Int, name: String) {
        confirmDialog?.dismiss()
        confirmDialog = AlertDialog.Builder(this)
            .setTitle("목적지를 바꿀까요?")
            .setMessage("차량 내비 목적지가 '$name'(으)로 바뀝니다.\n실시간 검색 결과라 진입구·이용 조건은 확인되지 않았습니다.")
            .setPositiveButton("안내 시작") { _, _ ->
                confirmDialog = null
                controller.confirmRouteToNearby(index)
            }
            .setNegativeButton("취소") { _, _ -> confirmDialog = null }
            .show()
    }

    private fun renderRestricted(s: UiState) {
        content.removeAllViews()
        val line = when (val sc = s.screen) {
            is Screen.GuidingOwnEntrance -> "${sc.facility.officialName} · ${sc.entrance.name}(으)로 안내 중"
            is Screen.GuidingNearby -> "${sc.parking.name}(으)로 안내 중"
            is Screen.FacilityEntrances -> when (val r = s.route) {
                is RouteStatus.Started -> "${sc.facility.officialName} · ${r.entrance.name}(으)로 안내 중"
                is RouteStatus.Completed -> "${sc.facility.officialName} · ${r.entrance.name}(으)로 안내 중"
                else -> "${sc.facility.officialName} · 정차 후 입구를 선택할 수 있습니다"
            }
            else -> "정차 후 이용할 수 있습니다"
        }
        content.addView(text(line, 30f, bold = true).apply { gravity = Gravity.CENTER })
        statusBar.visibility = View.GONE
        scroll.scrollTo(0, 0)
    }

    private fun renderRouteStatus(route: RouteStatus) {
        val t = when (route) {
            RouteStatus.Idle -> null
            is RouteStatus.Requested -> "경로 요청을 보냈습니다. 차량 내비 응답을 기다리는 중…"
            is RouteStatus.Started ->
                "'${route.entrance.name}'(으)로 차량 내비 안내가 시작되었습니다 · 약 ${(route.durationS / 60.0).roundToInt()}분, ${"%.1f".format(route.distanceM / 1000.0)}km"
            is RouteStatus.Completed -> "'${route.entrance.name}'(으)로 경로 탐색이 완료되었습니다." +
                (route.durationS?.let { " 약 ${(it / 60.0).roundToInt()}분" } ?: "")
            is RouteStatus.Failed -> failureText(route.reason)
            is RouteStatus.Cancelled -> "경로가 취소되었습니다."
            is RouteStatus.TimedOut -> "결과를 확인하지 못했습니다. 차량 내비 화면을 확인하세요."
        }
        statusBar.text = t ?: ""
        statusBar.visibility = if (t == null) View.GONE else View.VISIBLE
    }

    private fun askConfirm(sc: Screen.FacilityEntrances, e: Entrance) {
        val target = RouteTarget.of(sc.facility, e)
        confirmDialog?.dismiss()
        confirmDialog = AlertDialog.Builder(this)
            .setTitle("목적지를 바꿀까요?")
            .setMessage("차량 내비 목적지가 '${target.poiName}'(으)로 바뀝니다.\n현재 경로는 이 입구로 가는 경로로 대체됩니다.")
            .setPositiveButton("안내 시작") { _, _ ->
                confirmDialog = null
                controller.confirmRouteTo(e.id)
            }
            .setNegativeButton("취소") { _, _ -> confirmDialog = null }
            .show()
    }

    // ------------------------------------------------------------------ 문구

    private fun failureText(r: RouteFailure) = when (r) {
        RouteFailure.TOO_SHORT_DISTANCE -> "입구와 너무 가까워 경로를 바꾸지 못했습니다. 기존 안내가 유지됩니다."
        RouteFailure.OVER_DISTANCE -> "경로 탐색 가능 거리를 넘어 경로를 바꾸지 못했습니다."
        RouteFailure.ROUTE_SERVER -> "경로 서버 오류로 경로를 바꾸지 못했습니다. 기존 안내가 유지됩니다."
        RouteFailure.LOW_BATTERY -> "배터리 부족으로 경로를 바꾸지 못했습니다."
        RouteFailure.PERMISSION_DENIED -> "경로 변경 권한이 없어 요청하지 못했습니다."
        RouteFailure.REQUEST_EXCEPTION -> "경로 요청을 보내지 못했습니다."
        RouteFailure.UNKNOWN -> "경로를 바꾸지 못했습니다."
    }

    private fun accessLabel(a: VisitorAccess) = when (a) {
        VisitorAccess.ALLOWED -> "방문객 진입 가능"
        VisitorAccess.CONDITIONAL -> "조건부 진입"
        VisitorAccess.NOT_ALLOWED -> "방문객 진입 불가"
        VisitorAccess.UNKNOWN -> "진입 조건 미확인"
    }

    private fun verificationLabel(v: VerificationStatus) = when (v) {
        VerificationStatus.TEST_DATA -> "테스트 데이터 (실제 입구 아님)"
        VerificationStatus.UNVERIFIED_REALTIME -> "실시간 검색 결과 (입구·조건 미확인)"
        VerificationStatus.SOURCE_ONLY -> "공식 안내 기준 (현장 미확인)"
        VerificationStatus.REMOTE_VIEW_CHECKED -> "공식 안내 + 로드뷰 확인 (현장 미확인)"
        VerificationStatus.FIELD_VERIFIED -> "현장 확인"
        VerificationStatus.ROUTE_VERIFIED -> "현장·경로 확인"
    }

    private fun sourceLabel(t: SourceType) = when (t) {
        SourceType.TEST -> "테스트"
        SourceType.OFFICIAL_WEB -> "시설 공식 안내"
        SourceType.FIELD_SURVEY -> "현장 조사"
        SourceType.FACILITY_PROVIDED -> "시설 제공"
        SourceType.EXTERNAL_REALTIME -> "외부 실시간 검색"
    }

    // ------------------------------------------------------------------ 뷰 도우미

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418"))
            setPadding(dp(32), dp(24), dp(32), dp(16))
        }
        root.addView(text(getString(io.ipgukkaji.app.R.string.app_name), 22f, color = "#8FA3B8"))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        statusBar = text("", 24f, bold = true, color = "#FFFFFF").apply {
            setBackgroundColor(Color.parseColor("#1F4E79"))
            setPadding(dp(20), dp(16), dp(20), dp(16))
            visibility = View.GONE
        }
        root.addView(statusBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
    }

    private fun entranceCard(r: RankedEntrance): View {
        val e = r.entrance
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(if (e.visitorAccess == VisitorAccess.NOT_ALLOWED) "#1A1D21" else "#1E262E"))
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isClickable = true
            minimumHeight = dp(76)
            setOnClickListener {
                detailEntranceId = e.id
                render(controller.state)
            }
        }
        card.addView(text(e.name, 26f, bold = true))
        val tags = buildList {
            add(accessLabel(e.visitorAccess))
            if (r.purposeMatch) add("선택한 목적과 맞음")
            if (r.sameAsCurrentDestination) add("현재 내비 안내와 같은 입구")
            if (Freshness.isStale(e, LocalDate.now())) add("확인 6개월 경과")
            add(verificationLabel(e.verification.status))
        }
        card.addView(text(tags.joinToString(" · "), 20f, color = "#B8C7D6"))
        return card.also {
            it.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        }
    }

    private fun photoView(e: Entrance): View? {
        val asset = e.photo.asset ?: return null
        if (e.photo.license == PhotoLicense.NONE) return null // 사용 권한이 확인되지 않은 사진은 표시하지 않는다
        return runCatching {
            val bmp = assets.open("photos/$asset").use { BitmapFactory.decodeStream(it) }
            ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                contentDescription = "${e.name} 사진"
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(280)).apply { topMargin = dp(12) }
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        }.getOrNull()
    }

    private fun testBanner() {
        content.addView(text("테스트 데이터 — 실제 시설·입구 정보가 아닙니다", 20f, bold = true, color = "#1A1A1A").apply {
            setBackgroundColor(Color.parseColor("#F2C94C"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        })
    }

    private fun title(t: String) = content.addView(text(t, 32f, bold = true).withTopMargin(16))
    private fun message(t: String) = content.addView(text(t, 26f).withTopMargin(16))
    private fun body(t: String) = content.addView(text(t, 22f).withTopMargin(8))
    private fun small(t: String) = content.addView(text(t, 18f, color = "#8FA3B8").withTopMargin(6))

    private fun button(label: String, onClick: () -> Unit) =
        content.addView(makeButton(label, onClick).withTopMargin(16))

    private fun row(vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach { v ->
            r.addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
        }
        content.addView(r.withTopMargin(16))
    }

    private fun makeButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        minimumHeight = dp(76)
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = if (selected) "✓ $label" else label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        minimumHeight = dp(76)
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun text(t: String, sp: Float, bold: Boolean = false, color: String = "#FFFFFF") = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun <V : View> V.withTopMargin(dpValue: Int): V = apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(dpValue) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        const val TAG = "Ipgukkaji"
    }
}
