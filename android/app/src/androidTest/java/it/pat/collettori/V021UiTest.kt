package it.pat.collettori

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Screenshots and interactions use synthetic data and a controlled HTTP failure only. */
@RunWith(AndroidJUnit4::class)
class V021UiTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap{nodes(n.getChild(it))}
    private fun all()=nodes(inst.uiAutomation.rootInActiveWindow)
    private fun waitFor(text:String):AccessibilityNodeInfo {
        val start=SystemClock.elapsedRealtime()
        while(SystemClock.elapsedRealtime()-start<9000){all().firstOrNull{it.text?.toString()==text||it.contentDescription?.toString()==text}?.let{return it};Thread.sleep(70)}
        error("Missing UI: $text; "+all().mapNotNull{it.text}.joinToString())
    }
    private fun click(text:String){
        repeat(25){
            var n=all().firstOrNull{it.text?.toString()==text||it.contentDescription?.toString()==text}
            if(n!=null){while(!n!!.isClickable&&n!!.parent!=null)n=n!!.parent;if(n!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)){Thread.sleep(180);return}}
            all().firstOrNull{it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(140)
        };error("Cannot reveal $text: "+all().mapNotNull{it.text}.joinToString())
    }
    private fun edit(index:Int,value:String){val n=all().filter{it.isEditable}[index];assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}));inst.waitForIdleSync()}
    private fun screenshot(name:String){inst.waitForIdleSync();Thread.sleep(450);val dir=File(context.filesDir,"verification-v021").apply{mkdirs()};inst.uiAutomation.takeScreenshot().let{b->File(dir,name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    @Test fun mapPointDraftReturnAppearanceFailedSyncAndQueue(){
        val name="v021-ux-${UUID.randomUUID()}"
        val client=OkHttpClient.Builder().addInterceptor{chain->Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Controlled outage").body("{\"code\":\"TEST503\",\"message\":\"Synthetic outage\"}".toResponseBody()).build()}.build()
        val repo=Repository(context,"$name.db",name,client);runBlocking{installTestCatalog(repo)}
        try{ActivityScenario.launch(MainActivity::class.java).use{scenario->scenario.onActivity{it.setContent{MaterialTheme{Workspace(repo){}}}}
            waitFor("Centra posizione");Thread.sleep(1600);screenshot("map-synthetic.png")
            click("Pozzetti");waitFor("TS-001");click("TS-001");click("Nuova ispezione");waitFor("Rileva posizione")
            screenshot("inspection-synthetic.png");click("Salva bozza");waitFor("Bozza salvata");screenshot("save-summary.png");click("Chiudi");waitFor("Pozzetti")
            click("Collettori");waitFor("Anagrafica");click("Anagrafica");click("Personalizza aspetto");waitFor("Ripristina predefiniti");screenshot("appearance.png");click("Ripristina predefiniti");click("Salva");waitFor("Esito operazione");click("Chiudi")
            click("Impostazioni");click("Server e sincronizzazione");click("Sincronizza");waitFor("Esito operazione");screenshot("sync-failure.png");click("Chiudi");click("Visualizza coda");Thread.sleep(350);screenshot("queue-offline.png");click("Chiudi")
            assertTrue(runBlocking{repo.dao.visitsNow(repo.owner()).any{it.operational=="BOZZA"}})
        }}finally{runBlocking{repo.writes.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach{it.join()}};repo.store.clear();repo.db.close();context.deleteDatabase("$name.db")}
    }
    @Test fun manualCoordinateMapCancelConfirmPreservesCode(){
        val collector=collectorDefaults(UUID.randomUUID().toString(),"TEST-FORM","Collettore sintetico con descrizione lunga")
        var result:JSONObject?=null
        ActivityScenario.launch(MainActivity::class.java).use{scenario->scenario.onActivity{it.setContent{MaterialTheme{
            var done by remember{mutableStateOf(false)}
            if(done)Text("Pozzetto salvato test")else PointForm(JSONObject().put("id",UUID.randomUUID().toString()),collector,emptyList(),emptyList(),{}){p->result=p;done=true}
        }}}
            waitFor("Pozzetto del collettore");edit(0,"TEST-007");edit(1,"46,012345");edit(2,"11,054321");click("Punta su mappa");waitFor("Conferma posizione");Thread.sleep(1200);screenshot("map-picker.png");click("Annulla")
            waitFor("Codice / numero pozzetto");assertTrue(all().any{it.text?.toString()=="TEST-007"});assertTrue(all().any{it.text?.toString()=="46,012345"})
            click("Punta su mappa");click("Conferma posizione");click("Verifica posizione sulla mappa");click("Conferma pozzetto");waitFor("Pozzetto salvato test")
            assertEquals("TEST-007",result!!.getString("code"));assertEquals(46.012345,result!!.getDouble("latitude"),0.0000001)
        }
    }
}
