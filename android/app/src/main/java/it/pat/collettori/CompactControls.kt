package it.pat.collettori

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable fun ThresholdSlider(label:String,value:Double,values:List<Double>,change:(Double)->Unit){
    Text("$label · ${value.toInt()} m")
    Slider(value=values.indexOf(value).coerceAtLeast(0).toFloat(),onValueChange={change(values[it.roundToInt().coerceIn(values.indices)])},
        valueRange=0f..values.lastIndex.toFloat(),steps=values.size-2,
        modifier=Modifier.semantics{contentDescription=label;stateDescription="${value.toInt()} metri"})
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("${values.first().toInt()} m");Text("${values.last().toInt()} m")}
}

@Composable fun <T> CollapsibleItems(label:String,values:List<T>,itemKey:(T)->String,content:@Composable (T)->Unit){
    var expanded by rememberSaveable{mutableStateOf(false)}
    TextButton(onClick={expanded=!expanded}){Text("$label: ${values.size} — "+if(expanded)"Nascondi elenco" else "Mostra elenco")}
    if(expanded)LazyColumn(Modifier.fillMaxWidth().heightIn(max=320.dp)){itemsIndexed(values,key={index,item->"${itemKey(item)}:$index"}){_,item->content(item)}}
}

@Composable fun Repository.observedRole():String {
    val sessionVersion by store.changes.collectAsState()
    val preview by simulation.collectAsState()
    return remember(sessionVersion,preview){effectiveRole()}
}
