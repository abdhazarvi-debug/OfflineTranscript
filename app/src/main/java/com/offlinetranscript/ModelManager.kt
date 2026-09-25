package com.offlinetranscript
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
object ModelManager{
 const val MODEL_NAME="ggml-base.bin"
 private const val SHA256="60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
 private const val MODEL_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
 fun modelFile(c:Context)=File(File(c.filesDir,"models"),MODEL_NAME)
 fun isReady(c:Context)=modelFile(c).length()>100_000_000&&sha256(modelFile(c))==SHA256
 fun download(c:Context,onProgress:(Int)->Unit):File{
  val dir=File(c.filesDir,"models").apply{mkdirs()};val target=File(dir,MODEL_NAME);val temp=File(dir,"$MODEL_NAME.part")
  val con=(URL(MODEL_URL).openConnection() as HttpURLConnection).apply{connectTimeout=20000;readTimeout=60000;instanceFollowRedirects=true;connect()}
  if(con.responseCode !in 200..299)error("Model download failed: HTTP ${con.responseCode}");val total=con.contentLengthLong
  con.inputStream.use{input->FileOutputStream(temp).use{out->val b=ByteArray(262144);var done=0L;while(true){val n=input.read(b);if(n<0)break;out.write(b,0,n);done+=n;if(total>0)onProgress((done*100/total).toInt())}}};con.disconnect()
  check(sha256(temp)==SHA256){"Model checksum mismatch. Download was rejected."};if(target.exists())target.delete();check(temp.renameTo(target)){"Could not finalize model file"};return target
 }
 private fun sha256(f:File):String{val md=MessageDigest.getInstance("SHA-256");f.inputStream().use{input->val b=ByteArray(1048576);while(true){val n=input.read(b);if(n<0)break;md.update(b,0,n)}};return md.digest().joinToString(""){"%02x".format(it)}}
}
