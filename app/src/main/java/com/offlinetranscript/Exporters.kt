package com.offlinetranscript
import android.content.Context
import java.io.File
data class TranscriptSegment(val startMs:Long,val endMs:Long,val text:String)
object Exporters{fun srt(context:Context,segments:List<TranscriptSegment>):File=File(context.cacheDir,"transcript_${System.currentTimeMillis()}.srt").also{f->f.printWriter().use{out->segments.forEachIndexed{i,s->out.println(i+1);out.println("${stamp(s.startMs)} --> ${stamp(s.endMs)}");out.println(s.text.trim());out.println()}}};private fun stamp(ms:Long):String{val h=ms/3600000;val m=(ms%3600000)/60000;val s=(ms%60000)/1000;val z=ms%1000;return "%02d:%02d:%02d,%03d".format(h,m,s,z)}}
