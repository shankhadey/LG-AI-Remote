package com.home.tvremote.picks

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Serves the "What should we watch?" page to the TV's browser from the phone, over home Wi-Fi.
 * The page polls /s for state and sends clicks to /a; the phone does the real work (e.g. opening
 * the app on the TV). Every request must carry the one-time token, and only [allowedIp] (the TV)
 * may call it, so nobody else on the network can trigger anything.
 */
class PicksWeb(
    private val state: () -> String,
    private val action: (String, String) -> Unit,
    private val allowedIp: String?,
    /** What the TV pointer is over (e.g. "Seinfeld · Play"), shown on the phone. */
    private val focus: (String) -> Unit = {},
    /** What the page did (loaded, first pointer movement, clicks, script errors), for Diagnostics. */
    private val log: (String) -> Unit = {},
) {
    val token: String = ByteArray(9).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var socket: ServerSocket? = null
    val port: Int get() = socket?.localPort ?: 0

    fun start(preferredPort: Int = 8766) {
        if (socket != null) return
        val s = try { ServerSocket(preferredPort) } catch (_: Exception) { ServerSocket(0) }
        socket = s
        thread(isDaemon = true, name = "picks-web") {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { runCatching { handle(c) } }
            }
        }
    }

    fun stop() { runCatching { socket?.close() }; socket = null }

    private fun handle(c: Socket) = c.use {
        it.soTimeout = 3000
        val reader = BufferedReader(InputStreamReader(it.getInputStream()))
        val line = reader.readLine() ?: return@use
        while (true) { val h = reader.readLine() ?: break; if (h.isEmpty()) break }
        val target = line.split(' ').getOrNull(1) ?: "/"
        val path = target.substringBefore('?')
        val q = target.substringAfter('?', "").split('&').filter { p -> p.contains('=') }
            .associate { p -> p.substringBefore('=') to URLDecoder.decode(p.substringAfter('='), "UTF-8") }
        val remote = it.inetAddress.hostAddress
        val ok = q["k"] == token && (allowedIp == null || remote == allowedIp || remote == "127.0.0.1")
        val (status, type, body) = when {
            !ok -> Triple("403 Forbidden", "text/plain", "Forbidden")
            path == "/" || path == "/picks" -> { log("page requested by the TV"); Triple("200 OK", "text/html; charset=utf-8", localized()) }
            path == "/e" -> { log(com.home.tvremote.i18n.I18n.english(q["m"].orEmpty()).take(160)); Triple("200 OK", "application/json", "{}") }
            path == "/s" -> Triple("200 OK", "application/json", state())
            path == "/f" -> { focus(q["l"].orEmpty()); q["l"]?.takeIf { it.isNotEmpty() && it != "-" }?.let { log("pointing at ${com.home.tvremote.i18n.I18n.english(it)}") }; Triple("200 OK", "application/json", "{}") }
            path == "/a" -> { log("clicked " + q["do"].orEmpty() + " " + q["v"].orEmpty()); action(q["do"].orEmpty(), q["v"].orEmpty()); Triple("200 OK", "application/json", state()) }
            else -> Triple("404 Not Found", "text/plain", "Not found")
        }
        val bytes = body.toByteArray()
        it.getOutputStream().apply {
            write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            write(bytes); flush()
        }
    }

    fun url(phoneIp: String) = "http://$phoneIp:$port/picks?k=$token"

    companion object {
        /** 10-foot UI. Hovering focuses a button, so the remote's OK (Enter) clicks what the pointer is on. */
        /** The page's fixed phrases, in the app's language (button values sent back stay in English). */
        private val PHRASES = listOf("What should we watch?", "Choosing on the phone…", "Language", "Keep watching", "Pick a mood", "Surprise us",
            "Anything good", "Finding picks…", "Not now", "Never", "Show 5 more", "Back to moods", "Point with your phone · tap to choose",
            "Data from TMDB", "NEW")
        fun localized(): String {
            if (com.home.tvremote.i18n.I18n.lang == "en") return PAGE
            var h = PAGE
            for (p in PHRASES) h = h.replace(p, com.home.tvremote.i18n.I18n.t(p))
            h = h.replace("Who\\'s watching", com.home.tvremote.i18n.I18n.t("Who's watching").replace("'", "\\'"))
            h = h.replace("'▶ Play'", "'▶ " + com.home.tvremote.i18n.I18n.t("Play") + "'").replace("'Back','back'", "'" + com.home.tvremote.i18n.I18n.t("Back") + "','back'")
            return h.replace("<html lang=\"en\">", "<html lang=\"pt-BR\">")
        }
        val PAGE = """
<!doctype html><html lang="en"><head><meta charset="utf-8"><title>What should we watch?</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body{margin:0;height:100%;background:#0C0C0E;color:#F2F2F4;font-family:"LG Smart UI",Roboto,Helvetica,Arial,sans-serif;overflow:hidden}
#app{padding:5.5vh 6vw;height:100%;box-sizing:border-box;display:flex;flex-direction:column}
h1{margin:0;font-size:5.4vh;font-weight:700;letter-spacing:-.02em}
.ctx{color:#8E8E96;font-size:2.6vh;margin-top:1vh}
.lbl{color:#6A6A72;font-size:1.9vh;letter-spacing:.14em;text-transform:uppercase;margin:3.4vh 0 1.4vh}
.row{display:flex;gap:1.6vw;flex-wrap:wrap}
button{font:inherit;color:#F2F2F4;background:#1B1B1F;border:0;border-radius:2vh;padding:1.8vh 2.4vw;font-size:2.6vh;cursor:pointer;outline:none;transition:transform .12s,background .12s}
button.on{background:#F2F2F4;color:#0C0C0E}
button:focus,button:hover{background:#34343A;transform:scale(1.04);box-shadow:0 0 0 .5vh #F2F2F4}
button.on:focus,button.on:hover{background:#FFFFFF;color:#0C0C0E}
.mood{min-width:17vw;text-align:left;padding:2.6vh 2vw}
.mood b{display:block;font-size:3vh}.mood span{display:block;font-size:2vh;color:#8E8E96;margin-top:.6vh}
.mood:focus span,.mood:hover span{color:#C9C9CF}
.primary{background:#F2F2F4;color:#0C0C0E;font-weight:600}
.primary:focus,.primary:hover{background:#FFFFFF;color:#0C0C0E}
.primary span{color:#57575F}
.picks{display:flex;gap:1.4vw;margin-top:3vh;flex:1;min-height:0}
.card{flex:1;display:flex;flex-direction:column;gap:1.2vh;min-width:0}
.poster{height:38vh;flex:none;position:relative;border-radius:2vh;background:#222226 center/cover no-repeat;display:flex;align-items:flex-end;padding:2vh;box-sizing:border-box;font-size:3.6vh;font-weight:700}
.tag{position:absolute;left:1.4vh;top:1.4vh;background:#F2F2F4;color:#0C0C0E;font-size:1.7vh;font-weight:700;border-radius:.8vh;padding:.4vh .8vh}
.card h2{margin:0;font-size:2.8vh;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.meta{color:#8E8E96;font-size:1.8vh}.why{font-size:2vh;line-height:1.35;color:#D0D0D6;min-height:5.4vh}
.acts{display:flex;gap:.5vw}.acts button{padding:1.2vh .75vw;font-size:1.8vh;white-space:nowrap}
.big{font-size:4vh;color:#C9C9CF;margin:auto 0}
.foot{display:flex;justify-content:space-between;align-items:center;color:#5F5F67;font-size:1.8vh;margin-top:2vh}
</style></head><body><div id="app"><h1>What should we watch?</h1></div>
<script>
var k=new URLSearchParams(location.search).get('k'),v=-1,cur=null;
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function req(url,cb){var r=new XMLHttpRequest();r.open('GET',url+(url.indexOf('?')<0?'?':'&')+'k='+k+'&t='+Date.now());r.onload=function(){try{cb(JSON.parse(r.responseText))}catch(e){}};r.send();}
function label(b){var t=(b.querySelector('b')||b).textContent.replace('▶','').trim(),c=b.parentNode&&b.parentNode.parentNode,h=c&&c.className==='card'&&c.querySelector('h2');return h?h.textContent+' · '+t:t}
var told='';function tell(b){var l=label(b);if(l===told)return;told=l;req('/f?l='+encodeURIComponent(l),function(){})}
function note(m){req('/e?m='+encodeURIComponent(m),function(){})}
window.onerror=function(m,u,l){note('script error: '+m+' line '+l)};
var mx=-1,my=-1,seen={};function once(k,m){if(!seen[k]){seen[k]=1;note(m)}}
function under(){return mx<0?null:document.elementFromPoint(mx,my)}
function btnOf(e){while(e&&e.tagName!=='BUTTON')e=e.parentNode;return e}
document.addEventListener('mousemove',function(e){mx=e.clientX;my=e.clientY;once('mm','pointer seen by the page at '+mx+','+my);hover()},true);
var blankT=0;
// On blank space (for a moment, so crossing gaps doesn't flicker): drop the highlight and tell the phone.
function hover(clear){var b=btnOf(under());clearTimeout(blankT);
 if(!b){if(clear&&mx<0){told='';req('/f?l=',function(){})}else if(mx>=0)blankT=setTimeout(function(){if(btnOf(under()))return;var a=document.activeElement;if(a&&a.blur)a.blur();if(told!=='-'){told='-';req('/f?l=-',function(){})}},250);return}if(document.activeElement!==b)b.focus();tell(b)}
document.addEventListener('mousedown',function(e){var b=btnOf(e.target);note('tap landed on '+(b?label(b):'blank space at '+e.clientX+','+e.clientY))},true);
document.addEventListener('keydown',function(e){once('k'+e.keyCode,'key '+e.keyCode+' seen by the page');
 if(e.keyCode===13){var b=mx<0?btnOf(document.activeElement):btnOf(under());if(b){e.preventDefault();b.click()}}},true);
function act(d,val){req('/a?do='+d+'&v='+encodeURIComponent(val||''),show)}
function btn(id,cls,html,d,val){return '<button id="'+id+'" class="'+cls+'" data-d="'+d+'" data-v="'+esc(val)+'">'+html+'</button>'}
function show(s){if(!s||s.v===v)return;v=s.v;cur=s;var f=document.activeElement&&document.activeElement.id,h='';
 h+='<h1>'+esc(s.title)+'</h1><div class="ctx">'+esc(s.context)+'</div>';
 if(s.phase==='choose'){
  if(!s.leanBack){h+='<div class="big">Choosing on the phone…<br><span style="font-size:2.6vh;color:#8E8E96">'+esc(s.summary)+'</span></div>';}
  else{
   h+='<div class="lbl">Who\'s watching</div><div class="row">';s.who.forEach(function(w,i){h+=btn('w'+i,w.on?'on':'',esc(w.show||w.label),'who',w.label)});h+='</div>';
   if(s.langs.length>1){h+='<div class="lbl">Language</div><div class="row">';s.langs.forEach(function(l,i){h+=btn('l'+i,l.on?'on':'',esc(l.label),'lang',l.code)});h+='</div>';}
   if(s.keep){h+='<div class="lbl">Keep watching</div><div class="row">'+btn('kw','', '▶ '+esc(s.keep),'keep','')+'</div>';}
   h+='<div class="lbl">Pick a mood</div><div class="row">';s.moods.forEach(function(m,i){h+=btn('m'+i,'mood','<b>'+esc(m.label)+'</b><span>'+esc(m.hint)+'</span>','mood',m.id)});
   h+=btn('sur','mood primary','<b>Surprise us</b><span>Anything good</span>','surprise','')+'</div>';
  }
 } else if(s.phase==='loading'){h+='<div class="big">Finding picks…</div>';}
 else if(s.phase==='error'){h+='<div class="big">'+esc(s.message)+'</div><div class="row">'+btn('back','primary','Back','back','')+'</div>';}
 else if(s.phase==='playing'){h+='<div class="big">'+esc(s.message)+'</div>';}
 else if(s.phase==='picks'){
  h+='<div class="picks">';s.picks.forEach(function(p,i){
   h+='<div class="card"><div class="poster" style="'+(p.poster?'background-image:url('+esc(p.poster)+')':'background-color:'+esc(p.color))+'">'+(p.poster?'':esc(p.name))+(p.fresh?'<span class="tag">NEW</span>':'')+'</div>'+
   '<h2>'+esc(p.name)+'</h2><div class="meta">'+esc(p.meta)+'</div><div class="why">'+esc(p.why)+'</div>'+
   '<div class="acts">'+btn('p'+i,'primary','▶ Play','play',i)+btn('n'+i,'','Not now','notnow',i)+btn('x'+i,'','Never','never',i)+'</div></div>';});
  h+='</div><div class="row" style="margin-top:2.4vh">'+btn('more','','Show 5 more','more','')+(s.leanBack?btn('back','','Back to moods','back',''):'')+'</div>';
 }
 h+='<div class="foot"><span>Point with your phone · tap to choose</span><span>Data from TMDB</span></div>';
 document.getElementById('app').innerHTML=h;
 var bs=[].slice.call(document.querySelectorAll('button'));
 bs.forEach(function(b){b.onclick=function(){act(b.getAttribute('data-d'),b.getAttribute('data-v'))};b.onmouseover=function(){b.focus()};b.onfocus=function(){tell(b)}});
 var keep=f&&document.getElementById(f)||(mx<0?bs[0]:null);keep&&keep.focus();told='';hover(true);}
// Arrow keys move between buttons (for remotes without a pointer).
document.addEventListener('keydown',function(e){var bs=[].slice.call(document.querySelectorAll('button'));if(!bs.length)return;
 var a=document.activeElement,r=a&&a.getBoundingClientRect?a.getBoundingClientRect():null,best=null,bd=1e9,d={37:[-1,0],38:[0,-1],39:[1,0],40:[0,1]}[e.keyCode];
 if(!d||!r)return;bs.forEach(function(b){if(b===a)return;var q=b.getBoundingClientRect(),dx=(q.left+q.width/2)-(r.left+r.width/2),dy=(q.top+q.height/2)-(r.top+r.height/2);
 if(d[0]&&Math.sign(dx)!==d[0]||d[1]&&Math.sign(dy)!==d[1])return;var dist=Math.abs(dx)+Math.abs(dy)*(d[0]?3:1)+Math.abs(dx)*(d[1]?3:0);if(dist<bd){bd=dist;best=b}});
 if(best){best.focus();e.preventDefault()}});
note('page loaded '+window.innerWidth+'x'+window.innerHeight+' · '+navigator.userAgent.slice(0,90));
req('/s',show);setInterval(function(){req('/s',show)},800);
</script></body></html>
""".trimIndent()
    }
}
