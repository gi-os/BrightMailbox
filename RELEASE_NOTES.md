## v2.40 — SENT loads twenty at a time, and stops crashing

**The sent list was fetching sixty messages over a folder with no local cache behind it,
every single time the screen opened** — including when you pressed back out of a sent
message, which is why leaving one felt like the app had lost your place. Twenty now, with
the next twenty arriving as you reach the bottom of the list, and the list is kept when you
leave it. REFRESH is how you ask for it again, which is a button somebody presses on
purpose.

Two things were making it slow rather than one. Finding the sent folder costs a full
listing of every folder on the account — every label, on Gmail — and that was being paid
again on **each body fetch of a sent message**, because a message from outside the inbox
has to say which folder it came from before it can be read. The answer does not change
while the app is running, so it is asked once now.

The crash is guarded at the point it happened: two rows sharing an identifier is fatal to a
list, and it fails when the list draws rather than when the duplicate was made, so it
looked like opening the screen was what broke. Rows are made distinct before anything sees
them, and again where two pages join — a message sent between one fetch and the next can
legitimately appear in both.

**Cancelling a draft puts you back in the drafts list.** It always went to the inbox, which
is right when you pressed WRITE there and wrong everywhere else: opening a draft, deciding
not to finish it, and being thrown to the inbox loses the list you were working through.
Abandoning a reply now returns you to the message you were replying to, for the same
reason.

**Choosing a notification sound plays it.** Picking one and being told nothing is a setting
you have to guess at — the only way to hear it was to wait for mail to arrive, which is the
one thing you cannot arrange. It plays at notification volume, because a preview at media
volume is not a preview of what the phone will actually do.

## v2.39 — Both swipes, and you choose what they do

**Settings → GESTURES.** Pushing a row left has archived it since v2.16; now it can archive,
hold, mark read, delete or report as junk — and pushing a row *right*, which used to do
nothing at all, can do any of the same.

Left still defaults to Archive, so nobody's hands have to be retrained by an update. Right
defaults to Hold, because it is the one action with no consequence: finding a gesture by
accident should teach you it exists, not cost you a message.

Delete and Junk do not ask on a swipe, and cannot — a gesture that opens a dialog is slower
than the button it was meant to beat. Neither is a default, the row has to travel most of
the screen, and it buzzes when it arms. Anyone who turns one on has read the line under it.

A direction with nothing behind it does not move at all. The row staying put against a push
that would do nothing says "not that way" in the only language a gesture has; letting it
slide and spring back would read as a failed action rather than an absent one. And each
word waits on the side the row uncovers — push left and the word appears from the right
edge, which is the opposite of where it feels like it should go until you try it the other
way round.

The archive and the flagged list keep their own swipes. Putting a message back, and letting
one go, are what those two screens are for, and neither is a setting.

## v2.38 — Mail arrives while you are looking at it

**IMAP IDLE, held for exactly as long as the app is open.** The protocol has a way for a
server to tell you something arrived instead of you asking every fifteen minutes, and this
app has been asking. Now, while the inbox is on screen, the connection stays open and a
message appears the moment it lands.

The scope is the point. The watch starts when the app becomes visible and is cancelled the
instant it stops — no service, no notification, nothing running behind your back, and
therefore no battery question to answer: it costs what having the app open costs. If the
connection drops it reconnects with a backoff that doubles up to five minutes, because a
watch that retries instantly turns a flapping signal into a loop that opens a new encrypted
connection several times a second, which is far more expensive than the polling it was
meant to improve on. The fifteen-minute check underneath is untouched — a server without
IDLE, a network that will not hold a connection, and a phone with the app closed all still
get their mail on the old schedule. This is a way to hear sooner, never the only way to
hear.

**Drafts written somewhere else show up in DRAFTS.** Start a message at a desk, finish it on
the phone. One way only: uploading this app's drafts would mean a second copy of every
half-written message and two places that each believe they own the text, with no way to
tell an edit from a conflict. Imported drafts say where they came from, because sending
one from here does not remove it from the mailbox that wrote it.

**Settings → STORAGE** says how full the mailbox is, when the server publishes a quota.
Many do not, and then the section simply is not there rather than reporting its own
ignorance. Worth having next to an archive button: archiving keeps mail, so it keeps taking
room, and DELETE is the only thing in this app that gives any back.

## v2.37 — A place for what you are holding

**Flagged messages — 3**, one line at the top of the inbox, and tapping it opens them.

Holding a message already meant something strong here: it ignores the day's ration, stays
past midnight when everything else has moved on, and is skipped by ARCHIVE ALL. So a held
message can sit in the list for weeks, and once there are a few of them the marks are
scattered down a scrolling column with no way to see them together. The line is absent at
zero — a row reading "Flagged messages — 0" teaches you to stop looking at the top of the
screen — and it is on the empty screen too, which is exactly when a hold matters most:
everything else is read or put away and the ones you kept are all that is left to do
anything about.

The list crosses the archive line, which no other list in this app does. Putting a message
away does not stop it being held, and a held message you archived is precisely the one you
cannot find again by remembering who sent it; the archived ones say so underneath.

Swiping a row there **releases** it rather than archiving it. Everywhere else the swipe puts
a message away; on a list of held messages the thing you want rid of is the hold, and
archiving would leave the row exactly where it was.

## v2.36 — Stars sync both ways

**Flag a message at a desk and it is held here.** Holding a row has pushed IMAP
`\Flagged` since v2.14 — the same bit Gmail draws as its star and Outlook as its flag —
and the app never once asked what that flag said coming back. It was a one-way mirror: this
phone could tell your mailbox about a star, and your mailbox could not tell this phone.

Now the check that already asks the server what became of the messages it holds reads both
bits out of the same fetch, so nothing extra goes over the wire. Starred there means held
here. **Unstarred there means released here** — and that direction is the one worth being
careful about, because a star overrides the daily ration, survives the day rollover and is
skipped by ARCHIVE ALL, so removing one changes what the phone shows you. It is applied
only to messages the server actually answered about: a message it does not mention has left
the inbox entirely, which is a different thing and already handled.

Under it, the two copies of that reconciliation became one. They were written twice and the
second was already a line behind the first, which is how the star would have ended up
syncing on an ordinary check and not on a deep refresh.

## v2.35 — Deleting an archived message actually deletes it

**A bug I shipped in v2.33, found by reading the code rather than by anything going wrong
on the phone.** DELETE and JUNK were built on a move that always opened the inbox as its
source, because it had been generalized out of the archive button, where the inbox is
always right. It is wrong for everything the generalization was for. A message in the
archive or the sent folder has an identifier that means nothing in the inbox, so the server
was asked to move a message it could not find, answered that it had done so, and the app
removed the local copy of a message still sitting on the server — where a later deep
refresh could bring it back.

Moves now start from whichever folder the message is actually in, and a move that matched
nothing is reported as the failure it is instead of as success. Anything archived before
this release, and anything archived in a web client, is found by its Message-ID instead —
the same route un-archiving has always used, for the same reason.

## v2.34 — Preview lines are a setting, and the screen stays on while you wait

Settings → READING → **Preview lines**, off unless you turn it on. v2.33 shipped them on
for everyone, and that was the wrong default for this app: a ration exists so the day's
mail is a short list you finish, and a preview is where skimming starts. Sender and subject
are enough to decide whether to open something.

Nothing changes underneath. The text is still written into the row when the message body is
fetched, whether the setting is on or not, so switching it on fills the whole list at once
rather than waiting for the next sync — and switching it back off costs nothing either.

**The screen no longer turns off in the middle of a long job.** Archiving four hundred
messages is a couple of minutes of IMAP and a send over a weak connection is not instant;
the panel used to go dark thirty seconds in, and on this phone that is not only the
backlight — the process becomes a candidate for the system's power rules the moment the
screen goes. What you saw was a progress bar that had stopped halfway and a job that had
not finished. The screen is now held on for exactly as long as there is a progress bar to
watch, and released the moment there is not.

## v2.33 — Unsubscribe, delete, previews, and an outbox

**UNSUBSCRIBE, under the ··· in a message.** The `List-Unsubscribe` header has been read
since v2.0 — its presence is one of the facts that makes a message a Notice rather than a
Letter — and until now the app only ever used it to sort. Three routes, in the order of
how little each asks of you: a one-click POST when the sender published RFC 8058, an email
from the account the message arrived at, and failing both, their web page. A GET is never
issued, which is the whole reason RFC 8058 had to exist: a link that unsubscribes on GET
gets followed by a scanner eventually, and nobody pressed anything. Unsubscribing archives
the message too — leaving it in the pile is half a decision.

**Delete and report as junk.** Until now archive was the only way out, which on Gmail means
keeping a message forever under a different label. DELETE moves to Trash, where your
provider holds it for a while; JUNK moves to the Junk folder, which is what a spam filter
actually learns from, and sends that sender to Notices from then on. Both ask first, in
place, by replacing themselves with the question — the only two verbs here that take mail
away are the only two that ask.

**Rows have a preview line now.** IMAP carries no snippet and asking for one per message
costs a round trip each, which is why a row has been sender and subject for thirty
releases. But the app already downloads the bodies of everything on screen so a tapped
message opens instantly — the text was sitting in a file by the time the row was drawn, and
nothing read it back. Nothing extra goes over the network. Letters only: a Notice stays two
lines, which is the point of being a Notice.

**An outbox.** "Not sent. Your draft is still here" was true and still asked you to be the
retry loop — press send, walk into a tunnel, remember later. A message that will not go is
now queued and goes out on the next check, which is on open and every fifteen minutes.
Five attempts, then it stops and stays a draft: a message refused because the address does
not exist will be refused forever, and a queue that retries forever is one you uninstall.
DRAFTS says how many are waiting.

**The To field suggests people you have written to.** That list has been kept since v1 and
read by nothing, so the app knew everyone you had ever emailed and still made you type the
whole address. It matches any part of an address, not just the start, because on a phone
the half you remember is as often the domain as the name.

**Search further back now reaches the archive,** not just the inbox — which is where most of
a mailbox's history lives.

**The menu scrolls.** Adding SENT pushed SETTINGS under the action bar on a 3.9" screen, and
it was not cut off so much as silently unreachable. A menu that hides one of its own items
is worse than no menu.

**Fixed: the pull-down jumped up and down through the whole gesture.** The sheet was moved
with a layout offset, and the node it was moving is the one the gesture lives inside —
every pixel the sheet moved got subtracted from the next drag it was told about, so the
sheet fed its own input and the two chased each other. It is drawn lower now instead of
laid out lower, which moves the pixels and leaves every pointer coordinate where it was.
The settle is also allowed to happen once per gesture rather than twice.

## v2.32 — Sent mail, a deeper refresh, and a sheet that follows your finger

**SENT, under the hamburger menu.** Everything this mailbox has written, newest first,
read straight off the server's sent folder. It is not stored on the phone and never will
be: sent mail in the same table as the inbox would be caught by ARCHIVE ALL and by the
sync that reconciles what the inbox holds, both of which address a message by its position
in the inbox — and a bulk archive would then try to move messages out of a folder that has
never held them. So the list is fetched each time it opens, which also means it is never
out of date. A message you wrote opens in the same reader, with FORWARD where reply would
be and no archive button.

**Pulling a message down now follows your finger.** It used to sit still through the whole
gesture and then play a fixed slide once you let go, which is the difference between a
sheet you are holding and a button you pressed. The page moves one-to-one with the drag,
and letting go either finishes the journey or puts it back — and putting it back is the
part that makes the gesture safe to try, because an unfinished pull is visibly undone
rather than silently ignored. Changing your mind mid-drag works too: pulling back up
returns the sheet before it returns to scrolling the text.

**DEEP REFRESH, in Settings.** CHECK NOW reads the newest thirty messages per account,
which answers "has anything arrived". DEEP REFRESH walks the whole inbox as far back as
the history setting allows and re-asks the server about every message the app already
holds. It is for when the phone and the mailbox have drifted apart, which a week of
reading on a laptop will do. It never opens the archive, so nothing you have put away
comes back.

**SEARCH FURTHER BACK,** at the bottom of the search results. Search covers what the phone
has downloaded; this asks the server to search the rest — sender, subject and body — and
keeps what it finds, so a result is a real message that opens and can be replied to rather
than a preview that would need fetching again.

**A progress line at the top of the screen** for anything slow: archiving, sending, a deep
refresh, a server search. It says what is happening and how far along it is, and a bulk
archive is one line for the whole job rather than one per batch of twenty-five — the
batching is how the protocol works, not something worth reporting. The line sits above
every screen, so walking back to the inbox mid-archive does not make the work look like it
stopped.

**Dates from a previous year now say which year.** A stamp reading "3 Sep" on a message
from 2024 is misleading, and a bare weekday on a year-old message means nothing at all.
Only old mail carries the year; on this year's mail it would be noise on every row.

**Fixed: opening a sent message would have crashed the reader.** The "why is this here"
sheet looked its pile up in an enum of exactly two values, and sent mail is deliberately in
neither.

## v2.31 — Attachments, reply all, and forwarding

**Send files.** ATTACH on the compose screen opens the phone's file picker; attached files
are listed with a tap to remove one. About eight megabytes in total, which is what fits
under the size most mail servers accept once encoding has inflated it.

**Reply all** puts the sender in To and everyone else in Cc, minus you — your addresses
across every mailbox you have signed in, not only the one it arrived at, so a message sent
to two of your accounts does not copy you on your own reply.

**Forward** starts with the original message quoted beneath a header line and nobody in the
To field. A forward deliberately does not thread: it is a new message about an old one, and
filing it under a conversation the new recipient has never seen would be wrong.

Both live under the ··· in a message, next to the reply icon, which still replies to the
sender alone.
## v2.30 — Answer an invitation

**ACCEPTED / DECLINED / TENTATIVE, under a meeting invitation.** Tapping one sends the
organizer a real reply — not a note saying you accepted, but the thing their calendar
reads and files against the event. Nothing else on this phone can do that: it is an email,
and only a mail client sends those.

Invitations still go to Notices, which is right. One is not correspondence and should not
spend one of the day's five letters. But a notice you can act on is not a contradiction.

The reply is sent from the mailbox the invitation arrived at, which matters more than it
sounds: answering a work invitation from a personal address tells the organizer's calendar
about an attendee it has never heard of, and the answer is filed against nobody.
## v2.29 — Notifications actually work

**New letters notify again, and that is a fix rather than a feature.** Permission to post
notifications was declared but never asked for, and since Android 13 not asking means
denied — so nothing was ever posted, and the code quietly swallowed the refusal. No banner
on BrightControl, no sound, no lock-screen row, and nothing anywhere saying why. The app
asks once, after you have signed in.

**A refresh that finds mail makes a sound.** Announcing new letters had exactly one caller,
the background check — so pressing refresh and finding three letters was silent. The sound
belongs to mail having arrived, not to who went looking.

**The archive is paged, a hundred at a time**, with the count saying which hundred of how
many. It was capped at 500 with nothing to say so, which for a heavily archived mailbox is
indistinguishable from mail having gone missing.

**A second unsent message can no longer be stranded.** The compose screen restored "the
newest draft with no reply target" — so starting two separate messages and leaving both
saved the older one somewhere with no way back to it. There is a DRAFTS screen in the menu
now, and opening one from there opens that one.
## v2.28 — Any IMAP mailbox

**Fastmail, iCloud, Zoho, and anything else that speaks IMAP.** Setup lists them by name,
because nobody should need to know their own mail server's hostname to read their own mail.
Each uses an app password, the same as Gmail already did, and the screen says where to make
one.

**Something else** is the fourth option: type the incoming server and the app fills in the
likely outgoing one. That covers a mailbox at work, one you host yourself, and a bridge
running on a machine at home — Proton's, for instance, which is the only way Proton speaks
IMAP at all. A bridge has to be reachable from the phone, not just from the computer it
runs on.

Nothing else in the app changed, and that is the point: the transport underneath has been
plain IMAP since v2.0, and the only thing stopping it from talking to the rest of the world
was that the addresses were written into the code. Sorting, threading, the archive and
search never knew which provider they were dealing with.

Tuta is the exception and always will be. It has no IMAP, no POP and no SMTP — not an
omission but the architecture, since those protocols assume a server that can read what it
is routing and Tuta's cannot.
## v2.27 — The black screen, properly this time

**The message had no height.** Adding the white loading sheet in v2.24 wrapped the message
view in a box, and the instruction telling the message to fill the screen went to the box
instead of to the message. The box filled the screen; the message inside it shrank to
nothing. So a letter loaded white, and turned black the moment it finished — the white was
the box, and there was nothing in it.

That is the black screen, and it was mine. The fix in v2.26 was a real bug with the same
symptom — a failed fetch showing an empty message instead of saying it had failed — but it
was not this one, and this one was hitting every formatted message rather than the occasional
one.
## v2.26 — The black screen, the slow opens, and the app talking to itself

**Fixed: some letters opened to a black screen, permanently.** When the text could not be
fetched, the app handed the reader a perfectly valid but empty message instead of saying it
had failed — so the screen printed nothing at all, with no explanation. It then *saved* that
emptiness, which made one dropped connection blank that message for good: every later open
read the empty copy back without going near the network.

Failures are no longer saved, and the reader now says which of three things is happening —
here it is, getting it, or it did not come through, with a TRY AGAIN.

**Fixed: some letters opened slowly.** The app fetches the text of what you are likely to
tap before you tap it, and that list had never grown past the first eight letters and two
notices — from when those were the only two screens. Anything in the archive, in a search,
in a thread or further down the notices was always a cold fetch. It now covers the lists
that actually exist.

**Fixed: "Nothing new." appearing on its own every few seconds.** That sentence is a reply
to pressing refresh, and the automatic check on opening the app was saying it too — and
saying it again each time anything rebuilt the screen, which opening a message can do. The
automatic checks are silent now, and run at most once a minute. A *failure* is still always
reported: that was the point of putting it there.
## v2.25 — Conversations, and drafts that survive

**A message now knows what came before it.** Under a letter that is part of a conversation
there is a line saying how many earlier messages there are; tap it for the list, tap one to
read it. Collapsed by default — on five letters a day a thread is context for the one you
were handed, not a stream to scroll, and unfolding on its own would bury the message you
opened under its own history.

The rows show who and when rather than the subject, because inside a thread every subject
is the same subject. Archived messages are included: the first half of a conversation is
usually already filed away, and leaving it out makes a thread look like it began in the
middle.

**What you were writing is kept.** Leaving the compose screen — CANCEL, the back gesture,
or the app being closed behind you — saves the draft, and opening WRITE or replying to the
same message again brings it back. An empty one is not saved, and a sent one is cleared, so
nothing accumulates. Until now the message was simply thrown away, and "Not sent. Your
draft is still here." was not true.
## v2.24 — A white sheet while it loads, and no more tiny messages

**The letter is white on its way up.** It was held invisible until the message had painted,
so what slid up was a black rectangle that turned into a letter. The sheet is drawn in its
final shape and place from the start, so the thing arriving is a sheet the whole way.

**Fixed: some messages rendered tiny in the top-left corner.** Finding the width a message
was built for was matching the number inside `max-width` — which means the opposite:
"grow to fit, no further", the mark of a layout that has no fixed width at all. A message
with 400 pixels of content inside a `max-width:1200px` wrapper was laid out at 1200 and
scaled down to fit, so its content came out at a third of its size with white all around it.

The width is also now taken from the number that repeats through the message rather than
the largest one found anywhere, because an email declares its grid over and over — the
outer table, the rows, the spacer cells — and a single stray declaration should not decide
the layout for the whole message.
## v2.23 — Out of the archive, and back where you were

**Swipe a row in the archive to put it back.** The mirror of the swipe that archived it, so
the archive is a place you can take things out of rather than a one-way chute. It is a real
move back to your inbox on the server, not a local flag — a local one would be quietly
undone by the next sync.

**Leaving a message returns you to the list you opened it from.** Notices, the archive, a
search you had just typed — all of them used to drop you back on the front screen, which
meant finding your place again every time you read something.

**The second ARCHIVE ALL is gone** from the bottom of the inbox; the one in the menu is
enough.
## v2.22 — A menu, an archive, downloads and search

The gear at the top of the inbox is a **menu** now, because settings is one of five places
rather than the only one:

- **SEARCH** — one box across Letters, Notices and the archive together. Sender and subject;
  bodies are files rather than rows, so matching them would mean opening a few hundred of
  them per keystroke, and the screen says so instead of leaving you to guess.
- **VIEW ARCHIVE** — everything you have put away, reading and opening exactly like the
  piles it came from.
- **DOWNLOADS** — the files you have saved out of attachments.
- **ARCHIVE ALL** — clears the whole inbox, both piles, leaving anything you have held.
  Nothing is deleted: archive is a move to All Mail, where every other client can still
  see it.
- **SETTINGS** — where it always was.
## v2.21 — The wheel scrolls

The empty screen says **Clear skies** now, with the plain fact under it, and it has lost
both the rule across the middle and the CHECK NOW button — the refresh icon at the top of
the same screen already did that, and two controls for one action a few units apart reads
as an app unsure of itself.

The brightness wheel scrolls Mailbox, the same as it does in the other apps on this phone:
letters, notices, settings, a message, and a PDF. Turns only — the press, the camera button
and brightness still belong to LightControl, which owns them phone-wide.

It reads the way the wheel reads elsewhere, because it is the same code: one notch adds to
a debt that each frame pays a share of, so a single notch glides instead of jumping and a
fast spin becomes one continuous sweep. The first notch after a pause is held back and only
released by a second one, so a thumb brushing the wheel does not move the page.

The part specific to a mail client is the message itself. A rendered message is a focused
WebView, and a WebView takes the key before anything in the app can — so the wheel is read
at the activity, above the whole view hierarchy, and handed down to whatever is on screen.
## v2.20 — Clear today's letters in one go

**Letters has an ARCHIVE ALL of its own**, on the bar: the archive icon with ALL under it.
It clears the letters on screen — today's — and deliberately not the mail waiting for
tomorrow, which is not in front of you and should not be swept up by a button aimed at
what is. Starred letters are left where they are, the same as on Notices. Archive is a move
to All Mail, so everything it clears is still in the mailbox.

The stacked icon-over-word shape is there because "ARCHIVE ALL" spelled out takes most of
the row on a 3.9-inch screen, and this bar already carries two other things.
## v2.19 — SEND says what it is doing, invites go to Notices

**The compose screen said SENDING when nothing was being sent.** It was reading a flag
that every background sync also sets, and a sync runs when the app opens and every fifteen
minutes — so the button spent most of its life mislabelled and disabled. It now watches
only the message it is actually sending.

**Teams and Outlook meeting invitations go to Notices.** An invitation is not
correspondence: it is an object for a calendar, and nothing here can accept or decline it,
so it should not spend one of the day's five letters. This is the one rule that outranks
"you have replied to this person" — an invite from someone you write to daily is still an
invite.

**ARCHIVE ALL returns to the list straight away.** It always ended on the main screen, but
only once every notice had been moved on the server, so on a large pile it sat there
looking like a button that had done nothing. It leaves first now and finishes the work
behind you.

**The sheet's width is pinned to the view.** Third attempt at the clipped top-right corner.
The sheet was sized at 100%, and 100% is the browser's opinion of the page width, which can
land a fraction wider than the view really is — in which case the left corner is fine and
the right one falls off the end, which is exactly the symptom. It is now set to the view's
measured width, so the right edge is arithmetic rather than a computation I cannot see.
## v2.18 — Plain text scrolls again, PDFs open, and counts tell the truth

**Fixed: plain text could not be scrolled.** The pull-down-to-go-home gesture was taking
every vertical drag before the text could have it, so the message never moved and dragging
down — which is how you scroll up — left the message instead. It now only ever sees the
part of a drag the text could not use, which at the top of a message is all of it and
anywhere else is none of it. Reported by Bhughes, and it had made plain text unreadable.

**PDFs open in the app.** Attachments used to be handed to whatever else was installed,
and on a Light Phone that is usually nothing — so a statement or a boarding pass arrived on
the phone it was sent to and could not be read on it. Pages render one at a time as you
reach them, so a long document does not have to be held in memory all at once.

**Any attachment can be saved to the phone**, into Downloads, where a computer can reach
it. Anything the phone cannot open is offered there instead of refused.

**Read one on a laptop and it is read here.** A message read in another mail client now
goes grey in Mailbox on the next sync, exactly as if you had read it here — but it does
*not* spend one of the day's five. A morning at a desktop mailbox should not close the
phone's day before it starts.

**The notices count is the real number.** It said 300 no matter what, because it was
measuring the length of a list that stops at 300 rows rather than counting.

**Addresses that refuse replies go to Notices**, wherever the no-reply sits — the middle of
the address, spelled with an underscore, or in the domain with a friendly name in front of
it. All three were landing in Letters.

**Small:** WRITE is a send icon now. A message's own stylesheet can no longer resize the
page around it, which was the last of the cropping on the right edge.
## v2.17 — Outlook works out of the box

**Tap ADD OUTLOOK, sign in, done.** Until now that button was grey on every release ever
published and said "not set up in this build", because the Microsoft app registration it
needs did not exist. It does now, and it ships with the app — so Outlook and Microsoft 365
mailboxes need no setup at all, no portal, and nothing to type on a phone keyboard.

Gmail is unchanged and still uses an app password. That difference is not an oversight:
Google caps every mail scope at 100 users pending a four-figure annual audit, and
Microsoft caps nothing, so one registration covers everybody on the Microsoft side and no
registration would cover everybody on Google's.

SETUP.md gained the two traps worth knowing if you build your own copy — a personal
Microsoft account cannot hold an app registration at all, and the API permissions step
fails silently at first sync rather than at sign-in.
## v2.16 — A default view, a swipe, and a screen worth looking at

**Settings → READING now decides how messages open**, as sent or as text only. The ··· in
the reader still switches whichever message is in front of you; it no longer quietly
changes the setting, and the setting no longer gets overruled by something you did to one
message an hour ago.

**Swipe a letter or a notice to the left to archive it.** The row moves with your finger
the whole way, with the word behind it, and springs back if you let go early. Archive is a
move to All Mail, so nothing swiped away is gone. Vertical scrolling is untouched: the
gesture only starts once your finger is clearly going sideways.

**The empty screen has been redesigned.** It used to be two small lines in the top corner
of a black rectangle, which reads as a screen that failed rather than a mailbox with
nothing in it. It now says so at full size, with the state of the machine underneath —
when it last looked, how many letters are waiting for tomorrow, and, if it could not reach
your mail at all, what went wrong. That last line is new, and it is the one that matters:
an unreachable mailbox used to produce a screen that looked exactly like an empty one.
CHECK NOW sits on the bar there too, because on a screen with nothing on it that is the
only thing anybody wants.

**Centered messages stay centered.** A message that centers a fixed-width block was the
worst case for a narrow screen: a block wider than the space it sits in is not centered by
anything, so it started at the left edge and hung off the right. Messages that declare
their width are laid out at it and scaled, centering intact. Messages that declare nothing
now let their tables shrink to fit instead of running off the edge.
## v2.15 — The first letter animates too

**The first message you opened never seemed to slide up.** Two separate reasons, both now
fixed. The reader picks its layout from whether the message has HTML, and until the text
had been fetched that question had no answer yet — so the first letter slid up as a plain
page and then snapped into a formatted one, which looks exactly like no animation at all.
It now reads what is already on the phone before opening, so it knows what it is drawing.
The rendering engine also started up on the first message and took long enough to miss the
animation; it starts while you are still looking at the list instead.

**The rounded top corners are no longer clipped on the right.** Messages built on a
600-pixel grid were fitted by scaling the whole page, which scaled the letter's own frame
with them and landed its corners between pixels. Now only the message is scaled down. The
sheet, its corners, and the sender and subject above it stay at full size — so the header
is also no longer shrunk to match whatever width a newsletter happened to declare.
## v2.14 — Read mail stays put, and you can hold one

**Reading a letter no longer makes it disappear.** It turns gray and stays exactly where
it was until the next day. Until now, opening a letter took it off the list on the spot,
so the only sign you had read anything was that the list got shorter — and a letter opened
by mistake could not be found again from inside the app at all.

**Hold any letter or notice to keep it.** A held message gets a small star and stops
obeying the rules about what the list shows: it ignores the daily five, it stays past the
day it arrived, and ARCHIVE ALL leaves it alone. Hold it again to let it go. The star is
set on the server as well, so the same message reads as starred in Gmail and flagged in
Outlook.

**The finished-day screen is for an empty screen now.** It used to take over the moment the
fifth letter was read, which would hide the five gray letters this release is about. The
same two lines sit under the list instead, over the letters that prove them.

**The app can say things again.** Every sentence it tried to say went into a place nothing
displayed: "Sent.", "3 new.", "Nothing new.", and the line naming a mailbox it could not
reach. A failed check therefore looked exactly like a check that found nothing, which is
the confusion those messages were written to clear up. They appear above the bottom bar
now, for a few seconds.

## v2.13 — A proper sheet

**The letter is a slide-up sheet now.** White, from the bottom edge, rounded at the top
corners, decelerating into place rather than moving at a constant speed — which is what
made the last one feel mechanical. Putting it away runs the same motion backwards, so the
swipe down is the gesture that opened it, undone.

**A gutter down both sides**, so text no longer runs into the edge of the sheet. Horizontal
only: a message that ends in a full-width image or a coloured footer band still touches
both sides, because that is how it was built.

**Horizontal overflow is properly fixed this time, and the earlier attempts were treating
the symptom.** The page was being laid out at `width=device-width` — about 390 pixels —
while a bulk-mail message is built on a 600-pixel grid. It overflowed because it genuinely
did not fit, and no amount of overflow juggling makes a 600-pixel table narrower; it only
decides who does the scrolling. First that was the whole document sliding sideways, then it
was content spilling out of its box.

Messages that declare their width — most bulk mail says `width="600"` on the outer table —
are now laid out at that width and scaled down to the screen, so the whole thing fits and
there is nothing to scroll sideways at all. This is why mail looks zoomed out on a phone
rather than clipped, in every other client. Personal mail declares no width and stays full
size.
## v2.12 — The letter comes up past the list

The list now fades to black *while* the letter rides up from below it, instead of being
replaced first and the letter animating into an empty screen. Both are on screen at once,
which is the only way the two halves can happen together.

The letter itself never fades. It slides at full opacity and is already rendered before it
moves — a slide that also changes opacity reads as two animations disagreeing about what is
happening.

Everything else in the app still cuts straight to the next screen. A transition is for the
one move that changes what kind of thing you are looking at; putting one on every screen
change is how a phone starts to feel slow.

**Horizontal scrolling past the end of a message is fixed.** `width=device-width` pins the
page to the screen, so a message built around a 600-pixel table overflowed it — and that
overflow became horizontal scroll on the whole document, which is why you could drag the
sender's name sideways and end up in empty space beside the message.

The document no longer scrolls sideways at all. Wide content scrolls inside its own box
instead, so nothing is clipped and nothing is unreachable — a wide table still moves, it
just moves within itself while the page around it stays put. Images are clamped to the
width of the screen, which is the single most common cause.
## v2.11 — Opening a message, and a signature

**Pull a message down to put it away.** A downward drag that starts while the message is
already at its top takes you back home; the same drag anywhere else scrolls, because the
message asked for it first.

**Messages slide up from the bottom.** They used to swap in instantly, and because the
view was reused between messages you could catch a frame of the *previous* email before
the new one painted. The whole reader is thrown away between messages now, so there are no
stale pixels to see, and the slide covers the fetch over black rather than over the list.

**Fixed: a grey bar above some messages instead of black.** CSS hands a background up to
the page from `body` — and if body has none, from `html`. Plenty of marketing mail sets
`html { background: #f4f4f4 }`, and only `body` was being held transparent, so that grey
painted the whole canvas including the strip above the message.

**A signature**, in Settings. It is added to the end of everything you send, after the
"--" line that every mail client uses to fold a signature away when quoting a reply —
which is why it does not come back at you in every response. Added when the message is
sent rather than dropped into the draft, so there is nothing to type around and nothing to
delete by accident.

**LETTERS is gone from the top of the home screen.** The first screen of a mail app is its
letters; saying so was the same redundancy MAILBOX was, one row further down.
## v2.10 — Sync goes both ways

**Archive something in Gmail and it now disappears here too.** Until this release sync
only ever added: it read the inbox as a feed of new things and never asked the server what
had become of the messages it already held. So a newsletter you cleared on a laptop sat in
Mailbox forever.

Every sync now asks after the messages it holds. One that has left the inbox — archived,
filed or deleted somewhere else — is archived here. One you read elsewhere stops being
unread here. The cost is bounded by how many messages the app holds, not by how big the
mailbox is, and it asks for flags only: no headers, no bodies.

It is one-way in the sense that matters for safety: the app never un-archives and never
deletes. Anything it gets wrong leaves the message in your mailbox.

**ARCHIVE ALL on the Notices screen.** One tap clears the pile. It is a move to All Mail,
not a delete — every notice it clears is still in the mailbox and still findable from any
other client, which is the only reason a bulk action on somebody's mail belongs on a bar.
"MARK ALL READ" is now "READ" to make room; three items is the limit for a bar with any
text in it.
## v2.9 — Newest first

Letters are in time order now, newest at the top. Notices always were.

They were ranked by the learned model — a coarse score bucket, with time only breaking
ties inside it — so a letter from this morning could sit below one from Tuesday because
the model liked it more, and nothing on screen said why. A reader cannot see a score, so
a score should not be an order they are asked to make sense of.

The ranking is not gone. On a five-a-day ration it still chooses *which* five letters are
today's, because picking the five worth reading is the point of the ration. Those five are
then shown newest first. On Unlimited the ranking does nothing at all and never did, which
is where the shuffled look was worst.
## v2.8 — Attachments, and a setup screen you can reach the bottom of

**Fixed: ADD OUTLOOK could not be reached.** The title and the paragraph above it filled a
472 dp screen, the second service row sat below the fold, and the screen did not scroll —
so one of the two ways into the app was simply unavailable. It scrolls now, and the copy
is shorter. A setup screen is the one screen that has to survive any screen height and any
font scale, because the person reading it has no account yet and therefore no way past it.

**Attachments open.** They are listed under the subject, at the top of the message, with
their size, and tapping one fetches it and hands it to whatever app opens that kind of
file. Before this the app parsed the filenames and then said "attachments held", which was
true and useless.

The bytes are only fetched when you tap — a message with a 12 MB deck on it is not
downloaded in full to show you three lines of text. Files are cached where the app's own
cache lives, so they go away with the app rather than settling into the phone's storage as
loose copies of your mail.

**No scrollbar over the black.** It was drawn across the whole view, including the strip
above the message. Nothing else in the app has one.

**New reply and archive icons.** The old reply was the SDK's u-turn arrow, which is a road
sign — it lives among *slight left* and *roundabout*. The old archive was a downward arrow
into a tray, which reads as download. Now: the arrow that turns back, and a box with a lid.
Still not the trash can, which the SDK does have and which would be a lie — archive moves
a message to All Mail and delete does not.
## v2.7 — The notices were always there

**Fixed: notices you could not get to.** Report #351 said "not fetching notices", and the
fetching was fine — the report proved it, with "last sync 0 min ago" and no error beside
it. The mail was arriving, being sorted, and being stored. It just had nowhere to appear.

Home drew "Nothing yet." whenever there were no *letters* waiting, and that screen's only
button is WRITE. The NOTICES button is the single route to the notices list anywhere in
the app, and it lives on the screen you were no longer being shown. So: read your letters,
and every receipt and newsletter that arrived afterwards became invisible.

Three fixes. "Nothing yet" now means nothing at all, letters and notices both. With no
letters, the notices are listed on Home instead of hidden behind the ration rule. And the
day-done screen has a route out too, which it also lacked.

**The count on that button was wrong as well** — it counted unread notices, so a notice
that arrived already read somewhere else showed "NOTICES 0" over a list with a dozen
things in it. It counts what the list holds now.

**Opening a message no longer flashes.** The last release traded a white flash for a black
one; the page is now held back until it has actually painted and then fades in.

**The reader has no top bar.** The message is the screen. Back moved down beside the other
verbs, and reply and archive are icons — which is what buys the room for a fourth item on
a 27-unit row.

**The message starts just below the top edge**, so a strip of black shows above it, and
that strip scrolls away with the content rather than sitting there as a bar.
## v2.6 — Say when the mail did not arrive

**A failed check used to look exactly like an empty inbox.** Every per-account error was
swallowed, and the clock was stamped anyway — so Settings said "last checked a minute
ago", the refresh button looked like it worked, and no mail appeared. That is the first
field report this app got, and the bug was not the fetching. It was that nobody could see
the fetching fail.

Now: the reason is kept, shown in Settings under SYNC, and carried in a shake report.
Refresh says what it did — "3 new", "Nothing new", or what went wrong. And the clock is
only stamped when a mailbox was actually reached.

This does not by itself fix a mailbox that will not sync. It makes the next report say
why, which is the thing that was missing.

**No more white flash when a message opens.** The view painted a white rectangle a frame
or two before the page had anything in it. It is transparent now, and the message brings
its own white when it is ready.

**The sender and subject scroll with the message.** They were pinned above it and the mail
slid underneath — they are drawn into the page itself now, the way every other mail
client does it, with inline styles so a sender's own CSS cannot restyle them.

**"today" is no longer cut off** in the home header. The count sat in a fixed-width box
that was never wide enough for the word; the LETTERS label gives way instead.

**A new icon** — an envelope with the mailbox flag up beside it, drawn inside the
adaptive-icon safe circle so a round launcher mask cannot crop the flag.
## v2.5 — Mail looks like mail

Messages render as their sender built them. Until now the app flattened every message to
plain text and the formatted version was buried two taps down, behind ··· → SHOW
ORIGINAL, on a screen of its own. That is backwards: the HTML is the message.

**Faithful, on white.** No stylesheet is injected and no color is forced. Restyling an
email into this app's black and white was the obvious idea and it is a trap — a sender
who sets a text color and no background comes out invisible, and every logo with a white
matte glares anyway. So the message keeps its own page and sits on the app's black ground
like a sheet of paper, with the sender, time and subject above it in the app's own type.

**Images load now.** A remote image is a tracking pixel and blocking it is the careful
default, but it meant half the mail arrived as a column of grey boxes, which reads as
broken rather than careful. Settings → READING turns them off again, and says plainly what
the trade is.

**The plain-text reading is still there**, one tap away under ···, and it is still what a
message with no HTML gets. Letters from people are nearly always plain, so the
page-of-a-book setting is unchanged for exactly the mail it was designed for.

JavaScript stays off, and the content is loaded with no base URL, so a message has no
origin to resolve a relative reference against. Links open in the browser rather than
navigating inside the message.

**The home screen header is one line.** MAILBOX is gone — the app is open and its name is
on the launcher — so LETTERS, the count and the icons share one row instead of two. That
is three grid units of a 31-unit screen handed back to the mail.

**A refresh button**, left of the settings icon. Mail already arrives on its own every
fifteen minutes and on opening the app; this is for when you are waiting on something.
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
