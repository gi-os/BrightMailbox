# Mailbox

An email client for the Light Phone III that sorts your mail into two piles and then
gets out of the way.

**Letters** are messages a human wrote to a human. They get the whole screen, one at a
time, set like a page of a book. **Notices** are everything a machine sent — receipts,
shipping updates, 2FA codes, newsletters, password resets. They collapse to one line
each and never make a sound.

You set a daily ration. Five Letters a day, or unlimited. At five, when you have read
them, the app says so and there is nothing left to scroll.

Gmail with an app password. Outlook with one tap. Nothing to register.

---

## Signing in

**Gmail** takes a sixteen-character app password from
`myaccount.google.com/apppasswords`. No Google Cloud project, no consent screen, no
"Google hasn't verified this app" warning. Make the password on a computer, put it into
<https://gi-os.github.io/BrightMailbox/>, and scan the QR — or type it, if you prefer a
3.9-inch keyboard to a camera.

**Outlook** takes one tap, once the build has a client id. Microsoft finished retiring
Basic auth for IMAP in April 2026, so it has to be OAuth — there is no app-password route
for Outlook in any client, because app passwords are Basic auth and went with it. The
saving grace is that Microsoft caps nothing, so one registration covers everybody. A
build made without that id says so on the row and takes the id when you tap it.

The asymmetry is not an accident and it is worth knowing, because it reverses what most
people assume. Every Google scope that can read mail is **restricted**: capped at 100
users until the app passes a CASA Tier 2 audit, which costs four figures a year. Version
1 dodged the cap by making each user register their own Cloud project — eleven steps,
ten minutes, and the step people missed produced a sign-in that worked and then returned
403 on every request. An app password has no cap, no console and no audit.

So the older mechanism is the one that scales, and **Outlook is now the easy one**.

[SETUP.md](SETUP.md) has both procedures.

## Why IMAP rather than the REST APIs

The point is not the protocol — it is the sorting — but the protocol decided who could
use the app.

There is a second reason: it is faster. Gmail's REST API has no batch metadata read, so
version 1 issued one HTTP GET per message and a first sync was four hundred round trips.
One IMAP FETCH with a HEADERS profile pulls four hundred headers in a couple of round
trips on a single connection.

MIME parsing comes from [Angus Mail](https://eclipse-ee4j.github.io/angus-mail/), which
publishes a supported Android build. IMAP is simple enough to write by hand; MIME is
not, and MIME is what breaks a mail client on one message in twenty.

## How the sorting works

Two tiers, and the division of labor matters.

### Tier 0 — headers decide the pile

Deterministic, offline, microseconds, no model. A machine that sends mail in bulk is
required by convention to say so, and it does: `List-Unsubscribe` (RFC 2369),
`Precedence: bulk` (RFC 2076), `Auto-Submitted` (RFC 3834), `X-Auto-Response-Suppress`,
a VERP return path, a `no-reply@` sender.

Above all of those sits one rule: **have you ever replied to this address**. Someone you
have written back to is a person, whatever headers their mail carries. That signal is
read once from your Sent folder at setup, and it is why the app gets better the longer
you use it.

The rules are deliberately narrow in one direction. `hello@`, `team@`, `contact@` and
`info@` are *not* treated as robots, even though they look like it, because at a small
company that is exactly where a real person writes from. A false Notice costs you a
missed client reply; a false Letter costs one line on a screen.

### Tier 1 — a small learned model ranks what is left

Logistic regression over hashed n-grams. About 200 KB of weights, trained on device from
your own mailbox in roughly a second, updated instantly whenever you correct it.

Its job is to decide which five Letters are worth today's ration, and to absorb
corrections so a misfile is permanent knowledge rather than an annoyance. It is allowed
to move a message between piles only when the headers had nothing to say and it is very
confident — never against an explicit marker. A `List-Unsubscribe` header is a fact; a
model output is an opinion, and an opinion does not get to bury your mail.

### Why not an LLM

It was considered seriously and measured. A Gemma-class model needs two to five seconds
of prefill per message on the LP3's Snapdragon 4 Gen 2; a first sync of 200 messages
would spend ten minutes pegging the CPU against an 1800 mAh battery, add ~200 MB to the
install, and still be worse at *this particular question* than three headers are.

The local model that earns its place here is 200 KB, not 200 MB.

### It has to be inspectable

Every message can tell you why it landed where it did, in plain words — *"has an
unsubscribe link"*, *"you've replied to this address 34 times"*. Never a percentage,
never a confidence bar. Moving a sender between piles is one action, it is reversible,
and it says what it learned. Every override you make is listed in Settings and can be
removed.

Automation you cannot see or undo is not worth shipping.

## Notifications

**Letters notify. Notices never do.** That one rule is most of the reason to install
this instead of any other mail client.

Mailbox posts a standard notification at importance ≥ 3 and lets **BrightControl** draw
the banner and the lock-face row — it has done that for any app since v3.65. Mailbox
deliberately does not draw its own heads-up box, which would stack a second one on top.

### Sounds

Three, in this order: **Default** (whatever LightOS uses), **You've Got Mail**, and
**Music Box**. Plus a custom slot that points at any file you like.

No audio is committed to this repository. Every sound is synthesized at build time from
about 15 KB of Python — modal synthesis for the chimes, and a Klatt-style cascade
formant synthesizer for the voice, driven by measured articulation data. See
[scripts/README.md](scripts/README.md), which has the numbers.

The famous recording is Elwood Edwards' 1989 performance for AOL and it belongs to
somebody. Playing your own copy on your own phone is ordinary personal use; shipping it
inside an APK is redistribution, and the MIT license on this repo would then be making a
false claim to anyone who forks it. So the bundled one is synthesized, and there is a
**Custom** row for people who have the real thing.

## Building

```
./gradlew :app:assembleRelease
```

Needs `python3` with `numpy` for the sound generation. Without it the build still
succeeds and the app falls back to the system notification sound.

### The Microsoft client id

One id, for Outlook only — Gmail has none any more. It is not required at build time:
ADD OUTLOOK on a build without one asks for the id and stores it beside the credentials,
which is what makes a plain release APK usable by anyone. A public client has no secret
to leak, and the redirect scheme is fixed by the package name rather than by the id, so
this is not a credential being typed into a phone — it is a name.

Changing it signs the Microsoft accounts out. A refresh token belongs to the client that
issued it, so the old ones are already dead; dropping them beats leaving rows that fail
on the next sync with no explanation.

To bake it in, put it in `local.properties`:

```
microsoftClientId=00000000-0000-0000-0000-000000000000
```

Register it as a **multi-tenant public client** with the redirect
`com.gios.brightmailbox://oauth2redirect`, and grant the delegated permissions
`IMAP.AccessAsUser.All` and `SMTP.Send` — not the Graph `Mail.*` ones. A token minted
for Graph scopes is refused by the IMAP server with a bare AUTHENTICATE failure and no
explanation, which is a long afternoon if you do not know it.

The signing certificate is committed and pinned (`signing-fingerprint.txt`), so debug
and release APKs upgrade over each other:

```
SHA1: F0:BD:D0:0C:DE:1B:A6:FD:0F:CD:93:C6:6F:9A:86:52:18:73:A9:D9
```

### If the browser eats the redirect

`scripts/authorize.py` runs consent on a computer and hands the phone a refresh token by
QR. Gmail no longer needs it — an app password is typed straight in.

## Testing

The `sort/`, `text/` and `mail/Addr` packages have no Android imports, so they run on a
plain JVM:

```
./gradlew :app:testDebugUnitTest
```

48 tests covering the header rules, the learned model, the two-tier interaction, quote
and signature stripping, and address parsing. The classification is the product, so it
is the part that stays verifiable.

## Design

Built to the LightOS design system rather than an approximation of it: the 27 × 31
`LightGrid`, the named type scale (`designVerticalPxToSp`), three values and no fourth,
no ripples, a 45 ms haptic on finger-down. Units scale on width, type scales on height —
mixing those is what makes bars and text drift apart.

Body leading is locked to exactly two grid units, so every line of every Letter lands on
shared baselines and one wheel notch moves a whole number of lines. Nothing ever
half-clips at the fold.

## License

MIT.
