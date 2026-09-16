package it.pat.collettori

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class DemoModeTest {
    @Test fun bundledDatasetIsSyntheticAndSelfContained(){
        val p=JSONObject(javaClass.classLoader!!.getResource("demo-package.json")!!.readText())
        assertTrue(p.getBoolean("synthetic"))
        assertEquals(16,p.getJSONArray("points").length())
        assertEquals(15,p.getJSONArray("segments").length())
        assertFalse(p.isNull("basemap"))
        val points=p.getJSONArray("points").objects()
        val ids=points.map{it.getString("id")}.toSet()
        p.getJSONArray("segments").objects().forEach{
            assertTrue(ids.contains(it.getString("from_id")))
            assertTrue(ids.contains(it.getString("to_id")))
        }
        val style=localStyle(p,points,null,null)
        assertFalse(style.contains("https://"))
        assertFalse(style.contains("http://"))
    }
    @Test fun exportCannotBeMistakenForOperationalRecovery(){
        val exported=DemoMode.export(listOf(JSONObject().put("id","sample")))
        assertEquals("collettori-demo-1",exported.getString("format"))
        assertFalse(exported.getBoolean("server_received"))
        assertTrue(exported.getBoolean("synthetic"))
        assertFalse(exported.has("operations"))
        assertEquals(1,exported.getJSONArray("controls").length())
        assertFalse(DemoMode.session().has("access_token"))
    }
}
