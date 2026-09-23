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
    private fun window()=GpsWindow(0,20.0)
    private fun evidence(state:String="COMPATIBILE"):JSONObject {
        val w=window();listOf(3.0,5.0,7.5).forEach{w.add(sample(it),(it*1e9).toLong())}
        return w.finish(8_000_000_000).put("id","event").put("inspection_id","visit").put("manhole_id","point").put("permission","PRECISE").put("mock",false)
            .put("applied_limits",JSONObject().put("max_accuracy_m",20).put("radius_m",20))
            .put("local_evaluation",JSONObject().put("state",state).put("distance_m",if(state=="COMPATIBILE")0 else 50)).put("match_outcome","VERIFIED")
    }
    private fun inspection()=JSONObject().put("id","visit").put("manhole_id","point")
    private fun denied(block:()->Unit){try{block();fail("Operation unexpectedly accepted")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    @Test fun qualityLimitsAreInclusiveAndRejectNonFiniteValues(){
        assertNull(AcquisitionPolicy.validate(sample(1.0,accuracy=20.0),1_000_000_000,20.0))
        assertNotNull(AcquisitionPolicy.validate(sample(1.0,accuracy=20.1),1_000_000_000,20.0))
        listOf(null,Double.NaN,Double.POSITIVE_INFINITY,-1.0).forEach{assertNotNull(AcquisitionPolicy.validate(sample(1.0,accuracy=it),1_000_000_000,20.0))}
    }
    @Test fun threeSecondsExcludedThenFiveUsefulSecondsWithArithmeticAccuracy(){
        val w=window();w.add(sample(0.0,lat=0.0),0);w.add(sample(2.9,lat=0.0),3_500_000_000)
        w.add(sample(3.0,46.0,4.0),3_000_000_000);w.add(sample(5.0,46.00002,10.0),5_000_000_000);w.add(sample(7.5,46.00004,7.0),7_500_000_000)
        denied{w.finish(7_999_999_999)}
        val e=w.finish(8_000_000_000);assertEquals(46.00002,e.getDouble("latitude"),1e-8);assertEquals(7.0,e.getDouble("accuracy_m"),0.0);assertEquals(3,e.getInt("sample_count"));assertTrue(e.getDouble("dispersion_m")>2)
    }
    @Test fun duplicateLateInvalidAndMockCallbacksCannotCreateSamples(){
        val w=window();w.add(sample(2.0),4_000_000_000);w.add(sample(4.0),8_000_000_000);w.add(sample(9.0),8_000_000_000);w.add(sample(4.0,lat=100.0),4_000_000_000)
        listOf(3.0,5.0,7.5).forEach{w.add(sample(it),(it*1e9).toLong());w.add(sample(it),(it*1e9).toLong())}
        w.add(sample(6.0).copy(mock=true),6_000_000_000)
        assertEquals(3,w.finish(8_000_000_000).getInt("sample_count"))
    }
    @Test fun poorerFixIsIncludedWithoutAbortingOrImprovingMean(){val w=window();listOf(3.0,5.0,7.5).forEach{w.add(sample(it,accuracy=if(it==5.0)100.0 else 10.0),(it*1e9).toLong())};assertNull(w.failure);assertEquals(40.0,w.finish(8_000_000_000).getDouble("accuracy_m"),0.0)}
    @Test fun tooShortSpanAndOldLastSampleFail(){val w=window();listOf(3.0,3.1,3.2).forEach{w.add(sample(it),(it*1e9).toLong())};denied{w.finish(8_000_000_000)}}
    @Test fun verifiedPositionCannotBeReplacedByAnException(){val e=evidence();validateNewEvidence(e,inspection());assertTrue(usableInspectionGps(e));val mismatch=evidence("NON_COMPATIBILE").put("match_outcome","EXCEPTION").put("exception_reason","Motivo");denied{validateNewEvidence(mismatch,inspection())};assertFalse(usableInspectionGps(mismatch))}
    @Test fun rejectsForgedMeanAndWrongInspection(){val e=evidence().put("accuracy_m",1);denied{validateNewEvidence(e,inspection())};denied{validateNewEvidence(evidence().put("inspection_id","other"),inspection())}}
    @Test fun feedbackRequiresMatchingReceiptAndRetainsConflict(){val v=Visit("visit","owner","point","pack","{\"local_edit\":7}",sync="IN_ATTESA");val f=SaveFeedback("visit",7,false,1000);assertFalse(f.serverConfirmed(v));assertTrue(f.serverConfirmed(v.copy(sync="RICEVUTO_SERVER",receipt="{}")));assertFalse(f.mayReturn(v.copy(sync="CONFLICT")))}

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
