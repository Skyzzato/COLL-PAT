package it.pat.collettori
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray

class GpsRuleTest {
    private fun resource(name:String)=javaClass.classLoader!!.getResource(name)!!.readText()
    @Test fun sharedCases(){
        val rule=Rule.parse(JSONObject(resource("gps-rule.json")))
        JSONArray(resource("gps-cases.json")).objects().forEach{c->
            val result=GpsRule.decide(c.numberOrNull("d"),c.numberOrNull("a"),c.numberOrNull("age"),c.numberOrNull("g"),c.getString("permission"),c.boolOrNull("mock"),c.getBoolean("ambiguous"),if(c.isNull("error"))null else c.getString("error"),rule)
            assertEquals(c.getString("name"),c.getString("expected"),result.getString("state"))
        }
    }
    @Test fun sphericalGeodesic(){assertEquals(111195.0802335329,GpsRule.distance(0.0,0.0,0.0,1.0),0.000001);assertEquals(0.0,GpsRule.distance(46.0,11.0,46.0,11.0),0.000001)}
    @Test fun styleHasOnlyLocalResources(){
        val pack=JSONObject("""{"basemap":null,"segments":[],"points":[]}""")
        val style=localStyle(pack,emptyList(),null,null)
        assertFalse(style.contains("http://"));assertFalse(style.contains("https://"));assertTrue(style.contains("asset://glyphs/"))
    }
}
