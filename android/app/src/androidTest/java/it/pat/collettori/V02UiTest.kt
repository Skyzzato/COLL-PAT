package it.pat.collettori

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class V02UiTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap{nodes(n.getChild(it))}
    private fun all()=nodes(inst.uiAutomation.rootInActiveWindow)
    private fun waitFor(text:String):AccessibilityNodeInfo {
        val start=SystemClock.elapsedRealtime()
        while(SystemClock.elapsedRealtime()-start<8000){all().firstOrNull{it.text?.toString()==text||it.contentDescription?.toString()==text}?.let{return it};Thread.sleep(60)}
        error("Missing UI: $text; "+all().mapNotNull{it.text}.joinToString())
    }
    private fun click(text:String){
        repeat(20){
            var n=all().firstOrNull{it.text?.toString()==text||it.contentDescription?.toString()==text}
            if(n!=null){while(!n!!.isClickable&&n!!.parent!=null)n=n!!.parent;assertTrue(n!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));return}
            all().firstOrNull{it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(120)
        };error("Cannot reveal $text")
    }
    private fun edit(index:Int,value:String){val n=all().filter{it.isEditable}[index];assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}));inst.waitForIdleSync()}
    private fun screenshot(name:String){val dir=File(context.filesDir,"verification-v02").apply{mkdirs()};inst.uiAutomation.takeScreenshot().let{b->File(dir,name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    @Test fun collectorFormAddsManholeWithManualCoordinatesAndPersistsRealRoom(){
        val name="v02-ui-${UUID.randomUUID()}";val repo=Repository(context,"$name.db",name)
        runBlocking{repo.store.save(testSession());repo.dao.setting(Setting(repo.owner(),"generation","0"))}
        val collector=collectorDefaults(UUID.randomUUID().toString(),"UI-C","Collettore UI")
        try{ActivityScenario.launch(MainActivity::class.java).use{scenario->scenario.onActivity{activity->activity.setContent{MaterialTheme{
            var done by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
            if(done)Text("Collettore salvato") else CollectorForm(collector,emptyList(),{}){c,points->scope.launch{repo.saveCatalog(listOf(CatalogItem(repo.owner(),c.getString("id"),"collector",c.toString()))+points.map{CatalogItem(repo.owner(),it.getString("id"),"point",it.toString())});done=true}}
        }}}
            waitFor("Anagrafica collettore");click("Aggiungi pozzetto");waitFor("Pozzetto del collettore")
            val fields=all().filter{it.isEditable};assertTrue(fields.size>=3)
            assertFalse(fields[1].text?.toString().orEmpty().contains("46.067890"));assertFalse(fields[2].text?.toString().orEmpty().contains("11.123456"))
            edit(0,"0007");edit(1,"91");edit(2,"11,123456");click("Verifica posizione sulla mappa");waitFor("Latitudine ammessa: da −90 a 90")
            all().firstOrNull{it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);Thread.sleep(150)
            edit(1,"46.067890");click("Verifica posizione sulla mappa");waitFor("Latitudine 46.06789 · Longitudine 11.123456");Thread.sleep(3000);screenshot("manual-preview.png")
            click("Conferma pozzetto");click("Salva collettore e pozzetti");waitFor("Collettore salvato")
            val rows=runBlocking{repo.dao.catalogNow(repo.owner())};assertEquals(2,rows.size);val p=JSONObject(rows.single{it.kind=="point"}.body)
            assertEquals("0007",p.getString("code"));assertEquals(listOf(collector.getString("id")),p.memberships());assertEquals(46.06789,p.getDouble("latitude"),0.0)
        }}finally{repo.store.clear();repo.db.close();context.deleteDatabase("$name.db")}
    }
    @Test fun logoAndMultiselectRemainUsableAt320dpAndLargeFont(){
        val name="v02-layout-${UUID.randomUUID()}";val repo=Repository(context,"$name.db",name)
        val pack=runBlocking{installTestCatalog(repo)};val data=JSONObject(pack.body)
        try{ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{a->a.setContent{MaterialTheme{CompositionLocalProvider(LocalDensity provides Density(1f,1.5f)){Box(Modifier.width(320.dp).fillMaxHeight()){AppLoading("") {}}}}}}
            waitFor("v0.21 · build 21");inst.waitForIdleSync();screenshot("loading-320-font150.png")
            val vector=context.getDrawable(R.drawable.brand_name)!!;val bitmap=android.graphics.Bitmap.createBitmap(440,160,android.graphics.Bitmap.Config.ARGB_8888);vector.setBounds(0,0,440,160);vector.draw(android.graphics.Canvas(bitmap));File(context.filesDir,"verification-v02/brand-vector.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            val splash=context.getDrawable(R.drawable.splash)!!;val splashBitmap=android.graphics.Bitmap.createBitmap(320,640,android.graphics.Bitmap.Config.ARGB_8888);splash.setBounds(0,0,320,640);splash.draw(android.graphics.Canvas(splashBitmap));File(context.filesDir,"verification-v02/splash-vector.png").outputStream().use{splashBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};splashBitmap.recycle()
            scenario.onActivity{a->a.setContent{MaterialTheme{CompositionLocalProvider(LocalDensity provides Density(1f,1.5f)){Box(Modifier.width(320.dp).fillMaxHeight()){CollectorList(data.getJSONArray("collectors").objects(),emptySet(),"Catalogo test",rememberLazyListState(),{},{_,_->},{},{},data,emptyMap(),repo){}}}}}}
            waitFor("Seleziona più elementi");click("Seleziona più elementi");waitFor("Seleziona / deseleziona tutti i 1 risultati filtrati");screenshot("multiselect-320-font150.png")
        }}finally{repo.store.clear();repo.db.close();context.deleteDatabase("$name.db")}
    }
}
