package io.ipgukkaji.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntranceRankerTest {
    private val all = Fixtures.catalog.entrances

    @Test
    fun `목적 미선택 - 진입 가능, 조건부, 미확인, 불가 순`() {
        val ids = EntranceRanker.rank(all, null, null).map { it.entrance.id }
        assertEquals(listOf("north", "east", "unknown", "staff"), ids)
    }

    @Test
    fun `목적 선택 시 해당 입구가 먼저 오되 진입 불가는 항상 마지막`() {
        val ids = EntranceRanker.rank(all, "cancer", null).map { it.entrance.id }
        assertEquals(listOf("east", "north", "unknown", "staff"), ids)
    }

    @Test
    fun `현재 내비 목적지와 같은 입구는 선택 불가로 표시`() {
        val ranked = EntranceRanker.rank(all, null, GeoPoint(37.50121, 127.00001))
        val north = ranked.first { it.entrance.id == "north" }
        assertTrue(north.sameAsCurrentDestination)
        assertFalse(north.selectable)
        assertFalse(ranked.first { it.entrance.id == "staff" }.selectable)
    }
}
