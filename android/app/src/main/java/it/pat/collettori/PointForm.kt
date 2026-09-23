package it.pat.collettori

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng

@Composable fun PointForm(initial:JSONObject,collector:JSONObject,points:List<JSONObject>,segments:List<JSONObject>,dismiss:()->Unit,save:(JSONObject)->Unit){
    var code by remember{mutableStateOf(initial.optString("code"))};var lat by remember{mutableStateOf(initial.numberOrNull("latitude")?.toString().orEmpty())};var lon by remember{mutableStateOf(initial.numberOrNull("longitude")?.toString().orEmpty())}
    var sequence by remember{mutableStateOf(initial.numberOrNull("sequence")?.toString().orEmpty())};var branch by remember{mutableStateOf(initial.optString("branch"))};var end by remember{mutableStateOf(initial.optBoolean("topology_end"))}
    var links by remember{mutableStateOf(initial.optJSONArray("next_ids")?.strings().orEmpty())};var error by remember{mutableStateOf("")};var preview by remember{mutableStateOf<JSONObject?>(null)}
    val official=segments.filter{!it.optBoolean("schematic")&&it.optString("from_id")==initial.getString("id")}
    fun value()=manualPoint(initial,initial.getString("id"),collector.getString("id"),code,lat,lon,sequence,branch,links,end)
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())){
        Text("Pozzetto del collettore",style=MaterialTheme.typography.headlineSmall);Text(collector.optString("description").ifBlank{"Nuovo collettore"})
        Field("Codice / numero pozzetto",code){code=it;preview=null}
        OutlinedTextField(lat,{lat=it;preview=null},label={Text("Latitudine")},placeholder={Text("46.067890")},supportingText={Text("WGS84 · gradi decimali da −90 a 90")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(lon,{lon=it;preview=null},label={Text("Longitudine")},placeholder={Text("11.123456")},supportingText={Text("WGS84 · gradi decimali da −180 a 180")},modifier=Modifier.fillMaxWidth(),singleLine=true)
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
}
