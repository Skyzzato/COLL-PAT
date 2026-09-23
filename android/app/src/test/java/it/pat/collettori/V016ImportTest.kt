package it.pat.collettori

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class V016ImportTest {
    private fun points()=javaClass.classLoader!!.getResourceAsStream("gis/points.zip")!!.use{Shapefile.read(it)}
    private fun automatic(a:ShapeArchive,old:List<CatalogItem> = emptyList()):ImportPlan {
        val mapping=a.layers.map{ImportPlanner.propose(it)}
        return ImportPlanner.plan(a,mapping,"automatic-test","",ImportPlanner.automaticMode(a,mapping),true,false,5.0,old,"test")
    }
    @Test fun zipWithoutCollectorOrDisplayCodeCreatesStableCollectorAndCodesFromKeys(){
        val a=points().let{archive->archive.copy(layers=archive.layers.map{layer->
            layer.copy(fields=listOf("ID_POZZ"),features=layer.features.map{it.copy(fields=mapOf("ID_POZZ" to it.fields.getValue("id")))})
        })}
        val first=automatic(a);assertEquals(1,first.items.count{it.kind=="collector"});assertEquals(3,first.items.count{it.kind=="point"});assertEquals(0,first.items.count{it.kind=="segment"})
        assertEquals(a.layers.single().features.map{it.fields.getValue("ID_POZZ")}.toSet(),first.items.filter{it.kind=="point"}.map{JSONObject(it.body).getString("code")}.toSet())
        val repeated=automatic(a,first.items);assertEquals(0,repeated.inserted);assertEquals(0,repeated.updated)
        assertEquals(first.items.map{it.id}.toSet(),repeated.items.map{it.id}.toSet())
    }
    @Test fun archiveWithLinesChoosesRealGeometryAndOrderedPointsChooseSchematicGeometry(){
        val ordered=points();assertEquals("ORDERED",ImportPlanner.automaticMode(ordered,ordered.layers.map{ImportPlanner.propose(it)}))
        assertEquals(2,automatic(ordered).items.count{it.kind=="segment"})
        val lines=javaClass.classLoader!!.getResourceAsStream("gis/points-lines.zip")!!.use{Shapefile.read(it)}
        assertEquals("LINES",ImportPlanner.automaticMode(lines,lines.layers.map{ImportPlanner.propose(it)}))
        assertTrue(automatic(lines).items.filter{it.kind=="segment"}.all{!JSONObject(it.body).getBoolean("schematic")})
    }
    @Test(expected=IllegalArgumentException::class) fun automaticImportNeverReactivatesArchivedCollector(){
        val a=points().let{archive->archive.copy(layers=archive.layers.map{layer->layer.copy(fields=layer.fields.filter{it!="collector"},features=layer.features.map{it.copy(fields=it.fields-"collector")})})}
        val initial=automatic(a)
        automatic(a,initial.items.map{if(it.kind=="collector")it.copy(body=JSONObject(it.body).put("archived",true).toString())else it})
    }
}
