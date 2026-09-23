package it.pat.collettori

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
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

@RunWith(AndroidJUnit4::class)
class V02PersistenceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class Server {
        val items=linkedMapOf<String,JSONObject>();val deleted=mutableListOf<JSONObject>();var historyError=false;var stale=false;var role="admin";var historyCalls=0
        val client=OkHttpClient.Builder().addInterceptor{chain->
            val req=chain.request();val buf=Buffer();req.body?.writeTo(buf);val args=if(buf.size>0)JSONObject(buf.readUtf8())else JSONObject();var status=200
            val response=when(req.url.encodedPath.substringAfterLast('/')){
                "coll_pat_deleted"->JSONObject().put("items",JSONArray(deleted))
                "coll_pat_storage_pending"->JSONObject().put("items",JSONArray())
                "coll_pat_deletion_preview"->{val id=args.getString("p_id");val removed=items.values.filter{it.getJSONObject("data").let{b->b.getString("id")==id||b.memberships()==listOf(id)}}.map{it.getJSONObject("data").getString("id")}.sorted();val shared=items.values.filter{id in it.getJSONObject("data").memberships()&&it.getJSONObject("data").getString("id") !in removed}.map{it.getJSONObject("data").getString("id")}.sorted();JSONObject().put("id",id).put("kind",args.getString("p_kind")).put("removed",JSONArray(removed)).put("shared",JSONArray(shared)).put("inspections",JSONArray()).put("photos",JSONArray()).put("verified",true)}
                "coll_pat_version"->JSONObject().put("minimum_supported_version","0.14").put("latest_version","0.2")
                "coll_pat_status"->JSONObject().put("generation",0).put("role",role)
                "coll_pat_catalog"->JSONObject().put("items",JSONArray(items.values.filter{stale||deleted.none{d->d.getString("id")==it.getJSONObject("data").getString("id")}})).put("archived",JSONArray()).put("deleted",JSONArray(deleted)).put("has_more",false).put("revision",1)
                "coll_pat_history","coll_pat_inspection_summary"->{historyCalls++;if(historyError)status=503;JSONObject().put("items",JSONArray()).put("has_more",false).put("next",JSONObject.NULL)}
                "coll_pat_apply"->{val op=args.getJSONObject("operation");val p=op.getJSONObject("payload");p.getJSONArray("items").objects().forEach{row->items[row.getJSONObject("data").getString("id")]=row};receipt(op)}
                "coll_pat_delete_permanent"->{val op=args.getJSONObject("operation");val id=op.getJSONObject("payload").getString("id");items.values.filter{it.getJSONObject("data").let{p->p.getString("id")==id||p.memberships()==listOf(id)}}.forEach{deleted.add(JSONObject().put("id",it.getJSONObject("data").getString("id")).put("kind",it.getString("kind")).put("deleted",true))};receipt(op)}
                else->error("Unexpected request "+req.url.encodedPath)
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("contract").body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        fun receipt(op:JSONObject)=JSONObject().put("operation_id",op.getString("operation_id")).put("project_id",op.getString("project_id")).put("generation",0)
    }
    private fun repo(name:String,server:Server)=Repository(context,"$name.db",name,server.client)
    private suspend fun initialize(repo:Repository){repo.store.save(testSession().put("base","https://v02.example.test"));repo.dao.setting(Setting(repo.owner(),"generation","0"))}
    private fun items(repo:Repository):List<CatalogItem>{
        val c=collectorDefaults(UUID.randomUUID().toString(),"C-NEW","Collettore manuale")
        val q=manualPoint(JSONObject(),UUID.randomUUID().toString(),c.getString("id"),"0002","46.02","11,03","2","A",emptyList(),true)
        val p=manualPoint(JSONObject(),UUID.randomUUID().toString(),c.getString("id"),"0001","46,01","11.02","1","A",listOf(q.getString("id")),false)
        return listOf(CatalogItem(repo.owner(),c.getString("id"),"collector",c.toString()))+listOf(p,q).map{CatalogItem(repo.owner(),it.getString("id"),"point",it.toString())}
    }
    @Test fun offlineCollectorAndPointsPersistThenSyncAndDeleteWithoutResurrection()=runBlocking{
        val name="v02-${UUID.randomUUID()}";val server=Server();var r=repo(name,server)
        try{
            initialize(r);val created=items(r);val cid=created.first().id;r.saveCatalog(created)
            assertEquals(3,queuedLogicalIds(r.dao.allPending(r.owner())).size)
            r.db.close();r=repo(name,server)
            assertEquals(3,r.dao.catalogNow(r.owner()).size);assertEquals(2,r.localCatalog()!!.getJSONArray("points").length())
            assertTrue(r.sync());assertEquals(3,server.items.size);assertTrue(r.dao.allPending(r.owner()).isEmpty())
            val renamed=JSONObject(r.dao.catalogNow(r.owner()).first{it.kind=="collector"}.body).put("code","RENAMED")
            r.saveCatalog(listOf(CatalogItem(r.owner(),cid,"collector",renamed.toString())));assertTrue(r.sync())
            assertTrue(r.localCatalog()!!.getJSONArray("points").objects().all{cid in it.memberships()})
            assertFalse(r.deleteCollector(cid));assertEquals(0,r.localCatalog()!!.getJSONArray("collectors").length());assertEquals(1,r.dao.allPending(r.owner()).size)
            r.db.close();r=repo(name,server);assertEquals(0,r.localCatalog()!!.getJSONArray("points").length())
            server.stale=true;r.catalog();assertEquals(0,r.localCatalog()!!.getJSONArray("collectors").length())
            assertTrue(r.sync());r.catalog();assertTrue(r.dao.catalogNow(r.owner()).isEmpty());assertTrue(r.dao.allPending(r.owner()).isEmpty())
        }finally{r.store.clear();r.db.close();context.deleteDatabase("$name.db")}
    }
    @Test fun neverSentCollectorIsActuallyRemovedTogetherWithItsQueue()=runBlocking{
        val name="v02-local-${UUID.randomUUID()}";val r=repo(name,Server())
        try{initialize(r);val created=items(r);r.saveCatalog(created);assertFalse(r.deleteCollector(created.first().id));assertTrue(r.dao.catalogNow(r.owner()).isEmpty());assertEquals("permanent_delete",r.dao.allPending(r.owner()).single().kind);assertEquals(0,r.localCatalog()!!.getJSONArray("points").length())}
        finally{r.store.clear();r.db.close();context.deleteDatabase("$name.db")}
    }
    @Test fun refreshTimestampOnlyChangesAfterBothCatalogAndInspectionsSucceed()=runBlocking{
        val name="v02-refresh-${UUID.randomUUID()}";val server=Server();val r=repo(name,server)
        try{
            initialize(r);assertNull(r.dao.settingValue(r.owner(),"database-last-success"));r.refreshDatabase();val success=r.dao.settingValue(r.owner(),"database-last-success");assertNotNull(success);assertEquals(1,server.historyCalls)
            val created=items(r);r.saveCatalog(created);server.historyError=true
            try{r.refreshDatabase();fail("Expected HTTP failure")}catch(_:ApiError){}
            assertEquals(success,r.dao.settingValue(r.owner(),"database-last-success"));assertEquals(3,r.dao.catalogNow(r.owner()).size);assertEquals(3,queuedLogicalIds(r.dao.allPending(r.owner())).size);assertFalse(r.refreshing.value)
        }finally{r.store.clear();r.db.close();context.deleteDatabase("$name.db")}
    }
    @Test fun verifiedInspectorCannotCreateOrDeleteCatalog()=runBlocking{
        val name="v02-role-${UUID.randomUUID()}";val server=Server();val r=repo(name,server)
        try{
            initialize(r);val created=items(r);r.saveCatalog(created);server.role="inspector";r.reconcile();assertEquals("inspector",r.dao.settingValue(r.owner(),"verified-role"))
            try{r.saveCatalog(created);fail()}catch(_:IllegalStateException){}
            try{r.deleteCollector(created.first().id);fail()}catch(_:IllegalStateException){}
            assertEquals(3,r.dao.catalogNow(r.owner()).size)
        }finally{r.store.clear();r.db.close();context.deleteDatabase("$name.db")}
    }
    @Test fun cancellingPartOfLargeUnsentBatchKeepsChunkingAndOtherAssets()=runBlocking{
        val name="v02-chunks-${UUID.randomUUID()}";val r=repo(name,Server())
        try{
            initialize(r)
            val first=items(r);val second=items(r).map{if(it.kind=="collector")it.copy(body=JSONObject(it.body).put("code","C-SECOND").toString())else it}
            val rows=(first+second).map{if(it.kind=="point")it.copy(body=JSONObject(it.body).put("note","x".repeat(2300*1024)).toString())else it}
            r.saveCatalog(rows);assertTrue(r.dao.allPending(r.owner()).any{it.kind=="catalog_chunk"})
            assertEquals(4,r.localCatalog()!!.getJSONArray("points").length())
            assertTrue(r.dao.settingsNow(r.owner()).any{it.key.startsWith("catalog-upload:")&&it.value.length>4*1024*1024})
            assertFalse(r.deleteCollector(first.first().id))
            val queue=r.dao.allPending(r.owner());assertTrue(queue.any{it.kind=="catalog_chunk"});assertEquals(3,r.dao.catalogNow(r.owner()).size)
            val final=queue.single{it.kind=="catalog"};val payload=JSONObject(r.dao.settingValue(r.owner(),"catalog-upload:"+final.operationId)!!)
            assertEquals(second.map{it.id}.toSet(),payload.getJSONArray("items").objects().map{it.getJSONObject("data").getString("id")}.toSet())
            val unicode="è😀".repeat(400000);r.dao.setting(Setting(r.owner(),"large-unicode",unicode));assertEquals(unicode,r.dao.settingValue(r.owner(),"large-unicode"))
        }finally{r.store.clear();r.db.close();context.deleteDatabase("$name.db")}
    }
    @Test fun localDeleteAlsoRewritesSeparateUpdatesOfSharedPoints()=runBlocking{
        val name="v02-shared-local-${UUID.randomUUID()}";val server=Server();val r=repo(name,server)
        try{
            initialize(r);val created=items(r);val first=created.first().id
            val other=collectorDefaults(UUID.randomUUID().toString(),"OTHER","Altro collettore")
            r.saveCatalog(listOf(CatalogItem(r.owner(),other.getString("id"),"collector",other.toString()))+created)
            val shared=created.last().let{it.copy(body=JSONObject(it.body).put("collectors",JSONArray(listOf(first,other.getString("id")))).toString())}
            r.saveCatalog(listOf(shared));assertFalse(r.deleteCollector(first));assertTrue(r.sync())
            assertEquals(listOf(other.getString("id")),server.items.getValue(shared.id).getJSONObject("data").memberships())
            assertFalse(server.items.containsKey(first));assertTrue(r.dao.allPending(r.owner()).isEmpty())
        }finally{r.store.clear();r.db.close();context.deleteDatabase("$name.db")}
    }
}
