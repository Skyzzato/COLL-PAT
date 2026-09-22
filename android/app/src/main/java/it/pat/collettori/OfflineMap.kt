package it.pat.collettori

import android.graphics.RectF
import android.graphics.Bitmap
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


@Composable
fun OfflineMap(pack:JSONObject,points:List<JSONObject>,selected:String?,position:JSONObject?,center:Pair<LatLng,Int>?,modifier:Modifier,onSelect:(String)->Unit,onError:(String)->Unit,bounds:List<LatLng> = emptyList(),boundsTick:Int=0,settings:FieldSettings=FieldSettings()){
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
    val style=remember(pack,points,selected,position,settings){localStyle(pack,points,selected,position,settings)}
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
    LaunchedEffect(map){map?.let{m->
        val builder=Style.Builder().fromJson(latestStyle)
        ManholeSymbol.entries.forEach{symbol->
            val bitmap=Bitmap.createBitmap(symbol.sdfPixels(),ManholeSymbol.PIXELS,ManholeSymbol.PIXELS,Bitmap.Config.ARGB_8888)
            bitmap.density=android.util.DisplayMetrics.DENSITY_DEFAULT
            builder.withImage(symbol.imageId,bitmap,true)
        }
        m.setStyle(builder){styleReady=true}
    }}
    LaunchedEffect(map,style,styleReady){if(styleReady){
        val sources=JSONObject(style).getJSONObject("sources")
        sources.keys().forEach{id->val source=sources.getJSONObject(id);if(source.optString("type")=="geojson"){
            val json=source.getJSONObject("data").toString()
            if(sourceCache[id]!=json){map?.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>(id)?.setGeoJson(json);sourceCache[id]=json}
        }}
    }}
    LaunchedEffect(map,settings,styleReady){if(styleReady){
        val styleMap=map?.style
        listOf("manholes","labels","candidate-ring","selected-ring","asphalt-mark").forEach{styleMap?.getLayer(it)?.minZoom=settings.minZoomPozzetti}
        styleMap?.getLayerAs<org.maplibre.android.style.layers.SymbolLayer>("manholes")?.setProperties(
            org.maplibre.android.style.layers.PropertyFactory.iconImage(ManholeSymbol.fromId(settings.symbol).imageId),
            org.maplibre.android.style.layers.PropertyFactory.iconSize((settings.iconSize/ManholeSymbol.RADIUS).toFloat()))
        styleMap?.getLayerAs<org.maplibre.android.style.layers.CircleLayer>("candidate-ring")?.setProperties(org.maplibre.android.style.layers.PropertyFactory.circleRadius(settings.iconSize+8f))
        styleMap?.getLayerAs<org.maplibre.android.style.layers.CircleLayer>("selected-ring")?.setProperties(org.maplibre.android.style.layers.PropertyFactory.circleRadius(settings.iconSize+5f))
        styleMap?.getLayerAs<org.maplibre.android.style.layers.SymbolLayer>("asphalt-mark")?.setProperties(org.maplibre.android.style.layers.PropertyFactory.visibility(if(settings.asphalt)"visible" else "none"),org.maplibre.android.style.layers.PropertyFactory.textSize(settings.iconSize*2))
    }}
    LaunchedEffect(map,center){center?.let{map?.animateCamera(CameraUpdateFactory.newLatLngZoom(it.first,if(it.second<0)maxOf(14.5,settings.minZoomPozzetti.toDouble()) else maxOf(17.0,settings.minZoomPozzetti.toDouble())))}}
    LaunchedEffect(map,points){if(!positioned&&map!=null&&points.isNotEmpty()){
        val all=points.map{LatLng(it.getDouble("latitude"),it.getDouble("longitude"))}
        if(all.size>1)map?.moveCamera(CameraUpdateFactory.newLatLngBounds(org.maplibre.android.geometry.LatLngBounds.Builder().includes(all).build(),56))else map?.moveCamera(CameraUpdateFactory.newLatLngZoom(all.first(),16.0))
        positioned=true
    }}
    LaunchedEffect(map,bounds,boundsTick){if(bounds.size>1)map?.animateCamera(CameraUpdateFactory.newLatLngBounds(org.maplibre.android.geometry.LatLngBounds.Builder().includes(bounds).build(),64))}
    AndroidView(factory={view},modifier=modifier)
}
