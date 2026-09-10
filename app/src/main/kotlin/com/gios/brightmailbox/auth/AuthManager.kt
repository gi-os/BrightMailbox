package com.gios.brightmailbox.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.gios.brightmailbox.BuildConfig
import com.gios.brightmailbox.mail.Imap
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
 * Credentials for both mailboxes: an app password for Gmail, OAuth for Outlook.
 *
 * Only Microsoft reaches the OAuth code below. Gmail lost its OAuth path in v2 because
 * every Google scope that can read mail is restricted and capped at 100 users — see
 * [Service]. What remains here for Google is a stored app password and nothing else.
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

    /** The scheme is the package name; the shape after it differs per service. */
    private fun redirectFor(s: Service): String =
        s.redirectUri(BuildConfig.OAUTH_REDIRECT.substringBefore(':'))

    /* --------------------------------------------------------------- client ids */

    /**
     * Only Microsoft has one.
     *
     * Gmail signs in with an app password now, so there is no Google client id, no
     * consent screen and no Cloud project — see [Service] for why. Microsoft's id is
     * one multi-tenant registration shipped in the APK: a public client has no secret
     * to leak, and Microsoft imposes no user cap, so the same id serves everybody.
     */
    fun clientId(s: Service): String = when (s) {
        Service.GOOGLE -> ""
        Service.MICROSOFT -> prefs.getString("client_${s.key}", null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.MICROSOFT_CLIENT_ID
    }

    /** An app-password service needs no configuration at all. */
    fun isConfigured(s: Service): Boolean = !s.usesOAuth || clientId(s).isNotEmpty()

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
        if (!s.usesOAuth) return false
        val cleaned = raw.trim().removePrefix("brightmailbox:")
            .substringAfter('=', raw.trim()).trim().trim('"')
        if (!UUID_RE.matches(cleaned)) return false
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
            .remove("pass_$id")
            .apply()
        // Leave no open IMAP connection authenticated as an account we just forgot.
        Imap.disconnect(id)
    }

    /* ------------------------------------------------------------- app passwords */

    /**
     * Sign in with an app password. The credential is verified against the server before
     * it is stored, so a mistyped character is an error at the keyboard rather than a
     * sync that quietly never runs.
     *
     * Returns the account, or a sentence explaining what went wrong.
     */
    suspend fun signInWithPassword(
        service: Service,
        rawEmail: String,
        rawPassword: String,
    ): Result<Account> {
        val email = Imap.emailOf(rawEmail)
            ?: return Result.failure(IOException("That does not look like an email address."))
        // Google prints app passwords in four groups of four. People paste them that
        // way, and the spaces are not part of the secret.
        val password = rawPassword.filterNot { it.isWhitespace() }
        if (password.isEmpty()) return Result.failure(IOException("Enter the app password."))

        Imap.verify(service, email, password)?.let { return Result.failure(IOException(it)) }

        val id = service.key + ":" + email
        lock.withLock {
            val set = (prefs.getStringSet(KEY_ACCOUNTS, emptySet()) ?: emptySet()).toMutableSet()
            set.add(id)
            prefs.edit()
                .putStringSet(KEY_ACCOUNTS, set)
                .putString("svc_$id", service.key)
                .putString("email_$id", email)
                .putString("pass_$id", password)
                .apply()
        }
        return Result.success(Account(id, service, email))
    }

    /**
     * What the IMAP and SMTP layers authenticate with: an app password for Gmail, a
     * fresh OAuth access token for Outlook. Both go in the password field — XOAUTH2
     * passes the token there and encodes it itself.
     */
    suspend fun credential(accountId: String): String {
        val service = Service.of(prefs.getString("svc_$accountId", null))
            ?: throw ReauthRequired(accountId, "unknown account")
        if (!service.usesOAuth) {
            return prefs.getString("pass_$accountId", null)?.takeIf { it.isNotBlank() }
                ?: throw ReauthRequired(accountId, "no app password stored")
        }
        return token(accountId)
    }

    /* ------------------------------------------------------------- authorization */

    /** The consent URL, with a fresh PKCE verifier and state kept for the redirect. */
    fun authorizationUri(s: Service): Uri {
        val endpoint = s.authEndpoint
            ?: throw IllegalStateException("${s.key} does not use OAuth")
        val verifier = randomUrlSafe(64)
        val state = s.key + "." + randomUrlSafe(12)
        prefs.edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply()

        return Uri.parse(endpoint).buildUpon()
            .appendQueryParameter("client_id", clientId(s))
            .appendQueryParameter("redirect_uri", redirectFor(s))
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", s.scopes.orEmpty())
            .appendQueryParameter("code_challenge", challengeOf(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()
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
            .add("redirect_uri", redirectFor(service))
            .add("code_verifier", verifier)
        service.scopes?.let { form.add("scope", it) }

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
        service.scopes?.let { form.add("scope", it) }

        val body = try {
            post(service, form.build())
        } catch (e: TokenError) {
            if (e.error == "invalid_grant") {
                forgetLocked(accountId)
                throw ReauthRequired(accountId, "authorization expired")
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
        val endpoint = s.tokenEndpoint ?: throw IOException("${s.key} does not use OAuth")
        val request = Request.Builder().url(endpoint).post(form).build()
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
        val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
