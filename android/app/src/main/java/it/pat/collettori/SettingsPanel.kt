package it.pat.collettori

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable fun SettingsSection(title:String,icon:Int,initial:Boolean=false,content:@Composable ColumnScope.()->Unit){
    var expanded by rememberSaveable{mutableStateOf(initial)}
    Card(Modifier.fillMaxWidth().padding(vertical=5.dp)){Column(Modifier.padding(12.dp)){
        TextButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()){ActionIcon(icon);Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Text(if(expanded)"−" else "+")}
        if(expanded)content()
    }}
}
@Composable fun SettingsPanel(repo:Repository,settings:FieldSettings,onSettings:(FieldSettings)->Unit,catalog:List<CatalogItem>,visits:List<Visit>,queue:List<Pending>,onAccount:()->Unit,onMessage:(String)->Unit,onArchiveExport:()->Unit){
    val scope=rememberCoroutineScope();var busy by remember{mutableStateOf(false)}
    fun task(block:suspend()->Unit){if(busy)return;busy=true;scope.launch{try{block()}catch(e:Exception){onMessage(friendlyError(e))}finally{busy=false}}}
    var showImport by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)){
        Text("Impostazioni",style=MaterialTheme.typography.headlineSmall)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        SettingsSection("Mappa e aspetto",R.drawable.ic_map,true){
            Text("Visibilità pozzetti · zoom ≥ %.1f".format(settings.minZoomPozzetti))
            Slider(settings.minZoomPozzetti,{onSettings(settings.copy(minZoomPozzetti=it))},valueRange=8f..20f,steps=23)
            Text("I pozzetti vengono nascosti quando la mappa è troppo zoomata indietro. I tronchi rimangono visibili.",style=MaterialTheme.typography.bodySmall)
            Text("Dimensione pozzetti · %.0f".format(settings.iconSize));Slider(settings.iconSize,{onSettings(settings.copy(iconSize=it))},valueRange=4f..14f,steps=9)
            Choice("Simbologia pozzetti",settings.symbol,ManholeSymbol.entries.map{it.name to it.label}){onSettings(settings.copy(symbol=it))}
            Row(verticalAlignment=Alignment.CenterVertically){Switch(settings.asphalt,{onSettings(settings.copy(asphalt=it))});Text("Segno interno per pozzetti sotto asfalto",Modifier.padding(horizontal=10.dp))}
            InspectionState.entries.forEach{s->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(vertical=2.dp)){
                Box(Modifier.size(10.dp).background(Color(android.graphics.Color.parseColor(s.color)),CircleShape))
                Text(s.label,style=MaterialTheme.typography.bodySmall)
            }}
        }
        SettingsSection("GPS e rilevazione",R.drawable.ic_target){
            Text("Distanza massima dal pozzetto · %.0f m".format(settings.maxDistance))
            Slider(settings.maxDistance.toFloat(),{onSettings(settings.copy(maxDistance=it.toDouble()))},valueRange=1f..100f,steps=98)
            Text("Accuratezza GPS massima ammessa · %.0f m".format(settings.maxAccuracy))
            Slider(settings.maxAccuracy.toFloat(),{onSettings(settings.copy(maxAccuracy=it.toDouble()))},valueRange=1f..50f,steps=48)
            Text("Accuratezza e distanza sono controlli indipendenti. La soglia usata viene conservata nella rilevazione.",style=MaterialTheme.typography.bodySmall)
        }
        SettingsSection("Ispezioni",R.drawable.ic_history){
            Text("La periodicità segue le visite previste per ciascun collettore.",style=MaterialTheme.typography.bodySmall)
            Text("Il CSV comprende le ispezioni concluse nel trimestre civile corrente.",style=MaterialTheme.typography.bodySmall)
            QuarterExportButton(repo,onMessage)
            TextButton(onClick=onArchiveExport){Text("Esporta archivio di recupero")}
        }
        SettingsSection("Server e sincronizzazione",R.drawable.ic_sync){
            Text("Account server collegato")
            Text("${queue.size+visits.count{it.sync=="IN_ATTESA"&&queue.none{op->op.visitId==it.id}}} elementi in attesa")
            Button(enabled=repo.authenticated()&&!busy,onClick={task{
                repo.dao.retryBlocked(repo.owner());val done=repo.sync();val remaining=repo.dao.allPending(repo.owner())
                onMessage(when{
                    remaining.any{it.state=="AUTH_REQUIRED"}->"Accedi nuovamente per completare la sincronizzazione. I dati restano sul telefono."
                    remaining.any{it.state=="CONFLICT"}->"Alcuni dati richiedono una verifica del responsabile prima dell’invio. Le modifiche locali sono conservate."
                    !done||remaining.isNotEmpty()->"Invio ancora in attesa. Le modifiche sono conservate sul telefono."
                    else->"Sincronizzazione completata"
                })
            }}){Text("Sincronizza")}
            TextButton(enabled=repo.authenticated()&&!busy,onClick={task{repo.catalog();repo.downloadHistory();onMessage("Dati server aggiornati")}}){Text("Aggiorna collettori e ispezioni")}
            if(queue.any{it.state=="CONFLICT"})Text("Alcune modifiche a ispezioni o dati cartografici richiedono una verifica del responsabile.",color=MaterialTheme.colorScheme.error)
        }
        SettingsSection("Account",R.drawable.ic_account){AccountPanel(repo,onAccount)}
        SettingsSection("Dati cartografici",R.drawable.ic_pipe){
            if(repo.canManageCatalog()){
                Text("Seleziona uno ZIP: l'app riconosce automaticamente layer e campi, poi mostra l'anteprima prima dell'aggiornamento del progetto.",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick={showImport=true}){Text("Importa shapefile")}
                AdminPanel(repo,catalog,visits.size,onMessage)
            }else Text("L’importazione e la modifica dei dati cartografici sono riservate al responsabile del progetto.",style=MaterialTheme.typography.bodySmall)
        }
        SettingsSection("Informazioni",R.drawable.ic_info){
            Text("COLL-PAT",style=MaterialTheme.typography.titleLarge);Text("Ispezioni e rilievi dei collettori");Text("Versione ${AppSpec.version} · build ${BuildConfig.VERSION_CODE}")
            Text("© OpenStreetMap contributors · ODbL",style=MaterialTheme.typography.bodySmall)
        }
    }
    if(showImport&&repo.canManageCatalog())ImportDialog(repo,catalog,{showImport=false},onMessage)
}

@Composable fun AuthScreen(repo:Repository,onChange:()->Unit){
    val prefs=remember{repo.context.getSharedPreferences("public-config",android.content.Context.MODE_PRIVATE)}
    var url by rememberSaveable{mutableStateOf(prefs.getString("url",BuildConfig.SUPABASE_URL)?:"")}
    var key by rememberSaveable{mutableStateOf(prefs.getString("key",BuildConfig.SUPABASE_PUBLISHABLE_KEY)?:"")}
    var project by rememberSaveable{mutableStateOf(prefs.getString("project",AppSpec.LOCAL_PROJECT)!!)}
    var email by rememberSaveable{mutableStateOf("")};var password by remember{mutableStateOf("")};var confirmation by remember{mutableStateOf("")}
    var register by rememberSaveable{mutableStateOf(false)};var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")};val scope=rememberCoroutineScope()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)){
        Text("COLL-PAT",style=MaterialTheme.typography.headlineLarge);Text("Versione ${AppSpec.version}",style=MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(24.dp));Text(if(register)"Registrazione account" else "Login",style=MaterialTheme.typography.headlineSmall)
        Field("Email",email){email=it}
        OutlinedTextField(password,{password=it},label={Text("Password")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),singleLine=true)
        if(register)OutlinedTextField(confirmation,{confirmation=it},label={Text("Conferma password")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth().padding(top=8.dp),singleLine=true)
        if(message.isNotBlank())Text(message,Modifier.padding(vertical=12.dp))
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(enabled=!busy,onClick={scope.launch{busy=true;try{
            require(email.contains('@')&&password.isNotBlank()){ "Inserisci email e password" }
            prefs.edit().putString("url",url.trim()).putString("key",key.trim()).putString("project",project.trim()).apply()
            if(register){require(password.length>=8){"Usa una password di almeno 8 caratteri"};require(password==confirmation){"Le password non coincidono"};repo.api.register(url.trim(),key.trim(),email,password);password="";confirmation="";register=false;message="Account creato: verifica l’email, poi accedi. L’accesso al progetto richiede l’abilitazione del responsabile."}
            else{repo.api.login(url.trim(),key.trim(),email,password,project.trim());password="";repo.checkVersion();repo.prepareWorkspace();repo.catalog();repo.dao.resumeAuth(repo.owner());repo.syncNow();onChange()}
        }catch(e:Exception){message=if(e is ApiError&&e.code==400)"Accesso non riuscito. Verifica email, password e conferma dell’account." else friendlyError(e)}finally{busy=false}}},modifier=Modifier.fillMaxWidth()){Text(if(register)"Crea account" else "Accedi")}
        TextButton(enabled=!busy,onClick={register=!register;message=""}){Text(if(register)"Hai già un account? Accedi" else "Registra un account")}
        TextButton(onClick={message="Il recupero della password non è ancora disponibile in questa versione."}){Text("Recupera la password")}
        SettingsSection("Configurazione collegamento",R.drawable.ic_settings,url.isBlank()||key.isBlank()){
            Field("SUPABASE_URL",url){url=it};Field("SUPABASE_PUBLISHABLE_KEY",key){key=it};Field("Progetto applicativo",project){project=it}
        }
    }
}
