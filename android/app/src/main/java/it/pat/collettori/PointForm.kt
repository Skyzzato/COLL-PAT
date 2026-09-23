package it.pat.collettori

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng

@Composable fun PointForm(initial:JSONObject,collector:JSONObject,points:List<JSONObject>,segments:List<JSONObject>,dismiss:()->Unit,save:(JSONObject)->Unit){
    var code by rememberSaveable{mutableStateOf(initial.optString("code"))};var lat by rememberSaveable{mutableStateOf(initial.numberOrNull("latitude")?.toString().orEmpty())};var lon by rememberSaveable{mutableStateOf(initial.numberOrNull("longitude")?.toString().orEmpty())}
    var sequence by rememberSaveable{mutableStateOf(initial.numberOrNull("sequence")?.toString().orEmpty())};var branch by rememberSaveable{mutableStateOf(initial.optString("branch"))};var end by rememberSaveable{mutableStateOf(initial.optBoolean("topology_end"))}
    var links by rememberSaveable{mutableStateOf(initial.optJSONArray("next_ids")?.strings().orEmpty())};var error by rememberSaveable{mutableStateOf("")};var preview by remember{mutableStateOf<JSONObject?>(null)}
    val context=androidx.compose.ui.platform.LocalContext.current;val scope=rememberCoroutineScope()
    val formScroll=rememberScrollState()
    LaunchedEffect(preview){if(preview!=null){kotlinx.coroutines.delay(100);formScroll.animateScrollTo(formScroll.maxValue)}}
    var picking by rememberSaveable{mutableStateOf(false)};var mapLat by rememberSaveable{mutableStateOf(46.067)};var mapLon by rememberSaveable{mutableStateOf(11.123)}
    var locating by remember{mutableStateOf(false)};var currentFix by remember{mutableStateOf<JSONObject?>(null)}
    fun currentLocation(){if(locating)return;locating=true;scope.launch{try{
        val request=JSONObject().put("id","point-position").put("manhole_id",initial.getString("id")).put("user_id","").put("device_id","").put("dataset_id","")
        val rule=Rule.parse(JSONObject(context.assets.open("gps-rule.json").bufferedReader().use{it.readText()}))
        val result=LocationCapture(context).collect(request,rule)
        check(result.isNull("error")&&validCoordinates(result.numberOrNull("latitude"),result.numberOrNull("longitude"))&&acceptableMeasure(result.numberOrNull("age_s"),10.0)){result.optString("error","Posizione non recente o non disponibile")}
        currentFix=result
    }catch(e:Exception){error=friendlyError(e)}finally{locating=false}}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){granted->if(granted[android.Manifest.permission.ACCESS_FINE_LOCATION]==true)currentLocation()else error="Permesso posizione negato. Puoi inserire le coordinate o puntare sulla mappa."}
    val official=segments.filter{!it.optBoolean("schematic")&&it.optString("from_id")==initial.getString("id")}
    fun value()=manualPoint(initial,initial.getString("id"),collector.getString("id"),code,lat,lon,sequence,branch,links,end)
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()){Column(Modifier.padding(20.dp).verticalScroll(formScroll)){
        Text("Pozzetto del collettore",style=MaterialTheme.typography.headlineSmall);Text(collector.optString("description").ifBlank{"Nuovo collettore"})
        Field("Codice / numero pozzetto",code){code=it;preview=null}
        OutlinedTextField(lat,{lat=it;preview=null},label={Text("Latitudine")},placeholder={Text("46.067890")},supportingText={Text("WGS84 · gradi decimali da −90 a 90")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(lon,{lon=it;preview=null},label={Text("Longitudine")},placeholder={Text("11.123456")},supportingText={Text("WGS84 · gradi decimali da −180 a 180")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedButton(onClick={mapLat=lat.replace(',','.').toDoubleOrNull()?.takeIf{it in -90.0..90.0}?:points.firstOrNull()?.optDouble("latitude")?:46.067;mapLon=lon.replace(',','.').toDoubleOrNull()?.takeIf{it in -180.0..180.0}?:points.firstOrNull()?.optDouble("longitude")?:11.123;picking=true}){Text("Punta su mappa")}
        OutlinedButton(enabled=!locating,onClick={if(context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED)currentLocation()else permission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION))}){Text(if(locating)"Acquisizione posizione…" else "Usa coordinate attuali")}
        Field("Ordine nel ramo (facoltativo)",sequence){sequence=it;preview=null};Field("Ramo (facoltativo)",branch){branch=it;preview=null}
        if(official.isNotEmpty())Text("Collegamenti da geometrie importate: "+nextPointLabel(initial,points,official)+". La modifica delle coordinate non riscrive il tracciato ufficiale.")
        else {
            Text("Collegamenti in uscita (anche più di uno)")
            points.filter{it.getString("id")!=initial.getString("id")}.forEach{p->val id=p.getString("id");Row{Checkbox(id in links,{checked->links=if(checked)links+id else links-id;end=false;preview=null});Text("#"+p.getString("code"),Modifier.padding(top=12.dp))}}
            Row{Checkbox(end,{end=it;if(it)links=emptyList();preview=null});Text("Fine collettore / ramo accertata",Modifier.padding(top=12.dp))}
        }
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        OutlinedButton(onClick={try{preview=value();error=""}catch(e:Exception){error=friendlyError(e)}}){Text("Verifica posizione sulla mappa")}
        preview?.let{p->
            val data=JSONObject().put("osm",true).put("basemap",JSONObject.NULL).put("collectors",JSONArray(listOf(collector))).put("points",JSONArray(listOf(p))).put("segments",JSONArray())
            OfflineMap(data,listOf(p),p.getString("id"),null,null,Modifier.fillMaxWidth().height(240.dp),{},{error=it},listOf(LatLng(p.getDouble("latitude"),p.getDouble("longitude"))),1)
            Text("Latitudine ${p.getDouble("latitude")} · Longitudine ${p.getDouble("longitude")}")
        }
        Button(enabled=preview!=null,onClick={try{save(value())}catch(e:Exception){error=friendlyError(e)}}){Text("Conferma pozzetto")}
        TextButton(onClick=dismiss){Text("Indietro")}
    }}}
    if(picking)Dialog(onDismissRequest={picking=false},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){Column(Modifier.padding(12.dp)){
        Text("Punta su mappa",style=MaterialTheme.typography.headlineSmall);Text("Tocca la mappa per spostare la bandierina. Lo sfondo disponibile resta utilizzabile offline.")
        val flag=JSONObject().put("id","position-flag").put("code","⚑").put("latitude",mapLat).put("longitude",mapLon).put("collectors",JSONArray(listOf(collector.getString("id"))))
        val data=JSONObject().put("osm",true).put("basemap",JSONObject.NULL).put("collectors",JSONArray(listOf(collector))).put("points",JSONArray(listOf(flag))).put("segments",JSONArray())
        OfflineMap(data,listOf(flag),"position-flag",null,null,Modifier.weight(1f).fillMaxWidth(),{},{error=it},settings=FieldSettings(symbol="PIN",minZoomPozzetti=8f),onCoordinate={ll->mapLat=ll.latitude;mapLon=ll.longitude})
        Text("⚑ Latitudine %.6f · Longitudine %.6f".format(mapLat,mapLon))
        if(error.isNotBlank())Text(error,style=MaterialTheme.typography.bodySmall)
        Button(onClick={lat=mapLat.toString();lon=mapLon.toString();preview=null;picking=false}){Text("Conferma posizione")}
        TextButton(onClick={picking=false}){Text("Annulla")}
    }}}
    currentFix?.let{fix->AlertDialog(onDismissRequest={currentFix=null},title={Text("Coordinate attuali")},text={Column{Text("Latitudine ${fix.getDouble("latitude")} · Longitudine ${fix.getDouble("longitude")}");Text("Accuratezza ±${fix.numberOrNull("accuracy_m")?:"non disponibile"} m · ${fix.optString("acquired_at")}");Text("Questa posizione aggiorna il modulo del pozzetto; non è una rilevazione dell’ispezione.")}},confirmButton={TextButton(onClick={lat=fix.getDouble("latitude").toString();lon=fix.getDouble("longitude").toString();preview=null;currentFix=null}){Text("Conferma posizione")}},dismissButton={TextButton(onClick={currentFix=null}){Text("Annulla")}})}

}
