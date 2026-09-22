package it.pat.collettori

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.maplibre.android.geometry.LatLng
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable fun CollPatApp(repo:Repository){
    val app=repo.context.applicationContext as PilotApplication
    var ready by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};var attempt by remember{mutableIntStateOf(0)}
    LaunchedEffect(attempt){try{
        coroutineScope{
            val load=async(Dispatchers.IO){withTimeout(15000){repo.prepareWorkspace()}}
            if(!app.splashFinished)delay(AppSpec.SPLASH_MILLIS)
            load.await()
        };app.splashFinished=true;ready=true
    }catch(e:Exception){error=e.message?:"Archivio non disponibile"}}
    if(!ready){AppLoading(error){error="";attempt++};return}
    var accountVersion by remember{mutableIntStateOf(0)}
    if(repo.store.get()==null){AccountPanel(repo){accountVersion++;ready=false;attempt++};return}
    key(accountVersion,repo.owner()){Workspace(repo){accountVersion++;ready=false;attempt++}}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Workspace(repo:Repository,onAccount:()->Unit){
    val account=repo.owner();val scope=rememberCoroutineScope();var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")}
    fun task(block:suspend()->Unit){if(busy)return;busy=true;scope.launch{try{block()}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"Operazione non riuscita"}finally{busy=false}}}
    val packs by repo.dao.packages(account).collectAsState(emptyList());val visits by repo.dao.visits(account).collectAsState(emptyList());val catalog by repo.dao.catalog(account).collectAsState(emptyList())
    val pack=packs.firstOrNull{it.id==AppSpec.PACKAGE}
    val data=remember(pack?.body){pack?.let{JSONObject(it.body)}?:JSONObject().put("points",JSONArray()).put("segments",JSONArray()).put("collectors",JSONArray()).put("basemap",JSONObject.NULL).put("osm",true)}
    val points=remember(data){data.getJSONArray("points").objects()};val collectors=remember(catalog){catalog.filter{it.kind=="collector"}.map{JSONObject(it.body)}.sortedBy{it.optString("description")}}
    var hidden by remember{mutableStateOf(emptySet<String>())};var catalogState by remember{mutableStateOf("Catalogo locale · completezza remota non verificata")}
    val queue by repo.dao.outbox(account).collectAsState(emptyList())
    val pendingCount=queue.size
    LaunchedEffect(account,catalog,visits){hidden=repo.visibility();catalogState=repo.dao.settingValue(account,"catalog-state")?:"Catalogo locale · completezza remota non verificata"}
    val lifecycle=LocalLifecycleOwner.current
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,e->if(e==Lifecycle.Event.ON_RESUME)repo.syncNow()};lifecycle.lifecycle.addObserver(observer);onDispose{lifecycle.lifecycle.removeObserver(observer)}}
    var tab by rememberSaveable{mutableStateOf("Mappa")};var collectorFilter by rememberSaveable{mutableStateOf("")};var pointFilter by rememberSaveable{mutableStateOf("")}
    var query by rememberSaveable{mutableStateOf("")};var pointStatus by rememberSaveable{mutableStateOf("Tutti")}
    var selected by rememberSaveable{mutableStateOf<String?>(null)};var highlighted by rememberSaveable{mutableStateOf<String?>(null)};var editorId by rememberSaveable{mutableStateOf<String?>(null)}
    var selector by rememberSaveable{mutableStateOf(false)};var detail by remember{mutableStateOf<JSONObject?>(null)}
    var position by remember{mutableStateOf<JSONObject?>(null)};var center by remember{mutableStateOf<Pair<LatLng,Int>?>(null)};var bounds by remember{mutableStateOf<List<LatLng>>(emptyList())};var tick by remember{mutableIntStateOf(0)}
    val pointListState=rememberLazyListState();val collectorListState=rememberLazyListState();val historyListState=rememberLazyListState()
    val pageState=rememberSaveableStateHolder()
    fun visible(id:String,value:Boolean){hidden=if(value)hidden-id else hidden+id;scope.launch{repo.setVisible(id,value)}}
    val activeCollectors=collectors.filter{!it.optBoolean("archived")}.map{it.getString("id")}.toSet()
    fun focusCollector(c:JSONObject){val id=c.getString("id");visible(id,true);selector=false;tab="Mappa";tick++;bounds=collectorExtent(data,id);if(bounds.size==1)center=bounds.first() to tick}
    fun focusPoint(p:JSONObject){p.memberships().filter{it in activeCollectors}.forEach{visible(it,true)};highlighted=p.getString("id");selected=null;tab="Mappa";tick++;bounds=emptyList();center=LatLng(p.getDouble("latitude"),p.getDouble("longitude")) to tick}
    var pendingVisit by rememberSaveable{mutableStateOf<String?>(null)};var permissionRequested by rememberSaveable{mutableStateOf(false)}
    var activeCapture by remember{mutableStateOf<LocationCapture?>(null)}
    suspend fun capture(id:String?){
        val visit=id?.let{repo.dao.visit(it,account)}
        val payload=visit?.let{JSONObject(it.body)}?:JSONObject().put("id","orientation").put("manhole_id","").put("user_id",repo.store.get()!!.getString("user_id")).put("device_id",repo.store.deviceId).put("dataset_id",pack?.id?:AppSpec.PACKAGE)
        val rule=Rule.parse(repo.localCatalog()!!.getJSONObject("rule"))
        val request=LocationCapture(repo.context);activeCapture=request
        val event=try{request.collect(payload,rule)}finally{activeCapture=null};position=event
        if(visit!=null){val original=repo.snapshot(visit).getJSONArray("points").objects();val p=original.first{it.getString("id")==visit.manholeId};event.put("local_evaluation",GpsRule.evaluate(event,p,original,rule));repo.appendEvent(id,event)}
        else if(validCoordinates(event.numberOrNull("latitude"),event.numberOrNull("longitude"))){tick++;bounds=emptyList();center=LatLng(event.getDouble("latitude"),event.getDouble("longitude")) to tick}
        else message=event.optString("error","Posizione non disponibile")
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){val id=pendingVisit;pendingVisit=null;task{capture(id)}}
    fun locate(id:String?=null){
        if(repo.context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED||repo.context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED||permissionRequested)task{capture(id)}
        else{pendingVisit=id;permissionRequested=true;permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}
    }
    var exportText by remember{mutableStateOf("")}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)task{withContext(Dispatchers.IO){repo.context.contentResolver.openOutputStream(uri)?.use{it.write(exportText.toByteArray())}?:error("File non scrivibile")};message="Esportazione salvata; le foto originali restano sul telefono"}}
    val online=NetworkStatus();var clock by remember{mutableIntStateOf(0)};LaunchedEffect(Unit){while(true){delay(10000);clock++}}
    val identification=remember(position,clock,points){GpsIdentification.identify(points,position)}
    Box(Modifier.fillMaxSize()){
    Scaffold(bottomBar={NavigationBar{listOf("Mappa","Collettori","Pozzetti","Ispezioni","Altro").forEach{title->NavigationBarItem(selected=tab==title,onClick={tab=title},icon={Icon(painterResource(when(title){"Mappa"->R.drawable.ic_map;"Collettori"->R.drawable.ic_pipe;"Pozzetti"->R.drawable.ic_pin;"Ispezioni"->R.drawable.ic_history;else->R.drawable.ic_info}),contentDescription=title,modifier=Modifier.size(24.dp))},label={Text(title,maxLines=1)})}}}){padding->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()){
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(AppSpec.NAME,style=MaterialTheme.typography.titleLarge);Text("v${AppSpec.version}",style=MaterialTheme.typography.labelMedium)}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(activeCapture!=null)TextButton(onClick={activeCapture?.cancel()}){Text("Interrompi rilevazione GPS")}
            if(message.isNotBlank())Row(Modifier.padding(horizontal=12.dp)){Text(message,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall);TextButton(onClick={message=""}){Text("Chiudi")}}
            Box(Modifier.weight(1f).fillMaxWidth()){
                // Keep the map mounted while switching pages: camera and sources remain alive.
                val mapData=remember(data,hidden,activeCollectors){JSONObject(data.toString()).put("segments",JSONArray(data.getJSONArray("segments").objects().filter{s->s.memberships().any{it in activeCollectors&&it !in hidden}})).put("osm",true)}
                val shownPoints=remember(points,hidden,activeCollectors){points.filter{p->p.memberships().any{it in activeCollectors&&it !in hidden}}}
                val displayPosition=remember(position,identification){position?.let{JSONObject(it.toString()).put("candidate_ids",JSONArray(identification.candidates))}}
                OfflineMap(mapData,shownPoints,highlighted,displayPosition,center,Modifier.fillMaxSize(),{selected=it;highlighted=it},{message=it},bounds,tick)
                if(tab=="Mappa"){
                    Column(Modifier.align(Alignment.TopStart).padding(8.dp).background(MaterialTheme.colorScheme.surface.copy(alpha=.95f)).padding(8.dp)){
                        Text(identification.state,style=MaterialTheme.typography.labelMedium);Text(precisionText(position),style=MaterialTheme.typography.bodySmall)
                        if(!online)Text("Senza rete · cartografia memorizzata potenzialmente incompleta",style=MaterialTheme.typography.bodySmall)
                    }
                    Column(Modifier.align(Alignment.BottomEnd).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        ExtendedFloatingActionButton(onClick={selector=true},containerColor=MaterialTheme.colorScheme.surface){ActionIcon(R.drawable.ic_pipe);Text("Seleziona Collettori")}
                        ExtendedFloatingActionButton(onClick={if(!busy)locate()},containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary){ActionIcon(R.drawable.ic_target);Text("Centra posizione")}
                    }
                }else Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)){
                    pageState.SaveableStateProvider(tab){when(tab){
                        "Collettori"->CollectorList(collectors,hidden,catalogState,collectorListState,{c->focusCollector(c)},::visible,{detail=it},{c->collectorFilter=c.getString("id");tab="Pozzetti"},data,catalog.associate{it.id to it.sync}){task{repo.catalog()}}
                        "Pozzetti"->{
                            Column(Modifier.padding(horizontal=12.dp)){
                                Field("Cerca codice, descrizione o collettore",query){query=it}
                                if(collectorFilter.isNotBlank())Row{Text("Filtro: "+collectors.find{it.getString("id")==collectorFilter}?.optString("description"),Modifier.weight(1f));TextButton(onClick={collectorFilter=""}){Text("Rimuovi")}}
                                Choice("Mostra",pointStatus,listOf("Tutti","Da ispezionare","Con anomalie").map{it to it}){pointStatus=it}
                            }
                            val filtered=points.filter{p->(collectorFilter.isBlank()||collectorFilter in p.memberships())&&((p.optString("code")+p.optString("description")+collectorNames(p,collectors)).contains(query,true))&&when(pointStatus){"Da ispezionare"->visits.none{it.manholeId==p.getString("id")&&periodic(it)};"Con anomalie"->visits.any{it.manholeId==p.getString("id")&&!isCancelled(it)&&hasAnomaly(JSONObject(it.body))};else->true}}
                            LazyColumn(state=pointListState,modifier=Modifier.weight(1f).padding(horizontal=12.dp)){items(filtered,key={it.getString("id")}){p->Card(modifier=Modifier.fillMaxWidth().padding(bottom=8.dp),onClick={selected=p.getString("id")}){Row(Modifier.padding(12.dp)){
                                Column(Modifier.weight(1f)){Text(p.getString("code"),style=MaterialTheme.typography.titleMedium);Text(collectorNames(p,collectors));Text(assetLabel(p.optString("asset_type","UNKNOWN")),style=MaterialTheme.typography.labelSmall);Text(topologyLabel(p,points),style=MaterialTheme.typography.bodySmall)}
                                IconButton(onClick={focusPoint(p)}){Icon(painterResource(R.drawable.ic_target),"Centra "+p.getString("code"))}
                            }}}}
                        }
                        "Ispezioni"->HistoryList(visits,points,collectors,pointFilter,collectorFilter,historyListState,{pointFilter="";collectorFilter=""},{editorId=it;message=""}){v,reason->task{repo.cancel(v.id,null,reason)}}
                        else->Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)){
                            Text("${AppSpec.NAME} v${AppSpec.version} · build ${BuildConfig.VERSION_CODE}",style=MaterialTheme.typography.titleLarge)
                            Text("$pendingCount operazioni pendenti · "+if(repo.authenticated())"Supabase Auth collegato" else "Accesso server non configurato")
                            Button(onClick={task{repo.dao.retryBlocked(account);repo.syncNow();message="Invio richiesto; attendere la ricevuta server"}}){Text("Riprova invio")}
                            queue.filter{it.error!=null||it.state !in listOf("IN_ATTESA","IN_CORSO")}.take(10).forEach{Text("${it.kind}: ${it.state} · ${it.error?:"Accedere allo stesso account per inviare"}",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                            AccountPanel(repo,onAccount)
                            OutlinedButton(enabled=!busy,onClick={task{repo.catalog();message="Catalogo completo aggiornato"}}){Text("Aggiorna tutti i collettori dal server")}
                            OutlinedButton(enabled=!busy,onClick={task{repo.downloadHistory();message="Storico server aggiornato; lavoro locale preservato"}}){Text("Aggiorna ispezioni dal server")}
                            if(BuildConfig.DEV_ADMIN)AdminPanel(repo,catalog,visits.size,{message=it})
                            HorizontalDivider(Modifier.padding(vertical=12.dp))
                            Text("Informazioni",style=MaterialTheme.typography.titleLarge)
                            Text("Trento e Lavis inclusi sono sintetici, non infrastrutture PAT. Le importazioni mantengono la propria provenienza e il tipo di manufatto.")
                            Text("Room conserva anagrafica, bozze e coda nello spazio privato. Salva bozza non invia. Registra Ispezione e Registra impedimento accodano dati reali: ricevuti soltanto dopo conferma Supabase. Accedere prima di creare lavoro da trasmettere; il lavoro di altri account rimane separato.")
                            Text("La verifica esterna sotto asfalto è un controllo periodico senza apertura; l'impedimento non è conteggiato. Le visite semestrali del collettore sono obiettivi, non la somma delle singole schede.")
                            Text("GPS su richiesta, con precisione, età e incertezza. Nessun tracciamento continuo. NFC/RFID e QR: predisposizione futura, nessuna lettura simulata.")
                            Text("Foto reali locali, caricamento simulato e metadati separati. Cache cartografica nominale 200 MiB (${AppSpec.MAP_CACHE_BYTES} byte); le risorse visitate possono essere incomplete o eliminate dal sistema. Nessun pacchetto offline garantito, nessun download preventivo OSM.")
                            Text("Dopo arresto forzato gli invii riprendono alla riapertura. Non avvengono a telefono spento. La dashboard admin/admin è uno sblocco locale di sviluppo, distinto dai permessi server.")
                            OutlinedButton(onClick={task{exportText=repo.recoveryBundle();export.launch("COLL-PAT-v${AppSpec.version}-ispezioni.json")}}){Text("Esporta archivio ispezioni")}
                            Text("© OpenStreetMap contributors · ODbL. Dati infrastrutturali separati dalla base OSM.",style=MaterialTheme.typography.bodySmall)
                        }
                    }}
                }
            }
        }
    }
    val editor=visits.firstOrNull{it.id==editorId}
    if(editor!=null)Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)){
        val editorPoint=points.find{it.getString("id")==editor.manholeId}
        InspectionEditor(repo,editor,editorPoint,editorPoint?.let{collectorNames(it,collectors)}?:"Collettore storico",busy,message,{message=it},{editorId=null},{locate(editor.id)},activeCapture!=null,{activeCapture?.cancel()})
    }
    }
    if(selector)ModalBottomSheet(onDismissRequest={selector=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        Box(Modifier.fillMaxHeight(.93f)){CollectorList(collectors,hidden,catalogState,rememberLazyListState(),{focusCollector(it)},::visible,{detail=it},{c->collectorFilter=c.getString("id");selector=false;tab="Pozzetti"},data,catalog.associate{it.id to it.sync}){task{repo.catalog()}}}
    }
    detail?.let{c->AlertDialog(onDismissRequest={detail=null},title={Text(c.getString("description"))},text={Column{Text(c.getString("code")+" · "+c.getString("type"));Text("Obiettivi: ${c.getInt("visits_h1")} visite 1° semestre · ${c.getInt("visits_h2")} visite 2° semestre");Text("${c.getDouble("hours_km_visit")} ore per km per visita");Text(lengthLabel(c));Text("UUID: "+c.getString("id"),style=MaterialTheme.typography.labelSmall)}},confirmButton={TextButton(onClick={detail=null}){Text("Chiudi")}})}
    val p=points.find{it.getString("id")==selected}
    if(p!=null)ModalBottomSheet(onDismissRequest={selected=null}){Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())){
        Text(p.getString("code"),style=MaterialTheme.typography.headlineMedium);Text(collectorNames(p,collectors));Text(topologyLabel(p,points))
        Text(identification.distances[selected]?.let{"Distanza dall'operatore: %.0f m".format(it)}?:"Distanza dall'operatore non disponibile")
        Text("Conferma il codice sul posto.")
        val draft=visits.firstOrNull{it.manholeId==p.getString("id")&&it.operational=="BOZZA"&&!isCancelled(it)}
        if(draft!=null)OutlinedButton(onClick={editorId=draft.id;selected=null}){Text("Riprendi bozza")}
        Button(enabled=!busy&&pack!=null,onClick={task{val v=repo.begin(p,pack!!,"LIST");editorId=v.id;selected=null;message=""}}){Text("Nuova ispezione")}
        TextButton(onClick={pointFilter=p.getString("id");selected=null;tab="Ispezioni"}){ActionIcon(R.drawable.ic_history);Text("Storico del manufatto")}
        TextButton(onClick={focusPoint(p)}){ActionIcon(R.drawable.ic_target);Text("Centra sulla mappa")}
    }}
}

fun collectorNames(p:JSONObject,collectors:List<JSONObject>)=p.memberships().joinToString(" · "){id->collectors.find{it.getString("id")==id}?.let{it.getString("description")+" ("+it.getString("code")+")"}?:id}
fun topologyLabel(p:JSONObject,points:List<JSONObject>):String {
    val previous=p.optString("previous_id").takeIf{it.isNotBlank()};val distance=p.numberOrNull("previous_distance_m")
    val a=if(previous!=null&&distance!=null)"Dal precedente ${points.find{it.getString("id")==previous}?.optString("code")?:previous}: %.1f m".format(distance) else "Dal precedente: Non disponibile"
    val chain=p.numberOrNull("chainage_m");val origin=p.optString("origin_id").takeIf{it.isNotBlank()}
    val source=when(p.optString("chainage_source","GIS")){"CALCULATED_SCHEMATIC"->"stimata su collegamenti schematici";"CALCULATED_GEOMETRY"->"calcolata sui tratti";"GIS"->"fornita dal GIS";else->"calcolata sul percorso"}
    val b=if(chain!=null&&origin!=null)"Progressiva: %.1f m · %s · origine %s".format(chain,source,points.find{it.getString("id")==origin}?.optString("code")?:origin) else "Progressiva: Non disponibile"
    return a+"\n"+b+(p.numberOrNull("gis_chainage_m")?.let{"\nProgressiva fornita GIS: %.1f m".format(it)}?:"")
}
fun collectorExtent(data:JSONObject,id:String):List<LatLng>{
    val result=data.getJSONArray("points").objects().filter{id in it.memberships()}.map{LatLng(it.getDouble("latitude"),it.getDouble("longitude"))}.toMutableList()
    data.getJSONArray("segments").objects().filter{id in it.memberships()}.forEach{s->val g=s.getJSONObject("geometry");val a=g.getJSONArray("coordinates");val lines=if(g.getString("type")=="LineString")listOf(a)else(0 until a.length()).map{a.getJSONArray(it)};lines.forEach{line->(0 until line.length()).forEach{i->val c=line.getJSONArray(i);result.add(LatLng(c.getDouble(1),c.getDouble(0)))}}}
    return result.distinctBy{it.latitude to it.longitude}
}

@Composable fun CollectorList(collectors:List<JSONObject>,hidden:Set<String>,state:String,listState:androidx.compose.foundation.lazy.LazyListState,onFocus:(JSONObject)->Unit,onVisible:(String,Boolean)->Unit,onDetail:(JSONObject)->Unit,onPoints:(JSONObject)->Unit,data:JSONObject,statuses:Map<String,String>,refresh:()->Unit){
    var query by rememberSaveable{mutableStateOf("")};var multi by rememberSaveable{mutableStateOf(false)};var selection by rememberSaveable(stateSaver=androidx.compose.runtime.saveable.listSaver<Set<String>,String>(save={it.toList()},restore={it.toSet()})){mutableStateOf(emptySet<String>())};var actions by remember{mutableStateOf(false)}
    val filtered=collectors.filter{!it.optBoolean("archived")&&(it.getString("code")+" "+it.getString("description")).contains(query,true)}
    Column(Modifier.fillMaxSize().padding(horizontal=12.dp)){
        Field("Cerca codice / descrizione",query){query=it};Text(state,style=MaterialTheme.typography.labelSmall)
        TextButton(onClick=refresh){Text("Aggiorna catalogo completo")}
        Row(verticalAlignment=Alignment.CenterVertically){
            Checkbox(multi,{multi=it});Text("Selezione multipla",Modifier.weight(1f))
            Box{
                TextButton(enabled=selection.isNotEmpty(),onClick={actions=true}){Text("Azioni (${selection.size})")}
                DropdownMenu(actions,{actions=false}){
                    listOf(true to "Mostra",false to "Nascondi").forEach{(value,label)->
                        DropdownMenuItem(text={Text(label)},onClick={selection.forEach{onVisible(it,value)};actions=false})
                    }
                }
            }
        }
        if(multi)TextButton(onClick={selection=if(filtered.all{it.getString("id") in selection})selection-filtered.map{it.getString("id")}.toSet() else selection+filtered.map{it.getString("id")}}){Text("Seleziona / deseleziona tutti i ${filtered.size} risultati filtrati")}
        LazyColumn(state=listState,modifier=Modifier.weight(1f)){items(filtered,key={it.getString("id")}){c->val id=c.getString("id");val extent=collectorExtent(data,id)
            Card(Modifier.fillMaxWidth().padding(vertical=4.dp)){Column(Modifier.padding(10.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){if(multi)Checkbox(id in selection,{selection=if(it)selection+id else selection-id});Column(Modifier.weight(1f)){Text(c.getString("description"),style=MaterialTheme.typography.titleMedium);Text(c.getString("code")+" · "+c.getString("type"))};IconButton(onClick={onVisible(id,id in hidden)}){Icon(painterResource(if(id in hidden)R.drawable.ic_eye_off else R.drawable.ic_eye),if(id in hidden)"Mostra collettore" else "Nascondi collettore")};IconButton(enabled=extent.isNotEmpty(),onClick={onFocus(c)}){Icon(painterResource(R.drawable.ic_target),"Inquadra collettore")}}
                Text(lengthLabel(c),style=MaterialTheme.typography.bodySmall);if(extent.isEmpty())Text("Centraggio non disponibile: nessuna geometria associata",style=MaterialTheme.typography.bodySmall)
                Text(if(id in hidden)"Nascosto sulla mappa" else "Visibile sulla mappa",style=MaterialTheme.typography.labelSmall)
                if(statuses[id]!="RICEVUTO_SERVER")Text(if(statuses[id]=="SEED_LOCAL")"Dati sintetici locali" else "Anagrafica in attesa di sincronizzazione",style=MaterialTheme.typography.labelSmall)
                Row{TextButton(onClick={onDetail(c)}){Text("Anagrafica")};TextButton(onClick={onPoints(c)}){Text("Pozzetti del collettore")}}
            }}
        }}
    }
}
fun assetLabel(type:String)=when(type){"MANHOLE"->"Pozzetto";"PUMP_STATION"->"Sollevamento";"ACCESSORY"->"Opera accessoria";"UNKNOWN"->"Tipo manufatto da verificare";else->type}
fun modelLabel(model:String)=when(model){"ORDINARY"->"Ordinario";"ASPHALT_EXTERNAL"->"Sotto asfalto · verifica esterna";"ASSET_EXTERNAL"->"Verifica esterna del manufatto";else->"Modello storico"}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HistoryList(visits:List<Visit>,points:List<JSONObject>,collectors:List<JSONObject>,pointFilter:String,collectorFilter:String,listState:androidx.compose.foundation.lazy.LazyListState,clear:()->Unit,open:(String)->Unit,cancel:(Visit,String)->Unit){
    var date by rememberSaveable{mutableStateOf<String?>(null)};var datePicker by remember{mutableStateOf(false)};var showCancelled by rememberSaveable{mutableStateOf(false)};var target by remember{mutableStateOf<Visit?>(null)}
    Column(Modifier.fillMaxSize().padding(horizontal=12.dp)){
        Row{TextButton(onClick={datePicker=true}){ActionIcon(R.drawable.ic_calendar);Text(date?:"Filtra per data")};if(date!=null)TextButton(onClick={date=null}){Text("Rimuovi data")}}
        if(pointFilter.isNotBlank()||collectorFilter.isNotBlank())TextButton(onClick=clear){Text("Rimuovi filtro manufatto / collettore")}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(showCancelled,{showCancelled=it});Text("Mostra anche annullati e precedenti al reset")}
        val filtered=visits.filter{v->(showCancelled||(!isCancelled(v)&&v.sync!="RESET_OBSOLETE"))&&(date==null||visitDay(v).toString()==date)&&(pointFilter.isBlank()||v.manholeId==pointFilter)&&(collectorFilter.isBlank()||points.find{it.getString("id")==v.manholeId}?.memberships()?.contains(collectorFilter)==true)}
        if(filtered.isEmpty())Text("Nessuna ispezione nei filtri correnti")
        LazyColumn(state=listState,modifier=Modifier.weight(1f)){filtered.groupBy{visitDay(it)}.toSortedMap(compareByDescending{it}).forEach{(day,list)->
            item{Text(day.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))+" · ${list.size} registrazioni",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(vertical=8.dp))}
            items(list,key={it.id}){v->val p=points.find{it.getString("id")==v.manholeId};val b=JSONObject(v.body)
                Card(modifier=Modifier.fillMaxWidth().padding(bottom=8.dp),onClick={open(v.id)}){Row(Modifier.padding(12.dp)){
                    Column(Modifier.weight(1f)){Text(p?.optString("code")?:"Manufatto storico",style=MaterialTheme.typography.titleMedium);if(p!=null)Text(collectorNames(p,collectors),style=MaterialTheme.typography.bodySmall);Text(shown(b.optString("completed_at",b.getString("started_at"))));Text(modelLabel(b.optString("model"))+" · "+if(isCancelled(v))"ANNULLATA" else operational(v.operational));Text(if(hasAnomaly(b))"Anomalie segnalate" else "Nessuna anomalia dichiarata");Text(syncLabel(v.sync),style=MaterialTheme.typography.labelMedium)}
                    if(!isCancelled(v))IconButton(onClick={target=v}){Icon(painterResource(R.drawable.ic_trash),"Annulla ispezione")}
                }}
            }
        }}
    }
    if(datePicker){val state=rememberDatePickerState();DatePickerDialog(onDismissRequest={datePicker=false},confirmButton={TextButton(onClick={date=state.selectedDateMillis?.let{Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()};datePicker=false}){Text("Applica")}},dismissButton={TextButton(onClick={datePicker=false}){Text("Indietro")}}){DatePicker(state)}}
    target?.let{v->CancelDialog("ispezione",{target=null}){reason->cancel(v,reason);target=null}}
}
