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
would simply be refused — Outlook has to use OAuth. The saving grace is that Microsoft
imposes no user cap, so one app registration covers everybody and it is already built
into the APK.

Personal accounts and work or school accounts both work.

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
