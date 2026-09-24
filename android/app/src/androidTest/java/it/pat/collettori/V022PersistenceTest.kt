package it.pat.collettori

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class V022PersistenceTest{
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun pointEdgeAtomicOfflineRetryAndReopen()=runBlocking{
        val name="v022-graph-${UUID.randomUUID()}";var r=Repository(context,"$name.db",name)
        try{val pack=installTestCatalog(r);val data=JSONObject(pack.body);val target=data.getJSONArray("points").getJSONObject(0);val cid=target.memberships().single()
            val p=JSONObject(target.toString()).put("id",UUID.randomUUID().toString()).put("code","NEW").put("latitude",46.0006).put("manual_link_collector",cid).put("manual_link_ids",JSONArray(listOf(target.getString("id"))))
            val row=CatalogItem(r.owner(),p.getString("id"),"point",p.toString());r.saveCatalog(listOf(row));r.saveCatalog(listOf(row))
            assertEquals(1,r.dao.catalogNow(r.owner()).count{it.kind=="segment"})
            assertTrue(r.dao.allPending(r.owner()).any{JSONObject(it.body).getJSONObject("payload").getJSONArray("items").objects().map{it.getString("kind")}.containsAll(listOf("point","segment"))})
            r.db.close();r=Repository(context,"$name.db",name);assertEquals(1,r.localCatalog()!!.getJSONArray("segments").length())
            val moved=JSONObject(r.dao.catalogNow(r.owner()).first{it.id==p.getString("id")}.body).put("latitude",46.002)
            r.saveCatalog(listOf(row.copy(body=moved.toString())));val s=JSONObject(r.dao.catalogNow(r.owner()).single{it.kind=="segment"}.body)
            assertEquals(46.002,s.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0).getDouble(1),0.0)
        }finally{r.writes.cancel();r.store.clear();r.db.close();context.deleteDatabase("$name.db")};Unit
    }
    @Test fun realRolesSimulationAndRevocationBlockRepositoryWrites()=runBlocking{
        val name="v022-roles-${UUID.randomUUID()}";val r=Repository(context,"$name.db",name)
        try{val pack=installTestCatalog(r);val point=JSONObject(pack.body).getJSONArray("points").getJSONObject(0)
            val draft=r.begin(point,pack,"LIST");val pending=r.dao.allPending(r.owner()).size
            r.simulate("inspector");assertEquals("admin",r.realRole());assertTrue(r.canOperate());assertFalse(r.canManageCatalog())
            try{r.begin(point,pack,"LIST");fail("Simulation wrote")}catch(e:IllegalStateException){assertTrue(e.message!!.contains("Simulazione"))}
            assertEquals(pending,r.dao.allPending(r.owner()).size);assertEquals(1,r.dao.visitsNow(r.owner()).size)
            r.simulate("viewer");assertFalse(r.canOperate());r.simulate(null)
            r.store.save(testSession("viewer"));assertFalse(r.canOperate())
            for(write in listOf<suspend()->Unit>({r.begin(point,pack,"LIST");Unit},{r.saveDraft(draft.id,JSONObject(draft.body));Unit},{r.setPointAsphalt(point.getString("id"),true)},{r.deletePermanently(r.localDeletionScope("point",point.getString("id")))})){
                try{write();fail("Viewer wrote")}catch(_:IllegalStateException){}
            }
            r.store.save(testSession("inspector"));try{r.deletePermanently(r.localDeletionScope("point",point.getString("id")));fail("Operator deleted point")}catch(_:IllegalStateException){}
        }finally{r.writes.cancel();r.store.clear();r.db.close();context.deleteDatabase("$name.db")};Unit
    }
    @Test fun rapidSettingsPersistInOrderAndGpsUsesLatest()=runBlocking{
        val name="v022-settings-${UUID.randomUUID()}";var r=Repository(context,"$name.db",name)
        try{installTestCatalog(r);for(width in 1..10)r.updateSettings(FieldSettings(lineWidth=width,maxDistance=30.0,maxAccuracy=150.0))
            assertEquals(10,r.fieldSettings().lineWidth);r.writes.coroutineContext[Job]!!.children.toList().forEach{it.join()}
            r.db.close();r=Repository(context,"$name.db",name);assertEquals(10,r.fieldSettings().lineWidth);assertEquals(30.0,r.fieldSettings().maxDistance,0.0);assertEquals(150.0,r.fieldSettings().maxAccuracy,0.0)
        }finally{r.writes.cancel();r.store.clear();r.db.close();context.deleteDatabase("$name.db")};Unit
    }
}
