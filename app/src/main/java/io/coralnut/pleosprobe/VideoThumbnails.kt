package io.coralnut.pleosprobe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** 이미지 요청은 인증 큐와 분리하고, 화면이 다시 그려져도 같은 이미지는 한 번만 읽는다. */
class VideoThumbnails(private val log:(String)->Unit) {
    private val main=Handler(Looper.getMainLooper())
    private val workers=Executors.newFixedThreadPool(2)
    private val cache=object:LruCache<String,Bitmap>(8*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.byteCount}
    private val pending=mutableMapOf<String,MutableList<WeakReference<ImageView>>>()
    private val failedAt=mutableMapOf<String,Long>()
    private var closed=false
    fun bind(view:ImageView,video:CoralnutVideo)=bind(view,video.thumbnailUrl,video.fallbackUrl,video.id)

    fun bindAvatar(view:ImageView,url:String)=bind(view,url,url,"avatar")

    private fun bind(view:ImageView,url:String,fallback:String,id:String){
        cache.get(url)?.let{view.setImageBitmap(it);return}
        if(System.currentTimeMillis()-(failedAt[url] ?: 0L)<30000){if(id!="avatar")unavailable(view);return}
        val callbacks=pending[url]
        if(callbacks!=null){callbacks.add(WeakReference(view));return}
        pending[url]=mutableListOf(WeakReference(view))
        workers.execute{
            var usedFallback=false
            val bitmap=runCatching{download(url)}.getOrElse{
                if(url==fallback)null else runCatching{usedFallback=true;download(fallback)}.getOrNull()
            }
            main.post{
                if(closed)return@post
                val targets=pending.remove(url).orEmpty()
                if(bitmap!=null){
                    cache.put(url,bitmap)
                    targets.forEach{it.get()?.setImageBitmap(bitmap)}
                    log("THUMBNAIL_LOADED videoId=${id} size=${bitmap.width}x${bitmap.height} fallback=$usedFallback")
                }else{
                    failedAt[url]=System.currentTimeMillis()
                    if(id!="avatar")targets.forEach{it.get()?.let(::unavailable)}
                    log("THUMBNAIL_UNAVAILABLE videoId=${id}")
                }
            }
        }
    }
    private fun unavailable(view:ImageView){view.contentDescription="${view.contentDescription} · 이미지를 불러오지 못했어요.";view.setImageResource(android.R.drawable.ic_menu_gallery)}
    private fun download(source:String):Bitmap{
        val url=URL(source)
        require(url.protocol=="https"&&url.host in setOf("i.ytimg.com","yt3.ggpht.com","yt3.googleusercontent.com"))
        val connection=url.openConnection() as HttpURLConnection
        try{
            connection.connectTimeout=8000;connection.readTimeout=8000;connection.instanceFollowRedirects=false
            check(connection.responseCode==200)
            val bytes=connection.inputStream.use{input->
                val output=ByteArrayOutputStream();val chunk=ByteArray(8192)
                while(true){val count=input.read(chunk);if(count<0)break;check(output.size()+count<=4*1024*1024);output.write(chunk,0,count)}
                output.toByteArray()
            }
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            check(bounds.outWidth>0&&bounds.outHeight>0)
            var sample=1
            while(bounds.outWidth/sample>1280||bounds.outHeight/sample>720)sample*=2
            return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample}) ?: error("Invalid thumbnail")
        }finally{connection.disconnect()}
    }
    fun close(){closed=true;workers.shutdownNow();pending.clear();cache.evictAll();main.removeCallbacksAndMessages(null)}
}
