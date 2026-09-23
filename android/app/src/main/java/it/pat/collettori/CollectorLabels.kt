package it.pat.collettori
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Metre-based anchors on directed branches; no pixel spacing or invented straight network. */
fun collectorLabelFeatures(pack:JSONObject):List<JSONObject>{
    val result=mutableListOf<JSONObject>();val segments=pack.getJSONArray("segments").objects()
    for(c in pack.optJSONArray("collectors")?.objects().orEmpty().sortedBy{it.getString("id")}){
        val lines=segments.filter{c.getString("id") in it.memberships()};val remaining=lines.associateBy{it.getString("id")}.toMutableMap()
        while(remaining.isNotEmpty()){
            val first=remaining.values.firstOrNull{s->lines.count{it.optString("to_id")==s.optString("from_id")}!=1||lines.count{it.optString("from_id")==s.optString("from_id")}>1}?:remaining.values.first()
            val paths=mutableListOf<MutableList<JSONArray>>();var path=mutableListOf<JSONArray>();paths.add(path);var current:JSONObject?=first
            while(current!=null){val edge=current;remaining.remove(edge.getString("id"));val geom=edge.getJSONObject("geometry");val coordinates=geom.getJSONArray("coordinates")
                val parts=if(geom.getString("type")=="LineString")listOf(coordinates)else (0 until coordinates.length()).map{coordinates.getJSONArray(it)}
                parts.forEach{part->
                    if(part.length()>0&&path.isNotEmpty()){
                        val a=path.last();val b=part.getJSONArray(0)
                        if(GpsRule.distance(a.getDouble(1),a.getDouble(0),b.getDouble(1),b.getDouble(0))>0.01){path=mutableListOf();paths.add(path)}
                    }
                    for(i in 0 until part.length())path.add(part.getJSONArray(i))
                }
                val end=edge.optString("to_id");val outgoing=remaining.values.filter{it.optString("from_id")==end}
                current=outgoing.singleOrNull()?.takeIf{lines.count{s->s.optString("to_id")==end}==1}
            }
            for(path in paths){
            if(path.size<2)continue
            val distances=(1 until path.size).map{i->GpsRule.distance(path[i-1].getDouble(1),path[i-1].getDouble(0),path[i].getDouble(1),path[i].getDouble(0))}
            val length=distances.sum();if(length<0.01)continue
            val positions=if(length<2000)listOf(length/3,length*2/3)else generateSequence(500.0){it+1000}.takeWhile{it<length}.toList()
            for(target in positions){var offset=0.0
                for(i in distances.indices){val size=distances[i];if(offset+size>=target&&size>0){val a=path[i];val b=path[i+1];val f=(target-offset)/size
                    val lat=a.getDouble(1)+(b.getDouble(1)-a.getDouble(1))*f;val lon=a.getDouble(0)+(b.getDouble(0)-a.getDouble(0))*f
                    var angle=Math.toDegrees(atan2((b.getDouble(0)-a.getDouble(0))*cos(Math.toRadians(lat)),b.getDouble(1)-a.getDouble(1)))-90
                    while(angle>90)angle-=180;while(angle< -90)angle+=180
                    result.add(JSONObject().put("type","Feature").put("geometry",JSONObject().put("type","Point").put("coordinates",JSONArray(listOf(lon,lat)))).put("properties",JSONObject().put("code",c.getString("code")).put("angle",angle).put("collector_id",c.getString("id"))))
                    break
                };offset+=size}
            }
            }
        }
    };return result
}
