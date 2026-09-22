package it.pat.collettori

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupWorkflowTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val repo get()=(context.applicationContext as PilotApplication).repository

    @Test fun sdkConfiguredBeforeHttpClientAndActivityCanRecreate() {
        assertEquals(context.applicationContext,org.maplibre.android.MapLibre.getApplicationContext())
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.INTERNET))
        val app=context.applicationContext as PilotApplication
        instrumentation.runOnMainSync{app.splashFinished=false}
        val started=android.os.SystemClock.elapsedRealtime()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            while(!app.splashFinished&&android.os.SystemClock.elapsedRealtime()-started<20000)Thread.sleep(25)
            assertTrue("Cold splash initialization must finish",app.splashFinished)
            assertTrue("Cold splash must last at least three seconds",android.os.SystemClock.elapsedRealtime()-started>=AppSpec.SPLASH_MILLIS)
            scenario.onActivity { assertFalse(it.isFinishing) }
            scenario.recreate()
            Thread.sleep(250)
            assertTrue("Recreation must not reset the process splash",app.splashFinished)
            scenario.onActivity {
                assertFalse(it.isFinishing)
                assertTrue(it.window.decorView.isAttachedToWindow)
                assertTrue(it.window.decorView.width>0)
                assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED,it.lifecycle.currentState)
            }
        }
    }

    @Test fun demoInspectionDefaultsEditSaveReopenAndPhoto()=runBlocking {
        assumeTrue(BuildConfig.DEMO)
        repo.prepareDemo()
        val pack=repo.dao.packagesNow(repo.owner()).first{it.id==AppSpec.PACKAGE}
        val points=JSONObject(pack.body).getJSONArray("points").objects()
        assertEquals(16,points.size)
        val visit=repo.begin(points.first(),pack,"LIST")
        val body=JSONObject(visit.body)
        val sheet=body.getJSONObject("sheet")
        Repository.observationKeys.forEach{assertEquals("REGOLARE",sheet.getString(it))}
        assertTrue(sheet.getBoolean("opened"))
        sheet.put("notes","Verifica strumentale v0.12").put("exception_reason","Collaudo senza fix sul posto")
        repo.saveDraft(visit.id,body)
        assertEquals("Verifica strumentale v0.12",JSONObject(repo.dao.visit(visit.id,visit.owner)!!.body).getJSONObject("sheet").getString("notes"))
        val photos=PhotoRepository(repo)
        val uri=photos.newCapture()
        val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        context.contentResolver.openOutputStream(uri)!!.use{assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it))}
        bitmap.recycle()
        photos.attach(visit,uri,false)
        assertEquals(1,photos.list(visit).size)
        val rule=Rule.parse(JSONObject(context.assets.open("gps-rule.json").bufferedReader().use{it.readText()}))
        val event=LocationCapture(context).collect(body,rule)
        event.put("local_evaluation",GpsRule.evaluate(event,points.first(),points,rule))
        repo.appendEvent(visit.id,event)
        repo.complete(visit.id,body,"COMPLETO")
        val reopened=Repository(context)
        try {
            val saved=reopened.dao.visit(visit.id,visit.owner)!!
            assertEquals("COMPLETO",saved.operational)
            assertEquals("IN_ATTESA",saved.sync)
            assertEquals(1,PhotoRepository(reopened).list(saved).size)
            assertEquals(1,reopened.dao.pendingVisit(visit.id).size)
        } finally {reopened.db.close()}
        Unit
    }

    @Test fun deniedLocationReturnsExplicitEvidence()=runBlocking {
        assumeTrue(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
        assumeTrue(context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
        val inspection=JSONObject().put("id","test").put("manhole_id","point").put("user_id","test").put("device_id","test").put("dataset_id","test")
        val rule=Rule.parse(JSONObject(context.assets.open("gps-rule.json").bufferedReader().use{it.readText()}))
        val result=LocationCapture(context).collect(inspection,rule)
        assertEquals("DENIED",result.getString("permission"))
        assertEquals("permesso negato",result.getString("error"))
        assertTrue(result.isNull("latitude"))
        assertEquals("Precisione non ancora disponibile",precisionText(result))
    }
}
