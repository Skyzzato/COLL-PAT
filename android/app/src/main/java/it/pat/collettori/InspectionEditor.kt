package it.pat.collettori

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.os.SystemClock
import org.json.JSONObject

@Composable fun CancelDialog(label:String,onDismiss:()->Unit,onConfirm:(String)->Unit){
    var reason by rememberSaveable{mutableStateOf("Inserimento errato")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Annulla $label")},text={Column{
        Text("Il contenuto originale e la motivazione restano nello storico.")
        Field("Motivazione",reason){reason=it}
    }},confirmButton={TextButton(enabled=reason.trim().length>=3,onClick={onConfirm(reason)}){Text("Conferma annullamento")}},dismissButton={TextButton(onClick=onDismiss){Text("Indietro")}})
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun InspectionEditor(repo:Repository,visit:Visit,point:JSONObject?,collectorLabel:String,busy:Boolean,message:String,onMessage:(String)->Unit,onBack:()->Unit,captureFactory:()->InspectionCapture={LocationCapture(repo.context)}){
    var bodyText by rememberSaveable(visit.id,visit.operational){mutableStateOf(visit.body)}
    val body=JSONObject(bodyText);val sheet=body.getJSONObject("sheet");val draft=visit.operational=="BOZZA"&&!isCancelled(visit)
    DisposableEffect(visit.id){repo.editing.add(visit.id);onDispose{repo.editing.remove(visit.id)}}
    val scope=rememberCoroutineScope();var saveState by remember{mutableStateOf("")};var committing by remember{mutableStateOf(false)}
    var latestWrite by remember{mutableStateOf<kotlinx.coroutines.Job?>(null)}
    var cancelEvent by remember{mutableStateOf<String?>(null)};var cancelVisit by remember{mutableStateOf(false)}
    var changeModel by remember{mutableStateOf<String?>(null)};var showImpediment by rememberSaveable{mutableStateOf(false)}
    var feedback by remember{mutableStateOf<SaveFeedback?>(null)}
    var pendingGps by remember{mutableStateOf<JSONObject?>(null)}
    var gpsDialog by remember{mutableStateOf(false)};var exceptionMode by remember{mutableStateOf(false)}
    var capture by remember{mutableStateOf<InspectionCapture?>(null)};var seconds by remember{mutableIntStateOf(0)}
    val capturing=capture!=null
    val focus=remember{FocusRequester()};val bring=remember{BringIntoViewRequester()};val keyboard=LocalSoftwareKeyboardController.current
    val lifecycle=LocalLifecycleOwner.current
    DisposableEffect(visit.id,lifecycle){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP){capture?.cancel();pendingGps=null;gpsDialog=false;exceptionMode=false}}
        lifecycle.lifecycle.addObserver(observer)
        onDispose{capture?.cancel();pendingGps=null;lifecycle.lifecycle.removeObserver(observer)}
    }
    val saving=busy||committing||capturing||pendingGps!=null||visit.sync=="CONFLICT"
    LaunchedEffect(exceptionMode){if(exceptionMode){yield();bring.bringIntoView();focus.requestFocus();keyboard?.show()}}
    val latestBack by rememberUpdatedState(onBack)
    LaunchedEffect(visit.sync){if(visit.sync in setOf("CONFLICT","RESET_OBSOLETE")){feedback=null;committing=false;capture?.cancel();pendingGps=null;gpsDialog=false;exceptionMode=false}}

    fun persist(text:String){
        bodyText=text;saveState="Salvataggio…"
        val previous=latestWrite
        // Application scope survives leaving the editor; FIFO avoids older notes overtaking new ones.
        latestWrite=repo.writes.launch{previous?.join();try{repo.saveDraft(visit.id,JSONObject(text));saveState="Bozza salvata sul dispositivo"}catch(e:Exception){saveState="Errore salvataggio: ${e.message}"}}
    }
    fun change(key:String,value:Any){val b=JSONObject(bodyText)
        if(key=="unsafe")b.put("sheet",applyUnsafe(b.getJSONObject("sheet"),value as Boolean))
        else {b.getJSONObject("sheet").put(key,value);if(key=="anomaly_note")b.getJSONObject("sheet").put("notes","")}
        persist(b.toString())
    }
    fun leave(){if(feedback!=null){feedback=null;onBack();return};if(committing)return;capture?.cancel();pendingGps=null;gpsDialog=false;exceptionMode=false;committing=true;scope.launch{latestWrite?.join();try{if(draft)repo.saveDraft(visit.id,JSONObject(bodyText));onBack()}catch(e:Exception){onMessage(friendlyError(e))}finally{committing=false}}}
    BackHandler{leave()}
    fun commit(status:String?){
        if(committing)return;committing=true;val submitted=JSONObject(bodyText)
        scope.launch{try{
            val previous=latestWrite
            val saved=repo.writes.async{previous?.join();if(status==null)repo.saveDraft(visit.id,submitted,queueNow=true) else repo.complete(visit.id,submitted,status)}.await()
            onMessage("");feedback=SaveFeedback(saved.id,JSONObject(saved.body).getLong("local_edit"),status==null,SystemClock.elapsedRealtime())
        }catch(e:CancellationException){throw e}catch(e:Exception){committing=false;onMessage(friendlyError(e))}}
    }
    fun startCapture(){
        if(saving||capture!=null||pendingGps!=null||committing)return
        val request=captureFactory();capture=request;seconds=0;onMessage("")
        scope.launch{try{
            latestWrite?.join()
            repo.beginGpsAttempt(visit.id)
            val current=repo.dao.visit(visit.id,visit.owner)?:error("Bozza assente")
            val payload=JSONObject(current.body).put("user_id",repo.store.get()!!.getString("user_id"))
            val settings=repo.fieldSettings()
            val rule=Rule.parse(repo.localCatalog()!!.getJSONObject("rule")).copy(radius=settings.maxDistance,accuracy=settings.maxAccuracy)
            val points=repo.snapshot(current).getJSONArray("points").objects()
            val selected=points.first{it.getString("id")==current.manholeId}
            payload.put("target_latitude",selected.getDouble("latitude")).put("target_longitude",selected.getDouble("longitude"))
            val event=request.acquire(payload,rule){seconds=it}
            event.put("local_evaluation",GpsRule.evaluate(event,selected,points,rule))
            if(accurateEvidence(event)&&event.getJSONObject("local_evaluation").getString("state")=="COMPATIBILE"){
                event.put("match_outcome","VERIFIED");repo.appendEvent(visit.id,event);onMessage("Posizione registrata · stabilizzazione 3 s e campionamento utile di almeno 5 s")
            }else{onMessage("Rilievo non utilizzabile: accuratezza o corrispondenza insufficienti. Riprova, correggi il pozzetto oppure scegli Non rilevare GPS.")}
        }catch(e:CancellationException){onMessage("Rilevazione annullata: nessun evento registrato");throw e}catch(e:Exception){onMessage(friendlyError(e))}finally{capture=null;seconds=0}}
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){granted->if(granted[android.Manifest.permission.ACCESS_FINE_LOCATION]==true)startCapture() else scope.launch{latestWrite?.join();repo.beginGpsAttempt(visit.id);onMessage("Permesso posizione precisa negato. Puoi riprovare o scegliere Non rilevare GPS.")}}
    fun locate(){if(repo.context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED)startCapture() else permission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION))}

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp).verticalScroll(rememberScrollState())){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(enabled=!committing||feedback!=null,onClick={leave()}){Text("← Torna")};IconButton(enabled=!saving,onClick={cancelVisit=true}){Icon(painterResource(R.drawable.ic_trash),"Elimina ispezione")}}
        Text(point?.optString("code")?:visit.manholeId,style=MaterialTheme.typography.headlineMedium)
        Text(point?.optString("description","")?:"Anagrafica storica")
        Text(collectorLabel,style=MaterialTheme.typography.bodyMedium)
        Text("${operational(visit.operational)} · "+if(draft&&visit.sync=="RICEVUTO_SERVER")"Bozza condivisa" else syncLabel(visit.sync),style=MaterialTheme.typography.labelLarge)
        if(draft)Text("Ultima modifica: "+shown(body.optString("updated_at",body.optString("started_at"))),style=MaterialTheme.typography.bodySmall)
        if(isCancelled(visit))Text("ANNULLATA · esclusa dai controlli validi",color=MaterialTheme.colorScheme.error)
        if(saving)LinearProgressIndicator(Modifier.fillMaxWidth())

        if(draft)Text(saveState,style=MaterialTheme.typography.bodySmall)
        val model=body.optString("model","ORDINARY")
        val options=if(point?.optString("asset_type","MANHOLE")=="MANHOLE")listOf("ORDINARY" to "Ordinario","ASPHALT_EXTERNAL" to "Pozzetto sotto asfalto — verifica esterna")else listOf("ASSET_EXTERNAL" to "Verifica esterna del manufatto")
        if(draft&&!committing)Choice("Tipo di controllo",model,options){if(it!=model){capture?.cancel();pendingGps=null;gpsDialog=false;exceptionMode=false;changeModel=it}}
        else Text("Modello: "+when(model){"ASPHALT_EXTERNAL"->"Sotto asfalto · verifica esterna";"ASSET_EXTERNAL"->"Verifica esterna del manufatto";else->"Ordinario"})
        Text("Apertura dichiarata: "+(if(sheet.optBoolean("opened"))"effettuata" else "non effettuata · "+sheet.optString("no_open_reason")))
        Text("Pulizia: "+if(sheet.isNull("cleaning"))"non applicabile" else if(sheet.optBoolean("cleaning"))"eseguita" else "non eseguita")
        if(model!="ORDINARY")Text("Nessuna ispezione interna. La verifica esterna registrata è un controllo periodico previsto.",style=MaterialTheme.typography.bodySmall)
        Text("Rilevazioni GPS",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=12.dp))
        val persisted=JSONObject(visit.body);val events=activeEvents(persisted);val last=lastEvidence(persisted)
        if(last?.getJSONObject("local_evaluation")?.optString("state")!="COMPATIBILE")Row{
            Icon(painterResource(R.drawable.ic_warning),"Attenzione GPS",tint=MaterialTheme.colorScheme.error)
            Text("Prossimità GPS da verificare\n"+(last?.optString("error")?.takeIf{it!="null"&&it.isNotBlank()}?:last?.optJSONObject("local_evaluation")?.optJSONArray("reasons")?.strings()?.joinToString()?:"Nessuna rilevazione attiva"),color=MaterialTheme.colorScheme.error)
        }
        val maxAccuracy=last?.optJSONObject("applied_limits")?.optDouble("max_accuracy_m",15.0)?:15.0
        if(last!=null&&!acceptableMeasure(last.numberOrNull("accuracy_m"),maxAccuracy))Text("Precisione GPS insufficiente: ±${last.numberOrNull("accuracy_m")?:"—"} m · Limite configurato: $maxAccuracy m",color=MaterialTheme.colorScheme.error)
        if(noGpsConfirmed(persisted))Text("GPS non rilevato · "+persisted.getJSONObject("gps_state").getString("reason"),color=MaterialTheme.colorScheme.error)
        events.forEach{event->Row(Modifier.fillMaxWidth()){
            Column(Modifier.weight(1f)){
                Text(shown(event.optString("acquired_at")),style=MaterialTheme.typography.labelMedium)
                if(motivatedGpsException(event))Text("Corrispondenza non verificata — eccezione motivata: "+event.optString("exception_reason"),color=MaterialTheme.colorScheme.error)
                Text(if(event.has("cancelled"))"Annullata · "+event.getJSONObject("cancelled").optString("reason") else GpsRule.label(event.getJSONObject("local_evaluation").getString("state")))
                Text("Distanza dal manufatto: ${event.optJSONObject("local_evaluation")?.numberOrNull("distance_m")?.let{"%.1f m".format(it)}?:"non disponibile"}",style=MaterialTheme.typography.bodySmall)
                Text(precisionText(event),style=MaterialTheme.typography.bodySmall)
                Text("Età della misura: ${event.numberOrNull("age_s")?.let{"%.1f s".format(it)}?:"non disponibile"}",style=MaterialTheme.typography.bodySmall)
            }
            if(!event.has("cancelled")&&!isCancelled(visit))IconButton(enabled=!saving,onClick={cancelEvent=event.getString("id")}){Icon(painterResource(R.drawable.ic_trash),"Annulla rilevazione GPS")}
        }}
        if(persisted.optBoolean("evidence_rectified"))Text("Rilevazione aggiornata",color=MaterialTheme.colorScheme.error)
        if(draft)OutlinedButton(enabled=!saving,onClick={locate()}){ActionIcon(R.drawable.ic_target);Text("Rileva posizione")}
        if(draft&&!capturing&&persisted.optJSONObject("gps_state")?.optString("status")=="ATTEMPTED")OutlinedButton(enabled=!committing,onClick={gpsDialog=true}){Text("Non rilevare GPS")}
        if(capturing){
            Text(if(seconds<3)"Stabilizzazione · ${3-seconds} secondi" else "Campionamento · ${seconds-3} secondi utili")
            Text(capture?.status.orEmpty())
            LinearProgressIndicator(progress={(seconds/8f).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
            TextButton(onClick={capture?.cancel()}){Text("Interrompi rilevazione GPS")}
        }
        if(draft){
            TextButton(onClick={repo.context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+repo.context.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))}){Text("Impostazioni permessi posizione")}
            val obs=listOf("REGOLARE" to "Regolare / assenza di criticità","ANOMALO" to "Anomalia","NON_OSSERVABILE" to "Non osservabile","NON_APPLICABILE" to "Non applicabile")
            if(model=="ORDINARY"){
                var expanded by rememberSaveable{mutableStateOf(false)}
                Text(if(hasAnomaly(body))"Anomalie presenti" else "Controlli preimpostati regolari",style=MaterialTheme.typography.titleMedium)
                TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Riduci controlli" else "Controlli e dichiarazioni")}
                if(expanded){
                    listOf("accessible" to "Manufatto accessibile","opened" to "Apertura effettuata").forEach{(key,label)->InfoCheck(label,sheet.optBoolean(key),!committing){change(key,it)}}
                    Repository.observationKeys.forEach{k->InfoChoice(fieldLabels[k]?:k,sheet.optString(k),obs){change(k,it)}}
                    listOf("cleaning" to "Pulizia eseguita","unsafe" to "Controllo non eseguibile in sicurezza").forEach{(key,label)->InfoCheck(label,sheet.optBoolean(key),!committing){change(key,it)}}
                    if(!sheet.optBoolean("opened"))Field("Motivo mancata apertura",sheet.optString("no_open_reason")){change("no_open_reason",it)}
                }
            }else if(model=="ASPHALT_EXTERNAL"){
                externalKeys.forEach{k->InfoChoice(fieldLabels[k]?:k,sheet.optString(k),obs.take(2)){change(k,it)}}
                listOf("raise_needed" to "Necessità di rimessa in quota","road_repair_needed" to "Necessità di ripristino stradale").forEach{(key,label)->InfoCheck(label,sheet.optBoolean(key),!committing){change(key,it)}}
            }else Text("Verifica esterna del manufatto; descrivi quanto osservato in note e anomalie. Nessuna checklist impiantistica precompilata.")
            InfoLabel("Note / Anomalie")
            Field("Note / Anomalie",unifiedNotes(sheet)){change("anomaly_note",it)}
        }else{
            Text("Anomalie: "+if(hasAnomaly(body))sheet.optString("anomaly_note") else "nessuna dichiarata")
            Text("Note: "+sheet.optString("notes"));Text("Eseguita: "+shown(body.optString("completed_at",body.optString("started_at"))))
            if(visit.operational=="IMPEDITO")Text("Motivo: "+sheet.optString("impediment_reason",sheet.optString("no_open_reason")))
            Text("Controllo periodico: "+if(periodic(visit))"valido" else "non conteggiato")
            var full by remember{mutableStateOf(false)};TextButton(onClick={full=!full}){Text("Dettaglio dichiarazioni")}
            if(full)sheet.keys().forEach{key->Text("${fieldLabels[key]?:key}: ${sheet.opt(key)}")}
        }
        if(!committing)PhotoPanel(repo,visit)
        if(draft){
            OutlinedButton(enabled=!saving,onClick={commit(null)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){ActionIcon(R.drawable.ic_edit);Text("Salva bozza")}
            Button(enabled=!saving&&inspectionLocationReady(persisted),onClick={commit("COMPLETO")},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){ActionIcon(R.drawable.ic_check);Text("Registra Ispezione")}
            OutlinedButton(enabled=!saving,onClick={showImpediment=true},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){ActionIcon(R.drawable.ic_warning);Text("Registra impedimento")}
        }
        visit.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(visit.sync=="CONFLICT")OutlinedButton(onClick={scope.launch{try{repo.reloadConflict(visit.id);onBack()}catch(e:Exception){onMessage(friendlyError(e))}}}){Text("Conserva copia locale e apri versione server")}
        Spacer(Modifier.height(24.dp))
    }
    if(gpsDialog){
        var reason by rememberSaveable{mutableStateOf("")}
        AlertDialog(onDismissRequest={gpsDialog=false},title={Text("Non rilevare GPS")},text={Field("Motivazione non rilievo GPS",reason){reason=it}},
            confirmButton={TextButton(enabled=reason.isNotBlank()&&!committing,onClick={scope.launch{try{latestWrite?.join();repo.recordNoGps(visit.id,reason);gpsDialog=false}catch(e:Exception){onMessage(friendlyError(e))}}}){Text("OK")}},
            dismissButton={TextButton(onClick={gpsDialog=false}){Text("ANNULLA")}})
    }
    feedback?.let{saved->
        var photoPending by remember{mutableStateOf(false)}
        LaunchedEffect(visit.body,visit.sync){photoPending=repo.authenticated()&&PhotoRepository(repo).list(visit).any{it.optString("uploadStatus")!="UPLOADED"}}
        AlertDialog(onDismissRequest={leave()},title={Text(saved.title(visit))},text={Column{Text(saved.detail(visit));if(photoPending)Text("Fotografie: caricamento ancora in coda")}},confirmButton={TextButton(onClick={leave()}){Text("Chiudi")}})
    }
    changeModel?.let{model->AlertDialog(onDismissRequest={changeModel=null},title={Text("Cambia modello")},text={Text("I controlli vengono reimpostati per il nuovo modello. Note, anomalie descritte e foto restano conservate.")},confirmButton={TextButton(onClick={scope.launch{try{latestWrite?.join();val saved=repo.changeTemplate(visit.id,JSONObject(bodyText),model);bodyText=saved.body;changeModel=null}catch(e:Exception){onMessage(friendlyError(e))}}}){Text("Cambia modello")}},dismissButton={TextButton(onClick={changeModel=null}){Text("Indietro")}})}
    if(showImpediment)AlertDialog(onDismissRequest={showImpediment=false},title={Text("Registra impedimento")},text={Column{Text("Documenta il tentativo non eseguito; non vale come controllo periodico.");Field("Motivo",sheet.optString("impediment_reason")){change("impediment_reason",it)}}},confirmButton={TextButton(enabled=sheet.optString("impediment_reason").isNotBlank()&&!saving,onClick={showImpediment=false;commit("IMPEDITO")}){Text("Registra impedimento")}},dismissButton={TextButton(onClick={showImpediment=false}){Text("Indietro")}})
    if(cancelVisit)PermanentDeleteDialog(repo,"inspection",visit.id,"Ispezione · "+(point?.optString("code")?:visit.id),{cancelVisit=false},{cancelVisit=false;onBack()},onMessage)
    if(cancelEvent!=null)CancelDialog("rilevazione",{cancelEvent=null}){reason->val event=cancelEvent;scope.launch{try{latestWrite?.join();repo.cancel(visit.id,event,reason);onMessage("Rilevazione rettificata")}catch(e:Exception){onMessage(friendlyError(e))}};cancelEvent=null}
}
val fieldLabels=mapOf("cover" to "Botola e telaio","deposits" to "Depositi e materiali estranei","flow" to "Deflusso","walls" to "Pareti e canalette","damage" to "Danni, infiltrazioni e radici","closure" to "Richiusura","restored" to "Ripristino area","surface" to "Condizioni del manto","subsidence" to "Avvallamenti / cedimenti","accessible" to "Accessibile","opened" to "Apertura effettuata","cleaning" to "Pulizia","unsafe" to "Condizioni non sicure","raise_needed" to "Rimessa in quota necessaria","road_repair_needed" to "Ripristino stradale necessario","impediment_reason" to "Motivo impedimento","exception_reason" to "Motivazione GPS")
