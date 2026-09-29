package uk.andam.app.auth

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import uk.andam.app.Config
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Supabase session kept on the device (same account system as the website). */
object Session {
    data class User(val email: String)

    private lateinit var prefs: SharedPreferences
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val lock = Mutex()

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user

    /** True once the person chose to continue without an account (IPTV only). */
    private val _guest = MutableStateFlow(false)
    val guest: StateFlow<Boolean> = _guest

    fun init(context: Context) {
        prefs = context.getSharedPreferences("andam_session", Context.MODE_PRIVATE)
        val email = prefs.getString("email", null)
        if (prefs.getString("refresh", null) != null) _user.value = User(email ?: "")
        _guest.value = prefs.getBoolean("guest", false)
    }

    fun continueAsGuest() {
        prefs.edit().putBoolean("guest", true).apply()
        _guest.value = true
    }

    val signedIn: Boolean get() = prefs.getString("refresh", null) != null

    private fun save(json: JSONObject) {
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        val expiresIn = json.optLong("expires_in", 3600)
        val email = json.optJSONObject("user")?.optString("email") ?: prefs.getString("email", "") ?: ""
        saveTokens(access, refresh, expiresIn, email)
    }

    private fun saveTokens(access: String, refresh: String, expiresIn: Long, email: String) {
        prefs.edit()
            .putString("access", access)
            .putString("refresh", refresh)
            .putLong("expires_at", System.currentTimeMillis() + expiresIn * 1000)
            .putString("email", email)
            .putBoolean("guest", false)
            .apply()
        _guest.value = false
        _user.value = User(email)
    }

    fun signOut() {
        prefs.edit().clear().apply()
        _user.value = null
        _guest.value = false
    }

    private fun authRequest(grant: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("${Config.SUPABASE_URL}/auth/v1/token?grant_type=$grant")
            .header("apikey", Config.SUPABASE_KEY)
            .header("Authorization", "Bearer ${Config.SUPABASE_KEY}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!res.isSuccessful) {
                val msg = json.optString("error_description")
                    .ifBlank { json.optString("msg") }
                    .ifBlank { json.optString("message") }
                    .ifBlank { "Sign-in failed (${res.code})" }
                throw IllegalStateException(msg)
            }
            return json
        }
    }

    suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        val json = authRequest("password", JSONObject().put("email", email.trim()).put("password", password))
        save(json)
    }

    /** A valid access token, refreshed when it is about to expire; null for guests. */
    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            val refresh = prefs.getString("refresh", null) ?: return@withLock null
            val expiresAt = prefs.getLong("expires_at", 0)
            val access = prefs.getString("access", null)
            if (access != null && System.currentTimeMillis() < expiresAt - 60_000) return@withLock access
            try {
                save(authRequest("refresh_token", JSONObject().put("refresh_token", refresh)))
                prefs.getString("access", null)
            } catch (e: Exception) {
                // Refresh token revoked or expired: sign out cleanly.
                if (e is IllegalStateException) signOut()
                null
            }
        }
    }

    // ---------- Google / Apple (Lovable OAuth broker, same as the web app) ----------

    fun oauthUrl(provider: String): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val state = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("oauth_state", state).apply()
        return Uri.parse("${Config.BASE_URL}/~oauth/initiate").buildUpon()
            .appendQueryParameter("provider", provider)
            .appendQueryParameter("redirect_uri", Config.OAUTH_CALLBACK)
            .appendQueryParameter("state", state)
            .build().toString()
    }

    /** Handles lovable://oauth-callback?... Returns an error message, or null on success. */
    suspend fun finishOAuth(uri: Uri): String? = withContext(Dispatchers.IO) {
        val hash = Uri.parse("x://x?" + (uri.encodedFragment ?: ""))
        fun param(name: String): String? = uri.getQueryParameter(name) ?: hash.getQueryParameter(name)
        val expected = prefs.getString("oauth_state", null)
        prefs.edit().remove("oauth_state").apply()
        param("error_description")?.let { return@withContext it }
        param("error")?.let { return@withContext it }
        if (expected == null || param("state") != expected) return@withContext "The sign-in response could not be verified. Please try again."
        val access = param("access_token")
        val refresh = param("refresh_token")
        if (access.isNullOrBlank() || refresh.isNullOrBlank()) return@withContext "The sign-in provider did not return a complete session."
        val expiresIn = param("expires_in")?.toLongOrNull() ?: 3600
        saveTokens(access, refresh, expiresIn, "")
        // Fetch the email for the account screen (best effort).
        runCatching {
            val req = Request.Builder().url("${Config.SUPABASE_URL}/auth/v1/user")
                .header("apikey", Config.SUPABASE_KEY)
                .header("Authorization", "Bearer $access").build()
            http.newCall(req).execute().use { res ->
                val email = JSONObject(res.body?.string().orEmpty()).optString("email")
                if (email.isNotBlank()) {
                    prefs.edit().putString("email", email).apply()
                    _user.value = User(email)
                }
            }
        }
        null
    }
}
