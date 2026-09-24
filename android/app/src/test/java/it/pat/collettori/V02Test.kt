package it.pat.collettori

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.zip.*
import java.io.ByteArrayOutputStream

class V02Test {
    private fun p(id:String,code:String=id,x:Double=11.0)=JSONObject().put("id",id).put("code",code).put("latitude",46.0).put("longitude",x).put("collectors",JSONArray(listOf("c")))
    private fun layer()=ShapeLayer("nested/points","point",listOf("id_coll","numero","id"),listOf(
        ShapeFeature(mapOf("id_coll" to "A","numero" to "001","id" to "0"),JSONObject().put("type","Point").put("coordinates",JSONArray(listOf(11.0,46.0)))),
        ShapeFeature(mapOf("id_coll" to "B","numero" to "001","id" to "0"),JSONObject().put("type","Point").put("coordinates",JSONArray(listOf(11.001,46.0))))
    ),4326,"UTF-8")
    private fun plan(l:ShapeLayer,m:LayerMapping=ImportPlanner.propose(l),old:List<CatalogItem> = emptyList())=ImportPlanner.plan(ShapeArchive(listOf(l),"hash"),listOf(m),"synthetic-v02","","ISOLATED",false,false,5.0,old,"owner")
    private fun denied(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    @Test fun duplicateNumbersAcrossCollectorsUseScopedIdentityAndKeepZeroes(){val l=layer();val m=ImportPlanner.propose(l);assertEquals("numero",m.fields["key"]);assertEquals("id_coll",m.fields["scope"]);val a=plan(l);assertEquals(2,a.items.count{it.kind=="point"});assertTrue(a.items.filter{it.kind=="point"}.all{JSONObject(it.body).getString("code")=="001"});val b=plan(l,old=a.items);assertEquals(0,b.inserted);assertEquals(0,b.updated)}
    @Test fun duplicateMissingIdentifiersAreRejectedWithoutExplicitAssignment(){val l=layer().let{it.copy(features=it.features.map{f->f.copy(fields=f.fields+("id_coll" to "A"))})};denied{plan(l)}}
    @Test fun registeredIdentityNeverMergesClosePointsWithSameZeroCode(){val l=layer().let{it.copy(features=it.features.map{f->f.copy(fields=mapOf("id_coll" to "A","numero" to "0","id" to "0"))})};val m=LayerMapping(l.name,mapOf("key" to REGISTERED_IDENTITY,"code" to "numero","collector" to "id_coll"));val a=plan(l,m);assertEquals(2,a.items.count{it.kind=="point"});assertEquals(0,plan(l,m,a.items).inserted)}
    @Test fun changedRegisteredRecordNeedsExplicitReconciliation(){val l=layer();val m=LayerMapping(l.name,mapOf("key" to REGISTERED_IDENTITY,"code" to "numero","collector" to "id_coll"));val a=plan(l,m);val changed=l.copy(features=listOf(l.features.first().copy(fields=l.features.first().fields+("numero" to "002")))+l.features.drop(1));assertEquals(1,unresolvedFeatures(changed,m,"synthetic-v02",a.items).size);denied{plan(changed,m,a.items)};val old=a.items.first{it.kind=="point"};val chosen=m.copy(fields=m.fields+("match:"+featureFingerprint(changed.features.first()) to old.id));val b=plan(changed,chosen,a.items);assertEquals(0,b.inserted);assertEquals(old.id,b.items.first{it.kind=="point"}.id)}
    @Test fun indistinguishableRecordsAreNeverSilentlyDiscarded(){val l=layer().let{it.copy(features=listOf(it.features.first(),it.features.first()))};denied{plan(l,LayerMapping(l.name,mapOf("key" to REGISTERED_IDENTITY,"code" to "numero")))}}
    @Test fun reversedSourceRowsKeepRegisteredIdentities(){val l=layer();val m=LayerMapping(l.name,mapOf("key" to REGISTERED_IDENTITY,"code" to "numero"));val a=plan(l,m);val b=plan(l.copy(features=l.features.reversed()),m,a.items);assertEquals(a.items.map{it.id}.toSet(),b.items.map{it.id}.toSet());assertEquals(0,b.inserted)}
    @Test fun deletedSourceRecordGetsANewLifecycle(){val l=layer();val a=plan(l);val b=plan(l,old=a.items.filter{it.kind!="point"});assertTrue(b.items.filter{it.kind=="point"}.none{n->a.items.any{it.id==n.id}});assertEquals(0,plan(l,old=b.items).inserted)}
    @Test fun coordinateParserAcceptsBothSeparatorsAndNegativeAxes(){assertEquals(46.06789,coordinateInput("46,067890",true),0.0);assertEquals(-11.1,coordinateInput("-11.1",false),0.0);listOf("","abc","NaN","Infinity","90,1").forEach{denied{coordinateInput(it,true)}};denied{coordinateInput("180.1",false)}}
    @Test fun manualMembershipUsesUuidWhenCollectorCodeChanges(){val point=manualPoint(JSONObject(),"p","c","0002","46,01","11.02","","",emptyList(),false);assertEquals(listOf("c"),point.memberships());assertEquals("0002",point.getString("code"));assertFalse(point.has("sequence"));assertEquals("Pozzetto successivo non definito",nextPointLabel(point,listOf(point),emptyList()))}
    @Test fun outgoingGeometryLengthIsIndependentOfOperatorPosition(){val a=p("a");val b=p("b","PZ-002",11.001);val line=JSONObject().put("from_id","a").put("to_id","b").put("collectors",JSONArray(listOf("c"))).put("length_m",42.7);assertEquals("Al successivo #PZ-002: 42,7 m",nextPointLabel(a,listOf(a,b),listOf(line)))}
    @Test fun branchesListEveryOutgoingPointAndMissingDistanceIsExplicit(){val a=p("a").put("next_ids",JSONArray(listOf("b","missing")));val b=p("b");val label=nextPointLabel(a,listOf(a,b),emptyList());assertTrue(label.contains("#b"));assertTrue(label.contains("stima in linea d’aria"));assertTrue(label.contains("#missing: distanza non disponibile"))}
    @Test fun finalPointAndUnknownNextAreDifferent(){assertEquals("Fine collettore / ramo",nextPointLabel(p("a").put("topology_end",true),emptyList(),emptyList()));assertEquals("Pozzetto successivo non definito",nextPointLabel(p("a"),emptyList(),emptyList()))}
    @Test fun sequenceUsesTopologyNotAlphabetOrNearestCoordinate(){val a=p("a").put("sequence",1);val b=p("b","ZZZ",11.005).put("sequence",2);val c=p("c","AAA",11.000001).put("sequence",3);assertTrue(nextPointLabel(a,listOf(a,b,c),emptyList()).contains("#ZZZ"))}
    @Test fun frequencyUsesCurrentSemesterWithoutChangingContract(){val c=collectorDefaults("c","C","C").put("visits_h1",4).put("visits_h2",2);assertTrue(frequencyLabel(c,Instant.parse("2026-08-01T00:00:00Z")).contains("91 giorni"));assertTrue(frequencyLabel(c,Instant.parse("2026-01-01T00:00:00Z")).contains("46 giorni"));assertEquals("Frequenza ispezione non configurata",frequencyLabel(JSONObject()))}
    @Test fun roleNamesDoNotInventPrivileges(){assertEquals("Amministratore",roleLabel("admin"));assertEquals("Operatore",roleLabel("inspector"));assertEquals("Non disponibile",roleLabel(null))}
    @Test fun queueDeduplicatesRetriesAndChunksButRetainsErrors(){
        fun op(id:String,kind:String,p:JSONObject)=Pending(id,"o","batch",id.length,JSONObject().put("payload",p).toString(),state="CONFLICT",kind=kind)
        val item=JSONObject().put("kind","point").put("data",p("point"));val payload=JSONObject().put("items",JSONArray(listOf(item)))
        val q=listOf(op("a","catalog_chunk",payload),op("bb","catalog",payload),op("ccc","shared_inspection",JSONObject()),op("dddd","shared_inspection",JSONObject()))
        assertEquals(setOf("catalog:point","inspection:batch"),queuedLogicalIds(q));assertEquals("1 elemento in coda di caricamento",uploadCountLabel(1));assertEquals("0 elementi in coda di caricamento",uploadCountLabel(0))
    }
    @Test fun publicationOrderAndBuildAreCompatible(){listOf("0.1","0.11","0.12","0.13","0.14","0.15","0.16").forEach{assertTrue(compareVersions("0.2",it)>0)};assertEquals("0.22",BuildConfig.VERSION_NAME);assertTrue(BuildConfig.VERSION_CODE>21);assertTrue(compareVersions("0.21","0.2")>0)}
    @Test fun nestedZipAndMissingComponentsAreDiagnosed(){
        val original=javaClass.classLoader!!.getResourceAsStream("gis/points.zip")!!.readBytes()
        fun zip(exclude:String?):ByteArray{val out=ByteArrayOutputStream();ZipOutputStream(out).use{to->ZipInputStream(original.inputStream()).use{from->while(true){val entry=from.nextEntry?:break;val data=from.readBytes();if(entry.name.endsWith(exclude?:".never"))continue;to.putNextEntry(ZipEntry("folder/"+entry.name));to.write(data);to.closeEntry()}}};return out.toByteArray()}
        assertEquals("folder/points",Shapefile.read(zip(null).inputStream()).layers.single().name)
        try{Shapefile.read(zip(".dbf").inputStream());fail()}catch(e:IllegalArgumentException){assertTrue(e.message!!.contains(".dbf"));assertTrue(e.message!!.contains("folder/points"))}
        denied{Shapefile.read(zip(".prj").inputStream())};assertEquals(4326,Shapefile.read(zip(".prj").inputStream(),4326).layers.single().crs)
        assertTrue(Shapefile.read(zip(".cpg").inputStream()).layers.single().encodingNote.isNotBlank())
    }
    @Test fun projectedCrsNeverUsesNestedGeographicEpsg(){denied{Shapefile.detectCrs("PROJCS[\"Unknown projection\",GEOGCS[\"WGS 84\",AUTHORITY[\"EPSG\",\"4326\"]]]")}}
    @Test fun optionalPrivateDatasetUsesActualParserWithoutPublishingData(){
        val path=System.getenv("COLL_PAT_PRIVATE_ZIP")?:return
        val archive=java.io.File(path).inputStream().use{Shapefile.read(it)}
        val mappings=archive.layers.map{ImportPlanner.propose(it).let{m->m.copy(fields=m.fields+("key" to REGISTERED_IDENTITY)+("scope" to "")+("discriminator" to ""))}}
        val first=ImportPlanner.plan(archive,mappings,"private-validation","","ISOLATED",false,false,5.0,emptyList(),"private")
        val second=ImportPlanner.plan(archive,mappings,"private-validation","","ISOLATED",false,false,5.0,first.items,"private")
        assertEquals(archive.layers.sumOf{it.features.size},first.items.count{it.kind=="point"});assertEquals(0,second.inserted);assertEquals(0,second.updated)
    }
}
