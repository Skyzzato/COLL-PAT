package it.pat.collettori

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Controlled HTTP contract, paired with real PostgreSQL tests for server validation. */
@RunWith(AndroidJUnit4::class)
class V014SyncTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun submissionDuringDraftUploadKeepsOrderingAndFinalNotes()=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val rows=mutableMapOf<String,JSONObject>();val receipts=mutableMapOf<String,JSONObject>();val expected=mutableListOf<Int>()
        val seed=JSONObject(context.assets.open("demo-package.json").bufferedReader().use{it.readText()})
        val client=OkHttpClient.Builder().addInterceptor{chain->
            val req=chain.request();val buffer=Buffer();req.body?.writeTo(buffer);val args=if(buffer.size>0)JSONObject(buffer.readUtf8())else JSONObject();var code=200
            val result=when(req.url.encodedPath.substringAfterLast('/')){
                "coll_pat_version"->JSONObject().put("minimum_supported_version","0.14").put("latest_version","0.14")
                "coll_pat_status"->JSONObject().put("generation",0).put("role","inspector")
                "coll_pat_catalog"->{val items=JSONArray();listOf("collectors" to "collector","points" to "point","segments" to "segment").forEach{(array,kind)->seed.getJSONArray(array).objects().forEach{items.put(JSONObject().put("kind",kind).put("data",it))}};JSONObject().put("items",items).put("archived",JSONArray()).put("has_more",false).put("revision",1)}
                "coll_pat_inspection_summary"->JSONObject().put("items",JSONArray(rows.values.toList())).put("next",JSONObject.NULL)
                "coll_pat_save_inspection"->{
                    val op=args.getJSONObject("operation");val p=op.getJSONObject("payload");val id=p.getString("id");val operationId=op.getString("operation_id")
                    receipts[operationId]?:run{
                        val base=p.getInt("expected_revision");expected.add(base)
                        if(base!=(rows[id]?.getInt("revision")?:0)){code=409;JSONObject().put("message","conflict")}
                        else{
                            if(base==0){entered.countDown();check(release.await(15,TimeUnit.SECONDS))}
                            val row=JSONObject().put("id",id).put("manhole_id",p.getString("manhole_id")).put("status",p.getString("status")).put("revision",base+1).put("original",JSONObject(p.toString())).put("server_gps",JSONObject()).put("corrections",JSONArray()).put("photos",JSONArray())
                            rows[id]=row
                            JSONObject().put("operation_id",operationId).put("project_id",op.getString("project_id")).put("generation",0).put("revision",base+1).also{receipts[operationId]=it}
                        }
                    }
                }
                else->error("Unexpected endpoint: "+req.url.encodedPath)
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("contract").body(result.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val id=UUID.randomUUID().toString();val repo=Repository(context,"sync-$id.db","sync-$id",client)
        repo.store.save(JSONObject().put("base","https://sync.example.test").put("public_key","sb_publishable_test").put("user_id",DemoMode.user).put("project_id",AppSpec.LOCAL_PROJECT).put("protocol",2).put("access_token","test").put("refresh_token","test"))
        try{
            repo.prepareWorkspace();repo.catalog();val pack=repo.dao.pack(repo.owner(),AppSpec.PACKAGE)!!;val point=JSONObject(pack.body).getJSONArray("points").getJSONObject(0);val v=repo.begin(point,pack,"LIST")
            val event=JSONObject().put("id",UUID.randomUUID().toString()).put("acquired_at","2026-09-22T10:00:00Z").put("latitude",point.getDouble("latitude")).put("longitude",point.getDouble("longitude")).put("accuracy_m",5).put("age_s",0).put("permission","PRECISE").put("applied_limits",JSONObject().put("max_accuracy_m",10).put("radius_m",15)).put("local_evaluation",JSONObject().put("state","COMPATIBILE").put("distance_m",0))
            repo.appendEvent(v.id,event)
            val sending=async(Dispatchers.IO){repo.sync()}
            assertTrue(entered.await(15,TimeUnit.SECONDS))
            val body=JSONObject(repo.dao.visit(v.id,v.owner)!!.body);body.getJSONObject("sheet").put("notes","Final notes while draft upload is in flight")
            repo.complete(v.id,body,"COMPLETO");assertEquals(2,repo.dao.pendingVisit(v.id,v.owner).size)
            release.countDown();assertFalse(sending.await());assertTrue(repo.sync())
            assertEquals(listOf(0,1),expected);assertEquals("COMPLETO",rows.getValue(v.id).getString("status"));assertEquals("Final notes while draft upload is in flight",rows.getValue(v.id).getJSONObject("original").getJSONObject("sheet").getString("notes"))
            assertEquals("RICEVUTO_SERVER",repo.dao.visit(v.id,v.owner)!!.sync);assertTrue(repo.dao.pendingVisit(v.id,v.owner).isEmpty())
        }finally{release.countDown();repo.store.clear();repo.db.close();context.deleteDatabase("sync-$id.db")}
        Unit
    }
}
