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