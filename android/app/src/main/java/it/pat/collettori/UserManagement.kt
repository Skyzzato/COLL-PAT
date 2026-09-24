package it.pat.collettori

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import org.json.JSONObject

fun userManagementError(e:Exception):String=when {
    e is ApiError&&e.message=="Ultimo amministratore: abilitare prima un altro amministratore" -> "Ultimo amministratore: abilita prima un altro amministratore."
    e is ApiError&&e.code==409 -> "Il livello è cambiato sul server. Ricarica l’elenco prima di riprovare."
    e is java.io.IOException&&e !is ApiError || e is ApiError&&e.retryable -> "Esito online non verificabile. Nessun cambio di permesso viene accodato: ricarica l’elenco quando la connessione è disponibile."
    else -> friendlyError(e)
}

suspend fun Repository.projectUsers(query:String,after:String?):JSONObject {
    check(canManageUsers()){"Gestione utenti riservata all’amministratore reale"}
    return api.rpc("coll_pat_users",JSONObject().put("p_project",project()).put("p_query",query).put("p_after",after?:JSONObject.NULL),owner())
}
suspend fun Repository.changeUserRole(user:JSONObject,role:String,enabled:Boolean):JSONObject {
    requireWrite(admin=true);check(canManageUsers())
    val account=owner()
    val result=api.rpc("coll_pat_set_role",JSONObject().put("p_project",project()).put("p_user",user.getString("id"))
        .put("p_role",role).put("p_expected",user.optString("role").takeUnless{it.isBlank()||it=="null"}?:JSONObject.NULL).put("p_enabled",enabled),account)
    // The server reply is authoritative even when the actor has just disabled their own membership.
    if(user.getString("id")==store.get()?.optString("user_id"))store.get()?.let{store.save(it.put("role",result.optString("role").takeUnless{v->v=="null"}?:""));simulatedRole()}
    return result
}

@Composable fun UserManagementDialog(repo:Repository,dismiss:()->Unit){
    val accessRole=repo.observedRole()
    if(accessRole!="admin"||!repo.canManageUsers()){LaunchedEffect(accessRole){dismiss()};return}
    var query by remember{mutableStateOf("")};var rows by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var next by remember{mutableStateOf<String?>(null)};var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
    var selected by remember{mutableStateOf<JSONObject?>(null)};var role by remember{mutableStateOf("viewer")};var enabled by remember{mutableStateOf(true)}
    var confirm by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
    suspend fun load(after:String?=null){busy=true;try{val page=repo.projectUsers(query,after);rows=(if(after==null)emptyList()else rows)+page.getJSONArray("items").objects();next=page.optString("next").takeUnless{it.isBlank()||it=="null"};error=""}finally{busy=false}}
    LaunchedEffect(query){delay(300);try{load()}catch(e:CancellationException){throw e}catch(e:Exception){error=userManagementError(e)}}
    Dialog(onDismissRequest={if(!busy)dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){Column(Modifier.padding(16.dp)){
        Text("Gestione utenti",style=MaterialTheme.typography.headlineSmall)
        Text("Membri e richieste esplicite per questo progetto COLL-PAT. Le registrazioni senza appartenenza verificabile non sono incluse.",style=MaterialTheme.typography.bodySmall)
        Field("Cerca nome o email",query){query=it}
        TextButton(enabled=!busy,onClick={scope.launch{try{load()}catch(e:CancellationException){throw e}catch(e:Exception){error=userManagementError(e)}}}){Text("Ricarica elenco")}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f)){items(rows,key={it.getString("id")}){u->Card(Modifier.fillMaxWidth().padding(vertical=4.dp)){Column(Modifier.padding(12.dp)){
            Text(u.optString("name").ifBlank{"Nome non disponibile"});Text(u.optString("email"))
            Text(if(u.optString("state")=="ENABLED")roleLabel(u.optString("role"))+" · Abilitato" else if(u.optString("state")=="PENDING")"In attesa di abilitazione" else "Disabilitato")
            TextButton(enabled=!busy,onClick={selected=u;role=u.optString("role").takeIf{it in setOf("viewer","inspector","admin")}?:"viewer";enabled=u.optString("state")=="ENABLED"}){Text("Modifica abilitazione")}
        }}}
        if(next!=null)item{TextButton(enabled=!busy,onClick={scope.launch{try{load(next)}catch(e:CancellationException){throw e}catch(e:Exception){error=userManagementError(e)}}}){Text("Carica altri utenti")}}
        }
        TextButton(enabled=!busy,onClick=dismiss){Text("Chiudi")}
    }}}
    selected?.let{u->AlertDialog(onDismissRequest={if(!busy)selected=null},title={Text(if(confirm)"Conferma abilitazione" else "Modifica utente")},text={Column{
        Text(u.optString("name")+" · "+u.optString("email"))
        Text("Livello precedente: "+if(u.optString("state")=="ENABLED")roleLabel(u.optString("role"))else "Non abilitato")
        if(confirm)Text("Nuovo livello: "+if(enabled)roleLabel(role)else "Disabilitato") else {
            Choice("Nuovo livello",role,listOf("viewer","inspector","admin").map{it to roleLabel(it)}){role=it}
            Row{Switch(enabled,{enabled=it});Text("Abilitato",Modifier.padding(12.dp))}
        }
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(enabled=!busy,onClick={if(!confirm)confirm=true else {busy=true;scope.launch{try{
        val result=repo.writes.async{repo.changeUserRole(u,role,enabled)}.await()
        rows=rows.map{if(it.getString("id")==result.getString("id"))JSONObject(it.toString()).put("role",result.opt("role")).put("state",result.getString("state"))else it}
        selected=null;confirm=false;error="Abilitazione aggiornata dal server"
    }catch(e:CancellationException){throw e}catch(e:Exception){error=userManagementError(e)}finally{busy=false}}}}){Text(if(confirm)"Conferma e salva online" else "Continua")}},dismissButton={TextButton(enabled=!busy,onClick={selected=null;confirm=false}){Text("Annulla")}})}
}
