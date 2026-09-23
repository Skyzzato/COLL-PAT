package it.pat.collettori
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class V021SyncDependencyTest{
    @Test fun blockedPointConditionKeepsDependentInspectionQueued()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext;var sentInspections=0
        val client=OkHttpClient.Builder().addInterceptor{chain->val req=chain.request()
            val value=when(req.url.encodedPath.substringAfterLast('/')){
                "coll_pat_version"->JSONObject().put("minimum_supported_version","0.14").put("latest_version","0.21")
                "coll_pat_status"->JSONObject().put("generation",0).put("role","inspector")
                "coll_pat_deleted","coll_pat_storage_pending","coll_pat_inspection_summary"->JSONObject().put("items",JSONArray()).put("next",JSONObject.NULL)
                "coll_pat_catalog"->JSONObject().put("items",JSONArray()).put("archived",JSONArray()).put("has_more",false).put("revision",1)
                "coll_pat_save_inspection"->{sentInspections++;error("Dependent inspection must not be submitted")}
                else->error("Unexpected endpoint")
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("controlled").body(value.toString().toResponseBody()).build()
        }.build()
        val name="v021-dependency-${UUID.randomUUID()}";val r=Repository(context,"$name.db",name,client)
        try{val pack=installTestCatalog(r,"inspector");val v=r.begin(JSONObject(pack.body).getJSONArray("points").getJSONObject(0),pack,"LIST")
            r.changeTemplate(v.id,JSONObject(v.body),"ASPHALT_EXTERNAL")
            val patch=r.dao.allPending(r.owner()).single{it.kind=="object_patch"};r.dao.updatePending(patch.copy(state="CONFLICT",error="Synthetic concurrent edit"))
            assertFalse(r.sync());assertEquals(0,sentInspections);assertTrue(r.dao.allPending(r.owner()).any{it.kind=="shared_inspection"&&it.state=="IN_ATTESA"})
        }finally{r.store.clear();r.db.close();context.deleteDatabase("$name.db")};Unit
    }
}
