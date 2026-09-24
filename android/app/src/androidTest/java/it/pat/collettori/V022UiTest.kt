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
class V022UiTest {
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
            if(n!=null){val target=n;while(!n!!.isClickable&&n!!.parent!=null)n=n!!.parent;if(n!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)){Thread.sleep(180);return}
                val bounds=android.graphics.Rect();target.getBoundsInScreen(bounds)
                if(target.isVisibleToUser&&!bounds.isEmpty){val now=SystemClock.uptimeMillis()
                    for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){
                        val event=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
                        inst.uiAutomation.injectInputEvent(event,true);event.recycle()
                    };Thread.sleep(180);return
                }
            }
            all().firstOrNull{it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(140)
        };error("Cannot reveal $text: "+all().mapNotNull{it.text}.joinToString())
    }
    private fun edit(index:Int,value:String){val n=all().filter{it.isEditable}[index];assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}));inst.waitForIdleSync()}
    private fun screenshot(name:String){inst.waitForIdleSync();Thread.sleep(450);val dir=File(context.filesDir,"verification-v022").apply{mkdirs()};inst.uiAutomation.takeScreenshot().let{b->File(dir,name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun waitForRenderedNetwork(requireExposedLine:Boolean=true){
        val start=SystemClock.elapsedRealtime()
        while(SystemClock.elapsedRealtime()-start<15000){
            val bitmap=inst.uiAutomation.takeScreenshot();val width=bitmap.width;val height=bitmap.height;val pixels=IntArray(width*height);bitmap.getPixels(pixels,0,width,0,0,width,height);bitmap.recycle()
            var network=0;var points=0;var flag=0
            for(y in (height*.28).toInt() until (height*.8).toInt())for(x in width/20 until width*19/20){val color=pixels[y*width+x];val r=android.graphics.Color.red(color);val g=android.graphics.Color.green(color);val b=android.graphics.Color.blue(color)
                if(r in 10..45&&g in 90..130&&b in 95..140)network++
                if(r>200&&g in 135..190&&b<60)points++
                if(r in 60..90&&g in 35..65&&b in 115..150)flag++
            }
            if((!requireExposedLine||network>50)&&points>30&&flag>20)return
            Thread.sleep(250)
        };screenshot("network-render-failure.png");fail("Network, existing points and independent flag must all render in the picker")
    }

    private fun offlineRepo(name:String)=Repository(context,"$name.db",name,OkHttpClient.Builder().addInterceptor{chain->
        Thread.sleep(1500);Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Controlled outage").body("{}".toResponseBody()).build()
    }.build())
    private fun dispose(repo:Repository,name:String){repo.writes.coroutineContext[kotlinx.coroutines.Job]?.cancel();repo.store.clear();repo.db.close();context.deleteDatabase("$name.db")}
    @Test fun compactCardViewerSimulationAndNavigationCancellation(){
        val name="v022-ui-${UUID.randomUUID()}";val repo=offlineRepo(name);runBlocking{installTestCatalog(repo)}
        try{ActivityScenario.launch(MainActivity::class.java).use{scenario->scenario.onActivity{it.setContent{MaterialTheme{Workspace(repo){}}}}
            waitFor("Centra posizione");click("Pozzetti");click("TS-001");waitFor("Mostra dettagli")
            assertTrue(all().any{it.text?.contains("Mai ispezionato")==true});assertFalse(all().any{it.text?.contains("Conferma il codice sul posto")==true})
            assertFalse(all().any{it.text?.contains("Latitudine")==true});screenshot("compact-card-admin.png")
            click("Mostra dettagli");waitFor("Nascondi dettagli");assertTrue(all().any{it.text?.contains("Latitudine")==true});click("Nascondi dettagli")
            click("Storico del manufatto");waitFor("← Torna");click("← Torna");waitFor("Pozzetti")
            repeat(4){click("Ispezioni");click("Mappa")};Thread.sleep(1800)
            assertFalse(all().any{it.text?.contains("coroutine",true)==true||it.text?.toString()=="Esito operazione"})
            scenario.onActivity{repo.simulate("viewer")};waitFor("Simulazione: Visualizzatore");click("Pozzetti");click("TS-001");waitFor("Mostra dettagli")
            assertFalse(all().any{it.text?.toString() in setOf("Nuova ispezione","Elimina pozzetto","Personalizza forma")});screenshot("compact-card-viewer.png")
            click("Centra sulla mappa");click("Termina simulazione");click("Pozzetti");click("TS-002");waitFor("Mostra dettagli")
            assertTrue(all().any{it.text?.toString()=="Nuova ispezione"});assertFalse(all().any{it.text?.toString()=="Nascondi dettagli"})
        }}finally{dispose(repo,name)}
    }
    @Test fun simulatedOperatorShowsCommandsButCreatesNoDraft(){
        val name="v022-sim-${UUID.randomUUID()}";val repo=offlineRepo(name);runBlocking{installTestCatalog(repo)};repo.simulate("inspector")
        try{ActivityScenario.launch(MainActivity::class.java).use{scenario->scenario.onActivity{it.setContent{MaterialTheme{Workspace(repo){}}}}
            waitFor("Simulazione: Operatore");click("Pozzetti");click("TS-001");click("Nuova ispezione");waitFor("Esito operazione")
            assertTrue(all().any{it.text?.contains("anteprima senza scritture")==true});assertTrue(runBlocking{repo.dao.visitsNow(repo.owner()).isEmpty()});assertEquals("admin",repo.realRole())
            click("Chiudi");click("Centra sulla mappa");click("Termina simulazione")
        }}finally{dispose(repo,name)}
    }
    @Test fun longPointListStartsClosedAndRendersLazily(){
        ActivityScenario.launch(MainActivity::class.java).use{scenario->scenario.onActivity{it.setContent{MaterialTheme{androidx.compose.foundation.layout.Column{
            CollapsibleItems("Pozzetti",(1..126).toList(),{it.toString()}){Text("DEMO-ROW-$it")};Text("Conferma importazione")
        }}}}
            waitFor("Pozzetti: 126 — Mostra elenco");assertFalse(all().any{it.text?.startsWith("DEMO-ROW-")==true})
            click("Pozzetti: 126 — Mostra elenco");waitFor("Pozzetti: 126 — Nascondi elenco")
            assertTrue(all().count{it.text?.startsWith("DEMO-ROW-")==true} in 1..30);waitFor("Conferma importazione");screenshot("collapsible-import.png")
            click("Pozzetti: 126 — Nascondi elenco");assertFalse(all().any{it.text?.startsWith("DEMO-ROW-")==true})
        }
    }
    @Test fun manifestPortraitSurvivesRequestedDisplayRotation(){
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,it.requestedOrientation)}
            inst.uiAutomation.executeShellCommand("settings put system accelerometer_rotation 0").close()
            inst.uiAutomation.executeShellCommand("settings put system user_rotation 1").close();Thread.sleep(700)
            scenario.onActivity{assertEquals(android.content.res.Configuration.ORIENTATION_PORTRAIT,it.resources.configuration.orientation)}
            inst.uiAutomation.executeShellCommand("settings put system user_rotation 0").close()
            inst.uiAutomation.executeShellCommand("settings put system accelerometer_rotation 1").close()
        }
    }
    @Test fun actualSessionRolesHideAdministrativeCommands(){
        for(role in listOf("viewer","inspector")){
            val name="v022-role-ui-${UUID.randomUUID()}";val repo=offlineRepo(name);runBlocking{installTestCatalog(repo,role)}
            try{ActivityScenario.launch(MainActivity::class.java).use{scenario->scenario.onActivity{it.setContent{MaterialTheme{Workspace(repo){}}}}
                waitFor("Centra posizione");click("Pozzetti");click("TS-001");waitFor("Mostra dettagli")
                assertNull(repo.simulatedRole());assertEquals(role,repo.realRole())
                assertEquals(role=="inspector",all().any{it.text?.toString()=="Nuova ispezione"})
                assertFalse(all().any{it.text?.toString() in setOf("Elimina pozzetto","Personalizza forma","Gestione utenti")});screenshot("session-role-$role.png")
            }}finally{dispose(repo,name)}
        }
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion=29)
    @Test fun barbanigaLoadsExplicitlyAndPickerKeepsExistingNetwork(){
        val name="v022-barbaniga-${UUID.randomUUID()}";val repo=offlineRepo(name)
        try{val data=runBlocking{installTestCatalog(repo);repo.installBarbaniga();repo.installBarbaniga();repo.localCatalog()!!}
            val source=repo.barbanigaDataset();val c=source.getJSONArray("collectors").getJSONObject(0);val cid=c.getString("id")
            val points=data.getJSONArray("points").objects().filter{cid in it.memberships()};val segments=data.getJSONArray("segments").objects().filter{cid in it.memberships()}
            assertEquals(126,points.size);assertEquals(125,segments.size);assertEquals(5000.0,segments.sumOf{geometryLength(it.getJSONObject("geometry"))},0.01)
            val newPoint=JSONObject(points[62].toString()).put("id",UUID.randomUUID().toString()).put("code","DEMO-BAR-NEW").put("latitude",points[62].getDouble("latitude")+.0005)
            var saved=false
            ActivityScenario.launch(MainActivity::class.java).use{scenario->scenario.onActivity{it.setContent{MaterialTheme{PointForm(newPoint,c,points,segments,{}){saved=true}}}}
                waitFor("Pozzetto del collettore");click("Punta su mappa");waitFor("Conferma posizione");waitForRenderedNetwork(false);screenshot("barbaniga-entire-collector.png")
                // At the 5 km fit, 126 symbols overlap the thin line. Inspect a closer scale too.
                scenario.onActivity{
                    fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
                    val map=android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<org.maplibre.android.maps.MapView>().single{it.isShown}
                    map.getMapAsync{m->m.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(org.maplibre.android.geometry.LatLng(newPoint.getDouble("latitude"),newPoint.getDouble("longitude")),16.0))}
                }
                waitForRenderedNetwork();screenshot("barbaniga-picker-network.png")
                click("Annulla");waitFor("Pozzetto del collettore");assertFalse(saved)
            }
            newPoint.put("manual_link_collector",cid).put("manual_link_ids",org.json.JSONArray(listOf(points[62].getString("id"))))
            runBlocking{repo.saveCatalog(listOf(CatalogItem(repo.owner(),newPoint.getString("id"),"point",newPoint.toString())))}
            assertEquals(126,runBlocking{repo.localCatalog()!!}.getJSONArray("segments").length())
        }finally{dispose(repo,name)}
    }
}
