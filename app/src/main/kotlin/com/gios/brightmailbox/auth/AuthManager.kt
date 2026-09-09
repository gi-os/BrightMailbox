package com.gios.brightmailbox.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.gios.brightmailbox.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** The refresh token was revoked or expired; only fresh consent will fix it. */
class ReauthRequired(val accountId: String, message: String) : IOException(message)

/** One signed-in mailbox. */
data class Account(
    val id: String,
    val service: Service,
    val email: String,
) {
    val label: String get() = service.label
}

/**
 * OAuth against Google and Microsoft, by hand.
 *
 * Hand-rolled because AppAuth cannot sign in on a Light Phone III — confirmed on
 * hardware. Its BrowserSelector enumerates browsers through PackageManager and keeps
 * only those whose intent filter claims CATEGORY_BROWSABLE *and* the bare http scheme
 * with no host, its definition of a "full browser". The LightOS browser fails that test,
 * the candidate list comes back empty, and the library throws ActivityNotFoundException
 * before making a single request. AnyBrowserMatcher does not help: the matcher filters a
 * list that is already empty.
 *
 * A plain ACTION_VIEW intent has neither restriction — implicit launches do not consult
 * package visibility, and any activity that handles an https URL will take it. So the
 * flow is PKCE plus ACTION_VIEW plus a custom-scheme redirect back into MainActivity.
 *
 * Reach for ACTION_VIEW before any OAuth library on LightOS.
 *
 * The browser can also be skipped entirely: see [adopt] and scripts/authorize.py, the
 * same scan-a-credential-in trick every other app here uses.
 */
class AuthManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("brightmailbox_auth", Context.MODE_PRIVATE)
    private val lock = Mutex()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /* --------------------------------------------------------------- client ids */

    fun clientId(s: Service): String =
        prefs.getString("client_${s.key}", null)?.takeIf { it.isNotBlank() }
            ?: when (s) {
                Service.GOOGLE -> BuildConfig.GOOGLE_CLIENT_ID
                Service.MICROSOFT -> BuildConfig.MICROSOFT_CLIENT_ID
            }

    fun isConfigured(s: Service): Boolean = clientId(s).isNotEmpty()

    /**
     * A Desktop-type client secret, present only when credentials came from
     * scripts/authorize.py. Google expects that secret to ship inside installed apps and
     * wants it back on every refresh; an Android-type client has none.
     */
    private fun clientSecret(id: String): String? =
        prefs.getString("secret_$id", null)?.takeIf { it.isNotBlank() }

    /**
     * Store a client id. Returns false if it does not look like one, rather than letting
     * the flow fail later with an opaque `invalid_client`.
     */
    suspend fun setClientId(s: Service, raw: String): Boolean {
        val cleaned = raw.trim().removePrefix("brightmailbox:")
            .substringAfter('=', raw.trim()).trim().trim('"')
        val ok = when (s) {
            Service.GOOGLE -> cleaned.endsWith(GOOGLE_SUFFIX) && cleaned.length > GOOGLE_SUFFIX.length
            Service.MICROSOFT -> UUID_RE.matches(cleaned)
        }
        if (!ok) return false
        if (cleaned == clientId(s)) return true
        lock.withLock {
            prefs.edit().putString("client_${s.key}", cleaned).apply()
            // A refresh token belongs to the client that issued it, so accounts on the
            // old id are dead the moment it changes. Drop them rather than leave rows
            // that will fail on the next sync with no explanation.
            accounts().filter { it.service == s }.forEach { forgetLocked(it.id) }
        }
        return true
    }

    /* ------------------------------------------------------------------ accounts */

    fun accounts(): List<Account> =
        (prefs.getStringSet(KEY_ACCOUNTS, emptySet()) ?: emptySet())
            .mapNotNull { id ->
                val svc = Service.of(prefs.getString("svc_$id", null)) ?: return@mapNotNull null
                Account(id, svc, prefs.getString("email_$id", "") ?: "")
            }
            .sortedBy { it.email }

    val isSignedIn: Boolean get() = accounts().isNotEmpty()

    suspend fun forget(id: String) = lock.withLock { forgetLocked(id) }

    private fun forgetLocked(id: String) {
        val set = (prefs.getStringSet(KEY_ACCOUNTS, emptySet()) ?: emptySet()).toMutableSet()
        set.remove(id)
        prefs.edit()
            .putStringSet(KEY_ACCOUNTS, set)
            .remove("svc_$id").remove("email_$id").remove("secret_$id")
            .remove("refresh_$id").remove("access_$id").remove("expiry_$id")
            .apply()
    }

    /* ------------------------------------------------------------- authorization */

    /** The consent URL, with a fresh PKCE verifier and state kept for the redirect. */
    fun authorizationUri(s: Service): Uri {
        val verifier = randomUrlSafe(64)
        val state = s.key + "." + randomUrlSafe(12)
        prefs.edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply()

        val b = Uri.parse(s.authEndpoint).buildUpon()
            .appendQueryParameter("client_id", clientId(s))
            .appendQueryParameter("redirect_uri", BuildConfig.OAUTH_REDIRECT)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", s.scopes)
            .appendQueryParameter("code_challenge", challengeOf(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)

        if (s == Service.GOOGLE) {
            // offline for a refresh token, and prompt=consent every time: Google issues
            // a refresh token only on the FIRST grant, so a re-authorisation without it
            // returns an access token and no way to renew it.
            b.appendQueryParameter("access_type", "offline")
            b.appendQueryParameter("prompt", "consent")
        }
        return b.build()
    }

    /**
     * Handle the redirect. False for a user who backed out, and for a state mismatch —
     * the check that stops another app on the device feeding us its own auth code.
     */
    suspend fun onRedirect(uri: Uri): Account? {
        val expected = prefs.getString(KEY_STATE, null)
        val verifier = prefs.getString(KEY_VERIFIER, null)
        prefs.edit().remove(KEY_STATE).remove(KEY_VERIFIER).apply()

        if (expected == null || verifier == null) return null
        if (uri.getQueryParameter("state") != expected) return null
        val service = Service.of(expected.substringBefore('.')) ?: return null
        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() } ?: return null

        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", clientId(service))
            .add("redirect_uri", BuildConfig.OAUTH_REDIRECT)
            .add("code_verifier", verifier)
        if (service == Service.MICROSOFT) form.add("scope", service.scopes)

        val body = runCatching { post(service, form.build()) }.getOrNull() ?: return null
        val refresh = body.optString("refresh_token").takeIf { it.isNotBlank() } ?: return null
        val email = emailFromIdToken(body.optString("id_token")) ?: "signed in"
        val id = service.key + ":" + email.lowercase()

        lock.withLock {
            val set = (prefs.getStringSet(KEY_ACCOUNTS, emptySet()) ?: emptySet()).toMutableSet()
            set.add(id)
            prefs.edit()
                .putStringSet(KEY_ACCOUNTS, set)
                .putString("svc_$id", service.key)
                .putString("email_$id", email)
                .putString("refresh_$id", refresh)
                .putString("access_$id", body.optString("access_token"))
                .putLong("expiry_$id", expiryFrom(body.optInt("expires_in", 0)))
                .apply()
        }
        return Account(id, service, email)
    }

    /**
     * Adopt credentials obtained elsewhere — scripts/authorize.py on a computer, arriving
     * by QR. This is the path that needs no browser on the phone at all, and the fallback
     * when LightOS's browser eats the redirect.
     */
    suspend fun adopt(json: JSONObject): Account? {
        val service = Service.of(json.optString("service")) ?: return null
        val refresh = json.optString("refresh_token").trim().ifEmpty { return null }
        val id0 = json.optString("client_id").trim().ifEmpty { return null }
        val email = json.optString("email").trim().ifEmpty { "signed in" }
        val id = service.key + ":" + email.lowercase()

        lock.withLock {
            val set = (prefs.getStringSet(KEY_ACCOUNTS, emptySet()) ?: emptySet()).toMutableSet()
            set.add(id)
            prefs.edit()
                .putStringSet(KEY_ACCOUNTS, set)
                .putString("client_${service.key}", id0)
                .putString("svc_$id", service.key)
                .putString("email_$id", email)
                .putString("secret_$id", json.optString("client_secret").trim().ifEmpty { null })
                .putString("refresh_$id", refresh)
                .remove("access_$id").remove("expiry_$id")
                .apply()
        }
        return Account(id, service, email)
    }

    /* -------------------------------------------------------------------- tokens */

    /**
     * A valid access token for one account, refreshing if needed.
     *
     * invalid_grant is almost always a consent screen left in Testing, where Google
     * expires refresh tokens after seven days. Nothing is recoverable then, so drop the
     * credentials and let the UI ask for consent rather than retrying forever.
     */
    suspend fun token(accountId: String): String = lock.withLock {
        val service = Service.of(prefs.getString("svc_$accountId", null))
            ?: throw ReauthRequired(accountId, "unknown account")

        prefs.getString("access_$accountId", null)?.let { cached ->
            if (cached.isNotBlank() && System.currentTimeMillis() < prefs.getLong("expiry_$accountId", 0L)) {
                return cached
            }
        }
        val refresh = prefs.getString("refresh_$accountId", null)
            ?: throw ReauthRequired(accountId, "not signed in")

        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", clientId(service))
        clientSecret(accountId)?.let { form.add("client_secret", it) }
        if (service == Service.MICROSOFT) form.add("scope", service.scopes)

        val body = try {
            post(service, form.build())
        } catch (e: TokenError) {
            if (e.error == "invalid_grant") {
                forgetLocked(accountId)
                throw ReauthRequired(accountId, "authorisation expired")
            }
            throw e
        }
        val access = body.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IOException("token response carried no access_token")

        val edit = prefs.edit()
            .putString("access_$accountId", access)
            .putLong("expiry_$accountId", expiryFrom(body.optInt("expires_in", 0)))
        // Microsoft rotates refresh tokens: the response carries a new one and the old
        // one stops working. Miss this and the account dies at the next refresh.
        body.optString("refresh_token").takeIf { it.isNotBlank() }
            ?.let { edit.putString("refresh_$accountId", it) }
        edit.apply()
        access
    }

    suspend fun invalidate(accountId: String) = lock.withLock {
        prefs.edit().remove("expiry_$accountId").apply()
    }

    /* ------------------------------------------------------------------ plumbing */

    private class TokenError(val error: String, message: String) : IOException(message)

    private suspend fun post(s: Service, form: FormBody): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(s.tokenEndpoint).post(form).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
            if (!response.isSuccessful) {
                throw TokenError(json.optString("error"), "HTTP ${response.code}: ${text.take(200)}")
            }
            json
        }
    }

    /** A minute of slack, so a token cannot expire between the check and the request. */
    private fun expiryFrom(seconds: Int): Long =
        System.currentTimeMillis() + (seconds.coerceAtLeast(60) - 60) * 1000L

    /**
     * The email claim straight off the JWT payload. The issuer has just handed this over
     * TLS, so there is nothing to verify — it reads a claim for display, nothing more.
     * Microsoft puts it in `preferred_username` when `email` is absent.
     */
    private fun emailFromIdToken(jwt: String): String? = runCatching {
        val payload = jwt.split('.').getOrNull(1) ?: return@runCatching null
        val json = JSONObject(
            String(
                Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8,
            ),
        )
        json.optString("email").ifBlank { json.optString("preferred_username") }.ifBlank { null }
    }.getOrNull()

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun challengeOf(verifier: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(d, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private companion object {
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_VERIFIER = "pkce_verifier"
        const val KEY_STATE = "auth_state"
        const val GOOGLE_SUFFIX = ".apps.googleusercontent.com"
        val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
