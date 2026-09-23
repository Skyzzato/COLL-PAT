package it.pat.collettori
import org.json.JSONObject
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

val lineColors=listOf("#176D73" to "Verde petrolio","#254EBC" to "Blu","#783E9F" to "Viola","#8D4617" to "Marrone","#AF235A" to "Magenta")
fun resolvedCollectorColor(c:JSONObject?,settings:FieldSettings)=c?.optString("display_color")?.takeIf{it.matches(Regex("#[0-9a-fA-F]{6}"))}?:settings.lineColor
fun resolvedCollectorWidth(c:JSONObject?,settings:FieldSettings)=c?.numberOrNull("display_width")?.toInt()?.takeIf{it in 1..10}?:settings.lineWidth
fun resolvedSymbol(p:JSONObject,collectors:List<JSONObject>,settings:FieldSettings,context:String?=null):String{
    val member=collectors.filter{it.getString("id") in p.memberships()}.sortedWith(compareBy({it.optString("code")},{it.getString("id")}))
    val c=member.firstOrNull{it.getString("id")==context}?:member.firstOrNull()
    return ManholeSymbol.fromId(p.optString("symbol").takeIf{s->ManholeSymbol.entries.any{it.name==s}}?:c?.optString("symbol")?.takeIf{s->ManholeSymbol.entries.any{it.name==s}}?:settings.symbol).imageId
}
@Composable fun AppearanceDialog(repo:Repository,item:JSONObject,settings:FieldSettings,dismiss:()->Unit,message:(String)->Unit){
    val collector=!item.has("latitude");var color by remember{mutableStateOf(item.optString("display_color").takeUnless{it=="null"}.orEmpty())}
    var width by remember{mutableStateOf(item.numberOrNull("display_width")?.toInt()?.toString().orEmpty())}
    var symbol by remember{mutableStateOf(item.optString("symbol").takeUnless{it=="null"}.orEmpty())};var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest={if(!busy)dismiss()},title={Text("Personalizza aspetto · "+item.optString("code"))},text={Column(Modifier.verticalScroll(rememberScrollState())){
        if(collector){
            Choice("Colore tracciato",color,listOf("" to "Usa impostazioni predefinite")+lineColors){color=it}
            Choice("Spessore tracciato",width,listOf("" to "Usa impostazioni predefinite")+(1..10).map{it.toString() to it.toString()}){width=it}
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(30.dp)){drawLine(androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(color.ifBlank{settings.lineColor})),androidx.compose.ui.geometry.Offset(0f,size.height/2),androidx.compose.ui.geometry.Offset(size.width,size.height/2),(width.toFloatOrNull()?:settings.lineWidth.toFloat())*density)}
        }else Text("Il colore continua a indicare lo stato delle ispezioni e delle anomalie.")
        Choice("Forma pozzetti",symbol,listOf("" to "Usa impostazioni predefinite")+ManholeSymbol.entries.map{it.name to it.label}){symbol=it}
        TextButton(onClick={color="";width="";symbol=""}){Text("Ripristina predefiniti")}
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(enabled=!busy,onClick={busy=true;scope.launch{try{
        val changes=JSONObject().put("symbol",symbol.ifBlank{null}?:JSONObject.NULL)
        if(collector)changes.put("display_color",color.ifBlank{null}?:JSONObject.NULL).put("display_width",width.toIntOrNull()?:JSONObject.NULL)
        repo.patchObject(item.getString("id"),changes);dismiss();message("Aspetto salvato sul dispositivo · invio in coda")
    }catch(e:Exception){error=friendlyError(e)}finally{busy=false}}}){Text("Salva")}},dismissButton={TextButton(onClick=dismiss){Text("Annulla")}})
}
