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

### 3. Type it into the phone

Open Mailbox, tap **ADD GMAIL**, enter your address and the code. Spaces do not matter —
paste or type it however Google displayed it.

The app tries the credential against Gmail before it saves it, so if it is wrong you
find out immediately rather than by noticing no mail arrived.

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
