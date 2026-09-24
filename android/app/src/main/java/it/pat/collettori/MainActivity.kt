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

class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme(colorScheme=CollettoriColors){CollPatApp((application as PilotApplication).repository)}}}}

@Composable fun Choice(label:String,value:String,options:List<Pair<String,String>>,onChange:(String)->Unit){
    var open by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().padding(vertical=4.dp)){
        Text(if(value=="ANOMALO")"⚠ $label · Anomalia" else if(value=="REGOLARE")"✓ $label" else label,style=MaterialTheme.typography.labelLarge,color=if(value=="ANOMALO")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Box{OutlinedButton(onClick={open=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(options.find{it.first==value}?.second?:"Seleziona")}
            DropdownMenu(expanded=open,onDismissRequest={open=false}){options.forEach{(key,text)->DropdownMenuItem(text={Text(text)},onClick={open=false;onChange(key)})}}}
    }
}
@Composable fun Field(label:String,value:String,onChange:(String)->Unit){OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},modifier=Modifier.fillMaxWidth().padding(vertical=4.dp))}
fun shown(s:String?):String=try{DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.of("Europe/Rome")).format(Instant.parse(s))}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;s?:"—"}
fun operational(s:String)=when(s){"BOZZA"->"Bozza";"COMPLETO"->"Controllo dichiarato completo";"PARZIALE"->"Parziale";"IMPEDITO"->"Impedimento";else->s}
fun syncLabel(s:String)=when(s){"SALVATO_LOCALMENTE"->"Salvato localmente";"IN_ATTESA"->"In attesa di invio";"INVIO_IN_CORSO","IN_CORSO"->"Invio in corso";"RICEVUTO_SERVER"->"Ricevuto dal server";"AUTH_REQUIRED"->"Accesso Auth richiesto";"BLOCKED"->"Bloccato: correggere il problema indicato";"CONFLICT"->"Conflitto: intervento richiesto";"RESET_OBSOLETE"->"Sospeso: precedente al reset";else->"Errore da risolvere"}

@Composable fun NetworkStatus():Boolean{
    val context=LocalContext.current
    val manager=remember{context.getSystemService(Context.CONNECTIVITY_SERVICE)as ConnectivityManager}
    fun connected()=manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
    var online by remember{mutableStateOf(connected())}
    DisposableEffect(manager){val cb=object:ConnectivityManager.NetworkCallback(){override fun onAvailable(network:Network){online=connected()};override fun onLost(network:Network){online=connected()};override fun onCapabilitiesChanged(network:Network,c:NetworkCapabilities){online=connected()}};manager.registerDefaultNetworkCallback(cb);onDispose{manager.unregisterNetworkCallback(cb)}}
    return online
}
