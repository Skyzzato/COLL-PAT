package it.pat.collettori

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run prepareOnV021 against the released APK, install -r v0.22, then verifyOnV022. */
@RunWith(AndroidJUnit4::class)
class V022UpgradeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun version()=context.packageManager.getPackageInfo(context.packageName,0).versionName
    @Test fun prepareOnV021()=runBlocking {
        assumeTrue(version()=="0.21")
        val r=Repository(context,"v022-upgrade.db","v022-upgrade")
        try{val pack=installTestCatalog(r);val p=JSONObject(pack.body).getJSONArray("points").getJSONObject(0)
            val visit=r.begin(p,pack,"LIST");val body=JSONObject(visit.body);body.getJSONObject("sheet").put("notes","Bozza v0.21 da conservare")
            r.saveDraft(visit.id,body);r.saveSettings(FieldSettings(lineWidth=7,maxDistance=30.0,maxAccuracy=140.0))
            File(context.filesDir,"v022-upgrade-attachment.txt").writeText("Allegato di collaudo aggiornamento")
            File(context.filesDir,"v022-upgrade-id.txt").writeText(visit.id)
        }finally{r.writes.cancel();r.db.close()};Unit
    }
    @Test fun verifyOnV022()=runBlocking {
        assumeTrue(version()=="0.22"&&File(context.filesDir,"v022-upgrade-id.txt").exists())
        val r=Repository(context,"v022-upgrade.db","v022-upgrade")
        try{val id=File(context.filesDir,"v022-upgrade-id.txt").readText();val draft=r.dao.visit(id,r.owner())!!
            assertEquals("BOZZA",draft.operational);assertEquals("Bozza v0.21 da conservare",JSONObject(draft.body).getJSONObject("sheet").getString("notes"))
            assertEquals(7,r.fieldSettings().lineWidth);assertEquals(140.0,r.fieldSettings().maxAccuracy,0.0)
            assertEquals("IN_ATTESA",draft.sync);assertEquals(2,r.localCatalog()!!.getJSONArray("points").length())
            assertEquals("Allegato di collaudo aggiornamento",File(context.filesDir,"v022-upgrade-attachment.txt").readText())
        }finally{r.writes.cancel();r.db.close()};Unit
    }
}
