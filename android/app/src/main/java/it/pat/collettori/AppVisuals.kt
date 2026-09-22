package it.pat.collettori

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

val ConfirmedGreen = Color(0xFF216E40)
val AttentionOrange = Color(0xFF875000)
val CollettoriColors = lightColorScheme(
    primary=Color(0xFF176B68), onPrimary=Color.White,
    primaryContainer=Color(0xFFCCE8E1), onPrimaryContainer=Color(0xFF123E32),
    secondary=Color(0xFF52635D), secondaryContainer=Color(0xFFE0E9E3),
    onSecondaryContainer=Color(0xFF243B32), tertiary=AttentionOrange,
    tertiaryContainer=Color(0xFFFFE3B0), onTertiaryContainer=Color(0xFF4C2D00),
    error=Color(0xFFBA1A1A), background=Color(0xFFF3F7F2),
    surface=Color(0xFFF3F7F2), surfaceContainer=Color(0xFFEAF0E9),
    surfaceContainerHigh=Color(0xFFE0E9E3), onSurface=Color(0xFF1C2924)
)

/** Original vector symbols. Adjacent labels provide their accessible meaning. */
@Composable fun ActionIcon(@DrawableRes icon:Int) {
    Icon(painterResource(icon),contentDescription=null,modifier=Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
}

@Composable fun AppLoading(error:String, retry:()->Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        Image(painterResource(R.drawable.ic_pipe),null,Modifier.size(144.dp))
        Text(AppSpec.NAME,style=MaterialTheme.typography.headlineLarge)
        Text("v${AppSpec.version} · build ${BuildConfig.VERSION_CODE}")
        if(error.isBlank())CircularProgressIndicator(Modifier.padding(top=24.dp))
        if(error.isNotBlank()) {
            Spacer(Modifier.height(24.dp))
            Text(error,color=MaterialTheme.colorScheme.error)
            OutlinedButton(onClick=retry){Text("Riprova caricamento")}
        }
    }
}
