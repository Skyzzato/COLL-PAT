package it.pat.collettori

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class V015PersistenceTest {
    @Test fun explicitDraftSaveAtomicallyQueuesAndSurvivesReopening()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="v015-persistence-${UUID.randomUUID()}";val repo=Repository(context,"$name.db",name)
        repo.prepareDemo();val demo=repo.dao.pack(repo.owner(),AppSpec.PACKAGE)!!
        repo.store.save(JSONObject().put("base","https://offline.example.test").put("user_id",DemoMode.user).put("project_id",AppSpec.LOCAL_PROJECT).put("protocol",2).put("access_token","test").put("public_key","sb_publishable_test").put("role","inspector"))
        val owner=repo.owner();val pack=demo.copy(owner=owner);repo.dao.install(pack);repo.dao.setting(Setting(owner,"generation","0"))
        val v=repo.begin(JSONObject(pack.body).getJSONArray("points").getJSONObject(0),pack,"LIST")
        val body=JSONObject(v.body);body.getJSONObject("sheet").put("notes","Offline durable note")
        repo.saveDraft(v.id,body,queueNow=true)
        val op=repo.dao.pendingVisit(v.id,owner).single();assertEquals("shared_inspection",op.kind);assertEquals(0,JSONObject(op.body).getJSONObject("payload").getJSONArray("events").length())
        repo.db.close()
        val reopened=Repository(context,"$name.db",name)
        try{
            val saved=reopened.dao.visit(v.id,owner)!!;assertEquals("BOZZA",saved.operational);assertEquals("IN_ATTESA",saved.sync)
            assertEquals("Offline durable note",JSONObject(saved.body).getJSONObject("sheet").getString("notes"))
            assertEquals(op,reopened.dao.pendingVisit(v.id,owner).single())
            reopened.dao.save(saved.copy(sync="CONFLICT",error="Newer revision"))
            try{reopened.saveDraft(v.id,body,queueNow=true);fail("Conflict overwritten")}catch(_:IllegalStateException){}
            assertEquals(1,reopened.dao.pendingVisit(v.id,owner).size)
            try{reopened.saveCatalog(emptyList());fail("Inspector imported catalog")}catch(_:IllegalStateException){}
        }finally{reopened.store.clear();reopened.db.close();context.deleteDatabase("$name.db")}
        Unit
    }
}
