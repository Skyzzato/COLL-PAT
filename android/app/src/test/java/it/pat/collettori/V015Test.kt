package it.pat.collettori

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.*

class V015Test {
    private fun sample(second:Double,lat:Double=46.0,accuracy:Double?=5.0)=GpsSample((second*1e9).toLong(),lat,11.0,accuracy)
    private fun window()=GpsWindow(0,10.0)
    private fun evidence(state:String="COMPATIBILE"):JSONObject {
        val w=window();listOf(0.0,2.0,4.5).forEach{w.add(sample(it),5_000_000_000)}
        return w.finish(5_000_000_000).put("id","event").put("inspection_id","visit").put("manhole_id","point").put("permission","PRECISE")
            .put("applied_limits",JSONObject().put("max_accuracy_m",10).put("radius_m",20))
            .put("local_evaluation",JSONObject().put("state",state).put("distance_m",if(state=="COMPATIBILE")0 else 50)).put("match_outcome","VERIFIED")
    }
    private fun inspection()=JSONObject().put("id","visit").put("manhole_id","point")
    private fun denied(block:()->Unit){try{block();fail("Operation unexpectedly accepted")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    @Test fun preliminaryAccuracyAndFreshnessAreHardGates(){
        assertNull(AcquisitionPolicy.validate(sample(1.0,accuracy=10.0),1_000_000_000,10.0))
        assertTrue(AcquisitionPolicy.validate(sample(1.0,accuracy=10.1),1_000_000_000,10.0)!!.contains("insufficiente"))
        listOf(null,Double.NaN,Double.POSITIVE_INFINITY,-1.0).forEach{assertNotNull(AcquisitionPolicy.validate(sample(1.0,accuracy=it),1_000_000_000,10.0))}
        assertNotNull(AcquisitionPolicy.validate(null,1,10.0));assertFalse(AcquisitionPolicy.fresh(sample(1.0),12_000_000_000));assertFalse(AcquisitionPolicy.fresh(sample(1.0),0))
    }
    @Test fun fiveSecondsProducesSpatialMeanAndConservativeAccuracy(){
        val w=window();w.add(sample(0.0,46.0,4.0),0);w.add(sample(2.0,46.00002,10.0),2_000_000_000);w.add(sample(4.5,46.00004,6.0),4_500_000_000)
        val e=w.finish(5_000_000_000);assertEquals(46.00002,e.getDouble("latitude"),1e-8);assertEquals(10.0,e.getDouble("accuracy_m"),0.0);assertEquals(3,e.getInt("sample_count"));assertTrue(e.getDouble("dispersion_m")>2)
    }
    @Test fun oneSampleAndDuplicateCallbacksCannotMakeAnEvent(){val w=window();repeat(8){w.add(sample(4.0),4_500_000_000)};denied{w.finish(5_000_000_000)}}
    @Test fun windowExcludesEarlierFutureInvalidAndOutOfOrderSamples(){val w=window();w.add(sample(-1.0),0);w.add(sample(9.0),0);w.add(sample(0.0),0);w.add(sample(2.0),2_000_000_000);w.add(sample(1.0),3_000_000_000);w.add(sample(3.0,lat=100.0),3_000_000_000);w.add(sample(4.5),4_500_000_000);assertEquals(3,w.finish(5_000_000_000).getInt("sample_count"))}
    @Test fun tooShortSpanAndOldLastSampleFail(){val clustered=window();listOf(4.0,4.1,4.2).forEach{clustered.add(sample(it),5_000_000_000)};denied{clustered.finish(5_000_000_000)};val old=window();listOf(0.0,1.0,2.0).forEach{old.add(sample(it),5_000_000_000)};denied{old.finish(5_000_000_000)}}
    @Test fun deteriorationAbortsAndCannotBeMaskedByPreviousSamples(){val w=window();listOf(0.0,2.0,4.0).forEach{w.add(sample(it),5_000_000_000)};w.add(sample(4.5,accuracy=11.0),4_500_000_000);assertNotNull(w.failure);denied{w.finish(5_000_000_000)}}
    @Test fun compatibleEventAndMotivatedExceptionAreSeparate(){val e=evidence();validateNewEvidence(e,inspection());assertTrue(usableInspectionGps(e));val mismatch=evidence("NON_COMPATIBILE");denied{validateNewEvidence(mismatch,inspection())};assertFalse(usableInspectionGps(mismatch));mismatch.put("match_outcome","EXCEPTION").put("exception_reason"," ");denied{validateNewEvidence(mismatch,inspection())};mismatch.put("exception_reason","Accesso dal lato opposto");validateNewEvidence(mismatch,inspection());assertTrue(motivatedGpsException(mismatch));assertTrue(usableInspectionGps(mismatch));assertEquals("UNRELIABLE",gpsQuality(mismatch))}
    @Test fun exceptionCannotOverrideAccuracyOrMoveToAnotherVisit(){val e=evidence("INCERTA").put("match_outcome","EXCEPTION").put("exception_reason","Motivo");e.put("accuracy_m",11);denied{validateNewEvidence(e,inspection())};e.put("accuracy_m",5).put("inspection_id","other");denied{validateNewEvidence(e,inspection())}}
    @Test fun feedbackDeadlineIsIndependentOfServerAndBlockedByConflict(){
        val v=Visit("visit","owner","point","pack",JSONObject().put("local_edit",7).toString(),sync="IN_ATTESA")
        val f=SaveFeedback("visit",7,false,1000);assertEquals(2000,f.remaining(1000));assertEquals(1,f.remaining(2999));assertEquals(0,f.remaining(3000))
        assertTrue(f.title(v,false).contains("in attesa"));assertEquals("Ispezione salvata in locale",f.title(v,true));assertFalse(f.serverConfirmed(v.copy(sync="RICEVUTO_SERVER")))
        val ack=v.copy(sync="RICEVUTO_SERVER",receipt="{}");assertEquals("Ispezione salvata sul server",f.title(ack,false));assertEquals(500,f.remaining(2500));assertFalse(f.serverConfirmed(ack.copy(body="{\"local_edit\":8}")))
        assertEquals("Bozza salvata",f.copy(draft=true).title(v,false));assertTrue(f.copy(draft=true).detail(ack,false).contains("utenti autorizzati"));assertFalse(f.mayReturn(v.copy(sync="CONFLICT")));assertFalse(f.mayReturn(v.copy(id="another")))
    }

    private fun zip(cpg:String?=null,driver:Int=0,encoding:String="UTF-8",text:String="Città è più",ids:List<String> = listOf("01","abc","003")):ByteArray{
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use{target->ZipInputStream(javaClass.classLoader!!.getResourceAsStream("gis/points.zip")!!).use{input->while(true){
            val entry=input.nextEntry?:break;var data=input.readBytes();if(entry.name.endsWith(".cpg")){if(cpg==null)continue;data=cpg.toByteArray()}
            if(entry.name.endsWith(".dbf")){
                data[29]=driver.toByte();val buffer=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);val header=buffer.getShort(8).toInt() and 65535;val size=buffer.getShort(10).toInt() and 65535
                for(i in ids.indices){fun set(offset:Int,value:String){val bytes=value.toByteArray(Charset.forName(encoding));data.fill(32,(header+i*size+offset),(header+i*size+offset+60));bytes.copyInto(data,header+i*size+offset)};set(1,ids[i]);set(121,text)}
            }
            target.putNextEntry(ZipEntry(entry.name));target.write(data);target.closeEntry()
        }}}
        return out.toByteArray()
    }
    @Test fun recognizedCpgWinsOverDbfDriver(){val l=Shapefile.read(zip("UTF-8",3).inputStream()).layers.single();assertEquals("Città è più",l.features.first().fields["descr"]);assertTrue(l.encodingNote.contains(".cpg"))}
    @Test fun absentCpgUsesDbfWindowsDeclaration(){val l=Shapefile.read(zip(driver=3,encoding="windows-1252").inputStream()).layers.single();assertEquals("Città è più",l.features.first().fields["descr"]);assertTrue(l.encodingNote.contains("DBF"))}
    @Test fun legacyLdid87UsesLatin1WithExplicitPreviewCaveat(){val l=Shapefile.read(zip(driver=0x57,encoding="ISO-8859-1").inputStream()).layers.single();assertEquals("ISO-8859-1",l.encoding);assertTrue(l.encodingNote.contains("verifica"));assertEquals("Città è più",l.features.first().fields["descr"])}
    @Test fun noMetadataProposesUtf8OrLegacyWithoutClaimingCertainty(){for(enc in listOf("UTF-8","windows-1252")){val l=Shapefile.read(zip(encoding=enc).inputStream()).layers.single();assertEquals("Città è più",l.features.first().fields["descr"]);assertTrue(l.encodingNote.contains("proposta"))}}
    @Test fun asciiNeedsNoOperatorDistinction(){assertTrue(Shapefile.read(zip(text="ASCII only").inputStream()).layers.single().encodingNote.contains("ASCII"))}
    @Test fun encodingSelectionRereadsActualPreviewWithoutReplacingCharacters(){val bytes=zip(encoding="windows-1252");denied{Shapefile.read(bytes.inputStream(),explicitEncoding="UTF-8")};val a=Shapefile.read(bytes.inputStream());val b=Shapefile.read(bytes.inputStream(),layerEncodings=mapOf("points" to "ISO-8859-1"));assertEquals(a.hash,b.hash);assertEquals("ISO-8859-1",b.layers.single().encoding);assertEquals("Città è più",b.layers.single().features.first().fields["descr"])}
    @Test fun repeatedZeroAndEmptyValuesNeverTurnNonnumericIdsIntoZero(){val l=Shapefile.read(zip(ids=listOf("0","0","")).inputStream()).layers.single();val r=keyReport(l,"id");assertEquals(1,r.empty);assertEquals(1,r.duplicates);assertEquals(3,r.examples.size);assertEquals("code",ImportPlanner.propose(l).fields["key"]);val original=Shapefile.read(zip().inputStream()).layers.single();assertEquals(listOf("01","abc","003"),original.features.map{it.fields["id"]})}
    @Test fun identifierChecksEntireLayerAndNeverSelectsFid(){val l=Shapefile.read(zip().inputStream()).layers.single();val extra=l.copy(features=l.features+List(10){l.features.first()});assertFalse(keyReport(extra,"id").valid);val fid=l.copy(fields=listOf("FID"),features=l.features.mapIndexed{i,f->f.copy(fields=mapOf("FID" to "$i"))});assertEquals("",ImportPlanner.propose(fid).fields["key"])}
}
