package io.ipgukkaji.app.data

import android.content.Context
import io.ipgukkaji.app.domain.DataKind
import io.ipgukkaji.app.domain.Entrance
import io.ipgukkaji.app.domain.EntranceCatalog
import io.ipgukkaji.app.domain.Facility
import io.ipgukkaji.app.domain.GeoPoint
import io.ipgukkaji.app.domain.NaviPoiRef
import io.ipgukkaji.app.domain.Photo
import io.ipgukkaji.app.domain.PhotoLicense
import io.ipgukkaji.app.domain.Purpose
import io.ipgukkaji.app.domain.RemoteCheck
import io.ipgukkaji.app.domain.Source
import io.ipgukkaji.app.domain.SourceType
import io.ipgukkaji.app.domain.Verification
import io.ipgukkaji.app.domain.VerificationStatus
import io.ipgukkaji.app.domain.VisitorAccess
import org.json.JSONArray
import org.json.JSONObject

/** assets/entrances.json → EntranceCatalog. 형식은 docs/03_MVP_개발명세.md §3 */
object EntranceCatalogLoader {

    fun load(context: Context, assetName: String = "entrances.json"): EntranceCatalog {
        val text = context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parse(text)
    }

    fun parse(json: String): EntranceCatalog {
        val root = JSONObject(json)
        val facilities = root.getJSONArray("facilities").objects().map { it.toFacility() }
        val entrances = root.getJSONArray("entrances").objects().map { it.toEntrance() }
        val ids = facilities.map { it.id }.toSet()
        entrances.forEach { require(it.facilityId in ids) { "unknown facilityId ${it.facilityId} in ${it.id}" } }
        return EntranceCatalog(facilities, entrances, root.optNullableString("dataVersion"))
    }

    private fun JSONObject.toFacility() = Facility(
        id = getString("id"),
        dataKind = DataKind.valueOf(getString("dataKind")),
        officialName = getString("officialName"),
        aliases = optJSONArray("aliases").strings(),
        excludeKeywords = optJSONArray("excludeKeywords").strings(),
        address = getString("address"),
        center = getJSONObject("center").toPoint(),
        matchRadiusM = getDouble("matchRadiusM"),
        naviPoiIds = optJSONArray("naviPoiIds").objects()
            .map { NaviPoiRef(it.optString("provider"), it.optString("poiId")) }
            .filter { it.poiId.isNotBlank() },
        purposes = optJSONArray("purposes").objects().map { Purpose(it.getString("id"), it.getString("label")) },
    )

    private fun JSONObject.toEntrance(): Entrance {
        val photo = optJSONObject("photo")
        val source = getJSONObject("source")
        val v = getJSONObject("verification")
        return Entrance(
            id = getString("id"),
            facilityId = getString("facilityId"),
            name = getString("name"),
            routeTarget = getJSONObject("routeTarget").toPoint(),
            gate = optJSONObject("gate")?.toPoint(),
            approach = optNullableString("approach"),
            visitorAccess = VisitorAccess.valueOf(getString("visitorAccess")),
            conditions = optJSONArray("conditions").strings(),
            purposeIds = optJSONArray("purposeIds").strings(),
            heightLimitText = optNullableString("heightLimitText"),
            photo = Photo(
                asset = photo?.optNullableString("asset"),
                license = PhotoLicense.valueOf(photo?.optString("license", "NONE") ?: "NONE"),
                credit = photo?.optNullableString("credit"),
            ),
            source = Source(
                type = SourceType.valueOf(source.getString("type")),
                url = source.optNullableString("url"),
                retrievedAt = source.optNullableString("retrievedAt"),
            ),
            verification = Verification(
                status = VerificationStatus.valueOf(v.getString("status")),
                fieldCheckedAt = v.optNullableString("fieldCheckedAt"),
                routeCheckedAt = v.optNullableString("routeCheckedAt"),
                routeCheckedFrom = v.optJSONArray("routeCheckedFrom").strings(),
            ),
            coordinateSource = optNullableString("coordinateSource"),
            remoteChecks = optJSONArray("remoteChecks").objects().map {
                RemoteCheck(
                    provider = it.getString("provider"),
                    url = it.optNullableString("url"),
                    imageDate = it.optNullableString("imageDate"),
                    checkedAt = it.getString("checkedAt"),
                    note = it.optNullableString("note"),
                )
            },
        )
    }

    private fun JSONObject.toPoint() = GeoPoint(getDouble("lat"), getDouble("lon"))

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
