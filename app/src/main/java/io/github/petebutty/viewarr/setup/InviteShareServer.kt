package io.github.petebutty.viewarr.setup

import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * While an invite is on screen: a one-page site on the TV with the invite and a Copy button, so the admin's phone
 * (on the same Wi-Fi) gets it from a short, easy QR instead of a dense one. The invite is already locked with the
 * person's password; the page also sits behind a random path, and the server stops when the invite screen closes.
 */
class InviteShareServer(private val who: String, private val fields: List<Pair<String, String>>) {
    private val token = ByteArray(12).also(SecureRandom()::nextBytes)
        .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) }
    private val running = AtomicBoolean(false)
    private var socket: ServerSocket? = null

    /** The page's address (for the QR), or null with no Wi-Fi / network address. */
    fun start(): String? {
        val address = PhoneSetupServer.lanAddress() ?: return null
        val server = (8776..8786).firstNotNullOfOrNull { runCatching { ServerSocket(it) }.getOrNull() } ?: return null
        socket = server
        running.set(true)
        Thread {
            while (running.get()) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { s ->
                        s.soTimeout = 5_000
                        val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine().orEmpty()
                        val ok = line.startsWith("GET /$token ") || line.startsWith("GET /$token?")
                        val body = (if (ok) page() else "Not found").toByteArray()
                        val head = "HTTP/1.1 ${if (ok) "200 OK" else "404 Not Found"}\r\nContent-Type: ${if (ok) "text/html; charset=utf-8" else "text/plain"}\r\n" +
                            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                        s.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                    }
                }
            }
        }.apply { isDaemon = true; name = "invite-share" }.start()
        return "http://$address:${server.localPort}/$token"
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(): String {
        val rows = fields.mapIndexed { i, (label, value) ->
            "<label>${esc(label)}</label><div class=\"v\" id=\"v$i\">${esc(value)}</div><button onclick=\"cp($i)\" id=\"b$i\">Copy ${esc(label.lowercase())}</button>"
        }.joinToString("")
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Viewarr: ${esc(who)}</title><style>
body{margin:0;font:16px/1.45 system-ui,sans-serif;background:#0f1115;color:#eee;padding:20px}
h1{font-size:22px;margin:0 0 8px}p{color:#bbb}label{display:block;margin-top:18px;color:#f97316;font-weight:600}
.v{word-break:break-all;background:#1a1d24;border:1px solid #333;border-radius:10px;padding:12px;font:15px monospace;margin-top:6px}
button{width:100%;padding:14px;border:0;border-radius:12px;background:#f97316;color:#000;font-size:17px;font-weight:600;margin-top:8px}
</style></head><body>
<h1>Viewarr for ${esc(who)}</h1>
<p>Send these to ${esc(who)}. On their TV: Set up on your phone → Live TV → Dispatcharr, type the address and paste the API key.</p>
$rows
<script>
function cp(i){var t=document.getElementById('v'+i).textContent,b=document.getElementById('b'+i);
var done=function(){b.textContent='Copied';};
if(navigator.clipboard&&window.isSecureContext){navigator.clipboard.writeText(t).then(done);return;}
var a=document.createElement('textarea');a.value=t;a.style.position='fixed';a.style.opacity='0';document.body.appendChild(a);a.select();
try{document.execCommand('copy');done();}catch(e){}a.remove();}
</script></body></html>"""
    }
}
