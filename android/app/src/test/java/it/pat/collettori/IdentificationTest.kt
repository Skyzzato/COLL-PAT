package it.pat.collettori

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.time.Instant

class IdentificationTest {
    private fun point(id:String,offset:Double=0.0)=JSONObject().put("id",id).put("latitude",46.06+offset).put("longitude",11.11)
    private fun fix(accuracy:Double=4.0)=JSONObject().put("latitude",46.06).put("longitude",11.11).put("accuracy_m",accuracy).put("permission","PRECISE").put("age_s",0).put("acquired_at",Instant.now().toString())
    @Test fun probableNeedsAccurateUniqueCandidate(){
        val result=GpsIdentification.identify(listOf(point("a")),fix())
        assertTrue(result.state.startsWith("Identificazione molto probabile"))
        assertEquals(listOf("a"),result.candidates)
    }
    @Test fun configuredDistanceAndAccuracyAreIndependent(){
        val points=listOf(point("a",0.0002))
        assertTrue(GpsIdentification.identify(points,fix(4.0)).candidates.isEmpty())
        assertTrue(GpsIdentification.identify(points,fix(25.0)).candidates.isEmpty())
        assertEquals(listOf("a"),GpsIdentification.identify(points,fix(4.0),FieldSettings(maxDistance=25.0)).candidates)
    }
    @Test fun ambiguityNeverAutoSelects(){
        val result=GpsIdentification.identify(listOf(point("a"),point("b",0.00002)),fix())
        assertEquals(2,result.candidates.size)
        assertTrue(result.state.startsWith("Più pozzetti"))
    }
    @Test fun rejectsMissingPoorStaleAndMockPositions(){
        val points=listOf(point("a"))
        listOf(null,fix(51.0),fix().put("accuracy_m",JSONObject.NULL),fix().put("acquired_at","2020-01-01T00:00:00Z"),fix().put("mock",true),fix().put("permission","APPROXIMATE")).forEach{
            assertTrue(GpsIdentification.identify(points,it).candidates.isEmpty())
        }
        assertEquals("Precisione non ancora disponibile",precisionText(null))
    }
    @Test fun regularDefaultsDoNotClaimCleaning(){
        val sheet=Repository.defaultSheet()
        Repository.observationKeys.forEach{assertEquals("REGOLARE",sheet.getString(it))}
        assertTrue(sheet.getBoolean("accessible"));assertTrue(sheet.getBoolean("opened"))
        assertFalse(sheet.getBoolean("cleaning"));assertFalse(sheet.getBoolean("unsafe"))
    }
    @Test fun photosNeverClaimAnUpload(){
        val photo=InspectionPhoto("p","i","m","content://local").json()
        assertTrue(photo.isNull("remoteUrl"));assertEquals("LOCAL_ONLY",photo.getString("uploadStatus"))
        assertEquals("i",photo.getString("inspectionId"));assertEquals("m",photo.getString("manholeId"))
    }
}
