package io.ipgukkaji.app.domain

/** 테스트 전용 가상 시설. 실제 병원 데이터가 아니다. */
object Fixtures {
    val hospitalCenter = GeoPoint(37.5000, 127.0000)

    val hospital = Facility(
        id = "h1",
        dataKind = DataKind.TEST,
        officialName = "가상대학교병원",
        aliases = listOf("가상대병원"),
        excludeKeywords = listOf("분당"),
        address = "가상 주소",
        center = hospitalCenter,
        matchRadiusM = 400.0,
        naviPoiIds = listOf(NaviPoiRef(provider = "42dot", poiId = "POI-H1")),
        purposes = listOf(Purpose("main", "본관 외래"), Purpose("cancer", "암병원")),
    )

    /** 이름이 비슷하지만 다른 곳에 있는 시설(분원) — 등록하지 않은 상태를 흉내 낸다 */
    val branchLocation = GeoPoint(37.3500, 127.1200) // 약 19km 떨어짐

    private fun entrance(
        id: String,
        target: GeoPoint,
        access: VisitorAccess,
        purposes: List<String>,
    ) = Entrance(
        id = id,
        facilityId = "h1",
        name = "$id 입구",
        routeTarget = target,
        gate = target,
        approach = null,
        visitorAccess = access,
        purposeIds = purposes,
        source = Source(SourceType.TEST, null, null),
        verification = Verification(VerificationStatus.TEST_DATA),
    )

    val east = entrance("east", GeoPoint(37.5000, 127.0015), VisitorAccess.CONDITIONAL, listOf("cancer"))
    val north = entrance("north", GeoPoint(37.5012, 127.0000), VisitorAccess.ALLOWED, listOf("main"))
    val staff = entrance("staff", GeoPoint(37.4988, 127.0000), VisitorAccess.NOT_ALLOWED, emptyList())
    val unknown = entrance("unknown", GeoPoint(37.5005, 126.9990), VisitorAccess.UNKNOWN, emptyList())

    val catalog = EntranceCatalog(listOf(hospital), listOf(east, north, staff, unknown))

    fun dest(
        name: String,
        point: GeoPoint,
        poiId: String = "",
        provider: String = "",
    ) = Destination(name, "", point, null, poiId, provider, null, null)

    val noDestination = Destination("", "", GeoPoint(0.0, 0.0), null, "", "", null, null)
}

class ManualScheduler : Scheduler {
    var now = 0L
        private set
    private val tasks = mutableListOf<Triple<Long, Int, () -> Unit>>()
    private var seq = 0

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val entry = Triple(now + delayMs, seq++, task)
        tasks += entry
        return Cancellable { tasks.remove(entry) }
    }

    fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            val next = tasks.filter { it.first <= until }.minWithOrNull(compareBy({ it.first }, { it.second })) ?: break
            tasks.remove(next)
            now = next.first
            next.third()
        }
        now = until
    }
}

/** 가짜 내비. 실제 NaviHelper 동작을 보장하지 않는다 — 로직 검증 전용. */
class FakeNavi : NaviGateway {
    var events: NaviEvents? = null
    var connectResult: Throwable? = null
    var destinationToReturn: Destination? = null
    var throwOnRoute: Throwable? = null
    val routeRequests = mutableListOf<RouteTarget>()
    var destinationRequests = 0
    var disconnected = false

    override fun connect(events: NaviEvents, onConnected: () -> Unit, onFailed: (Throwable) -> Unit) {
        this.events = events
        val err = connectResult
        if (err == null) onConnected() else onFailed(err)
    }

    override fun requestDestination() {
        destinationRequests++
        destinationToReturn?.let { events?.onDestination(it) } // null 이면 응답하지 않음(목적지 없음 가정 A4)
    }

    override fun requestRoute(target: RouteTarget) {
        throwOnRoute?.let { throw it }
        routeRequests += target
    }

    override fun disconnect() {
        disconnected = true
    }
}
