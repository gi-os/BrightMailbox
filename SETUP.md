# Setting up mail

Two accounts, two different doors. Gmail wants a code you paste. Outlook wants one tap.
Neither asks you to register anything, and there is no server in the middle — the phone
talks to Google and Microsoft directly.

Ten minutes of console work per provider used to live on this page. It is gone.

---

## Gmail

Gmail signs in with an **app password**: a sixteen-character code you make once, use
only here, and can revoke at any time without changing your real password.

### 1. Turn on 2-Step Verification

<https://myaccount.google.com/signinoptions/two-step-verification>

Google only offers app passwords to accounts that have it. If you already use 2-Step
Verification, skip this.

### 2. Make the password

<https://myaccount.google.com/apppasswords>

Name it `Mailbox`. The name is only a label, so you can find it later to revoke it.

Google shows you sixteen letters in four groups of four. That is the only time it shows
them.

### 3. Get it onto the phone

Two ways. Both end the same: the app tries the credential against Gmail before saving
it, so a wrong character is an error while you are still standing there rather than a
sync that quietly never runs.

**By code, from the computer you just made it on.** Open
<https://gi-os.github.io/BrightMailbox/>, paste the address and the password, and a QR
appears. On the phone: **ADD GMAIL** → **SCAN A CODE**. Nothing is typed on a 3.9-inch
keyboard.

That page is static. It makes no network request, it has no analytics, and the QR
encoder is part of the page rather than a script fetched from somebody else — handing a
password to a third-party script would be the wrong shape. You can save the page and run
it with the Wi-Fi off if you would rather.

**The code is a working password while it is on screen.** Anyone who photographs it can
read your mail. Do not screenshot it, do not paste it into a chat, and close the tab once
the phone has it.

**By hand.** **ADD GMAIL** → **OR TYPE IT**, address and code. Spaces do not matter —
type it however Google displayed it.

### If the app password page is not there

Three reasons, in order of likelihood:

- **2-Step Verification is off.** The page does not exist until it is on.
- **A work or school account.** Your administrator can switch app passwords off, and
  many do. Ask them, or use the account's Outlook side if it has one.
- **Advanced Protection.** Google disables app passwords entirely for enrolled accounts.
  There is no way around this one, and Mailbox cannot read those mailboxes.

### Revoking

Same page. Delete the entry named `Mailbox` and the phone stops having access, at once,
without touching anything else you own.

---

## Outlook

Tap **ADD OUTLOOK**, sign in, agree. That is the whole procedure.

Microsoft finished retiring Basic authentication for IMAP in April 2026, so a password
would simply be refused — Outlook has to use OAuth. There is no app-password route for
Outlook, for anyone, in any client: app passwords *are* Basic authentication, they were
switched off with it, and new ones cannot be issued. The saving grace is that Microsoft
imposes no user cap, so one app registration covers everybody.

Personal accounts and work or school accounts both work.

Every release from v2.17 onwards carries a registration, so there is nothing to set up:
install it and tap **ADD OUTLOOK**. The rest of this section is for anyone building their
own copy, or running one from before v2.17.

### If ADD OUTLOOK is grey and says "not set up in this build"

That build has no client id in it. The id is not a secret — a public client has none —
but it is not in the source either, so a build made without one cannot sign in to
Microsoft. Every release before v2.17 was in this state, because the registration did not
exist yet. Register one once and every build afterwards has it:

1. Go to <https://entra.microsoft.com> → **Applications** → **App registrations** → **New
   registration**.

   **Sign in with a work or school account, not a personal one.** A personal Microsoft
   account — including a Gmail or Yahoo address registered as one — is put into a system
   tenant called *Microsoft Services* that has **no directory behind it**, so there is
   nowhere for a registration to live and the portal refuses at the door:

   > Selected user account does not exist in tenant 'Microsoft Services' and cannot
   > access the application '74658136-…'

   That application id is the admin center's own interface, which is the giveaway that
   the portal is rejecting you rather than anything to do with mail. The advice in the
   message — be added as an external user — does not apply; there is no tenant of yours
   to be added to. Creating a tenant of your own now generally requires a paid Azure
   account, so in practice this step needs an organization account.
2. Name it **after the app**, not after the project it is filed under. This is the
   user-facing display name and it is what the consent screen says out loud: *"Mailbox
   wants to read and write your mail."* A name nobody recognizes, asking for a mailbox,
   is the exact shape of a phishing prompt — and borrowing a real product's name is worse.
   It can be changed later under **Branding & properties**.
3. Under **Supported account types** choose **Accounts in any
   organizational directory and personal Microsoft accounts** — the multi-tenant option.
   Anything narrower refuses either work accounts or outlook.com ones.
4. Skip the redirect URI on that page. Register, then open **Authentication** →
   **Add a platform** → **Mobile and desktop applications** → **Custom redirect URIs**,
   and add exactly:

   ```
   com.gios.brightmailbox://oauth2redirect
   ```

   Microsoft stores what you type and compares it literally, so a trailing slash or a
   capital letter is a different URI and the sign-in ends on `redirect_uri_mismatch`.
5. **API permissions** → **Add a permission** → **APIs my organization uses** → search
   *Office 365 Exchange Online* → **Delegated** → tick `IMAP.AccessAsUser.All` and
   `SMTP.Send`. Graph's `Mail.*` permissions are the wrong ones: an IMAP server refuses a
   Graph token with a bare authentication failure that reads exactly like a bad password.
   This is the step with no warning attached to it — skip it and sign-in *succeeds*, then
   the first sync fails as though the password were wrong.
6. Add **no** certificate or secret. This is a public client: it holds no secret, it
   proves itself with PKCE, and a registration that has one configured is a different
   kind of application.
7. Copy the **Application (client) ID** from the Overview page.

Nothing here costs money. An app registration is free on every tier, and users sign in
against their **own** tenant or Microsoft account rather than yours, so no guest is
created in the tenant holding the registration and there is nothing to meter. What the
tenant does take on is the registration itself: its administrators can see it, audit it,
and delete it — and deleting it stops Outlook working for everyone using that build.

Then give it to the build, whichever suits:

- **CI** — add it as the repository secret `MICROSOFT_CLIENT_ID`. `build.yml` already
  passes it through; nothing else changes.
- **A local build** — put `microsoftClientId=<the id>` in `local.properties`.
- **A phone that already has the APK** — no rebuild needed. The id can be typed or
  scanned in at runtime and is stored beside the credentials. Signing in again after
  changing it is required: a refresh token belongs to the client that issued it, so the
  app drops Microsoft accounts when the id changes rather than leaving them to fail
  silently on the next sync.

No verification, no review and no fee: a multi-tenant public client that asks only for
delegated mail permissions is approved by the user signing in, not by Microsoft.

### If your workplace blocks it

Some tenants require an administrator to approve any application before staff can grant
it access. You will see a message saying approval is needed. Send your administrator the
app name and they can approve it once for the organization.

---

## Why not OAuth for Gmail too

Because Google would charge for it.

Every Google scope that can read mail — `gmail.readonly`, `gmail.modify` and IMAP's
`https://mail.google.com/` alike — is classified **restricted**. An application using
one is capped at **100 users** until it passes a **CASA Tier 2 security assessment**: an
audit by a Google-approved lab, roughly $540–$1,000, repeated every year.

Version 1 worked around the cap by making every user register their own Google Cloud
project, so that each installation had exactly one user and the cap never bound. It
worked, and it took ten minutes and eleven steps that people got wrong.

An app password is ordinary IMAP. No OAuth client, no consent screen, no cap, no audit,
no annual bill. It is the older mechanism and it is the one that scales.

---

## What is stored, and where

On the phone, in the app's private storage:

- the app password, or Outlook's refresh token
- message headers and the sort decisions made from them
- message bodies you have opened

Nothing else, nowhere else. There is no account to make, no server to sign in to, and no
copy of your mail anywhere but your phone and your provider. Removing an account in
Settings deletes its credential and closes its connection.
