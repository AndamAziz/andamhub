package uk.andam.app.net

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import uk.andam.app.Config
import uk.andam.app.auth.Session

/** CEO admin panel for the app: same data and actions as ip.andam.uk/admin (admins only). */
object AdminApi {

    data class ProviderRef(val id: String, val name: String, val active: Boolean)
    data class Relay(val ok: Boolean, val detail: String, val ms: Long)
    data class ErrorItem(val kind: String, val status: Int, val message: String, val at: String)
    data class LoginItem(val email: String, val at: String, val device: String)
    data class Overview(
        val providers: List<ProviderRef>,
        val activeProviders: Int,
        val totalProviders: Int,
        val totalUsers: Int,
        val relay: Relay,
        val errors: List<ErrorItem>,
        val logins: List<LoginItem>,
    )

    data class Redemption(val email: String, val at: String)
    data class Code(
        val id: String,
        val code: String,
        val provider: String,
        val sections: List<String>,
        val note: String,
        val expiresAt: String?,
        val maxUses: Int,
        val uses: Int,
        val status: String,
        val createdAt: String,
        val redeemedBy: List<Redemption>,
    )
    data class Codes(val codes: List<Code>, val providers: List<ProviderRef>)

    data class User(
        val id: String,
        val email: String,
        val name: String,
        val admin: Boolean,
        val suspended: Boolean,
        val createdAt: String,
        val lastLoginAt: String?,
        val sections: List<String>,
    )

    // ---------------- reads ----------------

    suspend fun overview(): Overview {
        val j = get("overview")
        val r = j.optJSONObject("relay") ?: JSONObject()
        return Overview(
            providers = j.optJSONArray("providers").objects { ProviderRef(it.s("id"), it.s("name"), it.optBoolean("active")) },
            activeProviders = j.optInt("activeProviders"),
            totalProviders = j.optInt("totalProviders"),
            totalUsers = j.optInt("totalUsers"),
            relay = Relay(r.optBoolean("ok"), r.s("detail"), r.optLong("ms")),
            errors = j.optJSONArray("recentErrors").objects { ErrorItem(it.s("kind"), it.optInt("status"), it.s("message"), it.s("created_at")) },
            logins = j.optJSONArray("recentLogins").objects { LoginItem(it.s("email"), it.s("created_at"), it.s("user_agent")) },
        )
    }

    suspend fun codes(): Codes {
        val j = get("codes")
        return Codes(
            codes = j.optJSONArray("codes").objects { c ->
                Code(
                    id = c.s("id"), code = c.s("code"), provider = c.s("provider"),
                    sections = c.optJSONArray("sections").strings(), note = c.s("note"),
                    expiresAt = c.s("expiresAt").ifBlank { null }, maxUses = c.optInt("maxUses"),
                    uses = c.optInt("uses"), status = c.s("status"), createdAt = c.s("createdAt"),
                    redeemedBy = c.optJSONArray("redeemedBy").objects { Redemption(it.s("email"), it.s("at")) },
                )
            },
            providers = j.optJSONArray("providers").objects { ProviderRef(it.s("id"), it.s("name"), it.optBoolean("active")) },
        )
    }

    suspend fun users(): List<User> = get("users").optJSONArray("users").objects { u ->
        User(
            id = u.s("id"), email = u.s("email"), name = u.s("displayName"),
            admin = u.s("role") == "admin", suspended = u.optBoolean("suspended"),
            createdAt = u.s("createdAt"), lastLoginAt = u.s("lastLoginAt").ifBlank { null },
            sections = u.optJSONArray("sections").strings(),
        )
    }

    // ---------------- actions ----------------

    /** Creates a code and returns it (e.g. "ABCD-EFGH-JKLM"). `days` 0 = never expires. */
    suspend fun createCode(sourceId: String?, sections: List<String>, maxUses: Int, days: Int, note: String): String =
        post(
            JSONObject().put("action", "code_create").put("sourceId", sourceId ?: JSONObject.NULL)
                .put("sections", JSONArray(sections)).put("maxUses", maxUses).put("days", days).put("note", note),
        ).s("code")

    suspend fun renewCode(id: String, days: Int, extraUses: Int = 0) {
        post(JSONObject().put("action", "code_renew").put("id", id).put("days", days).put("extraUses", extraUses))
    }

    suspend fun revokeCode(id: String) { post(JSONObject().put("action", "code_revoke").put("id", id)) }
    suspend fun deleteCode(id: String) { post(JSONObject().put("action", "code_delete").put("id", id)) }

    suspend fun setSection(userId: String, section: String, grant: Boolean) {
        post(JSONObject().put("action", "user_section").put("userId", userId).put("section", section).put("grant", grant))
    }

    suspend fun setSuspended(userId: String, suspended: Boolean) {
        post(JSONObject().put("action", "user_suspend").put("userId", userId).put("suspended", suspended))
    }

    suspend fun setAdmin(userId: String, admin: Boolean) {
        post(JSONObject().put("action", "user_role").put("userId", userId).put("role", if (admin) "admin" else "user"))
    }

    // ---------------- plumbing ----------------

    private const val PATH = "/api/public/admin"

    private suspend fun get(action: String): JSONObject = withContext(Dispatchers.IO) {
        val url = Uri.parse(Config.BASE_URL + PATH).buildUpon().appendQueryParameter("action", action).build().toString()
        send(Request.Builder().url(url).get())
    }

    private suspend fun post(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        send(Request.Builder().url(Config.BASE_URL + PATH).post(body.toString().toRequestBody("application/json".toMediaType())))
    }

    private suspend fun send(b: Request.Builder): JSONObject {
        val token = Session.accessToken() ?: throw ApiException(401, "Sign in first.")
        val req = b.header("Authorization", "Bearer $token").header("Accept", "application/json")
            .header("User-Agent", Config.USER_AGENT).header("Cache-Control", "no-cache").build()
        return Api.http.newCall(req).execute().use { res ->
            val j = runCatching { JSONObject(res.body?.string().orEmpty()) }.getOrElse { JSONObject() }
            if (!res.isSuccessful || j.s("error").isNotBlank()) {
                throw ApiException(res.code, j.s("error").ifBlank { "Request failed (${res.code})" })
            }
            j
        }
    }

    private fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k, "")

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }

    private inline fun <T> JSONArray?.objects(f: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) optJSONObject(i)?.let { out.add(f(it)) }
        return out
    }
}
