package com.home.tvremote.timer

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A tiny web server on the phone that serves the full-screen timer page to the TV's browser over
 * the home Wi-Fi. Nothing is hosted on the internet; +10 min / Cancel on the phone show up on the
 * TV within a second because the page polls /state.
 */
class TimerServer(private val state: () -> TimerState?) {
    private var socket: ServerSocket? = null
    val port: Int get() = socket?.localPort ?: 0

    fun start(preferredPort: Int = 8765) {
        if (socket != null) return
        val s = try { ServerSocket(preferredPort) } catch (_: Exception) { ServerSocket(0) }
        socket = s
        thread(isDaemon = true, name = "timer-server") {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { handle(c) }
            }
        }
    }

    fun stop() { runCatching { socket?.close() }; socket = null }

    private fun handle(c: Socket) = c.use {
        it.soTimeout = 3000
        val reader = BufferedReader(InputStreamReader(it.getInputStream()))
        val requestLine = reader.readLine() ?: return@use
        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
        val path = requestLine.split(' ').getOrNull(1) ?: "/"
        val (status, type, body) = when {
            path.startsWith("/state") -> Triple("200 OK", "application/json", stateJson())
            path == "/" || path.startsWith("/timer") -> Triple("200 OK", "text/html; charset=utf-8", PAGE)
            else -> Triple("404 Not Found", "text/plain", "Not found")
        }
        val bytes = body.toByteArray()
        val out = it.getOutputStream()
        out.write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    internal fun stateJson(): String {
        val s = state()
        val running = s != null && s.kind == TimerKind.OnScreen
        return """{"now":${System.currentTimeMillis()},"running":$running,"endsAt":${s?.endsAt ?: 0},"total":${s?.totalMs ?: 0}}"""
    }

    companion object {
        /** Big, quiet, 10-foot countdown. Keeps counting from its last known end time if the phone disappears. */
        val PAGE = """
<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Timer</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body{margin:0;height:100%;background:#0C0C0E;color:#F2F2F4;font-family:"LG Smart UI",Roboto,Helvetica,Arial,sans-serif;overflow:hidden;cursor:none}
.wrap{height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4vh}
.label{font-size:3vh;letter-spacing:.3em;color:#8E8E96;text-transform:uppercase}
.time{font-size:30vh;font-weight:300;font-variant-numeric:tabular-nums;letter-spacing:-.02em;line-height:1}
.bar{width:50vw;height:.8vh;border-radius:1vh;background:#222226;overflow:hidden}
.fill{height:100%;background:#F2F2F4;width:100%;transition:width 1s linear}
.hint{font-size:2.4vh;color:#6A6A72;min-height:3vh}
.done .time{font-size:16vh;font-weight:600}
.done .bar{display:none}
.done .hint{font-size:3vh}
</style></head><body><div class="wrap" id="w">
<div class="label" id="l">Timer</div><div class="time" id="t">--:--</div>
<div class="bar"><div class="fill" id="f"></div></div><div class="hint" id="h"></div></div>
<script>
var endsAt=0,total=0,offset=0,running=true,seen=false;
function fmt(ms){var s=Math.max(0,Math.ceil(ms/1000)),h=Math.floor(s/3600),m=Math.floor(s%3600/60),x=s%60;
 return (h?h+':'+(m<10?'0':''):'')+m+':'+(x<10?'0':'')+x;}
function poll(){var r=new XMLHttpRequest();r.open('GET','/state?'+Date.now());r.onload=function(){try{var j=JSON.parse(r.responseText);
 offset=j.now-Date.now();if(j.running){endsAt=j.endsAt;total=j.total;running=true;seen=true;}else if(seen&&endsAt-(Date.now()+offset)>1500){running=false;}}catch(e){}};r.send();}
function draw(){var left=endsAt-(Date.now()+offset),w=document.getElementById('w');
 if(!seen){return;}
 if(!running){w.className='wrap done';document.getElementById('l').textContent='';document.getElementById('t').textContent='Timer cancelled';document.getElementById('h').textContent='Press Back to return';return;}
 if(left<=0){w.className='wrap done';document.getElementById('l').textContent='';document.getElementById('t').textContent="Time's up";document.getElementById('f').style.width='0%';document.getElementById('h').textContent='Press Back to return';return;}
 w.className='wrap';document.getElementById('t').textContent=fmt(left);document.getElementById('f').style.width=(total?Math.max(0,left/total*100):0)+'%';}
poll();setInterval(poll,1000);setInterval(draw,200);
</script></body></html>
""".trimIndent()
    }
}
