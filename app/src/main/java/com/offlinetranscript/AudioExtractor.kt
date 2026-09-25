package com.offlinetranscript
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
object AudioExtractor {
 fun extract(context:Context,input:File):File{
  val out=File(context.cacheDir,"audio_${System.currentTimeMillis()}.wav");val ex=MediaExtractor();ex.setDataSource(input.absolutePath)
  var track=-1;for(i in 0 until ex.trackCount){val f=ex.getTrackFormat(i);if((f.getString(MediaFormat.KEY_MIME)?:"").startsWith("audio/")){track=i;break}}
  require(track>=0){"No audio track found"};val fmt=ex.getTrackFormat(track);val mime=fmt.getString(MediaFormat.KEY_MIME)!!
  val rate=fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE,16000);val channels=fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT,1);ex.selectTrack(track)
  val codec=MediaCodec.createDecoderByType(mime);codec.configure(fmt,null,null,0);codec.start()
  FileOutputStream(out).use{fos->writeHeader(fos,rate,channels,0);var total=0L;var inDone=false;var outDone=false;val info=MediaCodec.BufferInfo()
   while(!outDone){
    if(!inDone){val ii=codec.dequeueInputBuffer(10000);if(ii>=0){val b=codec.getInputBuffer(ii)!!;val n=ex.readSampleData(b,0);if(n<0){codec.queueInputBuffer(ii,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inDone=true}else{codec.queueInputBuffer(ii,0,n,ex.sampleTime,0);ex.advance()}}}
    val oi=codec.dequeueOutputBuffer(info,10000);if(oi>=0){val b:ByteBuffer=codec.getOutputBuffer(oi)!!;if(info.size>0){b.position(info.offset);b.limit(info.offset+info.size);val bytes=ByteArray(info.size);b.get(bytes);fos.write(bytes);total+=bytes.size};codec.releaseOutputBuffer(oi,false);if((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)outDone=true}
   }
   fos.flush();java.io.RandomAccessFile(out,"rw").use{raf->raf.seek(4);raf.writeIntLE((36+total).toInt());raf.seek(40);raf.writeIntLE(total.toInt())}
  };codec.stop();codec.release();ex.release();return out
 }
 private fun writeHeader(o:java.io.OutputStream,r:Int,c:Int,s:Long){val h=ByteArray(44);h[0]='R'.code.toByte();h[1]='I'.code.toByte();h[2]='F'.code.toByte();h[3]='F'.code.toByte();putInt(h,4,(36+s).toInt());h[8]='W'.code.toByte();h[9]='A'.code.toByte();h[10]='V'.code.toByte();h[11]='E'.code.toByte();h[12]='f'.code.toByte();h[13]='m'.code.toByte();h[14]='t'.code.toByte();h[15]=' '.code.toByte();putInt(h,16,16);putShort(h,20,1);putShort(h,22,c);putInt(h,24,r);putInt(h,28,r*c*2);putShort(h,32,c*2);putShort(h,34,16);h[36]='d'.code.toByte();h[37]='a'.code.toByte();h[38]='t'.code.toByte();h[39]='a'.code.toByte();putInt(h,40,s.toInt());o.write(h)}
 private fun putInt(b:ByteArray,o:Int,v:Int){b[o]=(v and 255).toByte();b[o+1]=((v shr 8)and 255).toByte();b[o+2]=((v shr 16)and 255).toByte();b[o+3]=((v shr 24)and 255).toByte()}
 private fun putShort(b:ByteArray,o:Int,v:Int){b[o]=(v and 255).toByte();b[o+1]=((v shr 8)and 255).toByte()}
 private fun java.io.RandomAccessFile.writeIntLE(v:Int){write(v and 255);write((v shr 8)and 255);write((v shr 16)and 255);write((v shr 24)and 255)}
}
