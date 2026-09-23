package it.pat.collettori

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable fun DatabaseRefreshButton(repo:Repository,onMessage:(String)->Unit){
    val stamp by repo.dao.settingFlow(repo.owner(),"database-last-success").collectAsState(null)
    val busy by repo.refreshing.collectAsState();val scope=rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()){
        Button(enabled=!busy&&repo.authenticated(),onClick={scope.launch{try{repo.refreshDatabase();onMessage("Database collettori e ispezioni aggiornato")}catch(e:Exception){onMessage(friendlyError(e))}}}){
            if(busy){CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp))}
            Text("Aggiorna database collettori")
        }
        Text(lastRefreshLabel(stamp),style=MaterialTheme.typography.bodySmall)
    }
}
