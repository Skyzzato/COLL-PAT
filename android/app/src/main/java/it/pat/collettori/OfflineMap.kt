package it.pat.collettori

import android.graphics.RectF
import androidx.compose.runtime.*
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
    fun source(id:String,data:JSONObject){sources.put(id,JSONObject().put("type","geojson").put("data",data))}
    source("base",if(pack.isNull("basemap"))fc(emptyList())else pack.getJSONObject("basemap"))
    source("network",fc(pack.getJSONArray("segments").objects().map{feature(it.getJSONObject("geometry"),JSONObject().put("code",it.getString("collector")))}))
    source("points",fc(points.map{feature(pointGeometry(it.getDouble("latitude"),it.getDouble("longitude")),JSONObject().put("id",it.getString("id")).put("code",it.getString("code")))}))
    source("selected",fc(points.filter{it.getString("id")==selected}.map{feature(pointGeometry(it.getDouble("latitude"),it.getDouble("longitude")))}))
    val latitude=position?.numberOrNull("latitude");val longitude=position?.numberOrNull("longitude")
    val hasPosition=latitude!=null && longitude!=null
    source("device",fc(if(hasPosition)listOf(feature(pointGeometry(latitude!!,longitude!!)))else emptyList()))
    val accuracy=position?.numberOrNull("accuracy_m")
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
        {"id":"selected-ring","type":"circle","source":"selected","paint":{"circle-radius":12,"circle-opacity":0,"circle-stroke-color":"#bd7117","circle-stroke-width":4}},
        {"id":"device-point","type":"circle","source":"device","paint":{"circle-radius":7,"circle-color":"#2766b0","circle-stroke-color":"#ffffff","circle-stroke-width":2}}
    ]""")
    return JSONObject().put("version",8).put("glyphs","asset://glyphs/{fontstack}/{range}.pbf").put("sources",sources).put("layers",layers).toString()
}

@Composable
fun OfflineMap(pack:JSONObject,points:List<JSONObject>,selected:String?,position:JSONObject?,center:Pair<LatLng,Int>?,modifier:Modifier,onSelect:(String)->Unit,onError:(String)->Unit){
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val select by rememberUpdatedState(onSelect)
    val error by rememberUpdatedState(onError)
    val view=remember{MapLibre.getInstance(context);MapView(context).apply{onCreate(null)}}
    var map by remember{mutableStateOf<MapLibreMap?>(null)}
    val style=remember(pack,points,selected,position){localStyle(pack,points,selected,position)}
    DisposableEffect(view,owner){
        val lifecycle=LifecycleEventObserver{_,event->when(event){Lifecycle.Event.ON_START->view.onStart();Lifecycle.Event.ON_RESUME->view.onResume();Lifecycle.Event.ON_PAUSE->view.onPause();Lifecycle.Event.ON_STOP->view.onStop();else->Unit}}
        owner.lifecycle.addObserver(lifecycle)
        view.onStart();view.onResume()
        view.addOnDidFailLoadingMapListener{error("Mappa non caricata: $it. Dati e schede restano disponibili.")}
        view.getMapAsync{m->map=m;m.uiSettings.isAttributionEnabled=false;m.uiSettings.isLogoEnabled=false
            m.addOnMapClickListener{ll->val px=m.projection.toScreenLocation(ll);val hits=m.queryRenderedFeatures(RectF(px.x-20,px.y-20,px.x+20,px.y+20),"manholes")
                if(hits.size==1)select(hits.first().getStringProperty("id"))else if(hits.size>1)error("Più pozzetti in questo punto: aumentare lo zoom o selezionare dall'elenco.");true}
            val first=pack.getJSONArray("points").objects().firstOrNull();if(first!=null)m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(first.getDouble("latitude"),first.getDouble("longitude")),15.0))}
        onDispose{owner.lifecycle.removeObserver(lifecycle);view.onPause();view.onStop();view.onDestroy()}
    }
    LaunchedEffect(map,style){map?.setStyle(Style.Builder().fromJson(style))}
    LaunchedEffect(map,center){center?.let{map?.animateCamera(CameraUpdateFactory.newLatLngZoom(it.first,17.0))}}
    AndroidView(factory={view},modifier=modifier)
}
