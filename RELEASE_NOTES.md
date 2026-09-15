## BrightMailbox v2.51 — a probe, not a feature

Hold a parcel row and the app loads the carrier's tracking page in a hidden browser and
shows the first screenful of what that page ends up saying. `adb logcat -s parcelprobe` gets
the same text, longer.

This is the experiment behind "our own tracking page with live data". A plain HTTP request
cannot do it: UPS, USPS, FedEx and DHL all answer with a bot wall — "Access Denied", "Your
tracking attempt has been blocked" — before anybody looks at the tracking number. A WebView
is a real browser with a real fingerprint, so it is the one client that might get through,
and this build is how we find out instead of assuming.

Nothing else uses it. Tapping a row still opens the carrier in whatever handles links. The
probe is temporary: it is the only thing in this app that fetches a page on its own, and it
leaves when the answer arrives.

## BrightMailbox v2.50 — the thing you are waiting for, named

The head of a parcel row is the item, when the mail names one. Amazon's shipping mail is
literally `Your Amazon.com order of "Anker USB-C Cable" has shipped`, so the row can say
that instead of saying "Amazon.com" — which is true and useless.

It is read from the subject and nowhere else. A body is a table of line items that the
cleaner flattens into a paragraph, and a wrong item name is worse than no item name at all.
When a mail names nothing — every carrier's own notice, plenty of shops — the row says the
shop, which is what it always said, with the carrier, number and ETA underneath either way.

**eBay's link was going to Seller Hub.** `/sh/` is where a *seller* tracks what they shipped,
and it is the same host as the buyer's pages, so it is excluded by name now and a row with no
link of its own goes to the buyer's purchase history instead. Being handed a seller's tools
for a parcel travelling towards you is the kind of thing that stays invisible until someone
taps it.

## BrightMailbox v2.49 — the parcel list you can actually reach

Three fixes to v2.48, all of them the same mistake in different clothes: the feature was
there and you could not get to it.

- **PARCELS is always in the menu now.** It used to be drawn only while a parcel was in
  flight — so the one moment the row was worth opening, an empty list you suspect should
  not be empty, was the moment it hid itself. A route you cannot see is a route you never
  learn. It now says "Nothing on its way yet." in its second line instead of vanishing.
- **The first empty look at the list asks the server.** "Nothing is coming" and "the mail
  that says otherwise is somewhere else" look identical on screen, and the cheap scan reads
  only text already on the phone. The first empty open of a session runs the search; after
  that the list keeps its manners.
- **SEARCH AGAIN is REFRESH**, which is what it is: it searches the archive on the server
  for shipping mail, stores what it finds as real messages, fetches the text of recent
  notices it cannot already read, and reads the list again.
- **The scan reads both piles.** It walked only Notices, on the assumption that a machine's
  mail is a Notice. A shipping mail the sorter filed as a Letter was invisible to the parcel
  list, which is exactly the case where the sorter and the customer disagree.
- **eBay's older twelve-digit order id** is read as well as the two-five-five form.

If a shop's parcel still does not appear, the thing that fixes it is one real message: the
sender, the subject and the body of a shipping mail that should have produced a row.

## BrightMailbox v2.48 — Amazon and eBay draw a row, and a way to ask again

An Amazon shipping mail usually does not state the tracking number. It is behind a "Track
package" button, and when the parcel is Amazon Logistics it is a `TBA…` that only some
messages spell out — so an Amazon order that reported every state change drew no row at
all, and Amazon is most of what a mailbox like this carries.

Order ids are real parcels now: Amazon's `123-4567890-1234567` and eBay's `14-11960-23534`.

The details that had to be decided:

- **An order id is the parcel's key when there is no number to have.** The row groups and
  advances exactly as a carrier's number does — "has shipped", then "out for delivery",
  then "was delivered", one row moving — because the rule was never about carriers, it was
  about what a shop puts on every one of its messages.
- **Believed only from the shop that issued it.** Both shapes are read from the sender and
  nowhere else: `123-4567890-1234567` from anyone but amazon.com is not a parcel.
- **The carrier's number wins when a mail carries both**, because it identifies the thing
  in the van and the order id only identifies the order.
- **eBay's own order id has no tracking page behind it**, so a row with no link in the mail
  falls back to eBay's orders list rather than to a URL this app made up. Amazon's falls
  back to Your Orders. The link the mail already carries is always preferred.

**SEARCH AGAIN**, at the bottom of the parcels list, answers "but what about the one you
missed". The list is built from message text already on the phone, which is cheap and
usually complete; a parcel whose mail was filed on another device, or whose text was never
cached, is not in it. The button searches the server for five phrases a shipping mail cannot
avoid — the archive included, which is where most of a mailbox's history lives — stores what
it finds as real mail, fetches the text of recent notices it cannot already read, and reads
the list again. Sixty days back, forty messages at a time. It is the only part of parcels
that touches the network, and only because it was asked.

Still nothing is stored about a parcel: no table, no migration, and the list is not a record
of anything. That remains the cost of it being silent and free.

## BrightMailbox v2.47 — filing an email does not un-ship a parcel

v2.46 read only the notices still lying in the pile, so ARCHIVE ALL emptied the parcel list,
and so did filing one shipping update by hand. The list is about the thing in the van, not
about your mail.

Parcels are read from every notice now, archived ones included, and the only thing that
takes a row off the list is delivery — the carrier mailing "delivered" is the end of a
parcel and nothing else is. Filing the announcement is a statement about mail.

Two gaps remain, both the cost of a design that stores nothing: a tracking number that
exists only inside a picture is still undetectable, and a parcel whose "delivered" mail
never arrives will sit in the list. The second one is what a stored parcel would fix, and
that is a different release.

## BrightMailbox v2.46 — what is on its way, read out of the mail

MENU → PARCELS lists the parcels the mailbox is already being told about.

A carrier mails every state change — shipped, out for delivery, delivered — and the shop
mails the tracking number, so the mailbox has been receiving the whole state machine all
along. Nothing here talks to a carrier: no developer account, no key, no polling. The
alternative was four registrations before the app did anything, and Amazon Logistics has
no public API at all, which would have left the largest single source of parcels
unreachable.

Carriers understood today: UPS, FedEx, USPS, DHL and Amazon Logistics.

The details that had to be decided:

- **A carrier and a number format have to agree, or nothing is returned.** A bare twelve
  digits is FedEx, or a USPS label, or an order id, and guessing is how a parcel list fills
  with junk. Shapes that cannot be anything else — UPS `1Z`, Amazon `TBA`, the international
  `S10` form — are believed on their own. The rest are believed only when the mail names
  that carrier and the number sits beside a tracking word.
- **The newest mail about a number is the state of the parcel.** That one rule is the
  merge: one order that generates four emails draws one row, and nothing has to be stored.
  No parcels table, no schema change, no second copy of the mail going stale.
- **"Out for delivery" is not "delivered".** Future tense comes out before the words are
  matched, so "will be delivered today" reads as what it means — the thing is not here yet.
- **A return label is not an inbound parcel.** Return, refund and RMA subjects are ignored.
  A return label is a tracking number for something travelling away from you, and it would
  otherwise sit in the list for a fortnight, waiting to arrive.
- **Silence is the point.** Nothing here notifies. In this app a shipping email is a Notice,
  and Notices never make a sound — being told a parcel is coming is worth a notification and
  the email announcing it is not. This is a list you go and read, and every row is a tap
  that hands the carrier's own tracking page to whatever opens links.
- **It reads only what is already on the phone.** Bodies are the ones the prefetch had
  fetched anyway so that a tapped message opens instantly. Nothing extra goes over the
  network and nothing new runs in the background.

Two things it cannot do: a tracking number that exists only inside a picture in the mail is
skipped rather than guessed at, and archiving the shipping mail takes its parcel off the
list — the list is the mail. Both would need a stored parcel to fix, which is a different
release.

## BrightMailbox v2.45 — letters are conversations now

A back-and-forth with one person was four rows in the list, and under a ration of five
that meant one exchange could spend the whole day. The list this app exists to keep short
was the one most likely to be filled by the person you talk to most.

Letters group by thread now: one row per conversation, with the number of messages it
stands for on the right of the sender line. **The ration counts conversations.** One
thread is one thing to read, so it takes one of the five however many messages are in it.

`threadId` has been computed on the way in since v2.0 and read by nothing but the reader's
footer. This is the list finally using it.

The details that had to be decided:

- **A conversation is unread until every message in it is read.** A reply landing on a
  thread you had finished brings it back rather than hiding behind what you already got
  through.
- **Opening the newest reads the rest, and charges the ration once.** The newest reply
  quotes what came before it, and a row that stays white right after you read it looks
  broken. The others are marked read without a ration stamp, using the same statement that
  handles mail read on a laptop.
- **A swipe acts on the whole thread.** Pushing a row away and leaving three of its four
  messages behind, invisibly, would look exactly like it had worked. A single-message
  conversation takes the original path, so archiving one letter still says nothing and
  draws no progress bar.
- **Hold sets, it does not toggle.** Holding a thread where one message was already
  starred would otherwise star the other three and release that one.
- A thread is ranked by its best message, so a long exchange is not buried by a one-line
  opener.

Every other list is unchanged: the archive, search, flagged and sent are still one row per
message, which is right — those are lists you go to looking for a particular message.

## BrightMailbox v2.44 — the demo mailbox is gone

Settings -> DEMO MAILBOX is removed, along with every branch behind it: the second
database, the fictional account, the seeded mail and the guards that kept the demo from
reaching a server. It existed for three releases, which was long enough to take the
screenshots in the README.

The data it wrote does not remove itself, so this release deletes it: the demo database,
its cached bodies and its one attachment, once, on the first launch. Removing a feature
and leaving its files on the phone would mean anybody who tried the demo kept carrying a
few hundred kilobytes of invented mail that no screen could ever show again.

Nothing about real mail changes. The interpolation fixes from v2.41 and the chime fix from
v2.43 are untouched.

## BrightMailbox v2.43 — the chime never played, and the reason was a number

Picking a sound in Settings played it, and then mail arrived silent or wrong.

A notification channel keeps its sound as a URI, and the app was handing it
`android.resource://com.gios.brightmailbox/2131361793` — a **numeric** resource id.
aapt2 assigns those at build time and renumbers them whenever the set of resources
changes, so a number that meant `snd_youve_got_mail` in the build that created the
channel points somewhere else entirely a few releases later. The live channel on a phone
here was pointing into a different resource type altogether.

Nothing corrected it, because a channel's sound is immutable after creation and
`configure` returned early whenever a channel with the right id already existed. The id
encoded which chime was picked, not what it actually resolved to, so a channel built with
a stale URI kept its id, kept its silence, and survived every launch and every update.

The preview button was fine the whole time — it resolves the id fresh on each tap, which
is exactly why the setting looked like it worked.

Two changes:

- The sound URI is now the `/raw/<name>` path form, resolved by name by the system at the
  moment it plays. It cannot go stale.
- The channel id hashes the resolved URI, so any difference in what would be set produces
  a different channel and the old one is swept. Anybody carrying a broken channel from an
  earlier build is repaired on the next launch, with nothing to uninstall and no sound to
  re-pick.

Both paths that announce new mail — the background sync and a manual refresh — go through
the same channel, so both were silent and both are fixed.

## BrightMailbox v2.42 — the demo switch lost its own setting

SHOW THE DEMO restarted the app and came back with the demo still off.

The preference was written with `apply`, which keeps the value in memory and writes the
file on a background thread. That is the right call everywhere else in this app — no tap
should block on a disk write — but this tap kills the process a few milliseconds later to
restart into the other database, and `Runtime.exit` does not wait for that write to land.
The value was gone before anything read it back.

`commit` writes before it returns, which is worth a few milliseconds on a tap that is
about to restart the app anyway.

Nothing else changed. If v2.41 is already installed, the demo mailbox it could not open is
the same one this opens.

## BrightMailbox v2.41 — a demo mailbox, and the rest of the escaped dollar signs

Two things: a mailbox you can show people, and the finish of the fix v2.40.1 started.

### Settings -> DEMO MAILBOX

Every screenshot of a mail client is a picture of somebody's mail. There was no way to
show this app to anybody without either publishing real correspondence or emptying a real
account first, so it has been described in words and almost never shown.

Turning the demo on restarts the app into a mailbox that does not exist: seven letters,
twelve notices, an archive, sent mail and a half-written draft. Everything works on it.
The ration counts it, the sorter explains it, the reader renders it, search finds it,
swipes archive it, the star holds it, the attachment opens, and the invitation can be
answered. None of that is drawn by special-case code — the rows go in the same table and
every screen runs against them unchanged, because a demo painted by its own code stops
matching the app the week after it is written.

Two properties it has to have, and both are structural rather than promised:

- **It cannot reach a server.** Every connection in the app is built in one function, and
  that function returns nothing while the demo is on. No credential is used, nothing is
  fetched, nothing is sent. Writing a message and pressing send says so instead of
  pretending.
- **It cannot touch your mail.** The demo has a database file of its own. Your mail is not
  filtered out of a shared table, it is in a file this build does not open — so no query
  has to remember to exclude it. Your accounts are hidden behind one fictional address
  while the demo is on, so the settings screen is safe to photograph too.

Leaving the demo deletes it: the database, the bodies, the attachment.

It restarts the app because the database file is chosen at startup and every list on
screen is bound to it. A demo that half-applied would be worse than one that takes a
second.

### The escape bug, in the eight places v2.40.1 did not reach

v2.40.1 fixed one line where a Kotlin string template had been written `${'$'}{x}`, which
does not interpolate `x` — it produces the literal text `${x}`. The same mistake was in
eight more places, and four of them were not cosmetic:

- **DEEP REFRESH was un-reading the whole mailbox.** The guard that keeps a re-read
  message from being written back over the stored one compared against a constant, so it
  matched nothing and every message was rewritten. `put` is REPLACE, so a deep refresh
  reset `readHere`, `starred`, `readDay` and `archived` across the inbox. The comment above
  that line describes this exact failure; the line had not done what it said since it was
  written.
- **SEARCH FURTHER BACK had the same fault**, and one worse consequence: a hit found in
  the archive folder is stored archived, so a server search could archive mail that was
  sitting in your inbox.
- **Server drafts were re-importing on every sync.** The key that makes the import happen
  once was the same constant for every draft, so a draft written at a desk arrived again
  every fifteen minutes, and the cleanup pass then dropped the ones already imported.
- The first sync of a second account had the same unguarded write.

The cosmetic four: the FLAGGED and SENT headers printed `${rows.size}` instead of a count,
the drafts list printed `${d.tries} tries`, and the storage bar in Settings reported
`${n / 1048576} MB`.

Nothing in this release changes how mail is fetched, sorted or stored.

## BrightMailbox v2.40.1 — Sent screen crash from duplicate keys is fixed

**The sent screen was crashing on open because every row shared the same identifier.**
The LazyColumn key was built with escaped dollar signs ("${'$'}{m.accountId}/${'$'}{m.id}"),
producing the literal string `${m.accountId}/${m.id}` for every message instead of
evaluating `m.accountId` and `m.id` to their actual values. The first row composed fine;
the second crashed with "Key was already used."

The escape was in the sent-list path only — the inbox path (line 888) has had unescaped
interpolation since the list was added, which is why the crash was specific to SENT and
never hit Letters or Notices. The `distinctBy` guard that v2.40 added to `loadMoreSent`
was correct for pagination overlap but could not help: every key was the same literal
string, so deduping left one row while silently dropping the rest.

The initial load (`loadSent`) now also calls `distinctBy` on the result — defense in depth
for the overlap case that the pagination guard already handles, harmless for the
non-overlap case, and the right answer for any future path that produces duplicates.

Fixes [light-reports#397] — "It closed itself" opening the Sent screen with
three mailboxes signed in.