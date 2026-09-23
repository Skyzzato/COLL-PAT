package it.pat.collettori
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class V021PersistenceTest{
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun motivatedAbsenceSurvivesRestartAndRetryInvalidatesIt()=runBlocking{
        val name="v021-gps-${UUID.randomUUID()}";var r=Repository(context,"$name.db",name)
        try{val pack=installTestCatalog(r,"inspector");val v=r.begin(JSONObject(pack.body).getJSONArray("points").getJSONObject(0),pack,"LIST")
            r.beginGpsAttempt(v.id);r.recordNoGps(v.id,"Nessuna misura utilizzabile");assertTrue(noGpsConfirmed(JSONObject(r.dao.visit(v.id,v.owner)!!.body)))
            r.db.close();r=Repository(context,"$name.db",name);assertTrue(noGpsConfirmed(JSONObject(r.dao.visit(v.id,v.owner)!!.body)))
            r.beginGpsAttempt(v.id);assertFalse(noGpsConfirmed(JSONObject(r.dao.visit(v.id,v.owner)!!.body)));assertTrue(activeEvents(JSONObject(r.dao.visit(v.id,v.owner)!!.body)).isEmpty())
            r.recordNoGps(v.id,"Nuovo tentativo insufficiente");val saved=r.complete(v.id,JSONObject(r.dao.visit(v.id,v.owner)!!.body),"COMPLETO");assertTrue(periodic(saved));assertTrue(noGpsConfirmed(JSONObject(saved.body)))
        }finally{r.store.clear();r.db.close();context.deleteDatabase("$name.db")};Unit
    }
    @Test fun asphaltTemplateAtomicallyUpdatesPointAndNextInspection()=runBlocking{
        val name="v021-asphalt-${UUID.randomUUID()}";val r=Repository(context,"$name.db",name)
        try{val pack=installTestCatalog(r,"inspector");val p=JSONObject(pack.body).getJSONArray("points").getJSONObject(0);val v=r.begin(p,pack,"LIST")
            r.changeTemplate(v.id,JSONObject(v.body),"ASPHALT_EXTERNAL")
            val point=JSONObject(r.dao.catalogNow(r.owner()).first{it.id==p.getString("id")}.body);assertTrue(point.getBoolean("under_asphalt"));assertEquals("object_patch",r.dao.pendingVisit(point.getString("id"),r.owner()).single().kind)
            val next=r.begin(point,r.dao.pack(r.owner(),AppSpec.PACKAGE)!!,"LIST");assertEquals("ASPHALT_EXTERNAL",JSONObject(next.body).getString("model"))
        }finally{r.store.clear();r.db.close();context.deleteDatabase("$name.db")};Unit
    }
    @Test fun permanentDeletionPurgesDraftAuditSnapshotPhotoAndQueueWithoutTouchingOthers()=runBlocking{
        val name="v021-purge-${UUID.randomUUID()}";val r=Repository(context,"$name.db",name)
        try{val pack=installTestCatalog(r);val points=JSONObject(pack.body).getJSONArray("points");val deleted=r.begin(points.getJSONObject(0),pack,"LIST");val kept=r.begin(points.getJSONObject(1),pack,"LIST")
            val body=JSONObject(deleted.body);body.getJSONObject("sheet").put("notes","synthetic-erased-v021");r.saveDraft(deleted.id,body,true)
            r.dao.audit(Audit(UUID.randomUUID().toString(),r.owner(),deleted.id,null,TEST_USER,"2026-09-23T10:00:00Z","test",body.toString()))
            val uri=PhotoRepository(r).newCapture();context.contentResolver.openOutputStream(uri)!!.use{it.write("synthetic image".toByteArray())}
            r.dao.setting(Setting(r.owner(),"photos:"+deleted.id,JSONArray(listOf(InspectionPhoto(UUID.randomUUID().toString(),deleted.id,deleted.manholeId,uri.toString()).json())).toString()))
            r.deletePermanently(r.localDeletionScope("inspection",deleted.id))
            assertNull(r.dao.visit(deleted.id,r.owner()));assertNotNull(r.dao.visit(kept.id,r.owner()));assertTrue(r.dao.audits(r.owner(),deleted.id).isEmpty());assertNull(r.dao.settingValue(r.owner(),"snapshot:"+deleted.id));assertTrue(r.dao.pendingVisit(deleted.id,r.owner()).all{it.kind=="permanent_delete"})
            assertFalse(r.dao.settingsNow(r.owner()).any{it.value.contains("synthetic-erased-v021")});assertEquals(2,r.localCatalog()!!.getJSONArray("points").length())
            try{context.contentResolver.openInputStream(uri)?.close();fail("Deleted photo remains readable")}catch(_:java.io.FileNotFoundException){}
        }finally{r.store.clear();r.db.close();context.deleteDatabase("$name.db")};Unit
    }
}
