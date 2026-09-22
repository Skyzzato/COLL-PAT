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
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable fun CancelDialog(label:String,onDismiss:()->Unit,onConfirm:(String)->Unit){
    var reason by rememberSaveable{mutableStateOf("Inserimento errato")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Annulla $label")},text={Column{
        Text("Il contenuto originale e la motivazione restano nello storico.")
        Field("Motivazione",reason){reason=it}
    }},confirmButton={TextButton(enabled=reason.trim().length>=3,onClick={onConfirm(reason)}){Text("Conferma annullamento")}},dismissButton={TextButton(onClick=onDismiss){Text("Indietro")}})
}

@Composable fun InspectionEditor(repo:Repository,visit:Visit,point:JSONObject?,collectorLabel:String,busy:Boolean,message:String,onMessage:(String)->Unit,onBack:()->Unit,onCapture:()->Unit,capturing:Boolean,onCancelCapture:()->Unit){
    var bodyText by rememberSaveable(visit.id,visit.operational){mutableStateOf(visit.body)}
    val body=JSONObject(bodyText);val sheet=body.getJSONObject("sheet");val draft=visit.operational=="BOZZA"&&!isCancelled(visit)
    DisposableEffect(visit.id){repo.editing.add(visit.id);onDispose{repo.editing.remove(visit.id)}}
    val scope=rememberCoroutineScope();var saveState by remember{mutableStateOf("")};var committing by remember{mutableStateOf(false)}
    var latestWrite by remember{mutableStateOf<kotlinx.coroutines.Job?>(null)}
    var cancelEvent by remember{mutableStateOf<String?>(null)};var cancelVisit by remember{mutableStateOf(false)}
    var changeModel by remember{mutableStateOf<String?>(null)};var showImpediment by rememberSaveable{mutableStateOf(false)}
    val saving=busy||committing||visit.sync=="CONFLICT"
    fun persist(text:String){
        bodyText=text;saveState="Salvataggio…"
        val previous=latestWrite
        // Application scope survives leaving the editor; FIFO avoids older notes overtaking new ones.
        latestWrite=repo.writes.launch{previous?.join();try{repo.saveDraft(visit.id,JSONObject(text));saveState="Bozza salvata sul dispositivo"}catch(e:Exception){saveState="Errore salvataggio: ${e.message}"}}
    }
    fun change(key:String,value:Any){val b=JSONObject(bodyText);b.getJSONObject("sheet").put(key,value);persist(b.toString())}
    fun leave(){if(committing)return;committing=true;scope.launch{latestWrite?.join();try{if(draft)repo.saveDraft(visit.id,JSONObject(bodyText));onBack()}catch(e:Exception){onMessage(friendlyError(e))}finally{committing=false}}}
    BackHandler{leave()}
    fun commit(status:String?){
        if(committing)return;committing=true;val submitted=JSONObject(bodyText)
        scope.launch{try{latestWrite?.join();if(status==null){repo.saveDraft(visit.id,submitted);onMessage("Bozza salvata · verrà condivisa alla sincronizzazione")}
            else{repo.complete(visit.id,submitted,status);onMessage("Registrazione accodata · in attesa della ricevuta server")}
        }catch(e:Exception){onMessage(friendlyError(e))}finally{committing=false}}
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState())){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(enabled=!committing,onClick={leave()}){Text("← Torna")};IconButton(onClick={cancelVisit=true}){Icon(painterResource(R.drawable.ic_trash),"Annulla ispezione")}}
        Text(point?.optString("code")?:visit.manholeId,style=MaterialTheme.typography.headlineMedium)
        Text(point?.optString("description","")?:"Anagrafica storica")
        Text(collectorLabel,style=MaterialTheme.typography.bodyMedium)
        Text("${operational(visit.operational)} · "+if(draft&&visit.sync=="RICEVUTO_SERVER")"Bozza condivisa" else syncLabel(visit.sync),style=MaterialTheme.typography.labelLarge)
        if(draft)Text("Ultima modifica: "+shown(body.optString("updated_at",body.optString("started_at"))),style=MaterialTheme.typography.bodySmall)
        if(isCancelled(visit))Text("ANNULLATA · esclusa dai controlli validi",color=MaterialTheme.colorScheme.error)
        if(saving)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(message.isNotBlank())Text(message,color=if(message.contains("accodata")||message.contains("salvata"))ConfirmedGreen else MaterialTheme.colorScheme.error)
        if(draft)Text(saveState,style=MaterialTheme.typography.bodySmall)
        val model=body.optString("model","ORDINARY")
        val options=if(point?.optString("asset_type","MANHOLE")=="MANHOLE")listOf("ORDINARY" to "Ordinario","ASPHALT_EXTERNAL" to "Pozzetto sotto asfalto — verifica esterna")else listOf("ASSET_EXTERNAL" to "Verifica esterna del manufatto")
        if(draft&&!committing)Choice("Tipo di controllo",model,options){if(it!=model)changeModel=it}
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
        if(draft&&gpsQuality(last)!="RELIABLE")Text("Acquisisci un GPS affidabile entro la distanza ammessa per registrare l’ispezione.",style=MaterialTheme.typography.bodySmall)
        events.forEach{event->Row(Modifier.fillMaxWidth()){
            Column(Modifier.weight(1f)){
                Text(shown(event.optString("acquired_at")),style=MaterialTheme.typography.labelMedium)
                Text(if(event.has("cancelled"))"Annullata · "+event.getJSONObject("cancelled").optString("reason") else GpsRule.label(event.getJSONObject("local_evaluation").getString("state")))
                Text("Distanza dal manufatto: ${event.optJSONObject("local_evaluation")?.numberOrNull("distance_m")?.let{"%.1f m".format(it)}?:"non disponibile"}",style=MaterialTheme.typography.bodySmall)
                Text(precisionText(event),style=MaterialTheme.typography.bodySmall)
                Text("Età della misura: ${event.numberOrNull("age_s")?.let{"%.1f s".format(it)}?:"non disponibile"}",style=MaterialTheme.typography.bodySmall)
            }
            if(!event.has("cancelled")&&!isCancelled(visit))IconButton(enabled=!saving,onClick={cancelEvent=event.getString("id")}){Icon(painterResource(R.drawable.ic_trash),"Annulla rilevazione GPS")}
        }}
        if(persisted.optBoolean("evidence_rectified"))Text("Rilevazione aggiornata",color=MaterialTheme.colorScheme.error)
        if(draft)OutlinedButton(enabled=!saving,onClick={scope.launch{latestWrite?.join();onCapture()}}){ActionIcon(R.drawable.ic_target);Text("Rileva posizione")}
        if(capturing)TextButton(onClick=onCancelCapture){Text("Interrompi rilevazione GPS")}
        if(draft){
            TextButton(onClick={repo.context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+repo.context.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))}){Text("Impostazioni permessi posizione")}
            val obs=listOf("REGOLARE" to "Regolare / assenza di criticità","ANOMALO" to "Anomalia","NON_OSSERVABILE" to "Non osservabile","NON_APPLICABILE" to "Non applicabile")
            if(model=="ORDINARY"){
                var expanded by rememberSaveable{mutableStateOf(false)}
                Text(if(hasAnomaly(body))"Anomalie presenti" else "Controlli preimpostati regolari",style=MaterialTheme.typography.titleMedium)
                TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Riduci controlli" else "Controlli e dichiarazioni")}
                if(expanded){
                    listOf("accessible" to "Manufatto accessibile","opened" to "Apertura effettuata","cleaning" to "Pulizia eseguita","unsafe" to "Controllo non eseguibile in sicurezza").forEach{(key,label)->Row{Checkbox(sheet.optBoolean(key),{change(key,it)},enabled=!committing);Text(label,Modifier.padding(top=12.dp))}}
                    Repository.observationKeys.forEach{k->Choice(fieldLabels[k]?:k,sheet.optString(k),obs){change(k,it)}}
                    if(!sheet.optBoolean("opened"))Field("Motivo mancata apertura",sheet.optString("no_open_reason")){change("no_open_reason",it)}
                }
            }else if(model=="ASPHALT_EXTERNAL"){
                externalKeys.forEach{k->Choice(fieldLabels[k]?:k,sheet.optString(k),obs.take(2)){change(k,it)}}
                listOf("raise_needed" to "Necessità di rimessa in quota","road_repair_needed" to "Necessità di ripristino stradale").forEach{(key,label)->Row{Checkbox(sheet.optBoolean(key),{change(key,it)});Text(label,Modifier.padding(top=12.dp))}}
            }else Text("Verifica esterna del manufatto; descrivi quanto osservato in note e anomalie. Nessuna checklist impiantistica precompilata.")
            Field("Anomalie / necessità di intervento",sheet.optString("anomaly_note")){change("anomaly_note",it)}
            Field("Note",sheet.optString("notes")){change("notes",it)}
            if(last?.getJSONObject("local_evaluation")?.optString("state")!="COMPATIBILE")Field("Motivazione eccezione GPS",sheet.optString("exception_reason")){change("exception_reason",it)}
        }else{
            Text("Anomalie: "+if(hasAnomaly(body))sheet.optString("anomaly_note") else "nessuna dichiarata")
            Text("Note: "+sheet.optString("notes"));Text("Eseguita: "+shown(body.optString("completed_at",body.optString("started_at"))))
            if(visit.operational=="IMPEDITO")Text("Motivo: "+sheet.optString("impediment_reason",sheet.optString("no_open_reason")))
            Text("Controllo periodico: "+if(periodic(visit))"valido" else "non conteggiato")
            var full by remember{mutableStateOf(false)};TextButton(onClick={full=!full}){Text("Dettaglio dichiarazioni")}
            if(full)sheet.keys().forEach{key->Text("${fieldLabels[key]?:key}: ${sheet.opt(key)}")}
        }
        PhotoPanel(repo,visit)
        if(draft){
            OutlinedButton(enabled=!saving,onClick={commit(null)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){ActionIcon(R.drawable.ic_edit);Text("Salva bozza")}
            Button(enabled=!saving&&gpsQuality(last)=="RELIABLE",onClick={commit("COMPLETO")},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){ActionIcon(R.drawable.ic_check);Text("Registra Ispezione")}
            OutlinedButton(enabled=!saving,onClick={showImpediment=true},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){ActionIcon(R.drawable.ic_warning);Text("Registra impedimento")}
        }
        visit.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(visit.sync=="CONFLICT")OutlinedButton(onClick={scope.launch{try{repo.reloadConflict(visit.id);onBack()}catch(e:Exception){onMessage(friendlyError(e))}}}){Text("Conserva copia locale e apri versione server")}
        Spacer(Modifier.height(24.dp))
    }
    changeModel?.let{model->AlertDialog(onDismissRequest={changeModel=null},title={Text("Cambia modello")},text={Text("I controlli vengono reimpostati per il nuovo modello. Note, anomalie descritte e foto restano conservate.")},confirmButton={TextButton(onClick={persist(applyTemplate(JSONObject(bodyText),model).toString());changeModel=null}){Text("Cambia modello")}},dismissButton={TextButton(onClick={changeModel=null}){Text("Indietro")}})}
    if(showImpediment)AlertDialog(onDismissRequest={showImpediment=false},title={Text("Registra impedimento")},text={Column{Text("Documenta il tentativo non eseguito; non vale come controllo periodico.");Field("Motivo",sheet.optString("impediment_reason")){change("impediment_reason",it)}}},confirmButton={TextButton(enabled=sheet.optString("impediment_reason").isNotBlank()&&!saving,onClick={showImpediment=false;commit("IMPEDITO")}){Text("Registra impedimento")}},dismissButton={TextButton(onClick={showImpediment=false}){Text("Indietro")}})
    if(cancelVisit||cancelEvent!=null)CancelDialog(if(cancelVisit)"ispezione" else "rilevazione",{cancelVisit=false;cancelEvent=null}){reason->val event=if(cancelVisit)null else cancelEvent;scope.launch{try{latestWrite?.join();repo.cancel(visit.id,event,reason);onMessage("Elemento rimosso")}catch(e:Exception){onMessage(friendlyError(e))}};cancelVisit=false;cancelEvent=null}
}
val fieldLabels=mapOf("cover" to "Botola e telaio","deposits" to "Depositi e materiali estranei","flow" to "Deflusso","walls" to "Pareti e canalette","damage" to "Danni, infiltrazioni e radici","closure" to "Richiusura","restored" to "Ripristino area","surface" to "Condizioni del manto","subsidence" to "Avvallamenti / cedimenti","accessible" to "Accessibile","opened" to "Apertura effettuata","cleaning" to "Pulizia","unsafe" to "Condizioni non sicure","raise_needed" to "Rimessa in quota necessaria","road_repair_needed" to "Ripristino stradale necessario","impediment_reason" to "Motivo impedimento","exception_reason" to "Motivazione GPS")
