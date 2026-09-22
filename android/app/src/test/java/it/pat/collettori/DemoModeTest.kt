package it.pat.collettori

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class DemoModeTest {
    @Test fun bundledDatasetIsSyntheticAndSelfContained(){
        val p=JSONObject(javaClass.classLoader!!.getResource("demo-package.json")!!.readText())
        DemoMode.validateDataset(p)
        assertTrue(p.getBoolean("synthetic"))
        assertEquals(22,p.getJSONArray("points").length())
        assertEquals(19,p.getJSONArray("segments").length())
        assertEquals(listOf("COLL_DEMO_01","COLL_DEMO_02","COLL_DEMO_03"),p.getJSONArray("collectors").objects().map{it.getString("code")})
        assertFalse(p.isNull("basemap"))
        val points=p.getJSONArray("points").objects()
        val ids=points.map{it.getString("id")}.toSet()
        p.getJSONArray("segments").objects().forEach{
            assertTrue(ids.contains(it.getString("from_id")))
            assertTrue(ids.contains(it.getString("to_id")))
        }
        val style=localStyle(p,points,null,null)
        assertTrue(style.contains("https://tile.openstreetmap.org"))
        assertTrue(style.contains("OpenStreetMap contributors"))
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
