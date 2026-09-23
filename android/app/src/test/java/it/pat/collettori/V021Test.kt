package it.pat.collettori
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*

class V021Test{
    @Test fun httpErrorsNeverMasqueradeAsOffline(){
        listOf(400,401,403,404,409,422,429,500,503).forEach{code->val message=friendlyError(ApiError(code,"private content","PGRST202","/rest/v1/rpc/coll_pat_catalog"));assertTrue(message.contains("HTTP $code"));assertFalse(message.contains("private content"));assertFalse(message.contains("Connessione non disponibile"))}
        assertTrue(friendlyError(java.net.UnknownHostException()).contains("Connessione"));assertTrue(friendlyError(java.net.SocketTimeoutException()).contains("Tempo"))
    }
    @Test fun exactThresholdsNormalizeLegacyWithoutChangingHistoricalEvents(){
        val raw=JSONObject().put("distance",100).put("accuracy",49);val settings=FieldSettings.parse(raw)
        assertEquals(30.0,settings.maxDistance,0.0);assertEquals(40.0,settings.maxAccuracy,0.0);assertEquals(100,raw.getInt("distance"))
        assertEquals(150.0,FieldSettings.parse(JSONObject().put("accuracy",150)).maxAccuracy,0.0)
        assertEquals(20.0,FieldSettings().maxAccuracy,0.0)
    }
    @Test fun neutralNoteAndUnsafeFlagHaveSeparateMeaning(){
        val b=JSONObject().put("sheet_version","sheet-3").put("sheet",Repository.defaultSheet().put("anomaly_note","Controllo alle 10"))
        assertFalse(hasAnomaly(b));b.getJSONObject("sheet").put("walls","ANOMALO");assertTrue(hasAnomaly(b))
        val s=applyUnsafe(b.getJSONObject("sheet").put("cover","ANOMALO"),true)
        assertEquals("ANOMALO",s.getString("cover"));assertEquals("NON_APPLICABILE",s.getString("closure"));assertEquals("NON_APPLICABILE",s.getString("restored"));assertFalse(s.getBoolean("opened"))
        assertEquals("NON_OSSERVABILE",applyUnsafe(s,false).getString("walls"))
    }
    @Test fun notesCombineOnceAndNeverDropPriorText(){val s=JSONObject().put("notes","Nota storica").put("anomaly_note","Anomalia storica");val merged=unifiedNotes(s);s.put("anomaly_note",merged).put("notes","");assertEquals(merged,unifiedNotes(s))}
    @Test fun sharedPointShapeUsesContextThenDeterministicCollectorAndStatusColor(){
        val a=collectorDefaults("a","A","A").put("symbol","SQUARE");val b=collectorDefaults("b","B","B").put("symbol","DIAMOND")
        val p=JSONObject().put("collectors",JSONArray(listOf("b","a")));val settings=FieldSettings()
        assertEquals(ManholeSymbol.SQUARE.imageId,resolvedSymbol(p,listOf(b,a),settings));assertEquals(ManholeSymbol.DIAMOND.imageId,resolvedSymbol(p,listOf(a,b),settings,"b"));p.put("symbol","HEXAGON");assertEquals(ManholeSymbol.HEXAGON.imageId,resolvedSymbol(p,listOf(a,b),settings,"b"))
        assertEquals(settings.lineColor,resolvedCollectorColor(a,settings));a.put("display_color","#AF235A");assertEquals("#AF235A",resolvedCollectorColor(a,settings.copy(lineColor="#254EBC")))
    }
    @Test fun shortCollectorHasTwoDistinctCodeAnchorsOnActualGeometry(){
        val c=collectorDefaults("c","ACTUAL-CODE","C");val line=JSONObject().put("id","s").put("from_id","a").put("to_id","b").put("collectors",JSONArray(listOf("c"))).put("geometry",JSONObject().put("type","LineString").put("coordinates",JSONArray("[[11,46],[11.001,46],[11.001,46.001]]")))
        val pack=JSONObject().put("collectors",JSONArray(listOf(c))).put("segments",JSONArray(listOf(line)))
        val anchors=collectorLabelFeatures(pack);assertEquals(2,anchors.size);assertTrue(anchors.all{it.getJSONObject("properties").getString("code")=="ACTUAL-CODE"});assertNotEquals(anchors[0].getJSONObject("geometry").toString(),anchors[1].getJSONObject("geometry").toString())
    }
    @Test fun chunksAndDraftPhotosAreGroupedOnce(){
        fun op(id:String,kind:String,ref:String)=Pending(id,"a",ref,1,JSONObject().put("payload",JSONObject()).toString(),kind=kind)
        val queue=(1..10).map{op("chunk$it","catalog_chunk","batch")}+op("final","catalog","batch")+op("draft","shared_inspection","v")
        val visit=Visit("v","a","p","d","{}",sync="IN_ATTESA")
        val settings=listOf(Setting("a","photos:v","[{\"photoId\":\"photo\",\"localUri\":\"content://local\",\"uploadStatus\":\"PENDING\"}]"))
        assertEquals(2,logicalQueue(queue,listOf(visit),settings).size);assertEquals(1,logicalQueue(queue,listOf(visit),settings).first{it.id=="inspection:v"}.photos.size)
    }
    @Test fun targetedScrubbingPreservesUnrelatedSharedContent(){val input=JSONObject("{\"items\":[{\"id\":\"remove\",\"note\":\"private\"},{\"id\":\"keep\",\"note\":\"active\"}],\"next_ids\":[\"remove\",\"keep\"]}");val result=scrubDeleted(input,setOf("remove")) as JSONObject;assertFalse(result.toString().contains("private"));assertTrue(result.toString().contains("active"));assertEquals(listOf("keep"),result.getJSONArray("next_ids").strings())}
}
