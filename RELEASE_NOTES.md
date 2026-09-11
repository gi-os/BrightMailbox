## v2.4 — Our own scanner

The sign-in scanner used to be a third-party library that opened its own activity: a
viewfinder in somebody else's layout, appearing in the middle of signing in to this one.
It is ours now, in the same black-and-white as the rest of the app, and it reads codes
from further away.

The decoder is the one from Roll, which absorbed LightQR. It is QR-only with ZXing's
`TRY_HARDER` hint — restricting the format list is most of the speed, since the general
reader runs every barcode format over every row first, and `TRY_HARDER` then buys back
the distance a code across a desk needs. It also carries a fix LightQR shipped without:
a camera frame is padded to a hardware-friendly row length, so copying the buffer whole
hands the decoder a sheared image where every row sits a little further over than the one
above. That reads as "the scanner just doesn't work at some resolutions".

ML Kit would be the obvious choice on any other Android phone and is useless here — its
model downloads through Play Services, which LightOS does not have, so it would bind and
never return a result.

## v2.3 — Reading a letter no longer closes it

**Opening an email put you straight back on the home screen.** The reader found its
message by looking it up in the Letters list. Opening a Letter is what marks it read, and
the Letters query filters read mail out — so a second after the page opened, the lookup
answered nothing and the home screen took its place. The reader holds the message it was
told to open now, and the list is only a fallback for a screen restored after the app was
killed.

**And it opened on a blank page for two seconds.** IMAP carries no snippet, so there is
nothing to show while a body is fetched. The text of the letters on the front screen is
now pulled down quietly when a sync finishes, so opening one is instant; a message that
still has to be fetched says so instead of showing an empty page.

**How far back the first sync reads is a setting.** It was four hundred messages an
account, decided in the code. Settings → HOW FAR BACK offers 200, 400, 2,000 or
everything. This was never a cap on the mailbox — new mail always arrives — it only says
how much of the past is there on day one, which is also how much the sorter has to learn
from. Asking for more runs the deep sync again; asking for less deletes nothing.

**Mailboxes can be named.** With one account "gmail" on a row was enough. With two it
identifies nothing. Settings → ACCOUNTS → tap one, and the name you give it replaces that
word everywhere: the letter rows, the reader, the line above a reply. The same screen
removes a mailbox, behind a confirmation.

**ADD OUTLOOK is no longer a dead row.** A build with no Microsoft client id in it said
"not set up in this build" and refused the tap, with the fix on the other side of the
wall. Tapping it now asks for the id, which is public by design and takes three minutes
to register — SETUP.md has the steps. Worth stating plainly, because it is the first
question everyone asks: there is no app-password route for Outlook, for any client. App
passwords are Basic authentication, Microsoft finished retiring that in April 2026, and
no new ones can be issued.

**The settings and back icons are LightOS's own.** Both were redrawn by hand and neither
quite matched the phone; the real ones ship in light-sdk under the MIT licence and are
used unchanged now. The first-sync counter also stopped wrapping onto two lines — four
digits either side of "of" at the title size is wider than the screen, so the total sits
beside the count at the body size.

## v2.2 — The phone can report its own bugs

Shake-to-report was wired into this app's build from v1 and never connected to anything.
The token field existed, CI passed the secret, and no code read either — so a shake did
nothing. Now it works: shake the phone and it files an issue, with a screenshot and a
note about what the app was doing.

**A report from a mail client says less than the others, on purpose.** Every other app
in the family dumps its state freely. This one sends counts and settings only — how many
accounts and of what kind, how long since the last sync, the ration, whether remote
images are on. No addresses, no sender names, no subjects, not even folder names. A bug
report should not be a copy of your correspondence.

Releases also reach BrightMarket straight away now. The repo was missing the step that
tells the catalogue a release exists, so each new version sat until the index happened to
rebuild — about fifteen minutes, sometimes longer.

---

## v2.1 — Sign in with the camera

An app password is sixteen characters and this keyboard is 3.9 inches wide. You make the
password on a computer anyway, so now the computer can hand it over directly.

Open <https://gi-os.github.io/BrightMailbox/>, paste your address and the password, and
it draws a QR. On the phone: **ADD GMAIL** → **SCAN A CODE**. Typing it still works and
is one tap further down the same screen.

The page is static and does everything in your browser: no network request, no
analytics, and the QR encoder is part of the page rather than a script pulled from
someone else's server, because handing a live password to a third-party script is the
wrong shape. Save it and run it offline if you like.

The code is a working password for as long as it is on screen — do not photograph it,
and close the tab once the phone has taken it. Revoking is the same Google page that
made it.

The encoder is the one written for BrightFantasy, where it was checked properly: every
matrix compared module for module against an independent encoder, and three hundred
codes decoded with the same ZXing build the app scans with. A sign-in payload lands at
version 5 — 37×37 modules, far easier on a camera than the dense codes it was built for.

---

## v2.0 — One protocol, no setup

Signing in used to take ten minutes and a Google Cloud project. Now it takes sixteen
characters.

**Gmail signs in with an app password.** Make one at `myaccount.google.com/apppasswords`
and type it in. No Cloud project, no consent screen, no "Google hasn't verified this
app" warning, no eleven-step procedure with a step that quietly breaks everything.

**Outlook signs in with one tap.** It still uses OAuth, because Microsoft finished
retiring Basic auth for IMAP in April 2026 and will refuse a password. But Microsoft
caps nothing, so the one client id ships in the app and nobody registers anything.

Why the reversal: every Google scope that can read mail is *restricted* — capped at 100
users until the app passes a CASA Tier 2 security audit, roughly a thousand dollars a
year. v1 worked around the cap by giving every user their own Cloud project so each
install had exactly one user. It worked and it was miserable. An app password is plain
IMAP: no cap, no console, no audit. The older mechanism is the one that scales, and
**Outlook is now the easy one.**

**Both mailboxes go over IMAP.** The Gmail REST and Microsoft Graph transports are gone,
replaced by one code path. It is also faster: Gmail's REST API has no batch metadata
read, so v1 made one HTTP request per message and a first sync was four hundred round
trips. One IMAP FETCH pulls four hundred headers at once.

**SHOW ORIGINAL works.** The button in the why-sheet was dead. It now renders the
sender's own HTML — with JavaScript off and remote images blocked, so opening a
marketing email does not fire its tracking pixel and tell the sender when you read it.
Links still open in the browser. There is a SHOW IMAGES toggle for the message that
needs it.

**Archive moves, it does not delete.** IMAP MOVE (RFC 6851) to the folder the server
marks `\All` or `\Archive`. The obvious implementation — flag `\Deleted` and expunge —
puts Gmail messages in Trash on a thirty-day timer, which is not what an archive button
should do.

**Attachments are detected**, which the Gmail transport never did.

### Known gaps

- **List rows have no preview line.** IMAP has no snippet, and fetching one costs a
  round trip per message, which would undo the speed. Rows show sender and subject; the
  body arrives when you open it.
- **Conversations are threaded by `References`**, not by a provider thread id, since
  IMAP has none. Standard and cross-provider, but it disagrees with Gmail's grouping on
  mail that has been forwarded around.
- Advanced Protection accounts cannot use Gmail here — Google disables app passwords for
  them, and there is no alternative.

---

## v1.0 — Letters and Notices

First build.

**The two piles.** Every message is sorted on arrival. Letters are mail a human wrote to
a human and get the whole screen, one at a time. Notices are everything a machine sent
and collapse to one line each.

**A daily ration.** Five Letters a day or unlimited, switched in Settings. At five, when
they are read, the app says so and stops. Letters past the ration are not deleted or
hidden — they are tomorrow's. Hold the wheel to unlock a sixth.

**Gmail and Outlook** over OAuth, with a QR fallback for when the browser eats the
redirect.

**Sorting you can inspect.** Every message says why it landed where it did, in plain
words. Moving a sender between piles is one action, it is remembered, and every override
is listed in Settings.

**Letters notify. Notices never do.** Banners and the lock-face row come from
BrightControl. Three sounds: Default, You've Got Mail, Music Box — all synthesized at
build time, no audio in the repo.

**Read, reply, write, archive.**
