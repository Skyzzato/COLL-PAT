package it.pat.collettori

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant

class V014Test {
    private val now=Instant.parse("2026-09-22T12:00:00Z")
    private val p=JSONObject().put("id","point").put("code","GL-001").put("collectors",JSONArray(listOf("collector")))
    private fun collector(visits:Int)=collectorDefaults("collector","C","Demo").put("visits_h1",visits).put("visits_h2",visits)
    private fun visit(days:Long,anomaly:Boolean=false,status:String="COMPLETO",id:String="v$days"):Visit {
        val b=JSONObject().put("started_at",now.minusSeconds(days*86400)).put("completed_at",now.minusSeconds(days*86400)).put("periodic_control",status=="COMPLETO").put("events",JSONArray()).put("sheet",Repository.defaultSheet().put("anomaly_note",if(anomaly)"Anomalia" else ""))
        return Visit(id,"owner","point","data",b.toString(),status)
    }
    private fun status(vararg v:Visit,frequency:Int=2)=getInspectionStatus(p,collector(frequency),v.toList(),now)
    @Test fun caseARecentRegular(){assertEquals(InspectionState.INSPECTED,status(visit(20)).calculatedStatus)}
    @Test fun caseBOldRegular(){assertEquals(InspectionState.DUE,status(visit(120)).calculatedStatus)}
    @Test fun caseCRecentAnomaly(){assertEquals(InspectionState.ANOMALY_RECENT,status(visit(20,true)).calculatedStatus)}
    @Test fun caseDOldAnomaly(){assertEquals(InspectionState.ANOMALY_DUE,status(visit(120,true)).calculatedStatus)}
    @Test fun caseEOldAnomalyResolved(){assertFalse(status(visit(120,true),visit(20)).latestInspectionHasAnomaly)}
    @Test fun caseFLatestAnomaly(){assertTrue(status(visit(120),visit(20,true)).latestInspectionHasAnomaly)}
    @Test fun caseGThreeVisits(){val s=status(visit(70),frequency=3);assertEquals(60.87,s.targetDays,.01);assertFalse(s.inspectionUpToDate)}
    @Test fun caseHOneVisit(){val s=status(visit(120),frequency=1);assertEquals(182.62,s.targetDays,.01);assertTrue(s.inspectionUpToDate)}
    @Test fun ignoreDraftImpedimentFutureCancelledAndObsolete(){
        val cancelled=visit(2,true).let{it.copy(body=JSONObject(it.body).put("cancelled",JSONObject()).toString())}
        val s=status(visit(20),visit(1,true,"BOZZA"),visit(3,true,"IMPEDITO"),visit(-1,true),cancelled,visit(4,true).copy(sync="RESET_OBSOLETE"))
        assertEquals("v20",s.latestInspection!!.id);assertFalse(s.latestInspectionHasAnomaly)
    }
    @Test fun emptyHistoryIsDue(){assertEquals(InspectionState.DUE,status().calculatedStatus)}
    @Test fun zeroFrequencyDoesNotDivideByZero(){assertFalse(status(visit(1),frequency=0).inspectionUpToDate)}
    @Test fun accuracyBoundary(){assertTrue(acceptableMeasure(5.0,10.0));assertTrue(acceptableMeasure(10.0,10.0));assertFalse(acceptableMeasure(10.1,10.0));assertFalse(acceptableMeasure(Double.NaN,10.0));assertFalse(acceptableMeasure(null,10.0))}
    @Test fun distanceBoundary(){assertTrue(acceptableMeasure(8.0,15.0));assertTrue(acceptableMeasure(15.0,15.0));assertFalse(acceptableMeasure(16.0,15.0))}
    @Test fun historicalGpsThresholdAndIndependentDistance(){
        val e=JSONObject().put("latitude",46.0).put("longitude",11.0).put("accuracy_m",10).put("age_s",0).put("permission","PRECISE").put("applied_limits",JSONObject().put("max_accuracy_m",10).put("radius_m",15)).put("local_evaluation",JSONObject().put("distance_m",15))
        assertEquals("RELIABLE",gpsQuality(e));e.put("accuracy_m",10.1);assertEquals("IMPRECISE",gpsQuality(e));e.put("accuracy_m",5);e.getJSONObject("local_evaluation").put("distance_m",16);assertEquals("UNRELIABLE",gpsQuality(e))
    }
    @Test fun numericVersionComparison(){assertTrue(compareVersions("0.9","0.14")<0);assertTrue(compareVersions("0.14","0.13")>0);assertEquals(0,compareVersions("0.14-demo","0.14.0"));assertTrue(compareVersions("1.0.0","0.99")>0);assertTrue(compareVersions("1.0.0-alpha.2","1.0.0-alpha.10")<0);assertTrue(compareVersions("1.0.0-rc.1","1.0.0")<0)}
    @Test fun csvEscapesQuotesNewlinesSeparatorsAndPreservesUtf8(){
        val v=visit(20);val b=JSONObject(v.body);b.getJSONObject("sheet").put("notes","Città; virgola, \"testo\"\nseconda riga")
        val csv=InspectionCsv.export(listOf(v.copy(body=b.toString()),visit(1,status="BOZZA"),visit(300)),listOf(p),listOf(collector(2)),setOf(v.id),now)
        assertTrue(csv.startsWith("\uFEFF"));assertTrue(csv.contains("\"Città; virgola, \"\"testo\"\"\nseconda riga\""));assertTrue(csv.contains("\"SI\""));assertFalse(csv.contains("v300"));assertEquals("COLL-PAT_ispezioni_2026_T3.csv",InspectionCsv.filename(now))
    }
    @Test fun mapZoomOnlyAffectsManholesAndCollectorColors(){
        val demo=JSONObject(javaClass.classLoader!!.getResource("trento-lavis-gilli-v0.14.json")!!.readText())
        demo.getJSONArray("collectors").getJSONObject(0).put("display_color","#254EBC")
        val style=JSONObject(localStyle(demo,demo.getJSONArray("points").objects(),null,null,FieldSettings(minZoomPozzetti=16f,symbol="RING",asphalt=false)))
        val layers=style.getJSONArray("layers").objects().associateBy{it.getString("id")}
        assertEquals(16.0,layers.getValue("manholes").getDouble("minzoom"),0.0);assertFalse(layers.getValue("pipes").has("minzoom"));assertEquals("none",layers.getValue("asphalt-mark").getJSONObject("layout").getString("visibility"))
        assertEquals("#254EBC",style.getJSONObject("sources").getJSONObject("network").getJSONObject("data").getJSONArray("features").getJSONObject(0).getJSONObject("properties").getString("display_color"))
    }
    @Test fun gilliSeedPassesReferenceCoordinate(){val d=JSONObject(javaClass.classLoader!!.getResource("trento-lavis-gilli-v0.14.json")!!.readText());val points=d.getJSONArray("points").objects().filter{it.getString("code").startsWith("GL-")};assertEquals(6,points.size);assertTrue(points.any{GpsRule.distance(46.0904585,11.1195695,it.getDouble("latitude"),it.getDouble("longitude"))<1});assertTrue(points.any{it.optBoolean("under_asphalt")})}
}
