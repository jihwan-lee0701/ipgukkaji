package io.ipgukkaji.app.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// 이 패키지는 Android·Pleos SDK에 의존하지 않는다 (JVM 단위 테스트 대상).

data class GeoPoint(val lat: Double, val lon: Double) {
    val isZero: Boolean get() = lat == 0.0 && lon == 0.0

    /** haversine 거리(m) */
    fun distanceTo(other: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }
}

/** TEST: 시연·개발용 가상 데이터, REAL: 출처가 있는 실제 시설 데이터 */
enum class DataKind { TEST, REAL }

enum class VisitorAccess { ALLOWED, CONDITIONAL, NOT_ALLOWED, UNKNOWN }

/** 화면에 그대로 표시한다. 낮은 단계의 데이터를 높은 단계처럼 보이게 하지 않는다. */
/**
 * REMOTE_VIEW_CHECKED: 공식 안내 + 로드뷰(원격 영상)로 위치·표지를 확인. 로드뷰 촬영 시점 기준이므로 현재와 다를 수 있다.
 */
enum class VerificationStatus { TEST_DATA, UNVERIFIED_REALTIME, SOURCE_ONLY, REMOTE_VIEW_CHECKED, FIELD_VERIFIED, ROUTE_VERIFIED }

/**
 * 로드뷰 등 원격 영상으로 확인한 기록. 이미지는 저장·배포하지 않고 링크와 촬영 시점만 남긴다
 * (카카오 개발자 포럼 답변: 로드뷰 캡처 저장·재배포 불허).
 */
data class RemoteCheck(
    val provider: String,
    val url: String?,
    /** 로드뷰 화면에 표시된 촬영 시점 (예: "2026-08") */
    val imageDate: String?,
    val checkedAt: String,
    val note: String?,
)

enum class PhotoLicense { OWN, PERMITTED, NONE }

enum class SourceType { TEST, OFFICIAL_WEB, FIELD_SURVEY, FACILITY_PROVIDED, EXTERNAL_REALTIME }

data class NaviPoiRef(val provider: String, val poiId: String)

data class Purpose(val id: String, val label: String)

data class Facility(
    val id: String,
    val dataKind: DataKind,
    val officialName: String,
    val aliases: List<String> = emptyList(),
    /** 목적지 이름에 포함되면 이 시설이 아니라고 본다 (예: 분원 지명) */
    val excludeKeywords: List<String> = emptyList(),
    val address: String,
    val center: GeoPoint,
    val matchRadiusM: Double,
    val naviPoiIds: List<NaviPoiRef> = emptyList(),
    val purposes: List<Purpose> = emptyList(),
)

data class Photo(val asset: String?, val license: PhotoLicense, val credit: String?)

data class Source(val type: SourceType, val url: String?, val retrievedAt: String?)

data class Verification(
    val status: VerificationStatus,
    val fieldCheckedAt: String? = null,
    val routeCheckedAt: String? = null,
    /** 경로 진입을 확인한 출발 방향 (N/E/S/W) */
    val routeCheckedFrom: List<String> = emptyList(),
)

data class Entrance(
    val id: String,
    val facilityId: String,
    val name: String,
    /** 경로 목표 지점: 올바른 진입 도로 위, 게이트 직전 → RouteInfo.latitude/longitude */
    val routeTarget: GeoPoint,
    /** 실제 게이트 위치 → RouteInfo.displayLatitude/displayLongitude */
    val gate: GeoPoint?,
    val approach: String?,
    val visitorAccess: VisitorAccess,
    val conditions: List<String> = emptyList(),
    val purposeIds: List<String> = emptyList(),
    /** 표지판·공식 안내에 있을 때만 원문 그대로. 추정 금지. */
    val heightLimitText: String? = null,
    val photo: Photo = Photo(null, PhotoLicense.NONE, null),
    val source: Source,
    val verification: Verification,
    /** 좌표의 출처 (예: "OpenStreetMap node 4340470059 (ODbL)") */
    val coordinateSource: String? = null,
    val remoteChecks: List<RemoteCheck> = emptyList(),
) {
    val canRouteTo: Boolean get() = visitorAccess != VisitorAccess.NOT_ALLOWED && !routeTarget.isZero

    /** 가장 최근에 사람이 확인한 날짜 (공식 안내 확인일·원격 확인일·현장 확인일·경로 확인일 중 최신). TEST 데이터는 null 가능 */
    val lastConfirmedAt: LocalDate?
        get() = (listOf(source.retrievedAt, verification.fieldCheckedAt, verification.routeCheckedAt) +
            remoteChecks.map { it.checkedAt })
            .mapNotNull { it?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull() } }
            .maxOrNull()
}

/** 데이터 신선도 규칙 (docs/06_데이터운영계획.md): 마지막 확인 후 183일이 지나면 재확인 대상 */
object Freshness {
    const val STALE_AFTER_DAYS = 183L

    fun isStale(e: Entrance, today: LocalDate): Boolean {
        val last = e.lastConfirmedAt ?: return false
        return ChronoUnit.DAYS.between(last, today) > STALE_AFTER_DAYS
    }
}

data class EntranceCatalog(
    val facilities: List<Facility>,
    val entrances: List<Entrance>,
    /** 데이터 버전 (예: "2026-11-15.1"). 앱 화면과 변경 기록에서 같은 값을 쓴다 */
    val dataVersion: String? = null,
) {
    fun facility(id: String): Facility? = facilities.firstOrNull { it.id == id }
    fun entrance(id: String): Entrance? = entrances.firstOrNull { it.id == id }
    fun entrancesOf(facilityId: String): List<Entrance> = entrances.filter { it.facilityId == facilityId }

    /** 배포(release)용: TEST 시설과 그 입구를 뺀다. 가상 시설이 실제 목적지 근처 질문에 끼어들지 않게 한다. */
    fun withoutTestData(): EntranceCatalog {
        val testIds = facilities.filter { it.dataKind == DataKind.TEST }.map { it.id }.toSet()
        if (testIds.isEmpty()) return this
        return copy(
            facilities = facilities.filterNot { it.id in testIds },
            entrances = entrances.filterNot { it.facilityId in testIds },
        )
    }

    /**
     * 개발·시연용: TEST 시설과 그 입구를 통째로 평행 이동한다(상대 위치 유지). REAL 시설은 절대 옮기지 않는다.
     * 에뮬레이터 내비의 현재 위치 근처에서 가상 시설로 흐름을 시험하기 위한 것이다.
     */
    fun withTestFacilityMovedTo(facilityId: String, newCenter: GeoPoint): EntranceCatalog {
        val f = facility(facilityId) ?: return this
        require(f.dataKind == DataKind.TEST) { "REAL 시설은 옮길 수 없습니다: $facilityId" }
        val dLat = newCenter.lat - f.center.lat
        val dLon = newCenter.lon - f.center.lon
        fun GeoPoint.shift() = GeoPoint(lat + dLat, lon + dLon)
        return copy(
            facilities = facilities.map { if (it.id == facilityId) it.copy(center = newCenter) else it },
            entrances = entrances.map {
                if (it.facilityId == facilityId) it.copy(routeTarget = it.routeTarget.shift(), gate = it.gate?.shift()) else it
            },
        )
    }
}

/** 차량 내비 목적지 (NaviHelper DestinationInfo 를 SDK 독립 형태로 옮긴 것) */
data class Destination(
    val name: String,
    val address: String,
    val point: GeoPoint,
    val displayPoint: GeoPoint?,
    val poiId: String,
    val poiProvider: String,
    val remainDistanceM: Int?,
    val remainTimeS: Int?,
) {
    /** 목적지 미설정 시 내비가 빈 값/0 좌표를 줄 수 있다는 가정(A4)에 대한 방어 */
    val isSet: Boolean get() = !point.isZero
}

/** requestRoute 에 넘길 값 (SDK RouteInfo 로 변환된다) */
data class RouteTarget(
    val point: GeoPoint,
    val displayPoint: GeoPoint?,
    val poiName: String,
    val address: String,
) {
    companion object {
        fun of(facility: Facility, entrance: Entrance) = RouteTarget(
            point = entrance.routeTarget,
            displayPoint = entrance.gate,
            poiName = "${facility.officialName} ${entrance.name}",
            address = facility.address,
        )
    }
}
