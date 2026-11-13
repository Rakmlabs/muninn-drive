package io.coralnut.pleosprobe

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.Gravity
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.io.File

/** 제어 가능한 앱 내부 플레이어만 사용하고, 주행 제한·백그라운드 전환 때 즉시 폐기한다. */
class PlaceVideoActivity:Activity(){
    private lateinit var guard:ParkedVideoGuard
    private lateinit var status:TextView
    private lateinit var container:LinearLayout
    private lateinit var retry:Button
    private val main=Handler(Looper.getMainLooper())
    private val playbackTimeout=Runnable{showError("영상이 아직 시작되지 않았어요. 다시 시도해주세요.")}
    private var web:WebView?=null
    private var stopped=false
    private var started=false
    private var videoId=""
    private var startSec=0
    private fun d(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun log(value:String){if(!BuildConfig.DEBUG)return;File(filesDir,"drive-ui.log").appendText("${System.currentTimeMillis()} $value\n")}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        videoId=intent.getStringExtra("videoId").orEmpty();startSec=intent.getIntExtra("startSec",0).coerceAtLeast(0)
        if(!Regex("[A-Za-z0-9_-]{11}").matches(videoId)){finish();return}
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK)}
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(d(32),d(18),d(32),d(18));setBackgroundColor(DrivePalette.ground)}
        header.addView(Button(this).apply{text="장소로 돌아가기";textSize=20f;isAllCaps=false;setTextColor(DrivePalette.ink);setOnClickListener{finish()}},LinearLayout.LayoutParams(d(220),d(64)))
        header.addView(TextView(this).apply{text=intent.getStringExtra("title").orEmpty();textSize=22f;setTextColor(DrivePalette.ink);setPadding(d(24),0,d(12),0);maxLines=2},LinearLayout.LayoutParams(0,-2,1f))
        root.addView(header)
        container=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(container,LinearLayout.LayoutParams(-1,0,1f))
        status=TextView(this).apply{text="정차 상태를 확인하고 있어요.";textSize=18f;setTextColor(DrivePalette.muted);setBackgroundColor(DrivePalette.ground);setPadding(d(32),d(12),d(32),d(12))}
        val footer=LinearLayout(this).apply{
            gravity=Gravity.CENTER_VERTICAL
            setBackgroundColor(DrivePalette.ground)
            setPadding(0,0,d(24),0)
        }
        footer.addView(status,LinearLayout.LayoutParams(0,-2,1f))
        retry=Button(this).apply{
            text="다시 재생";isAllCaps=false;textSize=18f;visibility=android.view.View.GONE
            stateListAnimator=null;elevation=0f;translationZ=0f;backgroundTintList=null
            setTextColor(DrivePalette.selectedInk)
            background=GradientDrawable().apply{setColor(DrivePalette.selected);cornerRadius=d(12).toFloat()}
        }
        footer.addView(retry,LinearLayout.LayoutParams(-2,d(56)))
        root.addView(footer);setContentView(root)
        guard=ParkedVideoGuard(this,changed={allowed->
            if(allowed)startPlayer() else if(started){stopPlayer("driving restriction");finish()}else status.text="정차 상태에서만 영상을 볼 수 있어요."
        },log={log(it)})
    }
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun startPlayer(){
        if(started||stopped||isFinishing||isDestroyed||!guard.refresh())return
        started=true
        val origin="https://$packageName"
        val player=WebView(this);web=player
        player.contentDescription="장소 소개 영상 플레이어"
        player.setBackgroundColor(Color.BLACK)
        player.settings.apply{
            javaScriptEnabled=true;domStorageEnabled=true;mediaPlaybackRequiresUserGesture=false
            allowFileAccess=false;allowContentAccess=false;javaScriptCanOpenWindowsAutomatically=false
            mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        player.webChromeClient=object:WebChromeClient(){override fun onConsoleMessage(message:ConsoleMessage):Boolean{
            val text=message.message()
            if(text.startsWith("CORALNUT_PLAYER ")){
                log("YOUTUBE ${text.removePrefix("CORALNUT_PLAYER ")}")
                when{
                    text.startsWith("CORALNUT_PLAYER PLAYING")->{
                        main.removeCallbacks(playbackTimeout)
                        retry.visibility=android.view.View.GONE
                        status.text="정차 중 재생 · 소개 시점 %d:%02d".format(startSec/60,startSec%60)
                    }
                    text.startsWith("CORALNUT_PLAYER ERROR")->showError("영상을 재생하지 못했어요. 네트워크와 영상의 재생 허용 여부를 확인해주세요.")
                }
                return true
            };return false
        }}
        player.webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
                if(!request.isForMainFrame)return false
                return request.url.toString()!="about:blank"&&request.url.host!=packageName
            }
            override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){
                if(request.isForMainFrame){showError("영상 서버에 연결하지 못했어요.");log("YOUTUBE LOAD_ERROR code=${error.errorCode}")}
            }
        }
        container.addView(player,LinearLayout.LayoutParams(-1,-1))
        val html="""<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="strict-origin-when-cross-origin"><style>html,body,#player{margin:0;width:100%;height:100%;background:black;overflow:hidden}</style></head><body><div id="player"></div><script>
        var player,last=-1;function report(s){console.log('CORALNUT_PLAYER '+s);}
        function onYouTubeIframeAPIReady(){player=new YT.Player('player',{videoId:${JSONObject.quote(videoId)},playerVars:{start:$startSec,autoplay:1,playsinline:1,fs:0,rel:0,origin:${JSONObject.quote(origin)}},events:{
        onReady:function(e){report('READY start=$startSec');e.target.loadVideoById({videoId:${JSONObject.quote(videoId)},startSeconds:$startSec});},
        onStateChange:function(e){report('STATE '+e.data);if(e.data===1)report('PLAYING time='+player.getCurrentTime());},
        onError:function(e){report('ERROR '+e.data);}}});}
        setInterval(function(){if(player&&player.getPlayerState&&player.getPlayerState()===1){var t=Math.floor(player.getCurrentTime());if(t!==last){last=t;report('TIME '+t);}}},1000);
        </script><script src="https://www.youtube.com/iframe_api"></script></body></html>"""
        fun load(){
            if(stopped||!guard.refresh())return
            retry.visibility=android.view.View.GONE
            status.text="영상 소개 시점 %d:%02d부터 여는 중이에요.".format(startSec/60,startSec%60)
            log("VIDEO_OPEN videoId=$videoId requestedStart=$startSec")
            main.removeCallbacks(playbackTimeout)
            main.postDelayed(playbackTimeout,20000)
            player.loadDataWithBaseURL("$origin/",html,"text/html","UTF-8",null)
        }
        retry.setOnClickListener{load()}
        load()
    }
    private fun showError(message:String){
        if(stopped)return
        main.removeCallbacks(playbackTimeout)
        status.text=message
        retry.visibility=android.view.View.VISIBLE
    }
    private fun stopPlayer(reason:String){
        if(stopped)return;stopped=true
        main.removeCallbacks(playbackTimeout)
        web?.let{it.stopLoading();it.onPause();it.removeAllViews();container.removeView(it);it.destroy()};web=null
        log("VIDEO_STOP reason=$reason")
    }
    override fun onPause(){stopPlayer("activity paused");super.onPause();if(!isFinishing)finish()}
    override fun onDestroy(){stopPlayer("activity destroyed");if(::guard.isInitialized)guard.close();super.onDestroy()}
}
