package io.ipgukkaji.app.domain

enum class MatchBasis { POI_ID, NAME_AND_DISTANCE, USER_CONFIRMED_NEARBY, OWN_REQUEST }

enum class NoMatchReason { NOT_REGISTERED, NAME_MATCH_BUT_FAR }

sealed interface MatchResult {
    /** 앱이 직전에 요청한 입구가 현재 목적지다 → 다시 추천하지 않는다(루프 방지) */
    data class OwnEntrance(val facility: Facility, val entrance: Entrance) : MatchResult
    data class Matched(val facility: Facility, val basis: MatchBasis) : MatchResult
    /** 좌표는 시설 반경 안이지만 이름이 다르다 → 자동 연결하지 않고 사용자에게 묻는다 */
    data class NearbyOnly(val facility: Facility, val distanceM: Double) : MatchResult
    data class NoMatch(val reason: NoMatchReason) : MatchResult
}

object NameNormalizer {
    private val removable = Regex("""[\s·.,\-_/()\[\]{}'"]""")

    fun normalize(raw: String): String =
        raw.lowercase().replace(removable, "").replace("본원", "")
}

/**
 * 차량 내비 목적지 → 등록 시설 연결. 규칙(03_MVP_개발명세 §3.3):
 * 1) 직전 요청 입구와 35m 이내 → OwnEntrance
 * 2) 내비 POI ID 일치(+거리 sanity check) → Matched(POI_ID)
 * 3) 이름 일치 AND 시설 반경 이내 → Matched(NAME_AND_DISTANCE)
 * 4) 이름만 일치, 거리 초과 → NoMatch(NAME_MATCH_BUT_FAR)  예: 본원 vs 분원
 * 5) 거리만 일치 → NearbyOnly
 * 6) 그 외 → NoMatch(NOT_REGISTERED)
 */
class FacilityMatcher(
    private val catalog: EntranceCatalog,
    private val ownEntranceRadiusM: Double = OWN_ENTRANCE_RADIUS_M,
) {
    fun match(dest: Destination, pendingEntranceId: String?): MatchResult {
        if (pendingEntranceId != null) {
            val e = catalog.entrance(pendingEntranceId)
            val f = e?.let { catalog.facility(it.facilityId) }
            if (e != null && f != null) {
                val near = e.routeTarget.distanceTo(dest.point) <= ownEntranceRadiusM
                // 내비가 좌표를 도로에 붙여 35m 이상 옮길 수 있으므로, 우리가 넘긴 poiName 이 그대로 돌아오면 같은 요청으로 본다.
                val sameName = dest.name.trim() == RouteTarget.of(f, e).poiName
                if (near || sameName) return MatchResult.OwnEntrance(f, e)
            }
        }

        if (dest.poiId.isNotBlank()) {
            val byPoi = catalog.facilities.firstOrNull { f ->
                f.naviPoiIds.any { ref ->
                    ref.poiId == dest.poiId && (ref.provider.isBlank() || ref.provider == dest.poiProvider)
                } && f.center.distanceTo(dest.point) <= f.matchRadiusM * 2
            }
            if (byPoi != null) return MatchResult.Matched(byPoi, MatchBasis.POI_ID)
        }

        val nameMatched = catalog.facilities.filter { nameMatches(it, dest.name) }
        val nameAndNear = nameMatched
            .map { it to it.center.distanceTo(dest.point) }
            .filter { (f, d) -> d <= f.matchRadiusM }
            .minByOrNull { it.second }
        if (nameAndNear != null) return MatchResult.Matched(nameAndNear.first, MatchBasis.NAME_AND_DISTANCE)
        if (nameMatched.isNotEmpty()) return MatchResult.NoMatch(NoMatchReason.NAME_MATCH_BUT_FAR)

        val nearby = catalog.facilities
            .map { it to it.center.distanceTo(dest.point) }
            .filter { (f, d) -> d <= f.matchRadiusM }
            .minByOrNull { it.second }
        if (nearby != null) return MatchResult.NearbyOnly(nearby.first, nearby.second)

        return MatchResult.NoMatch(NoMatchReason.NOT_REGISTERED)
    }

    private fun nameMatches(f: Facility, rawName: String): Boolean {
        if (rawName.isBlank()) return false
        if (f.excludeKeywords.any { it.isNotBlank() && rawName.contains(it) }) return false
        val n = NameNormalizer.normalize(rawName)
        return (listOf(f.officialName) + f.aliases)
            .map(NameNormalizer::normalize)
            .filter { it.isNotEmpty() }
            .any { n.contains(it) }
    }

    companion object {
        const val OWN_ENTRANCE_RADIUS_M = 35.0
    }
}
