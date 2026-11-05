package io.github.petebutty.viewarr.setup

import android.content.Context
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/**
 * Serves the phone setup app while the TV's setup screen is open.
 * The QR carries a random 128-bit path token (no token = 404) and the TV's X25519 public key in the URL
 * fragment (never sent over the network). Every API call from the phone is sealed with X25519 + HKDF-SHA256 +
 * ChaCha20-Poly1305, so passwords and keys never cross the LAN in clear and nobody without the QR can drive setup.
 * Replies carry only status text and short link codes. Too many bad requests shuts it down.
 */
class PhoneSetupServer(
    private val context: Context,
    private val handler: suspend (action: String, values: Map<String, String>) -> JsonObject,
) {
    private val random = SecureRandom()
    private val token = ByteArray(16).also(random::nextBytes)
    private val privateKey = ByteArray(X25519.SCALAR_SIZE).also { X25519.generatePrivateKey(random, it) }
    private val publicKey = ByteArray(X25519.POINT_SIZE).also { X25519.generatePublicKey(privateKey, 0, it, 0) }
    private val tokenText = b64(token)
    private val running = AtomicBoolean(false)
    private val badRequests = AtomicInteger(0)
    private val pool = Executors.newFixedThreadPool(4)
    private var socket: ServerSocket? = null

    /** Starts listening and returns the URL to encode in the QR, or null if there's no LAN address. */
    fun start(): String? {
        val address = lanAddress() ?: return null
        val server = (PORT_FIRST..PORT_LAST).firstNotNullOfOrNull { runCatching { ServerSocket(it) }.getOrNull() } ?: return null
        socket = server
        running.set(true)
        Thread {
            while (running.get()) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { pool.execute { runCatching { handle(client) } } }
            }
        }.apply { isDaemon = true }.start()
        return "http://$address:${server.localPort}/$tokenText#${b64(publicKey)}"
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        pool.shutdownNow()
    }

    private fun handle(client: Socket) = client.use { s ->
        s.soTimeout = 10_000
        val input = BufferedInputStream(s.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        val out = s.getOutputStream()
        when {
            method == "GET" && path == "/$tokenText" ->
                respond(out, 200, "text/html; charset=utf-8", context.assets.open("setup/index.html").use { String(it.readBytes()) }.replace("__TOKEN__", tokenText).toByteArray())
            method == "GET" && path == "/$tokenText/crypto.js" ->
                respond(out, 200, "application/javascript", context.assets.open("setup/viewarr-crypto.js").use { it.readBytes() })
            method == "POST" && path == "/$tokenText/api" -> {
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length <= 0 || length > MAX_BODY) return reject(out)
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val request = runCatching { open(String(body, 0, read)) }.getOrNull() ?: return reject(out)
                val action = request.first
                val reply = runCatching { runBlocking { handler(action, request.second) } }
                    .getOrElse { JsonObject(mapOf("ok" to JsonPrimitive(false), "message" to JsonPrimitive("Something went wrong on the TV. Try again."))) }
                respond(out, 200, "application/json", reply.toString().toByteArray())
            }
            else -> reject(out)
        }
    }

    private fun reject(out: OutputStream) {
        respond(out, 404, "text/plain", "Not found".toByteArray())
        if (badRequests.incrementAndGet() >= MAX_BAD) stop()
    }

    /** Decrypts the phone's sealed request: {"action": "...", "values": {...}}. */
    private fun open(sealed: String): Pair<String, Map<String, String>> {
        val envelope = Json.parseToJsonElement(sealed).jsonObject
        val phonePublic = unb64(envelope.string("pub"))
        val nonce = unb64(envelope.string("nonce"))
        val data = unb64(envelope.string("data"))
        val shared = ByteArray(32)
        require(X25519.calculateAgreement(privateKey, 0, phonePublic, 0, shared, 0))
        val key = ByteArray(32)
        HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(shared, token, INFO)) }.generateBytes(key, 0, 32)
        val cipher = ChaCha20Poly1305().apply { init(false, AEADParameters(KeyParameter(key), 128, nonce)) }
        val plain = ByteArray(cipher.getOutputSize(data.size))
        val n = cipher.processBytes(data, 0, data.size, plain, 0)
        val total = n + cipher.doFinal(plain, n)
        val request = Json.parseToJsonElement(String(plain, 0, total, Charsets.UTF_8)).jsonObject
        val values = (request["values"] as? JsonObject).orEmpty()
            .mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }
        return request.string("action") to values
    }

    private fun JsonObject.string(key: String) = (this[key] as JsonPrimitive).content

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = if (code == 200) "OK" else "Not Found"
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8.name()).trimEnd('\r')
            if (buf.size() > 8_192) return null
            buf.write(b)
        }
    }

    companion object {
        private const val PORT_FIRST = 8765
        private const val PORT_LAST = 8775
        private const val MAX_BODY = 64 * 1024
        private const val MAX_BAD = 30
        private val INFO = "viewarr-phone-setup-v1".toByteArray()

        private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        private fun unb64(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
