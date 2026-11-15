package io.ipgukkaji.app.domain

data class RankedEntrance(
    val entrance: Entrance,
    val purposeMatch: Boolean,
    /** 현재 내비 목적지와 30m 이내 → "현재 내비 안내와 같은 입구", 경로를 바꿀 필요 없음 */
    val sameAsCurrentDestination: Boolean,
) {
    val selectable: Boolean get() = entrance.canRouteTo && !sameAsCurrentDestination
}

/**
 * 정렬: 방문객 진입 불가는 항상 맨 아래 → 선택한 방문 목적 일치 우선 → ALLOWED > CONDITIONAL > UNKNOWN → 이름
 */
object EntranceRanker {
    const val SAME_AS_DESTINATION_M = 30.0

    fun rank(
        entrances: List<Entrance>,
        purposeId: String?,
        currentDestination: GeoPoint?,
    ): List<RankedEntrance> = entrances
        .map { e ->
            RankedEntrance(
                entrance = e,
                purposeMatch = purposeId != null && purposeId in e.purposeIds,
                sameAsCurrentDestination = currentDestination != null &&
                    !e.routeTarget.isZero &&
                    e.routeTarget.distanceTo(currentDestination) <= SAME_AS_DESTINATION_M,
            )
        }
        .sortedWith(
            compareBy<RankedEntrance> { if (it.entrance.visitorAccess == VisitorAccess.NOT_ALLOWED) 1 else 0 }
                .thenBy { if (it.purposeMatch) 0 else 1 }
                .thenBy { accessOrder(it.entrance.visitorAccess) }
                .thenBy { it.entrance.name },
        )

    private fun accessOrder(a: VisitorAccess) = when (a) {
        VisitorAccess.ALLOWED -> 0
        VisitorAccess.CONDITIONAL -> 1
        VisitorAccess.UNKNOWN -> 2
        VisitorAccess.NOT_ALLOWED -> 3
    }
}
