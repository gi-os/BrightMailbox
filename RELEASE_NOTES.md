## BrightMailbox v2.61 — the app stops opening on an empty inbox

**"Clear skies." was being said before anything had been read.** Every list in the app
starts as an empty one and is filled when the database answers, so for the first frames of
a launch the app holds an empty inbox that nobody has looked in — and the empty screen was
drawn on it. A mailbox with four hundred messages in it opened on "your inbox is empty"
every single time.

Two things have to be true before that sentence is, and they are separate questions: the
database has answered, and the first check of this launch has finished. Until both are, the
screen says "One moment. Looking in your mailbox." in the same place and at the same size,
so the answer replaces it instead of moving it. The second half matters on a phone that has
just been given an account: an empty database with mail on the way is not an empty mailbox.

**Mail arrives now instead of appearing.** A row fades up and settles the last few pixels
into place, and the rows under it start a little later, so a list arrives as a cascade. It
happens once per row: coming back from reading a letter does not replay the list, and
scrolling a long one does not either. Only rows this run of the app has never drawn.

**The air above a letter moved behind the page.** The last release added two lines of it by
growing the sheet's own top padding, which pushed the sender down inside the white. It is
the sheet that starts lower now, with black above it — the same move as taking the gutter
off the sides — and that black scrolls away with the message, because it is the document's
top margin and not a bar.

**The bar under a letter fades in rather than starting.** A white bar under a short message
was a white box sitting in black, with a line where the two met. The bar is drawn over the
letter now, on a ground that goes from nothing at the top to solid white at the bottom, so
the message dissolves into it. The fade is above the icons, never behind them, and the
letter is only ever padded by the solid part — a line of text can scroll under the fade and
never under a control.

## BrightMailbox v2.60 — the sorter stops being trained to agree with itself

`retrain()` labelled every stored message with `m.pile == LETTER` — the verdict the header
rules had already reached. A model trained on the output of the rules it sits behind cannot
learn anything those rules do not already know, however much mail it sees. It was copying
them, and the ration was being picked by the copy.

Labels now come from what you did with a message, ordered by how little the app itself
could have caused it. Starring is an explicit act with no default. Having written to an
address comes from the Sent folder, which the sort has no hand in. Archiving something
without ever opening it is a rejection, and it counts most for mail the rules had called a
Letter. Opening it here counts too, but least: Letters are shown first and rationed, so
they get opened partly because of where they were put. Where there is no evidence at all,
Tier 0's verdict still starts the model off, at a weight any of the above outweighs.

Measured on 7,654 messages from a real mailbox, against labels taken from its Sent folder
and scored over ten splits that share no senders between training and test: the header
rules rank this question at 0.62 AUC, and a model trained on behaviour reaches 0.97.
Average precision goes from 0.054 to 0.74. The rules were never the ceiling.

Corrections you make by hand still outrank everything inferred, at eight times the weight,
exactly as before.

Nothing about the piles changes today. The learned score ranks Letters and picks which
five a ration shows, so this is a change to the order mail arrives in and to which mail
waits for tomorrow.

## BrightMailbox v2.59 — the thread line is on the page, not under it

"3 EARLIER IN THIS THREAD" sat in gray on black between a white message and a white bar, so
the one strip of the reader that was still the app's own ground had the letter above it and
the letter's bar below it. It is white now, in black type, and so is an invitation's row of
answers, which sits in the same place for the same reason.

Whether the reader is a page or not is decided once, at the top, and the message, the
thread, the invitation and the bar all read that one answer. Plain text keeps white type on
black throughout.

## BrightMailbox v2.58 — two more lines of air at the top of a letter

The masthead sat 22 px under the top of the sheet, which is a gutter, not a margin. The
sender's name started just below the rounded corner and the letter read as though it had
been cut off at the top rather than laid down. It is 62 px now, about two lines more.

The number is one constant shared by both drawings of the masthead — the Compose sheet
that is up while the message loads, and the document the WebView paints over it. They have
to agree or the header moves at the handoff, which is the one thing a crossfade between two
drawings of the same letter must not do.

**The ··· in the reader's bar is black.** It was set in the secondary gray, which on the
white bar put a #777 mark beside three black icons and read as disabled rather than as the
quietest of four controls. A bar is one row.

## BrightMailbox v2.57 — the letter runs to both edges

Every screen in this app sits inside a one-unit gutter, and the reader was one of them. A
message is white, the gutter is black, and black is what this panel shows when nothing is
drawn — so the gutter never read as a margin around a page. It read as the background
coming through, and every email looked like it had been set down short of both edges.

The sheet goes edge to edge now. The gutter moved onto the chrome instead — plain text,
the thread line, the invitation row — because those are our page, not the sender's.

**The bar under the message is white.** With the sheet at both edges, a black bar was a
hard line across the bottom of the page and the white stopped a few units short of the
panel. White carries the page all the way down. The icons are the same white artwork,
tinted black, rather than four more files to keep in step. Plain text keeps its black bar:
a white strip under a black page belongs to nothing.

**A message that was not already on the phone opened black.** The reader picks its layout
off whether the message has HTML, and before the fetch answers, "no HTML" and "no answer
yet" look the same — so a letter nobody had prefetched arrived as a black rectangle,
printed a line about getting the text, and snapped into a white page when the fetch
landed. While the fetch is still out, the sheet is what is coming, so the sheet is what is
drawn: the same white shape, with the sender, the date and the subject already on it, in
the same place the message will put them. It can be pulled down to leave, like any other
message.

**The body fades in over that sheet.** It used to switch on in one frame, which was the
right call when what sat underneath was a blank white rectangle — the letter arrives by
sliding up, and a slide that also changes opacity reads as two animations arguing. Now the
header is already drawn in the numbers the message itself uses, so this is a crossfade
between two drawings of one letter and the only thing moving is the body.

## BrightMailbox v2.56 — the header stops being the biggest thing on the screen

Every screen wore a three-unit bar with a 30 px word in it. On a panel 31 units tall that
is a tenth of the screen spent saying where you already are — and on the screens whose
whole content is a list, the title was the largest thing drawn.

The word is 22 px now and the bar is 2.2 units: about a third off the type and a quarter
off the height, which is roughly one more row of mail on every list.

A screen title has its own entry in the scale now instead of borrowing `subheading`. It was
never a heading over anything: there is one per screen, it never sits beside body copy, and
it does not need to hold its own against a paragraph. What it does need is to read as a
title rather than a caption at that size, and the fix is the one the action bar already
uses — tracking. 15% is what makes a bar label read as a control without a box around it,
and it does the same for a small word at the top of a screen.

Every screen moved together: Home, Notices, Parcels, Archive, Flagged, Sent, Drafts,
Settings and the compose screen.

## BrightMailbox v2.55 — USPS said delivered on a parcel that was in transit

Reported from a real parcel, and it was the parser, not the mail.

**A tracking page draws the whole journey, not just where the parcel is.** USPS renders a
progress bar whose steps are labelled Shipped, In Transit, Out for Delivery and Delivered,
and every one of those words is in the page text on every parcel, whatever its state. The
reader took the first line that looked like a status, and on that page the first line that
looked like a status was a label on the bar.

Worse, the real status line was invisible to it: the pattern for a moving parcel matched
"In Transit" exactly and left no room for what follows, so "In Transit to Next Facility" —
the actual answer, sitting right there — matched nothing. All that was left to match were
the bar labels.

Two changes:

- **The status patterns have room for their own detail.** That is also what tells a status
  from a step: "In Transit" is a label on a bar, "In Transit to Next Facility" is where the
  parcel is.
- **When a page offers several candidates, the bare ones are the bar.** The detailed line
  wins. When every candidate is bare, there is no answer to give and the screen says it
  could not read the page rather than picking one.

Delivered gets one more hurdle, because it is the claim that costs something: a bare
"Delivered" needs a fact like "Delivered To" or "Received By" standing behind it. UPS says
the bare word and then says who received it; a progress bar says the bare word and nothing
at all.

Four more tests, including the exact shape that caused this.

I wrote the reader against a real UPS page and a USPS page that had nothing to report — I
never saw a USPS page with a live parcel on it, and this is precisely what was in the gap.

## BrightMailbox v2.54 — parcels are kept, and each one has a page

Two changes that turned out to be the same change.

### The list is stored

Opening PARCELS used to rebuild the whole list: a walk over every message in the mailbox,
reading every cached body off disk, running the detector across all of it. That is why the
screen took a beat to fill, and why the answer moved around depending on what had been
archived since. The work was the same work every time and the answer was almost always the
same answer.

Parcels are a table now. The screen draws what is stored the instant it opens, and a sync
updates it as the mail that describes a parcel arrives — which is the only moment it can
change. Nothing scans on the way in.

Storing it changes what the list *is*. It was a view over the mail; it is now a record of
parcels, which is what it was always describing. The merge is where that distinction lives:

- **A later mail usually knows less.** A carrier's "delivered" notice carries no shop and
  no item — it is about a box, not an order — so letting it overwrite the shop's own "your
  order of X has shipped" turns a row that said what is in the parcel into one that says
  "UPS". Every field survives unless the newer mail has one of its own.
- **Newest by mail, not by scan order.** The sweep reaches into the archive, so without
  comparing timestamps a six-week-old "shipped" found late would undo yesterday's
  "delivered".
- **Swipe to put a parcel away, and it stays away.** This had to arrive in the same release:
  a recomputed row vanished on its own once the mail stopped describing it, and a stored one
  does not. That was the gap the old design admitted to and could not close.

A delivered parcel stays on the list for three days so you can see that it arrived, and its
row is forgotten a month later so the table does not grow for ever.

### Each parcel has a page

Tapping a parcel used to hand the tracking number to whatever opens links: leave the app,
wait for a site built for a desktop to load on a 3.9" panel, find the one line you wanted
somewhere inside it. The page is still one tap away and still the authority, but the
question people actually have is "where is it", and that has a three-word answer.

So a parcel opens a parcel screen, and the screen reads the carrier on the way in. Opening
one *is* asking, which is why the reading needs no gesture of its own — and why the list
stays free and silent while the network is touched only on a screen about a single parcel.

It shows the carrier's own status large, the facts underneath it — Delivered To, Received
By, Estimated Delivery — and one line saying which answer you are looking at: the carrier's
page just now, or your mail. Those are worth different amounts and only one of them is on
the screen at a time.

When the page will not load it says so and says which answer it fell back to, rather than
showing the email's word and letting it look like the carrier's.

## BrightMailbox v2.53 — the carrier's own answer, on the row

Hold a parcel and the app reads the carrier's tracking page and puts what it says under
the row: "Delivered", "Delivered To · LONGVIEW, TX US", "Received By · TAYLOR". The line
the mail gave you stays where it was. Both are true and they are true at different times —
the mail says what the carrier announced and when it got round to announcing it, and this
says what the carrier says now.

This is what v2.51's probe was for, and the probe is gone.

**The answer to the probe's question is yes.** All four carriers render for a real browser.
UPS holds a bot-check interstitial for about five seconds and then serves the full page;
USPS and FedEx answer straight away. A plain HTTP request still gets nothing — that part
was never in doubt, and it is why this loads a WebView rather than a URL.

**Text, not selectors.** A DOM query written against today's markup breaks the week a
carrier ships a redesign, and it breaks silently into a screen that says nothing. Every
one of these pages puts a label on one line and its value on the next, and those words
change far more slowly than the markup around them. The parser is pure Kotlin with ten
tests, and the fixtures in them are real pages captured from all four carriers rather than
shapes I imagined.

The rules it needed, none of which were guessable from a spec:

- **Material icon ligatures land in the text as words.** UPS's status line is literally
  "Delivered check_circle", and "Tips to Avoid Fraudchevron_right" has no space in it at
  all. They are all lowercase with an underscore, which nothing else on a tracking page is.
- **The tracking number has to be on the page.** A stale link redirects to a marketing
  homepage that renders perfectly and says "delivered" in the advertising copy.
- **Only the lines around the number are read.** These pages are mostly navigation, cookie
  notice, careers advert and footer, and all four put the status directly under the number.
  That one rule throws the rest away without a per-carrier list of things to ignore.
- **Whole lines only.** "Your package has not yet been delivered" contains the answer and
  means the opposite of it.
- **A label is never a status.** "Delivered To" is one character class away from
  "Delivered", and reading the label as the status marks a parcel delivered the moment the
  page mentions where it is going.
- **"Tracking Not Available" is not a status either.** Each carrier's not-found page is
  otherwise a perfectly normal page, carrying the number and the word tracking, and a
  hopeful parser reads a status straight out of one.

**Anything it cannot read says so.** "Could not read that page. Tap to open it." A wrong
status on a parcel is worse than no status, because nobody double-checks a screen that
looks confident.

Still nothing is stored and nothing runs in the background. The list is free and silent;
this is the one thing on it that goes to the network, it happens because a row was held,
and the answer lasts as long as the screen is open. Nothing is logged either — while it
was a probe it printed the page to logcat, which is the wrong thing to leave in a feature
whose pages have somebody's delivery address on them.

## BrightMailbox v2.52 — DHL's link never ran the search

Tapping a DHL parcel opened `dhl.com/us-en/home/tracking.html?tracking-id=<number>`, which
loads DHL's tracking page with the number sitting in the query string and the form on it
empty. The search never ran. On screen that reads as a carrier who cannot find your parcel
rather than as a link that did nothing, which is why it survived six releases.

DHL wants the express path and `submit=1`:

```
https://www.dhl.com/us-en/home/tracking/tracking-express.html?submit=1&tracking-id=<number>
```

Checked against the live page rather than against the docs — the same number returns
results on the new URL and an empty form on the old one.

This only affects parcels where the app builds the link itself, which for DHL is most of
them: a shipper's mail names DHL and the number and links to its own order page, so there
is no carrier link in the mail to prefer.

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