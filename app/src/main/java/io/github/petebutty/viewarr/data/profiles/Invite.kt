package io.github.petebutty.viewarr.data.profiles

import android.util.Base64
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.net.Reach
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.repository.AuthRepository
import io.github.petebutty.viewarr.data.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Invites: how someone in another house signs in. The admin's TV (at home) makes a code holding the Dispatcharr
 * address (home and away), the person's username and their API key, locked with their password. They paste it on
 * their TV's phone setup page and type the password: no login page has to be open to the internet (only the
 * key-based API, which their apps use already).
 */
@Serializable
data class InvitePayload(
    // Short names: the invite goes in a QR code, and every character makes it denser.
    @kotlinx.serialization.SerialName("h") val home: String,
    @kotlinx.serialization.SerialName("u") val user: String,
    @kotlinx.serialization.SerialName("k") val key: String,
    /** Away addresses (home base → away base), for Dispatcharr and the other servers in the setup. */
    @kotlinx.serialization.SerialName("a") val away: Map<String, String> = emptyMap(),
    val v: Int = 1,
)

object InviteCode {
    private const val PREFIX = "VIEWARR1."
    private const val ROUNDS = 150_000

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ROUNDS, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    fun seal(plain: String, password: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        c.updateAAD(PREFIX.toByteArray())
        val out = salt + iv + c.doFinal(plain.toByteArray())
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    /** The code's contents, or null if it isn't an invite or the password is wrong. */
    fun open(code: String, password: String): String? = runCatching {
        // Whatever the messaging app did to it: spaces, line breaks, text around it.
        val clean = code.filterNot { it.isWhitespace() }
        val at = clean.indexOf(PREFIX)
        if (at < 0) return null
        val body = clean.substring(at + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        val all = Base64.decode(body, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password, all.copyOfRange(0, 16)), GCMParameterSpec(128, all, 16, 12))
        c.updateAAD(PREFIX.toByteArray())
        String(c.doFinal(all, 28, all.size - 28))
    }.getOrNull()

    fun looksLikeInvite(text: String) = text.filterNot { it.isWhitespace() }.contains(PREFIX)
}

@Singleton
class Invites @Inject constructor(
    private val http: HttpJson,
    private val users: DispatcharrUsers,
    private val accounts: AccountStore,
    private val auth: AuthRepository,
    private val json: Json,
    private val sync: io.github.petebutty.viewarr.data.sync.SettingsSync,
) {
    /**
     * An invite for [u] (admin, at home). [password] must be their Dispatcharr password: it's checked here (on the
     * home network, where the login works), so they only ever have the one password.
     */
    suspend fun create(admin: Session, u: DUser, password: String, knownKey: String? = null): Result<String> = runCatching {
        val away = accounts.current().away
        val home = Reach.normalise(admin.serverUrl, away = false) ?: error("This TV's Dispatcharr address isn't usable.")
        check(away.keys.any { Reach.normalise(it, false) == home }) { "Set Dispatcharr's away address first (Away addresses)." }
        val login = http.post(
            admin.serverUrl.trimEnd('/') + "/api/accounts/token/",
            buildJsonObject { put("username", JsonPrimitive(u.username)); put("password", JsonPrimitive(password)) },
        )
        check(login.ok) { if (login.code in listOf(400, 401)) "That isn't ${u.username}'s password. Reset it first if you don't know it." else "Dispatcharr didn't answer the password check (error ${login.code})." }
        // The key just made for them, if any: asking again would make a new one and lock out their saved setup.
        val key = knownKey ?: users.apiKeyFor(admin, u) ?: error("Dispatcharr wouldn't give a key for ${u.username}.")
        // Default ports left out of the away addresses (https://x:443 → https://x): a shorter invite, an easier QR.
        val short = away.mapValues { (_, v) -> v.removeSuffix(":443").let { if (it.startsWith("http://")) it.removeSuffix(":80") else it } }
        val payload = InvitePayload(home = admin.serverUrl.trimEnd('/'), user = u.username, key = key, away = short)
        withContext(Dispatchers.Default) { InviteCode.seal(json.encodeToString(InvitePayload.serializer(), payload), password) }
    }

    /**
     * Signs this TV in from an invite: away addresses first (so it can find the server from here), then their
     * key. Their saved setup is offered straight after, as for any Dispatcharr sign-in.
     */
    suspend fun redeem(code: String, password: String): Result<Session> {
        if (!InviteCode.looksLikeInvite(code)) return Result.failure(IllegalStateException("That isn't a Viewarr invite. Paste the whole message you were sent."))
        val plain = withContext(Dispatchers.Default) { InviteCode.open(code, password) }
            ?: return Result.failure(IllegalStateException("Wrong password, or the invite was cut short. Check both and try again."))
        val p = runCatching { json.decodeFromString(InvitePayload.serializer(), plain) }.getOrNull()
            ?: return Result.failure(IllegalStateException("This invite is from a newer Viewarr. Update Viewarr and try again."))
        accounts.update { it.copy(away = it.away + p.away) }
        Reach.setPairs(accounts.current().away)
        val signed = auth.signInWithKey(p.home, p.key)
        val session = signed.getOrNull() ?: return signed
        applyPrepared(session)
        // Their away addresses stay, whatever the saved copy had.
        accounts.update { it.copy(away = it.away + p.away) }
        return signed
    }

    /**
     * Someone invited signing in with the details their admin sent: the server's address (from outside) and their
     * API key. Their prepared setup goes straight on, and setup is finished.
     */
    suspend fun signInWithDetails(address: String, key: String): Result<Session> {
        if (address.isBlank() || key.isBlank()) return Result.failure(IllegalStateException("Enter the server address and the API key you were sent."))
        val found = auth.probe(address)
        val url = (found as? io.github.petebutty.viewarr.data.repository.ProbeResult.Found)?.url
            ?: return Result.failure(IllegalStateException("No Viewarr server found at that address. Check it with whoever sent it."))
        val signed = auth.signInWithKey(url, key.trim())
        val session = signed.getOrNull() ?: return signed
        applyPrepared(session)
        return signed
    }

    /** Their admin's prepared setup on (only when its sign-ins can be read), and setup finished. */
    private suspend fun applyPrepared(session: Session) {
        runCatching {
            val copy = sync.savedCopy(session)
            val snap = copy?.let { sync.snapshotOf(it, session) }
            // Only when its sign-ins could be read: otherwise this TV would save an empty setup over the one
            // their admin prepared (restore switches saving on).
            val readable = snap != null && ((snap["accounts"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "{}") != "{}"
            if (snap != null && (readable || copy["signIns"] == null)) sync.restore(snap)
        }
        accounts.setSetupComplete(true)
    }
}
