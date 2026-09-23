package it.pat.collettori

import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V013PersistenceTest {
    val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    val repo get()=(context.applicationContext as PilotApplication).repository
    @Test fun explicitRoomMigrationPreservesV1RowsAndSuspendsOldOperations()=runBlocking{
        val name="v013-migration-test.db";context.deleteDatabase(name)
        val raw=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
        raw.execSQL("CREATE TABLE visits (id TEXT NOT NULL PRIMARY KEY,owner TEXT NOT NULL,manholeId TEXT NOT NULL,datasetId TEXT NOT NULL,body TEXT NOT NULL,operational TEXT NOT NULL,sync TEXT NOT NULL,receipt TEXT,error TEXT)")
        raw.execSQL("CREATE INDEX index_visits_owner ON visits(owner)");raw.execSQL("CREATE INDEX index_visits_manholeId ON visits(manholeId)")
        raw.execSQL("CREATE TABLE outbox (operationId TEXT NOT NULL PRIMARY KEY,owner TEXT NOT NULL,visitId TEXT NOT NULL,revision INTEGER NOT NULL,body TEXT NOT NULL,state TEXT NOT NULL,error TEXT)")
        raw.execSQL("CREATE INDEX index_outbox_owner ON outbox(owner)");raw.execSQL("CREATE UNIQUE INDEX index_outbox_visitId_revision ON outbox(visitId,revision)")
        raw.execSQL("CREATE TABLE packages (owner TEXT NOT NULL,id TEXT NOT NULL,area TEXT NOT NULL,body TEXT NOT NULL,checksum TEXT NOT NULL,bytes INTEGER NOT NULL,installedAt TEXT NOT NULL,baseReady INTEGER NOT NULL,PRIMARY KEY(owner,id))")
        raw.execSQL("CREATE INDEX index_packages_owner ON packages(owner)");raw.execSQL("CREATE INDEX index_packages_area ON packages(area)")
        raw.execSQL("CREATE TABLE settings (owner TEXT NOT NULL,`key` TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(owner,`key`))")
        raw.execSQL("INSERT INTO visits VALUES('old','alice','point','dataset','{preserved}','BOZZA','SALVATO_LOCALMENTE',NULL,NULL)")
        raw.execSQL("INSERT INTO outbox VALUES('old-op','alice','old',1,'{original}','IN_ATTESA',NULL)")
        raw.version=1;raw.close()
        val db=Room.databaseBuilder(context,LocalDatabase::class.java,name).addMigrations(MIGRATION_1_2,MIGRATION_2_3).build()
        assertEquals("{preserved}",db.dao().visit("old","alice")!!.body)
        val op=db.dao().allPending("alice").single();assertEquals("LEGACY_SUSPENDED",op.state);assertEquals(-1L,op.generation)
        db.dao().retryBlocked("alice");assertTrue(db.dao().pending("alice").isEmpty());db.close();context.deleteDatabase(name);Unit
    }
    private suspend fun draft():Visit{val pack=installTestCatalog(repo,"inspector");return repo.begin(JSONObject(pack.body).getJSONArray("points").getJSONObject(0),pack,"LIST")}
    private suspend fun evidence(v:Visit){repo.appendEvent(v.id,JSONObject().put("inspection_id",v.id).put("manhole_id",v.manholeId).put("method",AcquisitionPolicy.METHOD).put("sample_count",5).put("duration_ms",5000).put("sample_span_ms",4000).put("match_outcome","VERIFIED").put("id",java.util.UUID.randomUUID().toString()).put("acquired_at","2026-01-01T00:00:00Z").put("latitude",46.0).put("longitude",11.0).put("accuracy_m",5).put("age_s",0).put("permission","PRECISE").put("applied_limits",JSONObject().put("max_accuracy_m",10).put("radius_m",15)).put("local_evaluation",JSONObject().put("state","COMPATIBILE").put("distance_m",8)))}
    @Test fun queuedDraftDoubleTapAndNewVisitSameDay()=runBlocking{
        val first=draft();val second=draft();assertNotEquals(first.id,second.id)
        val b=JSONObject(first.body);b.getJSONObject("sheet").put("notes","Last note intact").put("exception_reason","Synthetic failed fix")
        repo.saveDraft(first.id,b,queueNow=true);assertEquals(1,repo.dao.pendingVisit(first.id,first.owner).size);evidence(first)
        coroutineScope{awaitAll(async{repo.complete(first.id,b,"COMPLETO")},async{repo.complete(first.id,b,"COMPLETO")})}
        assertEquals(2,repo.dao.pendingVisit(first.id,first.owner).size);assertEquals("Last note intact",JSONObject(repo.dao.visit(first.id,first.owner)!!.body).getJSONObject("sheet").getString("notes"))
        repo.saveDraft(first.id,JSONObject(first.body));assertEquals("COMPLETO",repo.dao.visit(first.id,first.owner)!!.operational)
        val b2=JSONObject(second.body);b2.getJSONObject("sheet").put("exception_reason","Synthetic failed fix");evidence(second);repo.complete(second.id,b2,"COMPLETO")
        assertTrue(repo.dao.pendingVisit(second.id,second.owner).isNotEmpty());Unit
    }
    @Test fun eventCancellationAndOfflineAuditSurviveReopen()=runBlocking{
        val v=draft();evidence(v)
        val before=repo.dao.visit(v.id,v.owner)!!;val event=JSONObject(before.body).getJSONArray("events").getJSONObject(0).getString("id")
        repo.cancel(v.id,event,"Rilevazione errata");assertNull(lastEvidence(JSONObject(repo.dao.visit(v.id,v.owner)!!.body)));assertTrue(repo.dao.pendingVisit(v.id,v.owner).isEmpty())
        evidence(v);val body=JSONObject(repo.dao.visit(v.id,v.owner)!!.body);body.getJSONObject("sheet").put("exception_reason","Synthetic fix");repo.complete(v.id,body,"COMPLETO")
        repo.cancel(v.id,null,"Prova annullata")
        val reopened=Repository(context);try{assertTrue(isCancelled(reopened.dao.visit(v.id,v.owner)!!));assertEquals(listOf("shared_inspection","cancel"),reopened.dao.pendingVisit(v.id,v.owner).map{it.kind});assertEquals(2,reopened.dao.audits(v.owner,v.id).size)}finally{reopened.db.close()};Unit
    }
    @Test fun authenticatedCatalogEditsArePersisted()=runBlocking{
        val pack=installTestCatalog(repo,"admin");val items=repo.dao.catalogNow(repo.owner());val c=items.first{it.kind=="collector"}
        val changed=JSONObject(c.body).put("description","Local edit persists")
        repo.saveCatalog(listOf(c.copy(body=changed.toString())))
        assertEquals("Local edit persists",JSONObject(repo.dao.catalogNow(repo.owner()).first{it.id==c.id}.body).getString("description"));Unit
    }
}
