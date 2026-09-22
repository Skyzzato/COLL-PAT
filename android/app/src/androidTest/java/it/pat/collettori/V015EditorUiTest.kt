package it.pat.collettori

import android.Manifest
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** Real Compose editor and Room on Android; synthetic GPS source, no claim of field GPS testing. */
@RunWith(AndroidJUnit4::class)
class V015EditorUiTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null)emptyList() else listOf(node)+(0 until node.childCount).flatMap{nodes(node.getChild(it))}
    private fun all()=nodes(inst.uiAutomation.rootInActiveWindow)
    private fun waitFor(label:String,timeout:Long=7000):AccessibilityNodeInfo {
        val start=SystemClock.elapsedRealtime()
        while(SystemClock.elapsedRealtime()-start<timeout){all().firstOrNull{it.text?.toString()==label||it.contentDescription?.toString()==label}?.let{return it};Thread.sleep(40)}
        error("UI text absent: $label; visible="+all().mapNotNull{it.text}.joinToString())
    }
    private fun reveal(label:String):AccessibilityNodeInfo{
        var node:AccessibilityNodeInfo?=null
        repeat(14){if(node==null){node=all().firstOrNull{it.text?.toString()==label};if(node==null){all().firstOrNull{it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(120)}}}
        var target=node?:waitFor(label)
        while(!target.isClickable&&target.className?.toString()!="android.widget.Button"&&target.parent!=null)target=target.parent
        return target
    }
    private fun click(label:String){
        val start=SystemClock.elapsedRealtime()
        while(SystemClock.elapsedRealtime()-start<5000){val node=reveal(label);if(node.isEnabled&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;Thread.sleep(80)}
        fail("Click $label; visible="+all().mapNotNull{it.text}.joinToString())
    }
    private fun awaitEvents(repo:Repository,v:Visit,count:Int){val start=SystemClock.elapsedRealtime();while(SystemClock.elapsedRealtime()-start<5000){val n=runBlocking{JSONObject(repo.dao.visit(v.id,v.owner)!!.body).getJSONArray("events").length()};if(n==count)return;Thread.sleep(30)};fail("Event count != $count")}
    private fun withEditor(acquisitionDelay:Long=0,block:(Repository,Visit,()->Int)->Unit){
        val name="ui-v015-${UUID.randomUUID()}";val repo=Repository(context,"$name.db",name)
        var returns=0
        val v=runBlocking{repo.prepareDemo();val pack=repo.dao.pack(repo.owner(),AppSpec.PACKAGE)!!;repo.begin(JSONObject(pack.body).getJSONArray("points").getJSONObject(0),pack,"LIST")}
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{activity->activity.setContent{MaterialTheme{
                var open by remember{mutableStateOf(true)};var message by remember{mutableStateOf("")}
                val rows by repo.dao.visits(v.owner).collectAsState(listOf(v));val current=rows.first{it.id==v.id}
                if(open)InspectionEditor(repo,current,null,"Collettore Gilli",false,message,{message=it},{returns++;open=false},captureFactory={object:InspectionCapture{
                    private var job:Job?=null
                    override fun cancel(){job?.cancel()}
                    override suspend fun acquire(inspection:JSONObject,rule:Rule,progress:(Int)->Unit):JSONObject{
                        job=currentCoroutineContext()[Job];if(acquisitionDelay>0){progress(5);delay(acquisitionDelay)}
                        val point=repo.snapshot(v).getJSONArray("points").objects().first{it.getString("id")==v.manholeId}
                        return JSONObject().put("id",UUID.randomUUID().toString()).put("inspection_id",v.id).put("manhole_id",v.manholeId)
                            .put("user_id",inspection.getString("user_id")).put("device_id",inspection.getString("device_id")).put("dataset_id",inspection.getString("dataset_id"))
                            .put("method",AcquisitionPolicy.METHOD).put("sample_count",5).put("duration_ms",5000).put("sample_span_ms",4000)
                            .put("permission","PRECISE").put("mock",false).put("age_s",0).put("accuracy_m",5)
                            .put("latitude",point.getDouble("latitude")+.005).put("longitude",point.getDouble("longitude"))
                            .put("acquired_at",Instant.now().toString()).put("applied_limits",rule.json())
                    }
                }}) else Text("Pagina precedente · filtro Gilli")
            }}}
            try{waitFor("Collettore Gilli");block(repo,v){returns}}finally{runBlocking{repo.writes.coroutineContext[Job]?.children?.forEach{it.join()}}}
        }
        repo.store.clear();repo.db.close();context.deleteDatabase("$name.db")
    }
    @Test fun draftConfirmationRemainsTwoSecondsAndReturnsOnlyOnce(){withEditor{repo,v,returns->
        val save=reveal("Salva bozza");assertTrue(save.performAction(AccessibilityNodeInfo.ACTION_CLICK));save.performAction(AccessibilityNodeInfo.ACTION_CLICK);waitFor("Bozza salvata");val visible=SystemClock.elapsedRealtime()
        assertEquals(0,returns());waitFor("Pagina precedente · filtro Gilli");assertTrue(SystemClock.elapsedRealtime()-visible>=1700)
        Thread.sleep(300);assertEquals(1,returns());assertEquals("BOZZA",runBlocking{repo.dao.visit(v.id,v.owner)!!.operational});assertEquals(2L,runBlocking{JSONObject(repo.dao.visit(v.id,v.owner)!!.body).getLong("local_edit")})
    }}
    @Test fun manualBackCancelsScheduledNavigation(){withEditor{_,_,returns->
        click("Salva bozza");waitFor("Bozza salvata");click("Torna ora");waitFor("Pagina precedente · filtro Gilli");Thread.sleep(2300);assertEquals(1,returns())
    }}
    @Test fun mismatchDialogFocusAndExplicitReasonDoNotSaveEarly(){
        inst.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.ACCESS_FINE_LOCATION)
        inst.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.ACCESS_COARSE_LOCATION)
        withEditor{repo,v,_->
            click("Rileva posizione");waitFor("Corrispondenza GPS non verificata");awaitEvents(repo,v,0)
            click("Annulla");awaitEvents(repo,v,0);click("Rileva posizione");waitFor("Corrispondenza GPS non verificata")
            click("Continua con eccezione");waitFor("Eccezione GPS");Thread.sleep(400)
            val edit=all().first{it.isEditable&&it.isFocused};assertTrue(edit.isVisibleToUser)
            val confirm=reveal("Conferma registrazione con eccezione");assertFalse(confirm.isEnabled);awaitEvents(repo,v,0)
            assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Codice verificato sul posto")}))
            Thread.sleep(250);click("Conferma registrazione con eccezione");awaitEvents(repo,v,1)
            val event=runBlocking{lastEvidence(JSONObject(repo.dao.visit(v.id,v.owner)!!.body))!!}
            assertTrue(usableInspectionGps(event));assertEquals("EXCEPTION",event.getString("match_outcome"));assertEquals("Codice verificato sul posto",event.getString("exception_reason"))
            click("Registra Ispezione");waitFor("Ispezione salvata in locale");waitFor("Pagina precedente · filtro Gilli")
        }
    }
    @Test fun matchingReceiptUpdatesDraftNoticeWithoutRestartingTimer(){withEditor{repo,v,returns->
        click("Salva bozza");waitFor("Bozza salvata");val started=SystemClock.elapsedRealtime()
        runBlocking{delay(450);val current=repo.dao.visit(v.id,v.owner)!!;repo.dao.save(current.copy(sync="RICEVUTO_SERVER",receipt="{\"operation_id\":\"controlled-receipt\"}"))}
        waitFor("Sul server — disponibile agli utenti autorizzati");assertEquals(0,returns())
        waitFor("Pagina precedente · filtro Gilli");assertTrue(SystemClock.elapsedRealtime()-started<2400)
    }}
    @Test fun cancelledAcquisitionDoesNotCreateGhostEvents(){
        inst.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.ACCESS_FINE_LOCATION)
        inst.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.ACCESS_COARSE_LOCATION)
        withEditor(acquisitionDelay=3000){repo,v,_->
        click("Rileva posizione");click("Interrompi rilevazione GPS");Thread.sleep(3300);awaitEvents(repo,v,0)
        assertTrue(runBlocking{repo.dao.pendingVisit(v.id,v.owner).isEmpty()})
    }}
    @Test fun failedLocalTransactionKeepsEditorAndInput(){withEditor{repo,v,returns->
        repo.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER test_fail_update BEFORE UPDATE ON visits BEGIN SELECT RAISE(ABORT,'disk test'); END")
        repo.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER test_fail_insert BEFORE INSERT ON visits BEGIN SELECT RAISE(ABORT,'disk test'); END")
        click("Salva bozza");Thread.sleep(2400);assertEquals(0,returns());assertFalse(all().any{it.text?.toString()=="Bozza salvata"})
        assertEquals("BOZZA",runBlocking{repo.dao.visit(v.id,v.owner)!!.operational})
    }}
}
