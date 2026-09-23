package it.pat.collettori

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class V014FunctionalTest {
    private val now=Instant.parse("2026-09-22T12:00:00Z")
    private fun demo()=JSONObject(javaClass.classLoader!!.getResource("trento-lavis-gilli-v0.14.json")!!.readText())
    private fun visit(id:String,at:String,status:String="COMPLETO")=Visit(id,"owner","point","data",JSONObject().put("started_at",at).put("completed_at",at).put("events",JSONArray()).put("sheet",Repository.defaultSheet()).toString(),status)
    private fun archive(name:String="points")=javaClass.classLoader!!.getResourceAsStream("gis/$name.zip")!!.use{Shapefile.read(it)}
    private val collector=collectorDefaults("00000000-0000-4000-8000-000000000013","TEST","Synthetic")
    private fun initial()=listOf(CatalogItem("test",collector.getString("id"),"collector",collector.toString()))
    private fun plan(a:ShapeArchive,old:List<CatalogItem> = initial(),mode:String="ISOLATED",source:String="functional-source")=ImportPlanner.plan(a,a.layers.map{ImportPlanner.propose(it)},source,collector.getString("id"),mode,true,false,0.0,old,"test")

    @Test fun civilQuarterUsesRomeIncludingMidnightAndYearBoundary(){
        listOf("2026-03-31T21:59:59Z" to "2026-T1","2026-03-31T22:00:00Z" to "2026-T2","2026-06-30T22:00:00Z" to "2026-T3","2026-09-30T22:00:00Z" to "2026-T4","2026-12-31T23:00:00Z" to "2027-T1").forEach{(date,quarter)->assertEquals(quarter,InspectionCsv.quarter(Instant.parse(date)))}
    }
    @Test fun quarterIsNotRollingAndExcludesNonExportableRows(){
        val first=visit("first","2026-06-30T22:00:00Z")
        val impeded=visit("impeded","2026-08-01T00:00:00Z","IMPEDITO")
        val cancelled=first.copy(id="cancelled",body=JSONObject(first.body).put("cancelled",JSONObject()).toString())
        val rows=listOf(first,impeded,cancelled,visit("previous","2026-06-25T00:00:00Z"),visit("future","2026-09-25T00:00:00Z"),visit("draft","2026-08-01T00:00:00Z","BOZZA"),first.copy(id="old-reset",sync="RESET_OBSOLETE"))
        assertEquals(setOf("first","impeded"),InspectionCsv.rows(rows,now).map{it.id}.toSet())
        assertTrue(InspectionCsv.rows(rows.filter{it.id !in setOf("first","impeded")},now).isEmpty())
    }
    @Test fun serverSeedFixtureHasStableCompleteIds(){
        val d=demo();val items=listOf("collectors" to "collector","points" to "point","segments" to "segment").flatMap{(name,kind)->d.getJSONArray(name).objects().map{CatalogItem("server",it.getString("id"),kind,it.toString())}}
        assertEquals(3,items.count{it.kind=="collector"});assertEquals(22,items.count{it.kind=="point"});assertEquals(19,items.count{it.kind=="segment"})
        assertEquals(items.size,items.map{it.id}.toSet().size);assertTrue(items.all{java.util.UUID.fromString(it.id)!=null})
    }
    @Test fun serverInspectorCannotAcquireRightsFromDemoBuild(){
        val session=JSONObject().put("base","https://example.supabase.co").put("access_token","public-test-token").put("protocol",2).put("role","inspector")
        assertFalse(mayManageCatalog(session));assertTrue(mayManageCatalog(session.put("role","admin")))
        assertFalse(mayManageCatalog(JSONObject(session.toString()).put("protocol",1)));assertFalse(mayManageCatalog(null))
    }
    @Test fun sevenDistinctShapesRoundTripAndKeepStatusColorsIndependent(){
        assertEquals(7,ManholeSymbol.entries.size)
        assertEquals(7,ManholeSymbol.entries.map{it.sdfPixels().toList()}.distinct().size)
        val pack=demo();val points=pack.getJSONArray("points").objects()
        points.forEachIndexed{i,p->p.put("status_color",InspectionState.entries[i%4].color)}
        for(symbol in ManholeSymbol.entries){
            val settings=FieldSettings(symbol=symbol.name,iconSize=14f,minZoomPozzetti=17f,asphalt=true)
            assertEquals(settings,FieldSettings.parse(settings.json()))
            val style=JSONObject(localStyle(pack,points,null,null,settings));val layers=style.getJSONArray("layers").objects().associateBy{it.getString("id")}
            val holes=layers.getValue("manholes")
            assertEquals("symbol",holes.getString("type"));assertEquals("symbol",holes.getJSONObject("layout").getJSONArray("icon-image").getString(1))
            assertEquals(.7,holes.getJSONObject("layout").getDouble("icon-size"),1e-8)
            assertEquals("status_color",holes.getJSONObject("paint").getJSONArray("icon-color").getString(1))
            assertEquals(17.0,holes.getDouble("minzoom"),0.0);assertFalse(layers.getValue("pipes").has("minzoom"))
            assertEquals(28.0,layers.getValue("asphalt-mark").getJSONObject("layout").getDouble("text-size"),0.0)
            val properties=style.getJSONObject("sources").getJSONObject("points").getJSONObject("data").getJSONArray("features").objects().map{it.getJSONObject("properties")}
            assertEquals(InspectionState.entries.map{it.color}.toSet(),properties.map{it.getString("status_color")}.toSet())
        }
        assertEquals("CIRCLE",FieldSettings.parse(JSONObject().put("symbol","unknown")).symbol)
    }
    @Test fun ringIsHollowAndDotRetainsCenter(){
        assertTrue(ManholeSymbol.RING.distance(0.0,0.0)>0)
        assertTrue(ManholeSymbol.DOT.distance(0.0,0.0)<0)
        for(s in ManholeSymbol.entries){assertTrue(s.distance(31.0,31.0)>0);assertEquals(0,s.sdfPixels().first() ushr 24)}
    }
    @Test fun reimportChangedPointKeepsItsIdAndReportsUpdate(){
        val a=archive();val first=plan(a);val layer=a.layers.single();val firstPoint=layer.features.first()
        val changed=firstPoint.copy(geometry=JSONObject(firstPoint.geometry.toString()).put("coordinates",JSONArray(listOf(11.001,46.001))))
        val again=plan(a.copy(layers=listOf(layer.copy(features=listOf(changed)+layer.features.drop(1)))),first.items)
        assertEquals(0,again.inserted);assertEquals(1,again.counts.getValue("point").updated)
        assertEquals(first.items.map{it.id}.toSet(),again.items.map{it.id}.toSet());assertTrue(again.missing.isEmpty())
    }
    @Test fun missingItemsReportedWithoutDeletingOrArchiving(){
        val a=archive();val first=plan(a);val reduced=a.copy(layers=a.layers.map{it.copy(features=it.features.dropLast(1))})
        val next=plan(reduced,first.items)
        assertEquals(1,next.counts.getValue("point").missing);assertEquals(1,next.missing.size)
        val merged=first.items.associateBy{it.id}+next.items.associateBy{it.id}
        assertEquals(first.items.size,merged.size);assertEquals(next.missing.single().body,merged.getValue(next.missing.single().id).body)
        assertEquals(1,next.provenance.getJSONObject("report").getInt("missing"))
    }
    @Test fun omittedLayersAndOtherSourcesAreNotReportedAsMissing(){
        val a=archive();val first=plan(a,mode="ORDERED")
        val otherLayer=a.copy(layers=a.layers.map{it.copy(name="other-layer")})
        val both=plan(otherLayer,first.items,mode="ORDERED")
        val all=(first.items.associateBy{it.id}+both.items.associateBy{it.id}).values.toList()
        assertTrue(plan(a,all,mode="ORDERED").missing.isEmpty())
        assertTrue(plan(a,all,source="different-source").missing.isEmpty())
    }
    @Test fun reimportMatchesCollectorCodesWithoutCaseDuplicates(){
        val a=archive();val mapped=a.copy(layers=a.layers.map{layer->layer.copy(fields=layer.fields+"collector",features=layer.features.map{it.copy(fields=it.fields+("collector" to "test"))})})
        val result=plan(mapped)
        assertEquals(0,result.counts.getValue("collector").inserted)
        assertTrue(result.items.filter{it.kind=="point"}.all{JSONObject(it.body).memberships()==listOf(collector.getString("id"))})
    }
    @Test(expected=IllegalArgumentException::class) fun archivedCollectorRequiresExplicitRestore(){plan(archive(),initial().map{it.copy(body=JSONObject(it.body).put("archived",true).toString())})}
    @Test fun explicitLineKeysTakePriorityOverDistance(){
        val a=archive("points-lines");val moved=a.copy(layers=a.layers.map{layer->if(layer.kind!="segment")layer else layer.copy(features=layer.features.map{f->
            val coords=f.geometry.getJSONArray("coordinates").let{array->(0 until array.length()).map{index->array.getJSONArray(index).let{listOf(it.getDouble(0)+.01,it.getDouble(1)+.01)}}}
            f.copy(geometry=JSONObject(f.geometry.toString()).put("coordinates",JSONArray(coords)))
        })})
        val result=plan(moved,mode="LINES")
        assertEquals(2,result.counts.getValue("segment").inserted);assertTrue(result.warnings.any{it.contains("chiave esplicita")})
        assertEquals(1,result.preview.getJSONArray("collectors").length())
    }
    @Test fun incompleteZipAndUnrecognizedCrsAreRejected(){
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use{target->ZipInputStream(javaClass.classLoader!!.getResourceAsStream("gis/points.zip")!!).use{input->while(true){val entry=input.nextEntry?:break;if(entry.name.endsWith(".shx"))continue;target.putNextEntry(ZipEntry(entry.name));input.copyTo(target);target.closeEntry()}}}
        try{Shapefile.read(out.toByteArray().inputStream());fail("Missing .shx accepted")}catch(e:IllegalArgumentException){assertTrue(e.message!!.contains(".shx"))}
        try{Shapefile.detectCrs("LOCAL_CS[\"unknown\"]");fail("Unknown CRS accepted")}catch(e:IllegalStateException){assertTrue(e.message!!.contains("CRS non riconosciuto"))}
    }
}
