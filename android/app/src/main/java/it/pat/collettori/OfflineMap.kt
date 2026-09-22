package it.pat.collettori

import android.graphics.RectF
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.camera.CameraUpdateFactory
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.*

private fun fc(features:List<JSONObject>)=JSONObject().put("type","FeatureCollection").put("features",JSONArray(features))
private fun feature(geometry:JSONObject,properties:JSONObject=JSONObject())=JSONObject().put("type","Feature").put("geometry",geometry).put("properties",properties)
private fun pointGeometry(lat:Double,lon:Double)=JSONObject().put("type","Point").put("coordinates",JSONArray(listOf(lon,lat)))

/** Complete local style. All network and reference sources come from verified Room packages.
 * Vector layers, not per-point Android views. Glyph assets ship in the APK. */
fun localStyle(pack:JSONObject,points:List<JSONObject>,selected:String?,position:JSONObject?):String{
    val sources=JSONObject()
    if(pack.optBoolean("osm"))sources.put("osm",JSONObject().put("type","raster").put("tiles",JSONArray(listOf("https://tile.openstreetmap.org/{z}/{x}/{y}.png"))).put("tileSize",256).put("maxzoom",19).put("attribution","<a href=\"https://www.openstreetmap.org/copyright\">© OpenStreetMap contributors</a>"))
    fun source(id:String,data:JSONObject){sources.put(id,JSONObject().put("type","geojson").put("data",data))}
    source("base",if(pack.isNull("basemap"))fc(emptyList())else pack.getJSONObject("basemap"))
    source("network",fc(pack.getJSONArray("segments").objects().map{feature(it.getJSONObject("geometry"),JSONObject().put("code",it.optString("code",it.optString("collector",""))))}))
    source("points",fc(points.map{feature(pointGeometry(it.getDouble("latitude"),it.getDouble("longitude")),JSONObject().put("id",it.getString("id")).put("code",it.getString("code")))}))
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
        {"id":"pipes","type":"line","source":"network","paint":{"line-color":"#176d73","line-width":3}},
        {"id":"collector-labels","type":"symbol","source":"network","minzoom":13,"layout":{"symbol-placement":"line","text-field":"{code}","text-font":["Noto Sans Regular"],"text-size":11,"text-offset":[0,-1]},"paint":{"text-color":"#176d73","text-halo-color":"#ffffff","text-halo-width":2}},
        {"id":"accuracy-fill","type":"fill","source":"accuracy","paint":{"fill-color":"#3182ce","fill-opacity":0.16}},
        {"id":"accuracy-edge","type":"line","source":"accuracy","paint":{"line-color":"#3182ce","line-width":1}},
        {"id":"manholes","type":"circle","source":"points","paint":{"circle-radius":6,"circle-color":"#ffffff","circle-stroke-color":"#176d73","circle-stroke-width":2}},
        {"id":"labels","type":"symbol","source":"points","minzoom":14,"layout":{"text-field":"{code}","text-font":["Noto Sans Regular"],"text-size":12,"text-offset":[0,1.2]},"paint":{"text-color":"#143c43","text-halo-color":"#ffffff","text-halo-width":2}},
        {"id":"candidate-ring","type":"circle","source":"candidates","paint":{"circle-radius":16,"circle-opacity":0,"circle-stroke-color":"#2766b0","circle-stroke-width":2}},
        {"id":"selected-ring","type":"circle","source":"selected","paint":{"circle-radius":12,"circle-opacity":0,"circle-stroke-color":"#bd7117","circle-stroke-width":4}},
        {"id":"device-point","type":"circle","source":"device","paint":{"circle-radius":7,"circle-color":"#2766b0","circle-stroke-color":"#ffffff","circle-stroke-width":2}}
    ]""")
    if(pack.optBoolean("osm")) {
        val reordered=JSONArray().put(layers.getJSONObject(0)).put(JSONObject().put("id","osm-tiles").put("type","raster").put("source","osm"))
        for(i in 1 until layers.length())reordered.put(layers.getJSONObject(i))
        return JSONObject().put("version",8).put("glyphs","asset://glyphs/{fontstack}/{range}.pbf").put("sources",sources).put("layers",reordered).toString()
    }
    return JSONObject().put("version",8).put("glyphs","asset://glyphs/{fontstack}/{range}.pbf").put("sources",sources).put("layers",layers).toString()
}

@Composable
fun OfflineMap(pack:JSONObject,points:List<JSONObject>,selected:String?,position:JSONObject?,center:Pair<LatLng,Int>?,modifier:Modifier,onSelect:(String)->Unit,onError:(String)->Unit,bounds:List<LatLng> = emptyList(),boundsTick:Int=0){
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val select by rememberUpdatedState(onSelect)
    val error by rememberUpdatedState(onError)
    val view=remember{MapLibre.getInstance(context);MapView(context).apply{onCreate(null)}}
    var map by remember{mutableStateOf<MapLibreMap?>(null)}
    var cameraLat by rememberSaveable{mutableStateOf<Double?>(null)}
    var cameraLon by rememberSaveable{mutableStateOf<Double?>(null)}
    var cameraZoom by rememberSaveable{mutableStateOf<Double?>(null)}
    var positioned by remember{mutableStateOf(false)}
    val style=remember(pack,points,selected,position){localStyle(pack,points,selected,position)}
    val latestStyle by rememberUpdatedState(style)
    var styleReady by remember{mutableStateOf(false)}
    val sourceCache=remember{mutableMapOf<String,String>()}
    DisposableEffect(view,owner){
        val lifecycle=LifecycleEventObserver{_,event->when(event){Lifecycle.Event.ON_START->view.onStart();Lifecycle.Event.ON_RESUME->view.onResume();Lifecycle.Event.ON_PAUSE->view.onPause();Lifecycle.Event.ON_STOP->view.onStop();else->Unit}}
        owner.lifecycle.addObserver(lifecycle)
        // addObserver catches up with the owner's current state. Do not start twice.
        view.addOnDidFailLoadingMapListener{error("Mappa non caricata: $it. Dati e schede restano disponibili.")}
        view.getMapAsync{m->map=m;m.uiSettings.isAttributionEnabled=pack.optBoolean("osm");m.uiSettings.isLogoEnabled=false
            if(cameraLat!=null&&cameraLon!=null&&cameraZoom!=null){m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(cameraLat!!,cameraLon!!),cameraZoom!!));positioned=true}
            m.addOnCameraIdleListener{if(positioned){cameraLat=m.cameraPosition.target?.latitude;cameraLon=m.cameraPosition.target?.longitude;cameraZoom=m.cameraPosition.zoom}}
            m.addOnMapClickListener{ll->val px=m.projection.toScreenLocation(ll);val hits=m.queryRenderedFeatures(RectF(px.x-20,px.y-20,px.x+20,px.y+20),"manholes")
                if(hits.size==1)select(hits.first().getStringProperty("id"))else if(hits.size>1)error("Più pozzetti in questo punto: aumentare lo zoom o selezionare dall'elenco.");true}
        }
        onDispose{owner.lifecycle.removeObserver(lifecycle);view.onPause();view.onStop();view.onDestroy()}
    }
    LaunchedEffect(map){map?.setStyle(Style.Builder().fromJson(latestStyle)){styleReady=true}}
    LaunchedEffect(map,style,styleReady){if(styleReady){
        val sources=JSONObject(style).getJSONObject("sources")
        sources.keys().forEach{id->val source=sources.getJSONObject(id);if(source.optString("type")=="geojson"){
            val json=source.getJSONObject("data").toString()
            if(sourceCache[id]!=json){map?.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>(id)?.setGeoJson(json);sourceCache[id]=json}
        }}
    }}
    LaunchedEffect(map,center){center?.let{map?.animateCamera(CameraUpdateFactory.newLatLngZoom(it.first,if(it.second<0)14.5 else 17.0))}}
    LaunchedEffect(map,points){if(!positioned&&map!=null&&points.isNotEmpty()){
        val all=points.map{LatLng(it.getDouble("latitude"),it.getDouble("longitude"))}
        if(all.size>1)map?.moveCamera(CameraUpdateFactory.newLatLngBounds(org.maplibre.android.geometry.LatLngBounds.Builder().includes(all).build(),56))else map?.moveCamera(CameraUpdateFactory.newLatLngZoom(all.first(),16.0))
        positioned=true
    }}
    LaunchedEffect(map,bounds,boundsTick){if(bounds.size>1)map?.animateCamera(CameraUpdateFactory.newLatLngBounds(org.maplibre.android.geometry.LatLngBounds.Builder().includes(bounds).build(),64))}
    AndroidView(factory={view},modifier=modifier)
}
