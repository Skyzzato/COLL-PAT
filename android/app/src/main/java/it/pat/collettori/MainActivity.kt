package it.pat.collettori

import android.Manifest
import android.os.Bundle
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import org.maplibre.android.geometry.LatLng
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF176B68),secondary=Color(0xFF9B641D),background=Color(0xFFF5F7F3))){Pilot((application as PilotApplication).repository)}}}}

@Composable fun Choice(label:String,value:String,options:List<Pair<String,String>>,onChange:(String)->Unit){
    var open by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().padding(vertical=4.dp)){
        Text(label,style=MaterialTheme.typography.labelLarge)
        Box{OutlinedButton(onClick={open=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(options.find{it.first==value}?.second?:"Seleziona")}
            DropdownMenu(expanded=open,onDismissRequest={open=false}){options.forEach{(key,text)->DropdownMenuItem(text={Text(text)},onClick={open=false;onChange(key)})}}}
    }
}
@Composable fun Field(label:String,value:String,onChange:(String)->Unit){OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},modifier=Modifier.fillMaxWidth().padding(vertical=4.dp))}
fun shown(s:String?):String=try{DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.of("Europe/Rome")).format(Instant.parse(s))}catch(e:Exception){s?:"—"}
fun operational(s:String)=when(s){"BOZZA"->"Bozza";"COMPLETO"->"Controllo dichiarato completo";"PARZIALE"->"Parziale";else->"Impedito"}
fun syncLabel(s:String)=when(s){"DEMO_LOCALE"->"Demo salvata sul telefono · nessun invio";"SALVATO_LOCALMENTE"->"Salvato localmente";"IN_ATTESA"->"In attesa di invio";"INVIO_IN_CORSO"->"Invio in corso";"RICEVUTO_SERVER"->"Ricevuto dal server";else->"Errore da risolvere"}

@Composable fun NetworkStatus():Boolean{
    val context=LocalContext.current
    val manager=remember{context.getSystemService(Context.CONNECTIVITY_SERVICE)as ConnectivityManager}
    fun connected()=manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
    var online by remember{mutableStateOf(connected())}
    DisposableEffect(manager){val cb=object:ConnectivityManager.NetworkCallback(){override fun onAvailable(network:Network){online=connected()};override fun onLost(network:Network){online=connected()};override fun onCapabilitiesChanged(network:Network,c:NetworkCapabilities){online=connected()}};manager.registerDefaultNetworkCallback(cb);onDispose{manager.unregisterNetworkCallback(cb)}}
    return online
}

@Composable fun Pilot(repo:Repository){
    var session by remember{mutableStateOf(repo.store.get())}
    var message by remember{mutableStateOf("")}
    var runningTasks by remember{mutableIntStateOf(0)}
    val busy=runningTasks>0
    val scope=rememberCoroutineScope()
    fun task(block:suspend()->Unit){scope.launch{runningTasks++;try{block()}catch(e:Exception){message=e.message?:"Operazione non riuscita"}finally{runningTasks--}}}
    if(BuildConfig.DEMO && session==null){
        LaunchedEffect(Unit){task{repo.prepareDemo();session=repo.store.get()}}
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)){
            Text("Collettori Demo",style=MaterialTheme.typography.headlineLarge)
            Text("Preparazione dei dati sintetici sul telefono…")
            if(message.isNotBlank())Text(message)
        }
        return
    }
    if(session==null){
        var base by remember{mutableStateOf(if(BuildConfig.DEBUG)"http://10.0.2.2:8000" else "https://")}
        var username by remember{mutableStateOf("")};var password by remember{mutableStateOf("")}
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Collettori PAT",style=MaterialTheme.typography.headlineLarge);Text("Pilota 0.1 · Accesso individuale")
            Text("Primo accesso online. I controlli già salvati rimangono separati per utente.")
            Field("Indirizzo server",base){base=it};Field("Nome utente",username){username=it}
            OutlinedTextField(password,{password=it},label={Text("Password")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            Button(enabled=!busy,onClick={task{session=repo.api.login(base,username,password);password="";message="";repo.dao.resumeAuth(repo.owner());repo.syncNow()}},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("Accedi")}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth());if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.error)
        };return
    }
    val account=repo.owner()
    key(account){
        val packs by repo.dao.packages(account).collectAsState(initial=emptyList())
        val visits by repo.dao.visits(account).collectAsState(initial=emptyList())
        var tab by remember{mutableStateOf("Pozzetti")}
        var catalog by remember{mutableStateOf<JSONObject?>(null)}
        var area by remember{mutableStateOf("")}
        var collector by remember{mutableStateOf("")}
        var search by remember{mutableStateOf("")}
        var deadlineFilter by remember{mutableStateOf("ALL")}
        var anomalyFilter by remember{mutableStateOf(false)}
        var selected by remember{mutableStateOf<String?>(null)}
        var editor by remember{mutableStateOf<Visit?>(null)}
        var editorPoint by remember{mutableStateOf<JSONObject?>(null)}
        var editorPack by remember{mutableStateOf<OfflinePackage?>(null)}
        var localPosition by remember{mutableStateOf<JSONObject?>(null)}
        var center by remember{mutableStateOf<Pair<LatLng,Int>?>(null)}
        var centerTick by remember{mutableIntStateOf(0)}
        var recoveryText by remember{mutableStateOf("")}
        val exportRecovery=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)task{
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){repo.context.contentResolver.openOutputStream(uri)?.use{it.write(recoveryText.toByteArray(Charsets.UTF_8))}?:error("File non scrivibile")}
            recoveryText="";message=if(BuildConfig.DEMO)"Esportazione demo salvata. Include eventuali coordinate GPS reali: condividila consapevolmente." else "Copia di recupero salvata. Contiene dati personali: consegnarla solo al referente autorizzato. La coda originale è conservata."
        }}
        val online=NetworkStatus()
        val latest=packs.distinctBy{it.area}
        val pack=latest.find{it.area==area}?:latest.firstOrNull()
        val packJson=remember(pack?.id){pack?.let{JSONObject(it.body)}}
        val points=remember(pack?.id){packJson?.getJSONArray("points")?.objects()?:emptyList()}
        val selectedPoint=points.find{it.getString("id")==selected}
        val deadlines=catalog?.optJSONArray("deadlines")?.objects()?:emptyList()
        val filtered=remember(points,search,collector,deadlineFilter,anomalyFilter,visits,catalog){
            points.filter{p->val id=p.getString("id");val labels=p.getJSONArray("collectors").toString()
                val due=deadlines.filter{it.getString("manhole_id")==id}
                val completed=visits.filter{it.operational=="COMPLETO"}.mapNotNull{JSONObject(it.body).optString("deadline_id").takeIf{it!="null"&&it.isNotBlank()}}.toSet()
                val matchesDue=when(deadlineFilter){"UNSET"->due.isEmpty();"OVERDUE"->due.any{Instant.parse(it.getString("due_at")).isBefore(Instant.now())&&!completed.contains(it.getString("id"))};else->true}
                (search.isBlank() || (labels+" "+p.getString("code")).contains(search,true)) && (collector.isBlank()||p.getJSONArray("collectors").toString().contains("\"$collector\"")) && matchesDue && (!anomalyFilter||visits.any{it.manholeId==id&&JSONObject(it.body).getJSONObject("sheet").optString("anomaly_note").isNotBlank()})}
        }
        LaunchedEffect(account){catalog=repo.localCatalog()}
        var pendingPermission by remember{mutableStateOf<(() -> Unit)?>(null)}
        val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){pendingPermission?.invoke();pendingPermission=null}
        fun withPermission(action:()->Unit){pendingPermission=action;permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}
        suspend fun openEditor(v:Visit){editor=v;editorPack=repo.dao.pack(account,v.datasetId);editorPoint=editorPack?.let{JSONObject(it.body).getJSONArray("points").objects().find{p->p.getString("id")==v.manholeId}}}
        suspend fun capture(id:String){
            repo.checkOffline()
            val current=repo.dao.visit(id,account)?:error("Bozza assente")
            val payload=JSONObject(current.body)
            val ref=repo.dao.pack(account,current.datasetId)?:error("Versione cartografica assente")
            val all=JSONObject(ref.body).getJSONArray("points").objects();val p=all.first{it.getString("id")==current.manholeId}
            val rule=Rule.parse((repo.localCatalog()?:error("Scaricare la regola prima dell'uscita")).getJSONObject("rule"))
            val event=LocationCapture(repo.context).collect(payload,rule)
            event.put("local_evaluation",GpsRule.evaluate(event,p,all,rule))
            val saved=repo.appendEvent(id,event);openEditor(saved);message="Evento GPS salvato sul dispositivo"
        }
        if(editor!=null){
            InspectionEditor(repo,editor!!,editorPoint,editorPack,busy,message,
                onMessage={message=it},onBack={editor=null},onCapture={withPermission{task{capture(editor!!.id)}}},
                onComplete={body,status->task{val v=repo.complete(editor!!.id,body,status);openEditor(v);message=if(BuildConfig.DEMO)"Controllo demo salvato solo sul telefono" else "Controllo salvato sul dispositivo, in attesa di invio"}},
                onRevise={task{openEditor(repo.revise(editor!!.id));message="Revisione aperta; la versione precedente rimane conservata"}},
                onHistory={task{val d=String(repo.api.request("/api/inspections/"+editor!!.id));repo.dao.setting(Setting(account,"history:"+editor!!.id,d));message="Storico server aggiornato"}})
        }else{
        Scaffold(bottomBar={NavigationBar{listOf("Mappa","Pozzetti","Controlli","Dati offline","Account").forEach{t->NavigationBarItem(selected=tab==t,onClick={tab=t},icon={Text(when(t){"Mappa"->"◉";"Pozzetti"->"▦";"Controlli"->"✓";"Dati offline"->"↓";else->"●"})},label={Text(t)})}}}){padding->
            Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding().padding(horizontal=16.dp)){
                Text(if(BuildConfig.DEMO)"Collettori DEMO · autonoma" else "Collettori PAT · 0.1",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.padding(top=12.dp))
                Text(if(BuildConfig.DEMO)"Senza server · dati sintetici · salvataggio locale" else ((if(online)"Rete disponibile" else "Senza rete")+" · "+visits.count{it.operational!="BOZZA"&&it.sync!="RICEVUTO_SERVER"}+" controlli da inviare"),style=MaterialTheme.typography.bodySmall)
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(message.isNotBlank()){Text(message,modifier=Modifier.padding(vertical=8.dp),style=MaterialTheme.typography.bodySmall);TextButton(onClick={message=""}){Text("Chiudi messaggio")}}
                when(tab){
                    "Mappa","Pozzetti"->{
                        if(packJson==null){Text("Nessun dato locale disponibile. Scarica un’area in Dati offline.");Button(onClick={tab="Dati offline"}){Text("Dati offline")}}
                        else{
                            if(packJson.getBoolean("synthetic"))Text("DATI E BASE SINTETICI · non usare per interventi reali",color=MaterialTheme.colorScheme.secondary,style=MaterialTheme.typography.labelMedium)
                            Choice("Area",pack!!.area,latest.map{it.area to JSONObject(it.body).getString("name")}){area=it;selected=null;collector=""}
                            OutlinedTextField(search,{search=it},label={Text("Cerca collettore o numero pozzetto")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                            var filters by remember{mutableStateOf(false)}
                            TextButton(onClick={filters=!filters}){Text("Filtri · ${filtered.size} pozzetti")}
                            if(filters){
                                val codes=points.flatMap{p->val a=p.getJSONArray("collectors");(0 until a.length()).map{a.getString(it)}}.distinct().sorted()
                                Choice("Collettore",collector,listOf("" to "Tutti")+codes.map{it to it}){collector=it}
                                Choice("Scadenza",deadlineFilter,listOf("ALL" to "Tutte","UNSET" to "Non configurata","OVERDUE" to "Scaduta senza controllo completo locale")){deadlineFilter=it}
                                Row{Checkbox(anomalyFilter,{anomalyFilter=it});Text("Con anomalie nelle schede locali",modifier=Modifier.padding(top=12.dp))}
                            }
                            if(tab=="Mappa"){
                                if(!pack.baseReady)Text("Base offline non pronta. Schede e rete locale disponibili.",color=MaterialTheme.colorScheme.error)
                                Row{
                                    TextButton(onClick={withPermission{task{
                                        val r=Rule.parse((catalog?:error("Aggiornare il catalogo")).getJSONObject("rule"))
                                        val fake=JSONObject().put("id","local-orientation").put("manhole_id","").put("user_id",session!!.getString("user_id")).put("device_id",repo.store.deviceId).put("dataset_id",pack.id)
                                        val e=LocationCapture(repo.context).collect(fake,r);localPosition=e
                                        val lat=e.numberOrNull("latitude");val lon=e.numberOrNull("longitude")
                                        if(lat!=null&&lon!=null){centerTick++;center=LatLng(lat,lon) to centerTick}else message="Posizione non disponibile"
                                    }}}){Text("Centra su di me")}
                                    TextButton(enabled=selectedPoint!=null,onClick={selectedPoint?.let{centerTick++;center=LatLng(it.getDouble("latitude"),it.getDouble("longitude")) to centerTick}}){Text("Centra sul pozzetto")}
                                }
                                OfflineMap(packJson,filtered,selected,localPosition,center,Modifier.fillMaxWidth().weight(1f),onSelect={selected=it},onError={message=it})
                                Text(packJson.getString("attribution"),style=MaterialTheme.typography.labelSmall)
                                Text("Cerchio blu: dispositivo e accuratezza · Anello ocra: pozzetto selezionato",style=MaterialTheme.typography.labelSmall)
                            }else{
                                LazyColumn(Modifier.weight(1f)){items(filtered,key={it.getString("id")}){p->OutlinedButton(onClick={selected=p.getString("id")},modifier=Modifier.fillMaxWidth()){Column(Modifier.fillMaxWidth()){Text(p.getJSONArray("collectors").toString().replace("\"","")+" / "+p.getString("code"));Text(if(deadlines.any{it.getString("manhole_id")==p.getString("id")})"Scadenza assegnata" else "Periodicità/scadenza non configurata",style=MaterialTheme.typography.bodySmall)}}}}
                            }
                            if(selectedPoint!=null){
                                Card(Modifier.fillMaxWidth().padding(vertical=8.dp)){Column(Modifier.padding(12.dp)){
                                    Text("Pozzetto "+selectedPoint.getString("code"),style=MaterialTheme.typography.titleMedium)
                                    Text("Collettori: "+selectedPoint.getJSONArray("collectors").toString().replace("\"",""),style=MaterialTheme.typography.bodySmall)
                                    Text("Posizione cartografica: %.6f, %.6f".format(selectedPoint.getDouble("latitude"),selectedPoint.getDouble("longitude")),style=MaterialTheme.typography.bodySmall)
                                    Row{
                                        Button(enabled=!busy,onClick={task{val v=repo.begin(selectedPoint,pack,if(tab=="Mappa")"MAP" else "LIST");openEditor(v);withPermission{task{capture(v.id)}}}}){Text("Avvia controllo")}
                                        TextButton(onClick={tab="Controlli"}){Text("Storico")}
                                        TextButton(onClick={selected=null}){Text("Chiudi")}
                                    }
                                }}
                            }
                        }
                    }
                    "Controlli"->{
                        Row{if(!BuildConfig.DEMO)Button(onClick={repo.syncNow();message="Invio richiesto; l'esito comparirà dopo la ricevuta server"}){Text("Sincronizza ora")};if(selected!=null)TextButton(onClick={selected=null}){Text("Tutti")}}
                        LazyColumn{items(visits.filter{selected==null||it.manholeId==selected},key={it.id}){v->
                            Card(Modifier.fillMaxWidth().padding(vertical=5.dp)){Column(Modifier.padding(12.dp)){
                                val b=JSONObject(v.body);Text(operational(v.operational),style=MaterialTheme.typography.titleMedium)
                                Text(shown(b.optString("completed_at",b.getString("started_at"))));Text(syncLabel(v.sync))
                                val es=b.getJSONArray("events");if(es.length()>0)Text(GpsRule.label(es.getJSONObject(es.length()-1).getJSONObject("local_evaluation").getString("state")))
                                Text("Revisione: "+(v.receipt?.let{JSONObject(it).optString("review") }?:"Non esaminata"))
                                v.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                                Button(onClick={task{openEditor(v)}}){Text(if(v.operational=="BOZZA")"Riprendi bozza" else "Apri scheda")}
                            }}
                        }}
                    }
                    "Dati offline"->{
                        if(BuildConfig.DEMO){
                            Text("Cartografia sintetica inclusa: 16 pozzetti e 15 tratti. Nessun download richiesto.")
                            Text("GPS reale su richiesta. I pozzetti demo sono nell'area di Trento: altrove l'esito può essere incompatibile. Motiva l'eccezione per provare la scheda.")
                            Text("Bozze e controlli restano sul telefono anche riaprendo l'app. Nessun dato viene inviato a Supabase.")
                        }else Column(Modifier.verticalScroll(rememberScrollState())){
                            Text("Prima dell'uscita",style=MaterialTheme.typography.titleLarge)
                            Text("Spazio libero: "+android.os.StatFs(repo.context.filesDir.path).availableBytes/1024/1024+" MB")
                            Text("Abilitazione offline fino a "+shown(repo.store.get()?.getString("offline_until")))
                            Button(enabled=!busy,onClick={task{catalog=repo.catalog();session=repo.store.get();message="Catalogo aggiornato"}}){Text("Aggiorna catalogo e sessione")}
                            val available=catalog?.optJSONArray("datasets")?.objects()?:emptyList()
                            if(available.isEmpty())Text("Aggiornare il catalogo online per vedere le aree assegnate.")
                            available.forEach{meta->
                                val installed=packs.find{it.id==meta.getString("id")}
                                Card(Modifier.fillMaxWidth().padding(vertical=6.dp)){Column(Modifier.padding(12.dp)){
                                    Text(meta.getString("name"),style=MaterialTheme.typography.titleMedium);Text("Versione "+meta.getString("id"),style=MaterialTheme.typography.bodySmall)
                                    Text("Copertura: "+meta.getJSONArray("coverage"));Text("Dimensione: "+meta.getLong("bytes")+" byte")
                                    Text(when{installed==null->"Da scaricare";installed.baseReady->"Pacchetto locale verificato · base e risorse presenti";else->"Dati locali presenti · base non pronta"})
                                    Button(enabled=!busy,onClick={task{repo.download(meta);repo.downloadHistory(meta.getString("area_id"));message="Pacchetto e storico personale disponibili sul dispositivo"}}){Text(if(installed==null)"Scarica area e storico" else "Aggiorna area e storico")}
                                }}
                            }
                            Text("Le versioni precedenti e i controlli pendenti sono conservati.")
                            packs.filter{old->available.none{it.getString("id")==old.id}}.forEach{Text("Versione conservata: "+it.id)}
                        }
                    }
                    "Account"->{
                        if(BuildConfig.DEMO){
                            Column(Modifier.verticalScroll(rememberScrollState())){
                                Text("Demo autonoma",style=MaterialTheme.typography.headlineMedium)
                                Text("Nessun account, password o server richiesti. Nessuna scadenza di sessione.")
                                Text("Il GPS è reale e non viene simulato. I manufatti e la base sono sintetici: non usare per interventi.")
                                Text("Questa app è separata da Collettori PAT: non modifica i controlli o gli account del pilota.")
                                OutlinedButton(onClick={task{recoveryText=repo.recoveryBundle();exportRecovery.launch("collettori-demo.json")}}){Text("Esporta controlli demo")}
                                Text("Disinstallare questa demo o cancellarne i dati elimina le prove locali.")
                            }
                        }else Column(Modifier.verticalScroll(rememberScrollState())){
                            Text(session!!.getString("username"),style=MaterialTheme.typography.headlineMedium);Text("Ruolo: "+session!!.getString("role"));Text("Server: "+session!!.getString("base"))
                            Text("Collettori PAT · versione 0.1 · build ${BuildConfig.VERSION_CODE}")
                            Text("L'app raccoglie la posizione soltanto su richiesta. Il GPS non verifica apertura o qualità del controllo.",modifier=Modifier.padding(vertical=16.dp))
                            Text("Abilitazione offline: "+shown(repo.store.get()?.getString("offline_until")))
                            Button(onClick={task{catalog=repo.catalog();session=repo.store.get();message="Connessione verificata"}}){Text("Verifica sessione online")}
                            Text("Uscendo, le bozze e gli invii restano riservati a questo account. Per inviarli occorre accedere nuovamente allo stesso server e account.")
                            OutlinedButton(onClick={task{recoveryText=repo.recoveryBundle();exportRecovery.launch("collettori-recupero.json")}}){Text("Esporta lavoro non trasmesso")}
                            OutlinedButton(onClick={task{repo.dao.retryBlocked(account);repo.syncNow();message="Ritentativo richiesto senza modificare i dati originali. I conflitti non risolti verranno nuovamente segnalati."}}){Text("Riprova invii dopo verifica del referente")}
                            Button(onClick={repo.store.clear();session=null;message="Dati locali conservati; invii sospesi"}){Text("Esci / cambia account")}
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable fun InspectionEditor(repo:Repository,visit:Visit,point:JSONObject?,pack:OfflinePackage?,busy:Boolean,message:String,onMessage:(String)->Unit,onBack:()->Unit,onCapture:()->Unit,onComplete:(JSONObject,String)->Unit,onRevise:()->Unit,onHistory:()->Unit){
    var bodyText by remember(visit.id,visit.body){mutableStateOf(visit.body)}
    val body=JSONObject(bodyText);val sheet=body.getJSONObject("sheet")
    val draft=visit.operational=="BOZZA"
    var saveState by remember{mutableStateOf("")}
    var history by remember{mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope();val lock=remember{Mutex()}
    val catalog=remember{mutableStateOf<JSONObject?>(null)}
    LaunchedEffect(visit.id,message){catalog.value=repo.localCatalog();history=repo.dao.settingValue(repo.owner(),"history:"+visit.id)}
    fun change(key:String,value:Any){val next=JSONObject(bodyText);next.getJSONObject("sheet").put(key,value);bodyText=next.toString();saveState="Salvataggio…";scope.launch{lock.withLock{try{repo.saveDraft(visit.id,next);saveState="Bozza salvata sul dispositivo"}catch(e:Exception){saveState="Errore: "+e.message}}}}
    fun changeBody(key:String,value:Any){val next=JSONObject(bodyText).put(key,value);bodyText=next.toString();scope.launch{lock.withLock{try{repo.saveDraft(visit.id,next);saveState="Bozza salvata sul dispositivo"}catch(e:Exception){saveState="Errore: "+e.message}}}}
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState())){
        TextButton(onClick=onBack){Text("← Torna")}
        if(BuildConfig.DEMO)Text("DEMO · scheda locale su dati sintetici",color=MaterialTheme.colorScheme.secondary)
        Text("Pozzetto "+(point?.getString("code")?:visit.manholeId),style=MaterialTheme.typography.headlineMedium)
        Text("Versione dati: "+visit.datasetId,style=MaterialTheme.typography.bodySmall)
        Text(operational(visit.operational)+" · "+syncLabel(visit.sync));Text("Revisione "+body.getInt("revision")+" · "+(visit.receipt?.let{JSONObject(it).optString("review")}?:"Non esaminata"))
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(message.isNotBlank())Text(message)
        Text(saveState,style=MaterialTheme.typography.bodySmall)
        Text("Usa il telefono da posizione sicura, dopo le operazioni delicate. L'apertura è una dichiarazione dell'operatore.",modifier=Modifier.padding(vertical=12.dp))
        val events=body.getJSONArray("events").objects()
        events.forEachIndexed{index,e->
            val ev=e.getJSONObject("local_evaluation")
            Text("Evento ${index+1} · "+shown(e.getString("acquired_at")),style=MaterialTheme.typography.titleSmall)
            Text(GpsRule.label(ev.getString("state")))
            Text("Distanza: ${ev.numberOrNull("distance_m")?.let{"%.1f m".format(it)}?:"—"} · Accuratezza: ${e.numberOrNull("accuracy_m")?.let{"%.1f m".format(it)}?:"—"}",style=MaterialTheme.typography.bodySmall)
            Text("Dispositivo: ${e.numberOrNull("latitude")?:"—"}, ${e.numberOrNull("longitude")?:"—"}",style=MaterialTheme.typography.bodySmall)
            Text(ev.getJSONArray("reasons").toString(),style=MaterialTheme.typography.bodySmall)
        }
        if(draft&&body.getInt("revision")==1)Button(enabled=!busy,onClick={scope.launch{lock.withLock{onCapture()}}}){Text(if(events.isEmpty())"Rileva posizione" else "Rileva nuovamente (nuovo evento)")}
        if(!draft){
            Text("Scheda conservata",style=MaterialTheme.typography.titleLarge)
            sheet.keys().forEach{key->val value=sheet.opt(key);val shownValue=when(value){null,JSONObject.NULL->"Non indicato";true->"Sì";false->"No";else->value.toString()};Text("${fieldLabels[key]?:key}: $shownValue")}
            Text("Completamento: "+shown(body.optString("completed_at")))
            if(visit.sync=="RICEVUTO_SERVER"||(BuildConfig.DEMO&&visit.sync=="DEMO_LOCALE"))Button(onClick=onRevise){Text("Crea revisione motivata")}
            if(visit.sync=="RICEVUTO_SERVER")OutlinedButton(onClick=onHistory){Text("Aggiorna storico dal server")}
            history?.let{Text("Storico scaricato",style=MaterialTheme.typography.titleMedium);JSONObject(it).getJSONArray("revisions").objects().forEach{r->Text("Revisione ${r.getInt("number")} · ${r.getString("review")} · ${r.getString("reason")}")}}
            visit.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }else{
            if(body.getInt("revision")>1)Field("Motivo della revisione",body.optString("revision_reason")){changeBody("revision_reason",it)}
            Text("A · Accessibilità e apertura",style=MaterialTheme.typography.titleLarge)
            val yesNo=listOf("unset" to "Da indicare","true" to "Sì","false" to "No")
            Choice("Manufatto accessibile",(sheet.opt("accessible")?.toString()?:"null").let{if(it=="null")"unset" else it},yesNo){change("accessible",if(it=="unset")JSONObject.NULL else it.toBoolean())}
            Row{Checkbox(sheet.getBoolean("unsafe"),{change("unsafe",it)});Text("Controllo non eseguibile in sicurezza",modifier=Modifier.padding(top=12.dp))}
            Choice("Apertura dichiarata effettuata",(sheet.opt("opened")?.toString()?:"null").let{if(it=="null")"unset" else it},yesNo){change("opened",if(it=="unset")JSONObject.NULL else it.toBoolean())}
            if(sheet.boolOrNull("opened")!=true)Field("Motivo della mancata apertura / impedimento",sheet.getString("no_open_reason")){change("no_open_reason",it)}
            Text("B · Condizioni osservate",style=MaterialTheme.typography.titleLarge)
            val obs=listOf("NON_VERIFICATO" to "Non verificato","REGOLARE" to "Regolare","ANOMALO" to "Anomalo","NON_OSSERVABILE" to "Non osservabile","NON_APPLICABILE" to "Non applicabile")
            listOf("cover","deposits","flow","walls","damage").forEach{k->Choice(fieldLabels[k]!!,sheet.getString(k),obs){change(k,it)}}
            Text("C · Anomalie e attività",style=MaterialTheme.typography.titleLarge)
            Choice("Pulizia eseguita (attività distinta)",(sheet.opt("cleaning")?.toString()?:"null").let{if(it=="null")"unset" else it},yesNo){change("cleaning",if(it=="unset")JSONObject.NULL else it.toBoolean())}
            Field("Descrizione anomalia",sheet.getString("anomaly_note")){change("anomaly_note",it)}
            if(sheet.getString("anomaly_note").isNotBlank()){
                Choice("Priorità proposta",sheet.getString("priority"),listOf("BASSA","MEDIA","ALTA","URGENTE").map{it to it}){change("priority",it)}
                if(sheet.getString("priority")=="URGENTE")Text("Attiva subito il canale operativo previsto. Non attendere la sincronizzazione.",color=MaterialTheme.colorScheme.error)
            }
            Text("D · Richiusura e conclusione",style=MaterialTheme.typography.titleLarge)
            listOf("closure","restored").forEach{k->Choice(fieldLabels[k]!!,sheet.getString(k),obs){change(k,it)}}
            Field("Motivazione eccezione GPS",sheet.getString("exception_reason")){change("exception_reason",it)}
            Row{Checkbox(sheet.getBoolean("map_position_wrong"),{change("map_position_wrong",it)});Text("Posizione cartografica probabilmente errata",modifier=Modifier.padding(top=12.dp))}
            Field("Note finali",sheet.getString("notes")){change("notes",it)}
            val deadlines=catalog.value?.optJSONArray("deadlines")?.objects()?.filter{it.getString("manhole_id")==visit.manholeId}?:emptyList()
            if(deadlines.isEmpty())Text("Periodicità/scadenza non configurata")else Choice("Obbligo a cui associare il controllo",body.optString("deadline_id",""),listOf("" to "Nessuna associazione")+deadlines.map{it.getString("id") to (shown(it.getString("due_at"))+" · "+it.getString("source"))}){changeBody("deadline_id",if(it.isBlank())JSONObject.NULL else it)}
            var technical by remember{mutableStateOf(false)}
            TextButton(onClick={technical=!technical}){Text("E · Informazioni tecniche facoltative")}
            if(technical){Field("Materiali / dimensioni e unità",sheet.getString("technical_value")){change("technical_value",it)};Choice("Origine del dato",sheet.getString("technical_origin"),listOf("NON_NOTO","OSSERVATO","MISURATO","DOCUMENTALE","IPOTIZZATO").map{it to it}){change("technical_origin",it)}}
            listOf("COMPLETO" to "Completa controllo dichiarato","PARZIALE" to "Concludi come parziale","IMPEDITO" to "Registra impedimento").forEach{(state,label)->Button(enabled=!busy,onClick={scope.launch{lock.withLock{onComplete(JSONObject(bodyText),state)}}},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(label)}}
            OutlinedButton(onClick={scope.launch{lock.withLock{try{repo.saveDraft(visit.id,JSONObject(bodyText));onMessage("Bozza salvata sul dispositivo")}catch(e:Exception){onMessage(e.message?:"Salvataggio non riuscito")}}}},modifier=Modifier.fillMaxWidth()){Text("Salva bozza")}
        }
        Spacer(Modifier.height(32.dp))
    }
}
val fieldLabels=mapOf("accessible" to "Accessibilità","opened" to "Apertura dichiarata","cover" to "Botola e telaio","deposits" to "Pulizia, depositi e materiali estranei","flow" to "Deflusso, ristagni e ostruzioni","walls" to "Pareti e canalette","damage" to "Infiltrazioni, radici, corrosione e danni","closure" to "Richiusura","restored" to "Ripristino dell'area","cleaning" to "Pulizia eseguita","notes" to "Note","anomaly_note" to "Anomalia","priority" to "Priorità","exception_reason" to "Eccezione GPS","unsafe" to "Controllo non eseguibile in sicurezza","no_open_reason" to "Motivo della mancata apertura","technical_value" to "Materiali e dimensioni","technical_origin" to "Origine dell'informazione tecnica","map_position_wrong" to "Posizione cartografica probabilmente errata")
