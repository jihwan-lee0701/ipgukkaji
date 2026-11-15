package io.ipgukkaji.app.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.ipgukkaji.app.domain.GeoPoint
import io.ipgukkaji.app.domain.NearbyParking
import io.ipgukkaji.app.domain.NearbyParkingSource
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 카카오 로컬 API "카테고리로 장소 검색"(category_group_code=PK6 주차장)으로 목적지 주변 주차장을 실시간 조회한다.
 * - 결과는 호출한 화면에만 넘기고 저장·캐싱하지 않는다 (카카오 운영정책상 저장·캐싱 제한).
 * - 응답에는 주차장 대표 좌표 하나만 있고 진입구 좌표·이용 조건은 없다 → 화면에 "입구·조건 미확인"으로 표시한다.
 * - REST API 키는 저장소에 넣지 않는다 (local.properties 의 kakao.rest.api.key → BuildConfig). 키가 없으면 기능을 숨긴다.
 */
class KakaoNearbyParkingSource(private val restApiKey: String) : NearbyParkingSource {
    private val main = Handler(Looper.getMainLooper())

    override val available: Boolean get() = restApiKey.isNotBlank()
    override val providerLabel: String = "카카오"

    override fun search(center: GeoPoint, radiusM: Int, callback: (Result<List<NearbyParking>>) -> Unit) {
        Thread {
            val result = runCatching { request(center, radiusM) }
            result.exceptionOrNull()?.let { Log.w(TAG, "nearby parking search failed", it) }
            main.post { callback(result) }
        }.start()
    }

    private fun request(center: GeoPoint, radiusM: Int): List<NearbyParking> {
        val q = listOf(
            "category_group_code" to "PK6",
            "x" to center.lon.toString(),
            "y" to center.lat.toString(),
            "radius" to radiusM.coerceIn(1, 20_000).toString(),
            "sort" to "distance",
            "size" to "10",
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val conn = URL("$ENDPOINT?$q").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.setRequestProperty("Authorization", "KakaoAK $restApiKey")
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "Ipgukkaji/Nearby"
        private const val ENDPOINT = "https://dapi.kakao.com/v2/local/search/category.json"

        /** 응답 JSON → 목록 (문서 필드: place_name, road_address_name, address_name, x=경도, y=위도, distance) */
        fun parse(body: String): List<NearbyParking> {
            val docs = JSONObject(body).getJSONArray("documents")
            return (0 until docs.length()).map { i ->
                val d = docs.getJSONObject(i)
                NearbyParking(
                    name = d.getString("place_name"),
                    address = d.optString("road_address_name").ifBlank { d.optString("address_name") },
                    point = GeoPoint(d.getString("y").toDouble(), d.getString("x").toDouble()),
                    distanceM = d.optString("distance").toIntOrNull(),
                )
            }
        }
    }
}
