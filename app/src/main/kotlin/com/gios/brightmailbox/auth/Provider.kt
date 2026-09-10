package com.gios.brightmailbox.auth

/**
 * How an account proves who it is.
 *
 * The two providers diverged, and the divergence is the whole reason this app can be
 * handed to a stranger.
 *
 * Google classifies every scope that can read mail — `gmail.modify`, `gmail.readonly`
 * and IMAP's `https://mail.google.com/` alike — as **restricted**. A restricted scope is
 * capped at 100 users until the app passes a CASA Tier 2 security assessment, which is
 * an annual four-figure bill from a Google-approved lab. There is no version of OAuth
 * that avoids this, so v2 stops trying: Gmail authenticates with an **app password**,
 * which is ordinary IMAP with no OAuth client anywhere in the picture, no user cap and
 * no review. The user pastes sixteen characters and is done.
 *
 * Microsoft has no equivalent cap. One multi-tenant client id, registered once and
 * shipped in the APK, serves every user of the app — so Outlook keeps OAuth, which it
 * must, because Microsoft finished retiring Basic auth for IMAP in April 2026 and a
 * password will simply be refused.
 *
 * The inversion is worth stating plainly because it reverses v1: **Outlook is now the
 * easy one.**
 */
enum class AuthKind { APP_PASSWORD, OAUTH }

enum class Service(
    val key: String,
    /** Shown on the account row. */
    val label: String,
    val authKind: AuthKind,

    val imapHost: String,
    val imapPort: Int,

    /**
     * SMTP hosts to try in order.
     *
     * A list rather than a string because Microsoft never fully merged the personal and
     * work submission endpoints: `smtp.office365.com` is documented for OAuth SMTP AUTH
     * and works for tenant accounts, while long-lived personal outlook.com mailboxes
     * still answer on `smtp-mail.outlook.com`. Trying both costs one failed connect on
     * the accounts that need the second, and saves a support thread.
     */
    val smtpHosts: List<String>,
    val smtpPort: Int,
    /** True for implicit TLS on connect (465); false for STARTTLS (587). */
    val smtpSsl: Boolean,

    /**
     * Folder names to fall back on when the server does not advertise SPECIAL-USE.
     *
     * Checked only after the `\All` / `\Archive` / `\Sent` attributes come back empty.
     * Gmail exposes localized display names but keeps these English paths, and the
     * `[Google Mail]` prefix is what UK and German accounts get instead of `[Gmail]`.
     */
    val archiveNames: List<String>,
    val sentNames: List<String>,

    /* OAuth, null for an app-password service. */
    val authEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val scopes: String? = null,
    val redirectSuffix: String? = null,
) {
    GOOGLE(
        key = "google",
        label = "gmail",
        authKind = AuthKind.APP_PASSWORD,
        imapHost = "imap.gmail.com",
        imapPort = 993,
        smtpHosts = listOf("smtp.gmail.com"),
        smtpPort = 465,
        smtpSsl = true,
        archiveNames = listOf("[Gmail]/All Mail", "[Google Mail]/All Mail"),
        sentNames = listOf("[Gmail]/Sent Mail", "[Google Mail]/Sent Mail"),
    ),

    MICROSOFT(
        key = "microsoft",
        label = "outlook",
        authKind = AuthKind.OAUTH,
        imapHost = "outlook.office365.com",
        imapPort = 993,
        smtpHosts = listOf("smtp.office365.com", "smtp-mail.outlook.com"),
        smtpPort = 587,
        smtpSsl = false,
        archiveNames = listOf("Archive"),
        sentNames = listOf("Sent Items", "Sent"),
        // "common" so both personal Microsoft accounts and work/school tenants sign in.
        authEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        /*
         * IMAP and SMTP scopes, not Graph. v1 asked for Mail.ReadWrite because it spoke
         * Graph; the transport is IMAP now and Graph scopes do not authorize it — an
         * access token minted for Mail.ReadWrite is refused by the IMAP server with a
         * bare AUTHENTICATE failure and no explanation.
         *
         * offline_access is not optional the way Google's access_type=offline is:
         * without it Microsoft returns an access token and no refresh token at all, and
         * the account silently stops working an hour later.
         */
        scopes = "openid email offline_access " +
            "https://outlook.office.com/IMAP.AccessAsUser.All " +
            "https://outlook.office.com/SMTP.Send",
        /*
         * Microsoft's portal stores whatever you type and compares it literally, and the
         * form it accepts and round-trips is the ordinary authority form with two
         * slashes. The manifest's intent filter declares the SCHEME only, with no host or
         * path, so it catches this; declaring a path there would break it, because
         * Android will not match an android:path unless a host is declared too.
         */
        redirectSuffix = "://oauth2redirect",
    );

    val usesOAuth: Boolean get() = authKind == AuthKind.OAUTH

    /** The full redirect for this service, e.g. `com.gios.brightmailbox://oauth2redirect`. */
    fun redirectUri(scheme: String): String = scheme + (redirectSuffix ?: "://oauth2redirect")

    companion object {
        fun of(key: String?): Service? = entries.firstOrNull { it.key == key }
    }
}
