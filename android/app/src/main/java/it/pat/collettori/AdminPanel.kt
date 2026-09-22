package it.pat.collettori

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.util.UUID

@Composable fun AccountPanel(repo:Repository,onChange:()->Unit){
    var expanded by remember{mutableStateOf(repo.store.get()==null)};var url by rememberSaveable{mutableStateOf("https://zzipvrnhndigepufhkcj.supabase.co")}
    var publicKey by rememberSaveable{mutableStateOf("")};var project by rememberSaveable{mutableStateOf(AppSpec.LOCAL_PROJECT)};var email by rememberSaveable{mutableStateOf("")};var password by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")};val scope=rememberCoroutineScope()
    Column(Modifier.padding(vertical=12.dp)){
        TextButton(onClick={expanded=!expanded}){Text("Account · "+if(repo.authenticated())repo.store.get()!!.optString("username") else "configura Supabase Auth")}
        if(expanded){
            Text("Accedi prima di creare ispezioni da inviare. Ogni account/progetto conserva un archivio separato; il lavoro locale precedente non viene attribuito a un altro autore.",style=MaterialTheme.typography.bodySmall)
            if(repo.authenticated()){
                Text("Ruolo server: "+repo.store.get()!!.optString("role"))
                OutlinedButton(onClick={repo.store.clear();onChange()}){Text("Esci / cambia account")}
            }else{
                Field("URL progetto Supabase",url){url=it};Field("Chiave pubblica publishable / anon",publicKey){publicKey=it};Field("UUID progetto applicativo",project){project=it};Field("Email account Auth",email){email=it}
                OutlinedTextField(password,{password=it},label={Text("Password Auth")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                Button(enabled=!busy,onClick={busy=true;scope.launch{try{repo.api.login(url,publicKey,email,password,project);password="";repo.prepareWorkspace();repo.reconcile();repo.dao.resumeAuth(repo.owner());repo.syncNow();onChange()}catch(e:Exception){message=e.message?:"Accesso non riuscito"}finally{busy=false}}}){Text("Accedi al server")}
                Text("admin/admin sblocca soltanto la dashboard locale; non è una password Supabase.",style=MaterialTheme.typography.bodySmall)
            }
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth());if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.error)
        }
    }
}

@Composable fun AdminPanel(repo:Repository,catalog:List<CatalogItem>,visitCount:Int,onMessage:(String)->Unit){
    if(!BuildConfig.DEV_ADMIN)return
    var expanded by rememberSaveable{mutableStateOf(false)};var unlocked by remember{mutableStateOf(false)};var user by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var form by remember{mutableStateOf<JSONObject?>(null)}
    var archive by remember{mutableStateOf<CatalogItem?>(null)};var reset by remember{mutableStateOf(false)};var resetText by remember{mutableStateOf("")};var resetStatus by remember{mutableStateOf<JSONObject?>(null)}
    var import by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
    fun task(block:suspend()->Unit){if(busy)return;busy=true;scope.launch{try{block()}catch(e:Exception){onMessage(e.message?:"Operazione amministrativa non riuscita")}finally{busy=false}}}
    TextButton(onClick={expanded=!expanded}){Text("Admin dashboard")}
    if(expanded)Column{
        Text("Dashboard temporanea di sviluppo. Sblocco locale admin/admin. Ogni scrittura server verifica il ruolo amministratore Auth.")
        if(!unlocked){Field("Utente locale",user){user=it};OutlinedTextField(password,{password=it},label={Text("Password locale")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth());Button(onClick={if(user=="admin"&&password=="admin"){unlocked=true;password=""}else onMessage("Sblocco locale non valido")}){Text("Sblocca dashboard")}}
        else{
            Text(if(repo.authenticated())"Ruolo Auth: "+repo.store.get()!!.optString("role") else "Modifiche locali in attesa di sincronizzazione; Account Auth non configurato")
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick={form=collectorDefaults(UUID.randomUUID().toString(),"","")}){Text("Nuovo collettore")}
            catalog.filter{it.kind=="collector"}.forEach{item->val c=JSONObject(item.body)
                Row{TextButton(modifier=Modifier.weight(1f),onClick={form=JSONObject(item.body)}){Text(c.getString("description")+" · "+c.getString("code")+if(c.optBoolean("archived"))" (archiviato)" else "")};IconButton(enabled=!busy&&!c.optBoolean("archived"),onClick={archive=item}){Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_trash),"Archivia collettore")}}
            }
            OutlinedButton(onClick={import=true}){Text("Importa shapefile dal telefono")}
            OutlinedButton(enabled=!busy,onClick={task{repo.publishSeed();onMessage("Anagrafica sintetica accodata; attendere ricevuta server")}}){Text("Pubblica dati sintetici locali")}
            OutlinedButton(enabled=!busy,onClick={task{resetStatus=repo.reconcile();resetText="";reset=true}}){Text("Azzera tutte le ispezioni")}
            Text("Locali nell'account: $visitCount. Il reset richiede rete, backup privato e permessi amministrativi reali.",style=MaterialTheme.typography.bodySmall)
        }
    }
    form?.let{c->CollectorForm(c,{form=null}){next->task{repo.saveCatalog(listOf(CatalogItem(repo.owner(),next.getString("id"),"collector",next.toString())));form=null;onMessage("Anagrafica salvata localmente · accodata al server")}}}
    archive?.let{c->AlertDialog(onDismissRequest={archive=null},title={Text("Elimina / archivia collettore")},text={Text("Il collettore viene archiviato. Manufatti condivisi, geometrie e ispezioni restano conservati; il codice rimane riservato allo stesso UUID.")},confirmButton={TextButton(enabled=!busy,onClick={task{repo.archiveCollector(c.id);archive=null;onMessage("Collettore archiviato; storico conservato")}}){Text("Conferma archiviazione")}},dismissButton={TextButton(onClick={archive=null}){Text("Indietro")}})}
    if(reset)AlertDialog(onDismissRequest={if(!busy)reset=false},title={Text("Azzera tutte le ispezioni")},text={Column{Text("Progetto: ${repo.project()}\nServer: ${resetStatus?.optInt("inspection_count")} ispezioni\nLocali account: $visitCount\nSi conserva un backup privato sul telefono. Anagrafica e account rimangono intatti.");Field("Digita AZZERA",resetText){resetText=it}}},confirmButton={TextButton(enabled=resetText=="AZZERA"&&!busy,onClick={task{val result=repo.reset(resetText);reset=false;onMessage("Reset ricevuto: ${result.getInt("count")} ispezioni, generazione ${result.getLong("generation")}")}}){Text("Azzera adesso")}},dismissButton={TextButton(enabled=!busy,onClick={reset=false}){Text("Indietro")}})
    if(import)ImportDialog(repo,catalog,{import=false},onMessage)
}

@Composable fun CollectorForm(initial:JSONObject,onDismiss:()->Unit,onSave:(JSONObject)->Unit){
    var code by remember{mutableStateOf(initial.getString("code"))};var description by remember{mutableStateOf(initial.getString("description"))};var type by remember{mutableStateOf(initial.getString("type"))}
    var first by remember{mutableStateOf(initial.getInt("visits_h1").toString())};var second by remember{mutableStateOf(initial.getInt("visits_h2").toString())};var hours by remember{mutableStateOf(initial.getDouble("hours_km_visit").toString().replace('.',','))}
    var length by remember{mutableStateOf(initial.numberOrNull("length_m")?.toString()?.replace('.',',')?:"")};var source by remember{mutableStateOf(initial.getString("length_source"))};var complete by remember{mutableStateOf(initial.optBoolean("length_complete"))};var error by remember{mutableStateOf("")}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)){
        Text("Anagrafica collettore",style=MaterialTheme.typography.headlineSmall);Text("UUID stabile: "+initial.getString("id"),style=MaterialTheme.typography.labelSmall)
        Field("Codice obbligatorio",code){code=it};Field("Descrizione obbligatoria",description){description=it};Choice("Tipologia",type,collectorTypes.map{it to it}){type=it}
        Field("Numero visite 1° semestre",first){first=it};Field("Numero visite 2° semestre",second){second=it};Field("Ore per km per visita",hours){hours=it}
        Field("Lunghezza in metri (vuoto = sconosciuta)",length){length=it;source=if(it.isBlank())"UNAVAILABLE" else "DECLARED"}
        Text("Origine: $source");Row{Checkbox(complete,{complete=it});Text("Rete completa / totale dichiarato",Modifier.padding(top=12.dp))}
        Text("Obiettivi semestrali; le ispezioni dei singoli manufatti non sono automaticamente visite complete del collettore.")
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        Button(onClick={try{
            val a=first.toIntOrNull()?:error("Visite: interi non negativi");val b=second.toIntOrNull()?:error("Visite: interi non negativi");val h=decimalItalian(hours)?:error("Ore: numero non negativo")
            val l=if(length.isBlank())null else decimalItalian(length)?:error("Lunghezza non valida")
            val next=JSONObject(initial.toString()).put("code",code.trim()).put("description",description.trim()).put("type",type).put("visits_h1",a).put("visits_h2",b).put("hours_km_visit",h).put("length_m",l?:JSONObject.NULL).put("length_source",if(l==null)"UNAVAILABLE" else source).put("length_complete",complete)
            validateCollector(next);onSave(next)
        }catch(e:Exception){error=e.message?:"Campi non validi"}}){Text("Salva anagrafica")};TextButton(onClick=onDismiss){Text("Indietro")}
    }}}
}
