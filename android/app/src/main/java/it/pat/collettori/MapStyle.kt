package it.pat.collettori

import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.*

private fun fc(features:List<JSONObject>)=JSONObject().put("type","FeatureCollection").put("features",JSONArray(features))
private fun feature(geometry:JSONObject,properties:JSONObject=JSONObject())=JSONObject().put("type","Feature").put("geometry",geometry).put("properties",properties)
private fun pointGeometry(lat:Double,lon:Double)=JSONObject().put("type","Point").put("coordinates",JSONArray(listOf(lon,lat)))

/** Complete local style. All network and reference sources come from verified Room packages.
 * Vector layers, not per-point Android views. Glyph assets ship in the APK. */
fun localStyle(pack:JSONObject,points:List<JSONObject>,selected:String?,position:JSONObject?,settings:FieldSettings=FieldSettings()):String{
    val sources=JSONObject()
    if(pack.optBoolean("osm"))sources.put("osm",JSONObject().put("type","raster").put("tiles",JSONArray(listOf("https://tile.openstreetmap.org/{z}/{x}/{y}.png"))).put("tileSize",256).put("maxzoom",19).put("attribution","<a href=\"https://www.openstreetmap.org/copyright\">© OpenStreetMap contributors</a>"))
    fun source(id:String,data:JSONObject){sources.put(id,JSONObject().put("type","geojson").put("data",data))}
    source("base",if(pack.isNull("basemap"))fc(emptyList())else pack.getJSONObject("basemap"))
    val collectors=pack.optJSONArray("collectors")?.objects().orEmpty();val byId=collectors.associateBy{it.getString("id")}
    source("network",fc(pack.getJSONArray("segments").objects().map{segment->val c=segment.memberships().mapNotNull{byId[it]}.sortedWith(compareBy({it.optString("code")},{it.getString("id")})).firstOrNull()
        feature(segment.getJSONObject("geometry"),JSONObject().put("code",c?.optString("code").orEmpty()).put("collector_id",c?.optString("id").orEmpty()).put("display_color",resolvedCollectorColor(c,settings)).put("display_width",resolvedCollectorWidth(c,settings)))
    }))
    source("collector-anchors",fc(collectorLabelFeatures(pack)))
    source("points",fc(points.map{feature(pointGeometry(it.getDouble("latitude"),it.getDouble("longitude")),JSONObject().put("id",it.getString("id")).put("code",it.getString("code")).put("status_color",it.optString("status_color",InspectionState.DUE.color)).put("under_asphalt",it.optBoolean("under_asphalt")).put("symbol",resolvedSymbol(it,collectors,settings,pack.optString("context_collector"))))}))
    source("selected",fc(points.filter{it.getString("id")==selected}.map{feature(pointGeometry(it.getDouble("latitude"),it.getDouble("longitude")))}))
    val candidateIds=position?.optJSONArray("candidate_ids")
    source("candidates",fc(points.filter{p->candidateIds!=null && (0 until candidateIds.length()).any{candidateIds.getString(it)==p.getString("id")}}.map{feature(pointGeometry(it.getDouble("latitude"),it.getDouble("longitude")))}))
    val latitude=position?.numberOrNull("latitude");val longitude=position?.numberOrNull("longitude")
    val hasPosition=validCoordinates(latitude,longitude)
    source("device",fc(if(hasPosition)listOf(feature(pointGeometry(latitude!!,longitude!!)))else emptyList()))
    val accuracy=position?.numberOrNull("accuracy_m")?.takeIf{it in 0.0..20_000_000.0}
    val circle=if(hasPosition && accuracy!=null){
        val ring=(0..64).map{index->val bearing=index*2*PI/64;val delta=accuracy/6371008.8;val lat=Math.toRadians(latitude!!);val lon=Math.toRadians(longitude!!)
            val y=asin(sin(lat)*cos(delta)+cos(lat)*sin(delta)*cos(bearing));val x=lon+atan2(sin(bearing)*sin(delta)*cos(lat),cos(delta)-sin(lat)*sin(y));listOf(Math.toDegrees(x),Math.toDegrees(y))}
        listOf(feature(JSONObject().put("type","Polygon").put("coordinates",JSONArray(listOf(ring)))))
    }else emptyList()
    source("accuracy",fc(circle))
    val layers=JSONArray("""[
        {"id":"background","type":"background","paint":{"background-color":"#eef2e9"}},
        {"id":"land","type":"fill","source":"base","filter":["==","${'$'}type","Polygon"],"paint":{"fill-color":"#dbe5d3","fill-opacity":0.8}},
        {"id":"roads","type":"line","source":"base","filter":["==","${'$'}type","LineString"],"paint":{"line-color":"#ffffff","line-width":5}},
        {"id":"base-labels","type":"symbol","source":"base","layout":{"text-field":"{name}","text-font":["Noto Sans Regular"],"text-size":11},"paint":{"text-color":"#63716c","text-halo-color":"#ffffff","text-halo-width":1}},
        {"id":"pipes","type":"line","source":"network","paint":{"line-color":["get","display_color"],"line-width":["get","display_width"]}},
        {"id":"collector-labels","type":"symbol","source":"collector-anchors","minzoom":10,"layout":{"symbol-placement":"point","text-rotate":["get","angle"],"text-rotation-alignment":"map","text-keep-upright":true,"text-padding":8,"text-field":"{code}","text-font":["Noto Sans Regular"],"text-size":11,"text-offset":[0,-1]},"paint":{"text-color":"#176d73","text-halo-color":"#ffffff","text-halo-width":2}},
        {"id":"accuracy-fill","type":"fill","source":"accuracy","paint":{"fill-color":"#3182ce","fill-opacity":0.16}},
        {"id":"accuracy-edge","type":"line","source":"accuracy","paint":{"line-color":"#3182ce","line-width":1}},
        {"id":"manholes","type":"symbol","source":"points","layout":{"icon-allow-overlap":true,"icon-ignore-placement":false},"paint":{"icon-color":["get","status_color"],"icon-halo-color":"#ffffff","icon-halo-width":1}},
        {"id":"labels","type":"symbol","source":"points","minzoom":14,"layout":{"text-field":"{code}","text-font":["Noto Sans Regular"],"text-size":12,"text-offset":[0,1.2]},"paint":{"text-color":"#143c43","text-halo-color":"#ffffff","text-halo-width":2}},
        {"id":"candidate-ring","type":"circle","source":"candidates","paint":{"circle-radius":16,"circle-opacity":0,"circle-stroke-color":"#2766b0","circle-stroke-width":2}},
        {"id":"selected-ring","type":"circle","source":"selected","paint":{"circle-radius":12,"circle-opacity":0,"circle-stroke-color":"#bd7117","circle-stroke-width":4}},
        {"id":"device-point","type":"circle","source":"device","paint":{"circle-radius":7,"circle-color":"#2766b0","circle-stroke-color":"#ffffff","circle-stroke-width":2}}
    ]""")
    layers.objects().filter{it.optString("id") in listOf("manholes","labels","candidate-ring","selected-ring")}.forEach{it.put("minzoom",settings.minZoomPozzetti.toDouble())}
    layers.objects().first{it.getString("id")=="manholes"}.getJSONObject("layout")
        .put("icon-image",JSONArray(listOf("get","symbol"))).put("icon-size",settings.iconSize/ManholeSymbol.RADIUS)
    layers.objects().first{it.getString("id")=="candidate-ring"}.getJSONObject("paint").put("circle-radius",settings.iconSize+8.0)
    layers.objects().first{it.getString("id")=="selected-ring"}.getJSONObject("paint").put("circle-radius",settings.iconSize+5.0)
    layers.put(JSONObject().put("id","asphalt-mark").put("type","symbol").put("source","points").put("minzoom",settings.minZoomPozzetti.toDouble()).put("filter",JSONArray("[\"==\",\"under_asphalt\",true]"))
        .put("layout",JSONObject().put("text-field","×").put("text-font",JSONArray(listOf("Noto Sans Regular"))).put("text-size",settings.iconSize.toDouble()*2).put("text-allow-overlap",true).put("visibility",if(settings.asphalt)"visible" else "none"))
        .put("paint",JSONObject().put("text-color","#202020").put("text-halo-color","#ffffff").put("text-halo-width",.5)))
    val collectorLayer=layers.objects().first{it.getString("id")=="collector-labels"};val index=(0 until layers.length()).first{layers.getJSONObject(it).getString("id")=="collector-labels"};layers.remove(index);layers.put(collectorLayer)
    if(pack.optBoolean("osm")) {
        val reordered=JSONArray().put(layers.getJSONObject(0)).put(JSONObject().put("id","osm-tiles").put("type","raster").put("source","osm"))
        for(i in 1 until layers.length())reordered.put(layers.getJSONObject(i))
        return JSONObject().put("version",8).put("glyphs","asset://glyphs/{fontstack}/{range}.pbf").put("sources",sources).put("layers",reordered).toString()
    }
    return JSONObject().put("version",8).put("glyphs","asset://glyphs/{fontstack}/{range}.pbf").put("sources",sources).put("layers",layers).toString()
}

