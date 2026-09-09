package com.gios.brightmailbox.auth

/**
 * The two mail services, and the handful of strings that differ between them.
 *
 * Gmail and Outlook are the same OAuth flow with different endpoints, so the flow lives
 * once in [AuthManager] and the differences live here.
 */
enum class Service(
    val key: String,
    /** Shown on the account row. */
    val label: String,
    val authEndpoint: String,
    val tokenEndpoint: String,
    val scopes: String,
    /**
     * The redirect, which is NOT the same shape for the two of them.
     *
     * Google issues Android clients a scheme-and-path redirect with a single slash and
     * no host — `com.example:/path`. Microsoft's portal stores whatever you type and
     * compares it literally, and the form it accepts and round-trips is the ordinary
     * authority form with two slashes. Sending Google's shape to Microsoft gets a bare
     * `AADSTS50011: redirect URI does not match`.
     *
     * The manifest's intent filter declares the SCHEME only, with no host or path, so it
     * catches both. Declaring a path there would break this: Android will not match an
     * android:path unless a host is declared too.
     */
    val redirectSuffix: String,
) {
    GOOGLE(
        key = "google",
        label = "gmail",
        authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenEndpoint = "https://oauth2.googleapis.com/token",
        /*
         * gmail.modify rather than gmail.readonly. Both are "restricted" scopes in
         * Google's terms, so read-only buys no easier review — and the app has to mark
         * mail read, archive it and send replies, none of which readonly permits.
         */
        scopes = "openid email https://www.googleapis.com/auth/gmail.modify",
        redirectSuffix = ":/oauth2redirect",
    ),

    MICROSOFT(
        key = "microsoft",
        label = "outlook",
        // "common" so both personal Microsoft accounts and work/school tenants sign in.
        authEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        /*
         * offline_access is not optional here the way access_type=offline is for Google:
         * without it Microsoft returns an access token and no refresh token at all, and
         * the account silently stops working an hour later.
         */
        scopes = "openid email offline_access Mail.ReadWrite Mail.Send User.Read",
        redirectSuffix = "://oauth2redirect",
    );

    /** The full redirect for this service, e.g. `com.gios.brightmailbox://oauth2redirect`. */
    fun redirectUri(scheme: String): String = scheme + redirectSuffix

    companion object {
        fun of(key: String?): Service? = entries.firstOrNull { it.key == key }
    }
}
