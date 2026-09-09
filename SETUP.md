# Setting up mail

Mailbox does not ship with credentials. You register your own OAuth client — once, in
about ten minutes per provider — and scan it into the phone. This page is the whole
procedure, including the two places it reliably goes wrong.

---

## Why you have to do this at all

Reading a mailbox needs `gmail.modify`, which Google classifies as a **restricted
scope**. An app using a restricted scope is capped at **100 users** until it passes
verification, and verification for restricted scopes means a **CASA Tier 2 security
assessment** by a Google-approved lab — roughly $540–$1,000, repeated annually, plus the
review itself.

For an app used by a few dozen people with Light Phones, that is not a sensible trade.
So Mailbox inverts it: **the client id is entered at runtime, not baked into the APK.**
Every person runs their own Google Cloud project, in which they are the only user, and
100 is a cap nobody will ever touch.

Consequences worth knowing up front:

- The release APK contains no secret and is safe to hand to anyone.
- You will see Google's **"Google hasn't verified this app"** screen at sign-in. That is
  expected and correct — it is *your* project, unverified, with you as the only user.
  Click **Advanced → Go to Mailbox (unsafe)**.
- Nothing you do here sends your mail anywhere but between your phone and Google or
  Microsoft. There is no server in the middle. There is no server at all.

---

## Google (Gmail)

### 1. Make a project

<https://console.cloud.google.com/> → project picker → **New Project**. Call it
`mailbox`. Anything is fine; nobody sees it.

### 2. Turn on the Gmail API

**APIs & Services → Library** → search *Gmail API* → **Enable**.

Miss this and sign-in works perfectly and then every request returns
`403 Gmail API has not been used in project … before or it is disabled`.

### 3. Configure the consent screen

**APIs & Services → OAuth consent screen** (recent consoles call this the
**Google Auth Platform**).

- User type: **External**
- App name: `Mailbox`
- User support email and developer contact: your address
- Everything else: leave it

### 4. Add the scope

On the **Data access** / **Scopes** step, **Add or remove scopes**, then paste:

```
https://www.googleapis.com/auth/gmail.modify
```

It will be flagged as restricted with a warning about verification. That is expected.

**Do not use `gmail.readonly` instead** — it is restricted too, so it buys nothing, and
the app could not mark mail read, archive it, or send a reply.

### 5. Publish it — do not leave it in Testing

**Audience → Publish app**. Confirm. Status becomes **In production**, unverified.

This step matters more than it looks. **A consent screen left in Testing expires refresh
tokens after seven days.** The app then signs itself out every week for no visible reason,
which is a maddening bug to chase from the phone end. Published-and-unverified does not
expire.

### 6. Create the Android client

**Credentials → Create credentials → OAuth client ID**

- Application type: **Android**
- Name: `mailbox-phone`
- Package name: `com.gios.brightmailbox`
- SHA-1 certificate fingerprint:

```
F0:BD:D0:0C:DE:1B:A6:FD:0F:CD:93:C6:6F:9A:86:52:18:73:A9:D9
```

That is the fingerprint of the signing key committed to this repository, which is the key
every release APK is signed with. If you build your own APK with your own key, run
`keytool -list -v -keystore <yours>` and use yours instead.

### 7. The step everybody misses

Open the client you just made → **Advanced settings** → **Custom URI scheme** →
**Enable**. Confirm the dialog, then **Save**.

Without it, consent fails with a blank-looking

> **Error 400: invalid_request** — Access blocked: this request is invalid

and no stated reason. The real reason is hidden in the page URL: base64-decode the
`authError` query parameter and it says *"Custom URI scheme is not enabled for your
Android client."*

Google labels the setting "not recommended" because it prefers Play Services or App
Links, neither of which exists on LightOS. PKCE is what makes it safe, and Mailbox uses
PKCE.

The change applies in seconds despite the console warning about "5 minutes to a few
hours".

### 8. Put it in the phone

Copy the client id — it ends in `.apps.googleusercontent.com`.

On the phone: **Settings → Accounts → Client IDs → Google**, then scan a QR of it. Any QR
generator works; the app also accepts a typed id if you have the patience.

Then **Add Gmail** on the setup screen.

---

## Microsoft (Outlook, Microsoft 365)

Simpler: no restricted-scope regime, no verification, no consent warning for a personal
account.

### 1. Register the app

<https://portal.azure.com/> → **Microsoft Entra ID** → **App registrations** →
**New registration**.

- Name: `Mailbox`
- **Supported account types: Accounts in any organizational directory and personal
  Microsoft accounts**

That last choice is what lets both an `@outlook.com` address and a work 365 tenant sign
in. Pick single-tenant by mistake and personal accounts get `AADSTS50020`.

### 2. Add the redirect

**Authentication → Add a platform → Mobile and desktop applications → Custom redirect
URI**, and enter exactly:

```
com.gios.brightmailbox://oauth2redirect
```

**Two slashes.** Google's Android clients use a single slash and no host
(`com.gios.brightmailbox:/oauth2redirect`); Microsoft stores what you type and compares
it literally. Mailbox sends the right shape to each — the manifest declares the *scheme*
only, with no host and no path, so both come back to the app. Get this wrong and you get
`AADSTS50011: The redirect URI specified in the request does not match`.

Then, on the same page, set **Allow public client flows: Yes**.

### 3. Add the permissions

**API permissions → Add a permission → Microsoft Graph → Delegated permissions**:

| Permission | For |
|---|---|
| `Mail.ReadWrite` | reading, marking read, archiving |
| `Mail.Send` | replying and composing |
| `User.Read` | the address shown on the account row |
| `offline_access` | **the refresh token** |

`offline_access` is not optional and is easy to skip because it looks like boilerplate.
Without it Microsoft returns an access token and no refresh token, and the account
silently dies an hour later.

No admin consent is needed for a personal account. On a work tenant your admin may have
to approve it.

### 4. Put it in the phone

Copy the **Application (client) ID** from the Overview page — a plain UUID.

**Settings → Accounts → Client IDs → Microsoft**, scan it, then **Add Outlook**.

---

## If the browser eats the redirect

LightOS's browser sometimes opens the consent page and then loses the redirect back into
the app. There is a way round it that needs no browser on the phone at all:

```bash
python3 scripts/authorize.py google    --client-id <id> --client-secret <secret>
python3 scripts/authorize.py microsoft --client-id <id>
```

It runs consent on your computer against `http://localhost`, then prints a QR containing
the refresh token. Scan it in the app.

**For Google this needs a second, Desktop-type client**, not the Android one — Google will
not accept a loopback redirect for an Android client. Desktop clients have a "secret",
which Google expects to ship inside installed apps and does not treat as confidential.

For Microsoft, add `http://localhost` as a second redirect URI on the same registration.

---

## When it goes wrong

| What you see | What it is |
|---|---|
| `Error 400: invalid_request`, no reason | Custom URI scheme not enabled — step 7 |
| `403 Gmail API has not been used in project` | Gmail API not enabled — step 2 |
| Signed out roughly every 7 days | Consent screen still in Testing — step 5 |
| `AADSTS50011` redirect mismatch | One slash instead of two — Microsoft step 2 |
| `AADSTS50020` | Registration is single-tenant — Microsoft step 1 |
| Signs in, then dies after an hour | `offline_access` missing — Microsoft step 3 |
| "Google hasn't verified this app" | Expected. Advanced → Go to Mailbox |
| `invalid_client` | Client id pasted with a stray quote or newline |

## What the app stores

Refresh tokens in app-private `SharedPreferences`, message metadata in an app-private
Room database, message bodies as files in app-private storage, and the learned sorting
model as one text file. All of it inside the app sandbox, none of it leaving the phone.
Uninstalling removes the lot.

The only network traffic is to `accounts.google.com`, `oauth2.googleapis.com`,
`gmail.googleapis.com`, `login.microsoftonline.com` and `graph.microsoft.com`.
