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