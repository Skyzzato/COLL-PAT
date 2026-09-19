package it.pat.collettori

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DemoWorkspace(repo:Repository) {
    val scope=rememberCoroutineScope()
    var ready by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf("")}
    fun task(block:suspend()->Unit){scope.launch{busy=true;try{block()}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){android.util.Log.e("Collettori","Operazione non riuscita",e);message=e.message?:"Operazione non riuscita"}finally{busy=false}}}
    LaunchedEffect(Unit){task{repo.prepareDemo();ready=true}}
    if(!ready){AppLoading(message){task{message="";repo.prepareDemo();ready=true}};return}
    val packs by repo.dao.packages(repo.owner()).collectAsState(emptyList())
    val visits by repo.dao.visits(repo.owner()).collectAsState(emptyList())
    val pack=packs.firstOrNull{it.id=="00000000-0000-4000-8000-000000000011"}?:return
    val data=remember(pack.id){JSONObject(pack.body)}
    val points=remember(pack.id){data.getJSONArray("points").objects()}
    var tab by rememberSaveable{mutableStateOf("Mappa")}
    var selected by rememberSaveable{mutableStateOf<String?>(null)}
    var editorId by rememberSaveable{mutableStateOf<String?>(null)}
    var position by remember{mutableStateOf<JSONObject?>(null)}
    var center by remember{mutableStateOf<Pair<LatLng,Int>?>(null)}
    var tick by remember{mutableIntStateOf(0)}
    var clock by remember{mutableIntStateOf(0)}
    LaunchedEffect(Unit){while(true){delay(10000);clock++}}
    val identification=remember(position,clock){GpsIdentification.identify(points,position)}
    var pendingVisit by rememberSaveable{mutableStateOf<String?>(null)}
    suspend fun capture(visitId:String?) {
        val visit=visitId?.let{repo.dao.visit(it,repo.owner())}
        val payload=visit?.let{JSONObject(it.body)}?:JSONObject().put("id","orientation").put("manhole_id","").put("user_id",DemoMode.user).put("device_id",repo.store.deviceId).put("dataset_id",pack.id)
        val rule=Rule.parse(repo.localCatalog()!!.getJSONObject("rule"))
        val e=LocationCapture(repo.context).collect(payload,rule)
        position=e
        if(visit!=null){
            val original=repo.dao.pack(visit.owner,visit.datasetId)?:error("Versione dati assente")
            val originalPoints=JSONObject(original.body).getJSONArray("points").objects()
            val point=originalPoints.first{it.getString("id")==visit.manholeId}
            e.put("local_evaluation",GpsRule.evaluate(e,point,originalPoints,rule));repo.appendEvent(visit.id,e)
            repo.dao.setting(Setting(visit.owner,"identification:"+visit.id,JSONObject().put("method","GPS").put("manholeId",visit.manholeId).put("confirmedAt",java.time.Instant.now().toString()).put("gps",e).put("result",GpsIdentification.identify(originalPoints,e).state).toString()))
        }else{
            val lat=e.numberOrNull("latitude");val lon=e.numberOrNull("longitude")
            if(validCoordinates(lat,lon)){tick++;center=LatLng(lat!!,lon!!) to tick}
        }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
        val id=pendingVisit;pendingVisit=null;task{capture(id)}
    }
    fun locate(id:String?=null){
        if(repo.context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED || repo.context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED)task{capture(id)}
        else {pendingVisit=id;permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}
    }
    val editor=visits.firstOrNull{it.id==editorId}
    if(editor!=null){
        var editorPack by remember(editor.id){mutableStateOf<OfflinePackage?>(null)}
        LaunchedEffect(editor.id){editorPack=repo.dao.pack(editor.owner,editor.datasetId)}
        val point=editorPack?.let{JSONObject(it.body).getJSONArray("points").objects().find{p->p.getString("id")==editor.manholeId}}
        BackHandler{editorId=null}
        InspectionEditor(repo,editor,point,editorPack,busy,message,{message=it},{editorId=null},{locate(editor.id)},
            {body,status->task{repo.complete(editor.id,body,status);message="Ispezione registrata sul telefono"}},
            {task{repo.revise(editor.id)}},{})
        return
    }
    var exportText by remember{mutableStateOf("")}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)task{
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){repo.context.contentResolver.openOutputStream(uri)?.use{it.write(exportText.toByteArray(Charsets.UTF_8))}?:error("File non scrivibile")}
        message="Esportazione salvata; le immagini restano sul telefono"
    }}
    val online=NetworkStatus()
    Scaffold(bottomBar={NavigationBar{listOf("Mappa","Pozzetti","Ispezioni","Altro").forEach{title->NavigationBarItem(selected=tab==title,onClick={tab=title},icon={Icon(androidx.compose.ui.res.painterResource(when(title){"Mappa"->R.drawable.ic_map;"Pozzetti"->R.drawable.ic_pin;"Ispezioni"->R.drawable.ic_history;else->R.drawable.ic_info}),contentDescription=null)},label={Text(title)})}}}){padding->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()){
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween){Text("Collettori",style=MaterialTheme.typography.titleLarge);Text("DEMO · v0.12",style=MaterialTheme.typography.labelLarge)}
            Text("Trento · 10 pozzetti fittizi · nessuna infrastruttura PAT",Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.labelSmall)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(message.isNotBlank())Row(Modifier.padding(horizontal=16.dp)){Text(message,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall);TextButton(onClick={message=""}){Text("Chiudi")}}
            when(tab){
                "Mappa"->{
                    Box(Modifier.weight(1f).fillMaxWidth()){
                        val mapData=remember(data,online){JSONObject(data.toString()).put("osm",online)}
                        val displayPosition=remember(position,identification){position?.let{JSONObject(it.toString()).put("candidate_ids",org.json.JSONArray(identification.candidates))}}
                        OfflineMap(mapData,points,selected,displayPosition,center,Modifier.fillMaxSize(),{selected=it},{message=it})
                        Card(Modifier.align(Alignment.TopStart).padding(12.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface.copy(alpha=.96f))){
                            Column(Modifier.padding(12.dp)){
                                Text(identification.state,style=MaterialTheme.typography.labelLarge)
                                Text(precisionText(position),style=MaterialTheme.typography.bodySmall)
                                position?.optString("error")?.takeIf{it.isNotBlank()&&it!="null"}?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                                if(!online)Text("Senza rete · base OSM non disponibile; rete demo locale",style=MaterialTheme.typography.bodySmall)
                                if(identification.candidates.size==1)TextButton(onClick={selected=identification.candidates.first()}){Text("Verifica "+points.first{it.getString("id")==identification.candidates.first()}.getString("code"))}
                            }
                        }
                        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            SmallFloatingActionButton(containerColor=MaterialTheme.colorScheme.surface,onClick={tick++;center=LatLng(46.0647,11.1154) to -tick}){Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_map),"Mostra collettore")}
                            ExtendedFloatingActionButton(containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary,onClick={if(!busy)locate()}){ActionIcon(R.drawable.ic_target);Text("Centra posizione")}
                        }
                    }
                    Text("© OpenStreetMap contributors · OSM online",Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.labelSmall)
                }
                "Pozzetti"->{
                    var query by rememberSaveable{mutableStateOf("")}
                    var filter by rememberSaveable{mutableStateOf("Tutti")}
                    Column(Modifier.padding(horizontal=16.dp)){
                        Text("Selezione manuale di riserva",style=MaterialTheme.typography.titleMedium)
                        Text("Usala se GPS o identificazione fisica non sono disponibili.",style=MaterialTheme.typography.bodySmall)
                        Field("Cerca codice pozzetto",query){query=it}
                        Choice("Mostra",filter,listOf("Tutti","Da ispezionare","Con anomalie").map{it to it}){filter=it}
                        Text("${points.count{p->visits.any{it.manholeId==p.getString("id")&&it.operational=="COMPLETO"}}}/10 controllati")
                    }
                    LazyColumn(Modifier.weight(1f).padding(horizontal=16.dp)){items(points.filter{p->
                        val history=visits.filter{it.manholeId==p.getString("id")}
                        p.getString("code").contains(query,true)&&when(filter){"Da ispezionare"->history.none{it.operational=="COMPLETO"};"Con anomalie"->history.firstOrNull()?.let{v->Repository.observationKeys.any{JSONObject(v.body).getJSONObject("sheet").optString(it)=="ANOMALO"}}==true;else->true}
                    }){p->OutlinedButton(onClick={selected=p.getString("id")},modifier=Modifier.fillMaxWidth().heightIn(min=60.dp)){Text(p.getString("code")+" · progressiva "+p.getDouble("chainage_m").toInt()+" m")}}}
                }
                "Ispezioni"->{
                    if(visits.isEmpty())Text("Nessuna ispezione. Seleziona un pozzetto sulla mappa.",Modifier.padding(16.dp))
                    LazyColumn(Modifier.weight(1f).padding(16.dp)){items(visits,key={it.id}){v->Card(Modifier.fillMaxWidth().padding(bottom=8.dp)){
                        Column(Modifier.padding(12.dp)){
                            Text(points.find{it.getString("id")==v.manholeId}?.getString("code")?:"Pozzetto dataset precedente",style=MaterialTheme.typography.titleMedium)
                            Text(operational(v.operational));Text(shown(JSONObject(v.body).getString("started_at")));Text(syncLabel(v.sync),style=MaterialTheme.typography.bodySmall)
                            Button(onClick={editorId=v.id;message=""}){Text(if(v.operational=="BOZZA")"Riprendi bozza" else "Apri ispezione")}
                        }
                    }}}
                }
                else->Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())){
                    Text("Collettori v0.12",style=MaterialTheme.typography.headlineMedium)
                    Text("Dataset sintetico lungo circa 1 km presso Trento. Il GPS usa la posizione reale del dispositivo.")
                    Text("Ispezioni, bozze e foto sono locali. Nessun upload o account server nella demo. La base OpenStreetMap richiede rete; pozzetti e tracciato sono disponibili offline.")
                    Text("Identificazione futura: NFC/HF, lettori RFID esterni e QR. Nessun lettore simulato. La selezione manuale rimane un'alternativa.")
                    OutlinedButton(onClick={task{exportText=repo.recoveryBundle();export.launch("Collettori-v0.12-ispezioni.json")}}){Text("Esporta ispezioni JSON")}
                    Text("L'esportazione contiene coordinate e riferimenti fotografici locali, non le immagini. Disinstallare l'app elimina i dati locali.")
                }
            }
        }
    }
    val point=points.find{it.getString("id")==selected}
    if(point!=null)ModalBottomSheet(onDismissRequest={selected=null}){
        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(point.getString("code"),style=MaterialTheme.typography.headlineMedium)
            Text("Collettore demo Trento · progressiva ${point.getDouble("chainage_m").toInt()} m")
            Text(identification.distances[selected]?.let{"Distanza: %.0f m".format(it)}?:"Distanza non disponibile")
            Text(precisionText(position))
            val last=visits.firstOrNull{it.manholeId==selected&&it.operational!="BOZZA"}
            Text(last?.let{"Ultima: "+shown(JSONObject(it.body).optString("completed_at"))+" · "+operational(it.operational)}?:"Nessuna ispezione registrata")
            Text("Conferma sul posto il codice prima di iniziare.",style=MaterialTheme.typography.bodySmall)
            Button(enabled=!busy,onClick={task{
                val existing=visits.firstOrNull{it.manholeId==point.getString("id")&&it.operational=="BOZZA"}
                val visit=existing?:repo.begin(point,pack,if(tab=="Mappa")"MAP" else "LIST")
                editorId=visit.id;selected=null;message=""
                if(JSONObject(visit.body).getJSONArray("events").length()==0)locate(visit.id)
            }},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){ActionIcon(R.drawable.ic_check);Text("Avvia ispezione")}
            Row{
                TextButton(onClick={selected=null;tab="Ispezioni"}){ActionIcon(R.drawable.ic_history);Text("Ispezioni precedenti")}
                val next=points.getOrNull(points.indexOf(point)+1)
                if(next!=null)TextButton(onClick={selected=next.getString("id");tick++;center=LatLng(next.getDouble("latitude"),next.getDouble("longitude")) to tick}){Text("Prossimo: "+next.getString("code"))}
            }
        }
    }
}
