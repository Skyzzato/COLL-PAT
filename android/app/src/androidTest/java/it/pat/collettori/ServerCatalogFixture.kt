package it.pat.collettori

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Authenticated server-shaped catalogue used only by Android tests; no local demo mode. */
const val TEST_USER="00000000-0000-4000-8000-000000000001"
fun testSession(role:String="admin")=JSONObject().put("base","https://test.example.invalid").put("user_id",TEST_USER).put("project_id",AppSpec.LOCAL_PROJECT).put("protocol",AppSpec.PROTOCOL).put("access_token","test").put("refresh_token","test").put("public_key","sb_publishable_test").put("role",role)
fun testCatalog()=JSONObject().put("name","Catalogo test server").put("osm",true).put("basemap",JSONObject.NULL).put("collectors",JSONArray()).put("points",JSONArray()).put("segments",JSONArray())
suspend fun installTestCatalog(repo:Repository,role:String="admin"):OfflinePackage{
    repo.store.save(testSession(role));val owner=repo.owner();val collectorId="00000000-0000-4000-8000-000000000101"
    val collector=collectorDefaults(collectorId,"TEST-SERVER","Collettore server test")
    val points=listOf(
        JSONObject().put("id","00000000-0000-4000-8000-000000000201").put("code","TS-001").put("description","Pozzetto test").put("latitude",46.0).put("longitude",11.0).put("collectors",JSONArray(listOf(collectorId))).put("asset_type","MANHOLE").put("uncertainty_m",2).put("under_asphalt",false),
        JSONObject().put("id","00000000-0000-4000-8000-000000000202").put("code","TS-002").put("description","Pozzetto test 2").put("latitude",46.0003).put("longitude",11.0003).put("collectors",JSONArray(listOf(collectorId))).put("asset_type","MANHOLE").put("uncertainty_m",2).put("under_asphalt",false)
    )
    repo.dao.putCatalog(CatalogItem(owner,collectorId,"collector",collector.toString()))
    points.forEach{repo.dao.putCatalog(CatalogItem(owner,it.getString("id"),"point",it.toString()))}
    repo.dao.setting(Setting(owner,"generation","0"));repo.rebuildPackage(owner)
    return repo.dao.pack(owner,AppSpec.PACKAGE)!!
}

fun testEvidence(v:Visit,latitude:Double=46.0,longitude:Double=11.0):JSONObject{
    val window=GpsWindow(0,20.0);listOf(3_000_000_000L,5_000_000_000L,7_500_000_000L).forEach{window.add(GpsSample(it,latitude,longitude,5.0),it)}
    return window.finish(8_000_000_000L).put("id",UUID.randomUUID().toString()).put("inspection_id",v.id).put("manhole_id",v.manholeId).put("method",AcquisitionPolicy.METHOD)
        .put("permission","PRECISE").put("mock",false).put("acquired_at",java.time.Instant.now().toString()).put("applied_limits",JSONObject().put("max_accuracy_m",20).put("radius_m",20)).put("local_evaluation",JSONObject().put("state","COMPATIBILE").put("distance_m",0)).put("match_outcome","VERIFIED")
}
