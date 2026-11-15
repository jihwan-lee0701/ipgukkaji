package io.ipgukkaji.app.data

import io.ipgukkaji.app.domain.DataKind
import io.ipgukkaji.app.domain.PhotoLicense
import io.ipgukkaji.app.domain.SourceType
import io.ipgukkaji.app.domain.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 앱에 실제로 들어가는 assets/entrances.json 의 형식과 데이터 원칙을 검사한다. */
class EntranceDataFileTest {
    private val catalog = EntranceCatalogLoader.parse(
        File("src/main/assets/entrances.json").readText(Charsets.UTF_8),
    )

    @Test
    fun `파일이 파싱되고 모든 입구가 등록 시설에 속한다`() {
        assertTrue(catalog.facilities.isNotEmpty())
        val ids = catalog.facilities.map { it.id }.toSet()
        catalog.entrances.forEach { assertTrue(it.facilityId in ids) }
    }

    @Test
    fun `REAL 입구는 출처·좌표 출처·검증 상태를 갖고, 허락 없는 사진이 없다`() {
        val real = catalog.facilities.filter { it.dataKind == DataKind.REAL }.map { it.id }.toSet()
        val entrances = catalog.entrances.filter { it.facilityId in real }
        assertTrue("실제 시설 입구가 최소 1개 있어야 함", entrances.isNotEmpty())
        entrances.forEach { e ->
            assertTrue(e.id, e.source.type != SourceType.TEST)
            assertNotNull(e.id, e.source.url)
            assertNotNull(e.id, e.coordinateSource)
            assertTrue(e.id, e.verification.status != VerificationStatus.TEST_DATA)
            // 경로 검증 전에는 ROUTE_VERIFIED 로 표시하지 않는다
            if (e.verification.routeCheckedAt == null) {
                assertTrue(e.id, e.verification.status != VerificationStatus.ROUTE_VERIFIED)
            }
            // 사진은 직접 촬영이나 서면 허락만 (로드뷰 캡처 불가)
            if (e.photo.asset != null) assertTrue(e.id, e.photo.license != PhotoLicense.NONE)
            e.remoteChecks.forEach { rc -> assertNotNull(e.id, rc.url) }
        }
    }

    @Test
    fun `데이터 버전이 있고 변경 기록에 같은 버전이 적혀 있다`() {
        val v = catalog.dataVersion
        assertNotNull(v)
        val log = File("../DATA_CHANGELOG.md").readText(Charsets.UTF_8)
        assertTrue("DATA_CHANGELOG.md 에 $v 기록 필요", log.contains("| $v |"))
    }

    @Test
    fun `REAL 입구는 마지막 확인일이 있다`() {
        val real = catalog.facilities.filter { it.dataKind == DataKind.REAL }.map { it.id }.toSet()
        catalog.entrances.filter { it.facilityId in real }.forEach { assertNotNull(it.id, it.lastConfirmedAt) }
    }

    @Test
    fun `TEST 시설 입구는 테스트 상태로만 표시된다`() {
        val test = catalog.facilities.filter { it.dataKind == DataKind.TEST }.map { it.id }.toSet()
        catalog.entrances.filter { it.facilityId in test }.forEach {
            assertEquals(it.id, VerificationStatus.TEST_DATA, it.verification.status)
        }
    }
}
