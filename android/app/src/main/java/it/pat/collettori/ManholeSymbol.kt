package it.pat.collettori

import kotlin.math.*

/** Monochrome SDF shapes: MapLibre applies the inspection colour independently. */
enum class ManholeSymbol(val label:String) {
    CIRCLE("Cerchio pieno"), SQUARE("Quadrato"), DIAMOND("Rombo"),
    HEXAGON("Esagono"), RING("Anello"), CHAMBER("Chiusino"), DOT("Anello con punto");

    val imageId get()="manhole-${name.lowercase()}"
    fun distance(x:Double,y:Double):Double {
        val circle=hypot(x,y)-RADIUS
        val ring=abs(hypot(x,y)-(RADIUS-3.0))-3.0
        return when(this){
            CIRCLE->circle
            SQUARE->{val dx=abs(x)-RADIUS;val dy=abs(y)-RADIUS;hypot(max(dx,0.0),max(dy,0.0))+min(max(dx,dy),0.0)}
            DIAMOND->(abs(x)+abs(y)-RADIUS)/sqrt(2.0)
            HEXAGON->{val a=abs(x);val b=abs(y);max(b-RADIUS*sqrt(3.0)/2.0,(sqrt(3.0)*a+b)/2.0-RADIUS*sqrt(3.0)/2.0)}
            RING->ring
            CHAMBER->min(ring,max(circle,min(abs(y-6.0),abs(y+6.0))-2.0))
            DOT->min(ring,hypot(x,y)-5.0)
        }
    }
    fun sdfPixels():IntArray=IntArray(PIXELS*PIXELS){index->
        val d=distance(index%PIXELS+.5-PIXELS/2.0,index/PIXELS+.5-PIXELS/2.0)
        // MapLibre SDF boundary is 0.75, with an eight-pixel distance buffer.
        val alpha=((.75-d/8.0)*255).roundToInt().coerceIn(0,255)
        (alpha shl 24) or 0x00ffffff
    }
    companion object {
        const val PIXELS=64
        const val RADIUS=20.0
        fun fromId(id:String)=entries.firstOrNull{it.name==id}?:CIRCLE
    }
}
