package it.pat.collettori

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream

class V013Test {
    private fun read(name:String)=javaClass.classLoader!!.getResourceAsStream("gis/$name.zip")!!.use{Shapefile.read(it)}
    private val collector=collectorDefaults("00000000-0000-4000-8000-000000000013","TEST","Synthetic")
    private fun plan(a:ShapeArchive,mode:String,old:List<CatalogItem> = listOf(CatalogItem("test",collector.getString("id"),"collector",collector.toString())))=ImportPlanner.plan(a,a.layers.map{ImportPlanner.propose(it)},"synthetic-source",collector.getString("id"),mode,true,false,0.0,old,"test")
    private fun body():JSONObject=JSONObject().put("model","ORDINARY").put("sheet",Repository.defaultSheet().put("notes","Never lose this").put("exception_reason","Synthetic GPS exception"))
        .put("events",JSONArray().put(JSONObject().put("id","1").put("acquired_at","2026-01-01T00:00:00Z").put("local_evaluation",JSONObject().put("state","NON_DISPONIBILE"))))
    @Test fun collectorValidationUnknownLengthAndRename(){
        validateCollector(collector);val id=collector.getString("id");collector.put("code","NEW").put("description","Changed").put("type","BOE'").put("hours_km_visit",decimalItalian("2,75"));validateCollector(collector)
        assertEquals(id,collector.getString("id"));assertTrue(collector.isNull("length_m"));assertEquals(2.75,collector.getDouble("hours_km_visit"),0.0)
    }
    @Test(expected=IllegalArgumentException::class) fun fractionalVisitsRejected(){validateCollector(collector.put("visits_h1",1.5))}
    @Test(expected=IllegalArgumentException::class) fun negativeHoursRejected(){validateCollector(collector.put("hours_km_visit",-1))}
    @Test fun templatesPreserveNotesWithoutFictitiousInternals(){val b=applyTemplate(body(),"ASPHALT_EXTERNAL");assertEquals("Never lose this",b.getJSONObject("sheet").getString("notes"));assertFalse(b.getJSONObject("sheet").getBoolean("opened"));assertEquals("NON_OSSERVABILE",b.getJSONObject("sheet").getString("walls"));assertFalse(b.getJSONObject("sheet").getBoolean("raise_needed"));validateInspection(b,"COMPLETO")}
    @Test(expected=IllegalArgumentException::class) fun partialCannotBeNewResult(){validateInspection(body(),"PARZIALE")}
    @Test(expected=IllegalArgumentException::class) fun impedimentRequiresReason(){validateInspection(body(),"IMPEDITO")}
    @Test fun noValidEvidenceAfterCancellation(){val b=body();val first=b.getJSONArray("events").getJSONObject(0);first.put("cancelled",JSONObject().put("reason","wrong"));assertNull(lastEvidence(b));try{validateInspection(b,"COMPLETO");fail()}catch(_:IllegalArgumentException){}}
    @Test fun latestEvidenceNotMostFavourable(){val b=body();b.getJSONArray("events").put(JSONObject().put("id","2").put("acquired_at","2026-01-01T00:01:00Z").put("local_evaluation",JSONObject().put("state","NON_COMPATIBILE")));assertEquals("2",lastEvidence(b)!!.getString("id"))}
    @Test fun romeMidnightSemesterAndDst(){assertEquals("2026-S2",semester(Instant.parse("2026-06-30T22:30:00Z")));assertEquals("2026-S1",semester(Instant.parse("2026-03-29T01:30:00Z")))}
    @Test fun pointReaderPreservesLeadingZerosAndProjectedCoordinates(){val geographic=read("points");val projected=read("points-utm");assertEquals("0001",geographic.layers.single().features.first().fields["code"]);assertEquals(32632,projected.layers.single().crs);val c=projected.layers.single().features.first().geometry.getJSONArray("coordinates");assertEquals(11.0,c.getDouble(0),.0000001);assertEquals(46.0,c.getDouble(1),.0000001)}
    @Test fun isolatedPointsNeverGetInventedLines(){val p=plan(read("points"),"ISOLATED");assertEquals(3,p.items.count{it.kind=="point"});assertEquals(0,p.items.count{it.kind=="segment"});assertTrue(p.items.filter{it.kind=="point"}.all{!JSONObject(it.body).has("previous_id")});assertTrue(JSONObject(p.items.first{it.kind=="collector"}.body).isNull("length_m"))}
    @Test fun orderedPointsAreSchematicAndReimportUsesSourceKeys(){val a=read("points");val p=plan(a,"ORDERED");assertEquals(2,p.items.count{it.kind=="segment"});assertTrue(p.items.filter{it.kind=="segment"}.all{JSONObject(it.body).getBoolean("schematic")});val reimport=plan(a,"ORDERED",p.items);assertEquals(0,reimport.inserted);assertEquals(p.items.map{it.id}.toSet(),reimport.items.map{it.id}.toSet());assertEquals(0,reimport.updated)}
    @Test fun realLinesAndSharedMembership(){val p=plan(read("points-lines"),"LINES");assertEquals(2,p.items.count{it.kind=="segment"});assertTrue(p.items.filter{it.kind=="segment"}.all{!JSONObject(it.body).getBoolean("schematic")});assertTrue(p.items.filter{it.kind=="segment"}.all{JSONObject(it.body).getDouble("length_m")>100})}
    @Test fun declaredLengthSurvivesImport(){val c=JSONObject(collector.toString()).put("length_source","DECLARED").put("length_m",450.0);val p=plan(read("points-lines"),"LINES",listOf(CatalogItem("test",c.getString("id"),"collector",c.toString())));assertTrue(p.warnings.any{it.contains("dichiarata preservata")});assertFalse(p.items.any{it.kind=="collector"})}
    @Test fun orderIsAcceptedAutomatically(){val a=read("points");val plan=ImportPlanner.plan(a,a.layers.map{ImportPlanner.propose(it)},"source",collector.getString("id"),"ORDERED",false,false,0.0,listOf(CatalogItem("test",collector.getString("id"),"collector",collector.toString())),"test");assertEquals(2,plan.items.count{it.kind=="segment"})}
    @Test(expected=IllegalArgumentException::class) fun unsafeZipRejected(){val out=ByteArrayOutputStream();ZipOutputStream(out).use{it.putNextEntry(ZipEntry("../bad.shp"));it.write(byteArrayOf(1));it.closeEntry()};Shapefile.read(out.toByteArray().inputStream())}
    @Test fun missingCrsAndEncodingRequireExplicitValues(){
        val original=javaClass.classLoader!!.getResourceAsStream("gis/points.zip")!!;val out=ByteArrayOutputStream()
        ZipOutputStream(out).use{z->java.util.zip.ZipInputStream(original).use{input->while(true){val entry=input.nextEntry?:break;if(entry.name.endsWith(".prj")||entry.name.endsWith(".cpg"))continue;z.putNextEntry(ZipEntry(entry.name));input.copyTo(z);z.closeEntry()}}}
        try{Shapefile.read(out.toByteArray().inputStream());fail()}catch(e:IllegalStateException){assertTrue(e.message!!.contains("CRS assente"))}
        assertEquals(3,Shapefile.read(out.toByteArray().inputStream(),4326,"UTF-8").layers.single().features.size)
        try{Shapefile.read(out.toByteArray().inputStream(),3003,"UTF-8");fail()}catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("non supportato"))}
    }
    @Test fun utmAndWebMercatorDoNotSumDegrees(){val c=Shapefile.toWgs84(1224514.3987260093,5780349.220256354,3857);assertEquals(11.0,c.first,1e-7);assertEquals(46.0,c.second,1e-7);val geom=JSONObject("""{"type":"LineString","coordinates":[[11,46],[11.001,46.001]]}""");assertTrue(geometryLength(geom) in 130.0..140.0)}
}
