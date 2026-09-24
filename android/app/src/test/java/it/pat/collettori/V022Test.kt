package it.pat.collettori

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant

class V022Test {
    private fun p(id:String,lat:Double=46.1,c:String="c")=JSONObject().put("id",id).put("code",id).put("latitude",lat).put("longitude",11.18585).put("collectors",JSONArray(listOf(c)))
    private fun item(kind:String,b:JSONObject)=CatalogItem("test",b.getString("id"),kind,b.toString())
    @Test fun defaultsAreResolvedIndependentlyAndStatusColorsRemain(){
        val cs=listOf(collectorDefaults("a","A","A"),collectorDefaults("b","B","B"),collectorDefaults("c","C","C").put("display_color","#783E9F"))
        val settings=FieldSettings(lineColor="#AF235A",lineWidth=9,symbol="SQUARE")
        assertEquals(listOf("#AF235A","#AF235A","#783E9F"),cs.map{resolvedCollectorColor(it,settings)})
        assertTrue(cs.all{resolvedCollectorWidth(it,settings)==9})
        cs[2].put("display_width",2);assertEquals(2,resolvedCollectorWidth(cs[2],settings))
        cs[2].put("display_width",JSONObject.NULL).put("display_color",JSONObject.NULL)
        assertEquals(9,resolvedCollectorWidth(cs[2],settings));assertEquals(settings.lineColor,resolvedCollectorColor(cs[2],settings))
        val point=p("p",c="c").put("status_color",InspectionState.ANOMALY_DUE.color)
        val line=schematicConnection("c",point,p("q",46.101))
        val pack=JSONObject().put("basemap",JSONObject.NULL).put("collectors",JSONArray(cs)).put("segments",JSONArray(listOf(line)))
        val style=JSONObject(localStyle(pack,listOf(point),null,null,settings))
        val source=style.getJSONObject("sources").getJSONObject("network").getJSONObject("data").getJSONArray("features").getJSONObject(0).getJSONObject("properties")
        assertEquals(settings.lineColor,source.getString("display_color"));assertEquals(9,source.getInt("display_width"))
        assertEquals(InspectionState.ANOMALY_DUE.color,style.getJSONObject("sources").getJSONObject("points").getJSONObject("data").getJSONArray("features").getJSONObject(0).getJSONObject("properties").getString("status_color"))
        assertEquals(settings,FieldSettings.parse(settings.json()))
    }
    @Test fun explicitConnectionsAreStableUndirectedAndNeverDuplicateImportedEdges(){
        val a=p("a");val b=p("b",46.101);val c=collectorDefaults("c","C","C")
        val changed=JSONObject(a.toString()).put("manual_link_collector","c").put("manual_link_ids",JSONArray(listOf("b","b")))
        val initial=listOf(item("collector",c),item("point",a),item("point",b))
        val first=materializeManualConnections(listOf(item("point",changed)),initial)
        assertEquals(1,first.count{it.kind=="segment"});assertFalse(JSONObject(first.first().body).has("manual_link_ids"))
        assertEquals(manualConnectionId("c","a","b"),manualConnectionId("c","b","a"))
        val edge=JSONObject(first.single{it.kind=="segment"}.body);assertTrue(edge.getBoolean("schematic"));assertTrue(edge.getDouble("length_m")>100)
        val retry=materializeManualConnections(listOf(item("point",changed)),initial+first)
        assertEquals(1,(initial+first+retry).associateBy{it.id}.values.count{it.kind=="segment"})
        val imported=JSONObject(edge.toString()).put("id","official").put("schematic",false)
        assertTrue(materializeManualConnections(listOf(item("point",changed)),initial+item("segment",imported)).none{it.kind=="segment"})
    }
    @Test fun connectionsRejectSelfDeletedAndForeignAndMovingKeepsRealGeometry(){
        val a=p("a");val b=p("b",46.101);val edge=schematicConnection("c",a,b)
        for(other in listOf(a,p("x",c="foreign"),p("x").put("deleted",true))){try{schematicConnection("c",a,other);fail("Invalid edge accepted")}catch(_:IllegalArgumentException){}}
        val real=JSONObject(edge.toString()).put("id","real").put("schematic",false)
        val moved=JSONObject(a.toString()).put("latitude",46.102)
        val updates=updateSchematicSegments(listOf(item("point",moved)),listOf(item("point",a),item("point",b),item("segment",edge),item("segment",real)))
        assertTrue(updates.none{it.id=="real"});assertEquals(46.102,JSONObject(updates.single{it.kind=="segment"}.body).getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0).getDouble(1),0.0)
    }
    @Test fun positioningKeepsNetworkSymbolsAndIndependentFlag(){
        val a=p("a");val b=p("b",46.101);val c=collectorDefaults("c","C","C");val edge=schematicConnection("c",a,b)
        val data=positioningData(c,listOf(a,b),listOf(edge),p("new",46.1005),listOf("b"))
        assertEquals(2,data.getJSONArray("points").length());assertEquals(2,data.getJSONArray("segments").length())
        val style=JSONObject(localStyle(data,data.getJSONArray("points").objects(),null,null))
        val layers=style.getJSONArray("layers").objects().associateBy{it.getString("id")}
        assertEquals("points",layers.getValue("manholes").getString("source"));assertEquals("proposed-point",layers.getValue("coordinate-flag").getString("source"))
        assertEquals(1,style.getJSONObject("sources").getJSONObject("proposed-point").getJSONObject("data").getJSONArray("features").length())
    }
    @Test fun completedQuarterExamplesExactBoundaryLeapAndRome(){
        for((now,start,end) in listOf(Triple("2026-05-10T00:00:00Z","2025-12-31T23:00:00Z","2026-03-31T22:00:00Z"),Triple("2026-09-24T00:00:00Z","2026-03-31T22:00:00Z","2026-06-30T22:00:00Z"),Triple("2027-01-01T00:00:00Z","2026-09-30T22:00:00Z","2026-12-31T23:00:00Z"),Triple("2026-03-31T22:00:00Z","2025-12-31T23:00:00Z","2026-03-31T22:00:00Z"))){
            val period=ExportPeriod.lastCompletedQuarter(Instant.parse(now));assertEquals(Instant.parse(start),period.start);assertEquals(Instant.parse(end),period.end);assertTrue(period.contains(period.start!!));assertFalse(period.contains(period.end!!))
        }
        assertTrue(ExportPeriod.year(2024).contains(Instant.parse("2024-02-29T12:00:00Z")))
        assertFalse(ExportPeriod.year(2024).contains(Instant.parse("2024-12-31T23:00:00Z")))
    }
    @Test fun exportDeduplicatesRevisionsExcludesDraftsAndKeepsPendingImpediments(){
        fun v(id:String,revision:Int,status:String="COMPLETO",sync:String="RICEVUTO_SERVER")=Visit(id,"a","p","d",JSONObject().put("started_at","2026-05-02T00:00:00Z").put("server_revision",revision).put("local_edit",if(sync=="RICEVUTO_SERVER")0 else 2).toString(),status,sync)
        val rows=exportRows(listOf(v("a",1),v("a",2),v("b",0,"BOZZA"),v("c",0,"IMPEDITO","IN_ATTESA")),ExportPeriod.year(2026))
        assertEquals(2,rows.size);assertEquals(2,JSONObject(rows.first{it.id=="a"}.body).getInt("server_revision"));assertEquals("IN_ATTESA",rows.first{it.id=="c"}.sync)
        assertTrue(exportRows(rows,ExportPeriod.year(2027)).isEmpty())
    }
    @Test fun filterVisibilityUsesBothDateEndsAndCollector(){
        assertFalse(hasInspectionFilters(null,null,""));assertTrue(hasInspectionFilters("2026-01-01",null,""));assertTrue(hasInspectionFilters(null,"2026-06-01",""));assertTrue(hasInspectionFilters(null,null,"c"));assertTrue(hasInspectionFilters("2026-01-01","2026-06-01","c"));assertFalse(hasInspectionFilters(null,null,""));assertTrue(hasInspectionFilters(null,null,"again"))
    }
    @Test fun syntheticDatasetIsContinuousAndLabelledAlongFiveKilometres(){
        val data=JSONObject(javaClass.classLoader!!.getResourceAsStream("demo-barbaniga-v022.json")!!.bufferedReader().readText())
        val points=data.getJSONArray("points").objects();val segments=data.getJSONArray("segments").objects()
        assertEquals(126,points.size);assertEquals(125,segments.size);assertEquals(5000.0,segments.sumOf{geometryLength(it.getJSONObject("geometry"))},0.001)
        segments.forEachIndexed{i,s->assertEquals(points[i].getString("id"),s.getString("from_id"));assertEquals(points[i+1].getString("id"),s.getString("to_id"));assertTrue(s.getBoolean("synthetic"))}
        assertEquals(46.1016388889,points[62].getDouble("latitude"),1e-8);assertTrue(collectorLabelFeatures(data).size>=5)
    }
}
