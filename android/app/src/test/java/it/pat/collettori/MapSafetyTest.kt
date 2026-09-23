package it.pat.collettori

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MapSafetyTest {
    private fun dataset() = JSONObject(javaClass.classLoader!!.getResource("trento-lavis-gilli-v0.14.json")!!.readText())

    @Test(expected=IllegalArgumentException::class) fun invalidDemoRejectedBeforeDatabaseWrite() {
        val pack=dataset()
        pack.getJSONArray("points").getJSONObject(0).put("latitude",95)
        require(pack.getJSONArray("points").objects().all{validCoordinates(it.numberOrNull("latitude"),it.numberOrNull("longitude"))})
    }

    @Test fun noFixAndMissingAccuracyRemainUsableOffline() {
        val pack = dataset().put("osm", false)
        val points = pack.getJSONArray("points").objects()
        for (position in listOf(null, JSONObject(), JSONObject().put("latitude", 46.0647).put("longitude", 11.1154))) {
            val style = JSONObject(localStyle(pack, points, points.first().getString("id"), position))
            val sources = style.getJSONObject("sources")
            assertFalse(sources.has("osm"))
            assertEquals(22, sources.getJSONObject("points").getJSONObject("data").getJSONArray("features").length())
            assertEquals(19, sources.getJSONObject("network").getJSONObject("data").getJSONArray("features").length())
            assertEquals(0, sources.getJSONObject("accuracy").getJSONObject("data").getJSONArray("features").length())
            assertEquals("Precisione non ancora disponibile", precisionText(position))
        }
    }

    @Test fun invalidCoordinatesAndAccuracyDoNotReachMap() {
        val pack = dataset()
        val points = pack.getJSONArray("points").objects()
        val position = JSONObject().put("latitude", 95).put("longitude", 181).put("accuracy_m", -5)
        val sources = JSONObject(localStyle(pack, points, null, position)).getJSONObject("sources")
        assertEquals(0, sources.getJSONObject("device").getJSONObject("data").getJSONArray("features").length())
        assertEquals(0, sources.getJSONObject("accuracy").getJSONObject("data").getJSONArray("features").length())
        assertEquals("Precisione non ancora disponibile", precisionText(position))
        assertEquals("Posizione non disponibile", GpsIdentification.identify(points, position).state)
    }
}
