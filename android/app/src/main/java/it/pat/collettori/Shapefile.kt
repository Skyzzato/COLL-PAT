package it.pat.collettori

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

data class ShapeFeature(val fields:Map<String,String>,val geometry:JSONObject)
data class ShapeLayer(val name:String,val kind:String,val fields:List<String>,val features:List<ShapeFeature>,val crs:Int,val encoding:String)
data class ShapeArchive(val layers:List<ShapeLayer>,val hash:String)

/** Bounded native reader for ESRI point/polyline (including Z/M). No desktop GIS dependency. */
object Shapefile {
    const val MAX_BYTES=32*1024*1024
    const val MAX_FEATURES=10000
    fun read(input:InputStream,explicitCrs:Int?=null,explicitEncoding:String?=null):ShapeArchive{
        val entries=linkedMapOf<String,ByteArray>();var total=0
        ZipInputStream(input).use{zip->while(true){val e=zip.nextEntry?:break
            require(entries.size<80){"ZIP: troppi componenti (massimo 80)"}
            val name=e.name.replace('\\','/');require(!name.startsWith('/')&&!name.contains(':')&&name.split('/').none{it==".."}){"ZIP: percorso non sicuro"}
            if(e.isDirectory)continue
            require(name.length<=240){"ZIP: nome troppo lungo"}
            val key=name.lowercase();require(!entries.containsKey(key)){"ZIP: nomi duplicati"}
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=zip.read(buffer);if(n<0)break;total+=n;require(total<=MAX_BYTES){"ZIP oltre 32 MiB decompressi: dividere il file"};out.write(buffer,0,n)}
            require(e.compressedSize<=0||out.size().toLong()<=maxOf(1024*1024L,e.compressedSize*200)){"ZIP: rapporto di compressione eccessivo"}
            entries[key]=out.toByteArray()
        }}
        val shapes=entries.keys.filter{it.endsWith(".shp")};require(shapes.isNotEmpty()){"Nessun .shp nel file ZIP"}
        val digest=java.security.MessageDigest.getInstance("SHA-256");entries.toSortedMap().forEach{(name,bytes)->digest.update(name.toByteArray());digest.update(0.toByte());digest.update(bytes)}
        val layers=shapes.map{path->
            val stem=path.removeSuffix(".shp");val shp=entries[path]!!;val shx=entries["$stem.shx"]?:error("$stem: manca .shx");val dbf=entries["$stem.dbf"]?:error("$stem: manca .dbf")
            val crs=explicitCrs?:entries["$stem.prj"]?.let{detectCrs(String(it,Charsets.UTF_8))}?:error("$stem: CRS assente; indicare EPSG esplicito")
            require(crs in listOf(4326,3857,32632,32633,25832,25833)){"EPSG:$crs non supportato (4326, 3857, 32632/33, 25832/33)"}
            val encoding=explicitEncoding?:entries["$stem.cpg"]?.let{String(it,Charsets.US_ASCII).trim().trim('\uFEFF')}?:error("$stem: manca .cpg; specificare esplicitamente la codifica")
            val charset=when(encoding.uppercase()){ "65001","UTF8","UTF-8"->Charsets.UTF_8;"1252","WINDOWS-1252"->Charset.forName("windows-1252");"ISO-8859-1","LATIN1"->Charsets.ISO_8859_1;else->error("Codifica $encoding non supportata")}
            val (fields,rows)=dbf(dbf,charset);val geometry=shp(shp,shx,crs)
            require(rows.size==geometry.size){"$stem: cardinalità SHP/DBF incoerente"}
            val features=rows.mapIndexedNotNull{i,row->row?.let{ShapeFeature(it,geometry[i])}}
            require(features.isNotEmpty()){"$stem: nessun oggetto attivo"}
            val kinds=features.map{it.geometry.getString("type")}.toSet();require(kinds.all{it=="Point"}||kinds.all{it in listOf("LineString","MultiLineString")}){"Geometrie miste nello stesso layer"}
            ShapeLayer(stem,if(kinds==setOf("Point"))"point" else "segment",fields,features,crs,charset.name())
        }
        require(layers.sumOf{it.features.size}<=MAX_FEATURES){"Massimo 10000 oggetti per importazione atomica"}
        return ShapeArchive(layers,digest.digest().joinToString(""){"%02x".format(it)})
    }
    fun detectCrs(wkt:String):Int{
        val epsg=Regex("(?:AUTHORITY|ID)\\s*\\[\\s*\"EPSG\"\\s*,\\s*\"?(\\d+)\"?\\s*]",RegexOption.IGNORE_CASE).findAll(wkt).map{it.groupValues[1].toInt()}.toList().lastOrNull()
        if(epsg!=null)return epsg
        val n=wkt.uppercase().replace('_',' ')
        if(!n.contains("PROJCS")&&!n.contains("PROJCRS")&&(n.contains("GCS WGS 1984")||n.contains("WGS 84")))return 4326
        if(n.contains("WGS 1984")||n.contains("WGS 84")){if(n.contains("UTM ZONE 32N"))return 32632;if(n.contains("UTM ZONE 33N"))return 32633}
        if(n.contains("ETRS89")||n.contains("ETRS 1989")){if(n.contains("UTM ZONE 32N"))return 25832;if(n.contains("UTM ZONE 33N"))return 25833}
        error("CRS non riconosciuto; specificare un EPSG supportato dopo verifica del .prj")
    }
    private fun le(bytes:ByteArray)=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun dbf(bytes:ByteArray,charset:Charset):Pair<List<String>,List<Map<String,String>?>>{
        require(bytes.size>=33){"DBF troncato"};val b=le(bytes);val count=b.getInt(4);val header=b.getShort(8).toInt() and 65535;val size=b.getShort(10).toInt() and 65535
        require(count in 0..MAX_FEATURES&&header>=33&&header<=bytes.size&&size>0&&header.toLong()+count.toLong()*size<=bytes.size){"Dimensioni DBF non valide"}
        data class Field(val name:String,val offset:Int,val length:Int)
        val fields=mutableListOf<Field>();var pos=32;var offset=1
        while(pos<header-1&&bytes[pos]!=13.toByte()){
            require(pos+32<=header){"Descrittore DBF troncato"};val raw=bytes.copyOfRange(pos,pos+11);val end=raw.indexOf(0).let{if(it<0)raw.size else it};val name=String(raw,0,end,Charsets.US_ASCII)
            val len=bytes[pos+16].toInt() and 255;require(name.isNotBlank()&&len>0&&offset+len<=size){"Campo DBF non valido"}
            require(bytes[pos+11].toInt().toChar() in "CNFLD"){"Tipo DBF non supportato: ${bytes[pos+11].toInt().toChar()}"}
            fields.add(Field(name,offset,len));offset+=len;pos+=32
        }
        require(pos<header&&bytes[pos]==13.toByte()&&fields.map{it.name}.distinct().size==fields.size){"Intestazione DBF non valida"}
        val decoder=charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val rows=(0 until count).map{i->val start=header+i*size;when(bytes[start].toInt().toChar()){'*'->null;' '->fields.associate{f->f.name to decoder.decode(ByteBuffer.wrap(bytes,start+f.offset,f.length)).toString().trim().also{decoder.reset()}};else->error("Record DBF non valido")}}
        return fields.map{it.name} to rows
    }
    private fun shp(bytes:ByteArray,index:ByteArray,crs:Int):List<JSONObject>{
        require(bytes.size>=100&&index.size>=100&&(index.size-100)%8==0){"SHP/SHX troncato"}
        val b=le(bytes);val x=ByteBuffer.wrap(index).order(ByteOrder.BIG_ENDIAN)
        require(ByteBuffer.wrap(bytes).int==9994&&x.int==9994&&b.getInt(28)==1000){"Intestazione shapefile non valida"}
        require(ByteBuffer.wrap(bytes).getInt(24).toLong()*2==bytes.size.toLong()&&x.getInt(24).toLong()*2==index.size.toLong()){"Lunghezza SHP/SHX non valida"}
        val type=b.getInt(32);require(type in listOf(1,11,21,3,13,23)){"Geometria SHP $type non supportata: usare punti o polilinee"}
        require(le(index).getInt(32)==type){"Tipo SHX non corrispondente"}
        val count=(index.size-100)/8;require(count<=MAX_FEATURES)
        var expected=100
        return (0 until count).map{i->
            val at=x.getInt(100+i*8).toLong()*2;val length=x.getInt(104+i*8).toLong()*2
            require(at==expected.toLong()&&length>=4&&at+8+length<=bytes.size){"Offset SHX non valido"}
            val start=at.toInt();require(ByteBuffer.wrap(bytes).getInt(start)==i+1&&ByteBuffer.wrap(bytes).getInt(start+4).toLong()*2==length){"Indice SHX incoerente"}
            val r=le(bytes.copyOfRange(start+8,(at+8+length).toInt()));require(r.int==type){"Record nullo o tipo incoerente"};expected=(at+8+length).toInt()
            fun coordinate():JSONArray {require(r.remaining()>=16);val (lon,lat)=toWgs84(r.double,r.double,crs);require(validCoordinates(lat,lon)){"Coordinate fuori intervallo"};return JSONArray(listOf(lon,lat))}
            val result=if(type in listOf(1,11,21))JSONObject().put("type","Point").put("coordinates",coordinate()) else{
                require(r.limit()>=44){"Polilinea troncata"};r.position(36);val parts=r.int;val n=r.int
                require(parts in 1..MAX_FEATURES&&n in 2..500000&&44L+parts*4L+n*16L<=r.limit()){"Dimensione polilinea non valida"}
                val offsets=(0 until parts).map{r.int}+n;require(offsets.first()==0&&offsets.zipWithNext().all{(a,z)->z-a>=2}){"Parti lineari non valide"}
                val coords=(0 until n).map{coordinate()};val lines=offsets.zipWithNext().map{(a,z)->JSONArray(coords.subList(a,z))}
                JSONObject().put("type",if(parts==1)"LineString" else "MultiLineString").put("coordinates",if(parts==1)lines.first() else JSONArray(lines))
            }
            if(i==count-1)require(expected==bytes.size){"Record SHP non indicizzati"};result
        }
    }
    /** Inverse Transverse Mercator (Snyder), northern UTM WGS84 / GRS80 only. */
    fun toWgs84(x:Double,y:Double,epsg:Int):Pair<Double,Double>{
        require(x.isFinite()&&y.isFinite()){"Coordinate non finite"}
        if(epsg==4326)return x to y
        if(epsg==3857){require(abs(x)<=20037508.35&&abs(y)<=20037508.35){"Coordinate Web Mercator fuori intervallo"};return Math.toDegrees(x/6378137.0) to Math.toDegrees(2*atan(exp(y/6378137.0))-PI/2)}
        require(epsg in listOf(32632,32633,25832,25833)){"EPSG non supportato"};require(x in 100000.0..900000.0&&y in 0.0..9400000.0){"Coordinate UTM Nord fuori intervallo"}
        val a=6378137.0;val f=1.0/(if(epsg<30000)298.257222101 else 298.257223563);val e=f*(2-f);val ep=e/(1-e);val k=.9996
        val mu=(y/k)/(a*(1-e/4-3*e*e/64-5*e*e*e/256));val e1=(1-sqrt(1-e))/(1+sqrt(1-e))
        val phi=mu+(3*e1/2-27*e1.pow(3)/32)*sin(2*mu)+(21*e1*e1/16-55*e1.pow(4)/32)*sin(4*mu)+151*e1.pow(3)/96*sin(6*mu)+1097*e1.pow(4)/512*sin(8*mu)
        val n=a/sqrt(1-e*sin(phi).pow(2));val t=tan(phi).pow(2);val c=ep*cos(phi).pow(2);val r=a*(1-e)/(1-e*sin(phi).pow(2)).pow(1.5);val d=(x-500000)/(n*k)
        val lat=phi-n*tan(phi)/r*(d*d/2-(5+3*t+10*c-4*c*c-9*ep)*d.pow(4)/24+(61+90*t+298*c+45*t*t-252*ep-3*c*c)*d.pow(6)/720)
        val lon=(d-(1+2*t+c)*d.pow(3)/6+(5-2*c+28*t-3*c*c+8*ep+24*t*t)*d.pow(5)/120)/cos(phi)
        return (Math.toDegrees(lon)+(epsg%100)*6-183) to Math.toDegrees(lat)
    }
}
