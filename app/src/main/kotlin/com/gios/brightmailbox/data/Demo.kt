package com.gios.brightmailbox.data

import com.gios.brightmailbox.auth.Account
import com.gios.brightmailbox.auth.Service
import com.gios.brightmailbox.mail.Attachment
import com.gios.brightmailbox.mail.Quota

/**
 * A mailbox that does not exist, for showing the app to somebody.
 *
 * Every screenshot of a mail client is a picture of somebody's correspondence. Taking one
 * meant either publishing real mail or emptying a real account first, and both are bad
 * enough that the app has been shown off almost entirely in words. This is the third
 * option: a fictional mailbox, seeded into a database of its own, that reads and behaves
 * exactly like a real one.
 *
 * **The demo is not a mock layer.** Nothing about the UI knows it exists. These rows go
 * into the same table, their bodies into the same files, and every screen, gesture, sort
 * and count runs against them unchanged — which is the only version of this worth
 * building, because a demo drawn by special-case code stops matching the app the week
 * after it is written.
 *
 * What makes it safe is [Repo.demoOn], and it is one switch in one place: `serviceFor`
 * returns null, so there is no transport, so nothing in this app can reach a server while
 * the demo is on. Every verb already tolerates that — archiving, starring and marking read
 * all write locally and treat the server as best-effort — so they work here for free.
 *
 * Nobody in this mailbox is real. The names, the senders and the domains are invented, and
 * `example.com` is reserved by RFC 2606 for exactly this.
 */
object Demo {

    /** The account id every demo row hangs off. Real ids all carry a service prefix. */
    const val ACCOUNT_ID = "demo"

    const val ADDRESS = "you@example.com"

    /**
     * The one mailbox that appears to be signed in while the demo is on.
     *
     * Presented instead of the real accounts rather than beside them, so a settings
     * screenshot cannot leak the address of whoever is holding the phone.
     */
    val account = Account(
        id = ACCOUNT_ID,
        service = Service.IMAP,
        email = ADDRESS,
        name = "",
        provider = "demo",
    )

    /** A believable mailbox, so the storage bar has something to draw. */
    val quota = Quota(usedBytes = 2_254_857_830L, limitBytes = 16_106_127_360L)

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /**
     * One demo message: the row, plus everything the repository keeps beside it.
     *
     * [html] blank means the message genuinely has none, and it is written as an empty
     * file on purpose — an empty html file is the cached answer "asked already, there is
     * none", where a missing one means "nobody has looked". The reader picks its whole
     * layout off that distinction.
     */
    data class Mail(
        val id: String,
        val from: String,
        val fromName: String,
        val subject: String,
        val agoMinutes: Long,
        val pile: String,
        val reason: String,
        val rule: String,
        val text: String,
        val html: String = "",
        val score: Double = 0.5,
        val unread: Boolean = true,
        val starred: Boolean = false,
        val archived: Boolean = false,
        val readHere: Boolean = false,
        val attachments: List<Attachment> = emptyList(),
        val ics: String = "",
        val unsubscribe: String = "",
        val oneClick: Boolean = false,
        /** The Message-ID of the conversation this belongs to, blank for its own. */
        val threadRoot: String = "",
        val toAddrs: String = ADDRESS,
        val ccAddrs: String = "",
    ) {
        val messageId: String get() = "<$id@demo.invalid>"
        val threadId: String get() = threadRoot.ifBlank { messageId }
    }

    /**
     * The row as the database holds it.
     *
     * Stamps are relative to the moment the demo is seeded, so the front screen always
     * reads like this morning rather than like whenever this file was written.
     */
    fun row(m: Mail, now: Long): Msg = Msg(
        key = "$ACCOUNT_ID/${m.id}",
        accountId = ACCOUNT_ID,
        providerId = m.id,
        threadId = m.threadId,
        sender = m.from,
        senderName = m.fromName,
        subject = m.subject,
        // Written here rather than left to the prefetch: the prefetch runs off a sync,
        // and a sync is the one thing the demo does not have. Without it the preview
        // setting would look broken in the only build anybody uses to look at it.
        snippet = preview(m.text),
        receivedAt = now - m.agoMinutes * MINUTE,
        unread = m.unread,
        pile = m.pile,
        reason = m.reason,
        rule = m.rule,
        score = m.score,
        messageId = m.messageId,
        references = m.threadRoot.ifBlank { null },
        hasAttachments = m.attachments.isNotEmpty(),
        readHere = m.readHere,
        rationDay = 0,
        readDay = 0,
        starred = m.starred,
        unsubscribe = m.unsubscribe,
        oneClick = m.oneClick,
        toAddrs = m.toAddrs,
        ccAddrs = m.ccAddrs,
        archived = m.archived,
    )

    private fun preview(text: String): String =
        text.replace(Regex("\\s+"), " ").trim().take(90)

    /* ------------------------------------------------------------------- letters */

    private val SATURDAY_ROOT = "<mara-sat-1@demo.invalid>"

    private val letters = listOf(
        Mail(
            id = "mara-sat-2",
            from = "mara@ellisworks.com",
            fromName = "Mara Ellis",
            subject = "Re: Saturday",
            agoMinutes = 38,
            pile = "LETTER",
            reason = "you've replied to this address 4 times",
            rule = "replied-before",
            score = 0.91,
            threadRoot = SATURDAY_ROOT,
            text = """
                One o'clock works. I'll bring the folding table and the good chairs, you
                bring everything that needs a fridge.

                Ben is coming after his shift so we'll be eight, not six. Is that going to
                break the plan?

                M
            """.trimIndent(),
        ),
        Mail(
            id = "dad-boat",
            from = "henry@fieldhouse.net",
            fromName = "Dad",
            subject = "the boat",
            agoMinutes = 130,
            pile = "LETTER",
            reason = "you've replied to this address 12 times",
            rule = "replied-before",
            score = 0.88,
            text = """
                Took her out Thursday. The new starter held, so that's forty dollars well
                spent and an afternoon I'd like back.

                Your mother says call her, not me.
            """.trimIndent(),
        ),
        Mail(
            id = "vane-signed",
            from = "toby@vanepartners.com",
            fromName = "Toby Vane",
            subject = "Signed and scanned",
            agoMinutes = 245,
            pile = "LETTER",
            reason = "was addressed to you directly",
            rule = "addressed-to-me",
            score = 0.76,
            starred = true,
            text = """
                Attached, signed, both copies. Nothing changed from the draft you sent on
                Tuesday except the start date, which is now the 2nd.

                Invoice whenever suits you.

                Toby
            """.trimIndent(),
            attachments = listOf(
                Attachment("services-agreement.pdf", "application/pdf", 700L, "2"),
            ),
        ),
        Mail(
            id = "priya-notes",
            from = "priya@northlight.studio",
            fromName = "Priya Raman",
            subject = "notes from this morning",
            agoMinutes = 375,
            pile = "LETTER",
            reason = "you've replied to this address 6 times",
            rule = "replied-before",
            score = 0.82,
            text = """
                Three things we agreed, so there's a record of it:

                1. The onboarding screens get cut from six to three.
                2. Nobody touches the data model before the 20th.
                3. I own the copy, you own the order it appears in.

                Shout if I've remembered any of that wrong.

                Priya
            """.trimIndent(),
            html = """
                <p>Three things we agreed, so there's a record of it:</p>
                <ol>
                  <li>The onboarding screens get cut from six to three.</li>
                  <li>Nobody touches the data model before the 20th.</li>
                  <li>I own the copy, you own the order it appears in.</li>
                </ol>
                <p>Shout if I've remembered any of that wrong.</p>
                <p>Priya</p>
            """.trimIndent(),
        ),
        Mail(
            id = "june-jacket",
            from = "june@harts.cc",
            fromName = "June Hart",
            subject = "you left your jacket",
            agoMinutes = 21 * 60,
            pile = "LETTER",
            reason = "was addressed to you directly",
            rule = "addressed-to-me",
            score = 0.64,
            text = """
                It's on the hook by the door and it will stay there until one of us moves.
                No rush.
            """.trimIndent(),
        ),
        Mail(
            id = "sam-weekend",
            from = "sam@okada.photo",
            fromName = "Sam Okada",
            subject = "the weekend, mostly out of focus",
            agoMinutes = 26 * 60,
            pile = "LETTER",
            reason = "carries no sign of being automated",
            rule = "no-bulk-markers",
            score = 0.58,
            text = """
                Forty frames, four keepers, which is about the usual rate. The one of you
                on the rocks is the only one worth printing and you are facing the wrong
                way in it.

                I'll bring them Saturday.
            """.trimIndent(),
        ),
        /*
         * The earlier half of the Saturday conversation.
         *
         * Read, and read on a day that is not today, so it is out of the Letters query and
         * reachable only from inside the thread — which is exactly where a week-old
         * message you have already answered should be.
         */
        Mail(
            id = "mara-sat-1",
            from = "mara@ellisworks.com",
            fromName = "Mara Ellis",
            subject = "Saturday",
            agoMinutes = 50 * 60,
            pile = "LETTER",
            reason = "you've replied to this address 4 times",
            rule = "replied-before",
            score = 0.9,
            unread = false,
            readHere = true,
            text = """
                Are we still doing this? Say a time and I'll work around it.
            """.trimIndent(),
        ),
    )

    /* ------------------------------------------------------------------- notices */

    private val notices = listOf(
        Mail(
            id = "kestrel-code",
            from = "no-reply@kestrel.app",
            fromName = "Kestrel",
            subject = "418 203 is your sign-in code",
            agoMinutes = 6,
            pile = "NOTICE",
            reason = "comes from an address that takes no replies",
            rule = "no-reply",
            score = 0.2,
            text = """
                Your sign-in code is 418 203.

                It expires in ten minutes. If you did not ask to sign in, you can ignore
                this message.
            """.trimIndent(),
        ),
        Mail(
            id = "northbound-receipt",
            from = "receipts@northbound.coffee",
            fromName = "Northbound Coffee",
            subject = "Receipt from Northbound",
            agoMinutes = 64,
            pile = "NOTICE",
            reason = "comes from receipts@, which nobody reads",
            rule = "robot-sender",
            score = 0.12,
            text = """
                Northbound Coffee

                Filter, large        4.20
                Almond croissant     3.75

                Total                7.95
                Card ending 4417

                Thanks for coming in.
            """.trimIndent(),
            html = """
                <table width="600" cellpadding="0" cellspacing="0" style="font-family:Georgia,serif">
                  <tr><td style="padding:24px 28px 8px 28px;font-size:20px">Northbound Coffee</td></tr>
                  <tr><td style="padding:0 28px 18px 28px;color:#777;font-size:13px">
                    Receipt &middot; today
                  </td></tr>
                  <tr><td style="padding:0 28px">
                    <table width="100%" cellpadding="6" cellspacing="0" style="font-size:15px">
                      <tr><td>Filter, large</td><td align="right">4.20</td></tr>
                      <tr><td>Almond croissant</td><td align="right">3.75</td></tr>
                      <tr><td style="border-top:1px solid #ddd"><b>Total</b></td>
                          <td align="right" style="border-top:1px solid #ddd"><b>7.95</b></td></tr>
                    </table>
                  </td></tr>
                  <tr><td style="padding:18px 28px 28px 28px;color:#777;font-size:12px">
                    Card ending 4417. Thanks for coming in.
                  </td></tr>
                </table>
            """.trimIndent(),
        ),
        /*
         * The one that earns the renderer.
         *
         * Built on a 600 px table like nearly all bulk mail, so opening it exercises the
         * declared-width path: the message is laid out at 600 and scaled to the panel
         * rather than clipped. A demo full of plain text would never show that working.
         */
        Mail(
            id = "ridgeline-112",
            from = "hello@ridgeline.press",
            fromName = "The Ridgeline",
            subject = "The Ridgeline — No. 112",
            agoMinutes = 190,
            pile = "NOTICE",
            reason = "has an unsubscribe link",
            rule = "list-unsubscribe",
            score = 0.31,
            unsubscribe = "<https://ridgeline.press/u/9f31b>, <mailto:u-9f31b@ridgeline.press>",
            oneClick = true,
            text = """
                No. 112 — The long way round

                Every map of the pass shows a road that has not existed since 1974. We
                walked it anyway, which took nine hours and cost one pair of boots.

                Also this week: the reservoir at its lowest since records began, a defense
                of the humble lay-by, and what happened to the last night bus.

                Read the rest on the site.
            """.trimIndent(),
            html = """
                <table width="600" cellpadding="0" cellspacing="0" style="font-family:Georgia,serif;background:#ffffff">
                  <tr><td style="padding:30px 32px 6px 32px;border-bottom:3px solid #111">
                    <span style="font-size:26px;letter-spacing:1px">THE RIDGELINE</span>
                  </td></tr>
                  <tr><td style="padding:10px 32px 0 32px;color:#888;font-size:12px">
                    No. 112 &middot; Saturday
                  </td></tr>
                  <tr><td style="padding:22px 32px 0 32px">
                    <div style="font-size:21px;line-height:1.3">The long way round</div>
                    <p style="font-size:15px;line-height:1.6;color:#222">
                      Every map of the pass shows a road that has not existed since 1974.
                      We walked it anyway, which took nine hours and cost one pair of
                      boots.
                    </p>
                    <p style="font-size:15px;line-height:1.6;color:#222">
                      Also this week: the reservoir at its lowest since records began, a
                      defense of the humble lay-by, and what happened to the last night
                      bus.
                    </p>
                  </td></tr>
                  <tr><td style="padding:14px 32px 30px 32px">
                    <table cellpadding="0" cellspacing="0"><tr>
                      <td style="background:#111;padding:11px 20px">
                        <a href="https://ridgeline.press/112"
                           style="color:#fff;text-decoration:none;font-size:14px">READ No. 112</a>
                      </td>
                    </tr></table>
                  </td></tr>
                  <tr><td style="padding:16px 32px 28px 32px;background:#f4f4f4;color:#888;font-size:11px">
                    You are getting this because you asked for it, once.
                  </td></tr>
                </table>
            """.trimIndent(),
        ),
        /*
         * An invitation, in Notices, which is the rule that surprises people most.
         *
         * Nina is a correspondent and a letter from her would go straight to Letters — but
         * a meeting request is a calendar object, generated by a calendar, and nothing on
         * this phone can do anything with it except answer it. So it is not allowed to
         * spend one of the day's five. The reader offers the answer instead.
         */
        Mail(
            id = "nina-review",
            from = "nina@northlight.studio",
            fromName = "Nina Okafor",
            subject = "Design review — Thursday 11:00",
            agoMinutes = 300,
            pile = "NOTICE",
            reason = "is a calendar invitation",
            rule = "calendar",
            score = 0.4,
            ccAddrs = "priya@northlight.studio",
            text = """
                Design review

                Thursday, 11:00 – 11:45
                Studio, and the usual link for anybody who is not in.

                Bring the three screens, not the six.
            """.trimIndent(),
            ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Northlight//Calendar//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:d3f7a1c0-4e2b-4a19-9f6c-0b8e51a27d44-northlight-design-revi
                 ew-thursday
                SEQUENCE:0
                DTSTAMP:20260914T090000Z
                DTSTART:20260917T110000Z
                DTEND:20260917T114500Z
                SUMMARY:Design review
                LOCATION:Studio
                ORGANIZER;CN=Nina Okafor:mailto:nina@northlight.studio
                ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:you@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent(),
        ),
        Mail(
            id = "parcel-delivered",
            from = "tracking@parcelworks.com",
            fromName = "Parcelworks",
            subject = "Delivered: one box",
            agoMinutes = 430,
            pile = "NOTICE",
            reason = "was generated automatically",
            rule = "auto-submitted",
            score = 0.1,
            text = """
                Your parcel was left in the porch at 14:12.

                Tracking PW-4419-8827.
            """.trimIndent(),
        ),
        Mail(
            id = "fielding-statement",
            from = "billing@fieldingwater.com",
            fromName = "Fielding Water",
            subject = "Your statement is ready",
            agoMinutes = 545,
            pile = "NOTICE",
            reason = "comes from billing@, which nobody reads",
            rule = "robot-sender",
            score = 0.09,
            text = """
                Your September statement is ready to view.

                Nothing is due until the 30th.
            """.trimIndent(),
        ),
        Mail(
            id = "halo-replies",
            from = "notifications@halo.social",
            fromName = "Halo",
            subject = "3 people replied to your post",
            agoMinutes = 660,
            pile = "NOTICE",
            reason = "comes from notifications@, which nobody reads",
            rule = "robot-sender",
            score = 0.06,
            unsubscribe = "<https://halo.social/settings/email>",
            text = """
                Three people replied to what you posted on Tuesday.

                Open Halo to read them.
            """.trimIndent(),
        ),
        Mail(
            id = "kestrel-password",
            from = "no-reply@kestrel.app",
            fromName = "Kestrel",
            subject = "Your password was changed",
            agoMinutes = 22 * 60,
            pile = "NOTICE",
            reason = "comes from an address that takes no replies",
            rule = "no-reply",
            score = 0.25,
            unread = false,
            text = """
                The password on your Kestrel account was changed today.

                If that was not you, this is the part where you should worry.
            """.trimIndent(),
        ),
        Mail(
            id = "seam-sale",
            from = "news@seam.store",
            fromName = "Seam",
            subject = "40% off, today only",
            agoMinutes = 25 * 60,
            pile = "NOTICE",
            reason = "has an unsubscribe link",
            rule = "list-unsubscribe",
            score = 0.04,
            unread = false,
            unsubscribe = "<https://seam.store/unsubscribe/7c1a>",
            oneClick = true,
            text = """
                40% off everything until midnight. After that, 30% off everything until
                Thursday, and 20% off everything for the rest of your life.
            """.trimIndent(),
            html = """
                <table width="600" cellpadding="0" cellspacing="0" style="font-family:Helvetica,Arial,sans-serif">
                  <tr><td align="center" style="background:#111;padding:44px 20px">
                    <div style="color:#fff;font-size:44px;letter-spacing:3px">40% OFF</div>
                    <div style="color:#bbb;font-size:13px;padding-top:10px">TODAY ONLY</div>
                  </td></tr>
                  <tr><td align="center" style="padding:26px 30px;font-size:15px;color:#333">
                    Everything, including the things nobody buys.
                  </td></tr>
                  <tr><td align="center" style="padding:0 30px 34px 30px">
                    <a href="https://seam.store/sale"
                       style="background:#111;color:#fff;text-decoration:none;padding:13px 30px;font-size:14px">
                      SHOP THE SALE</a>
                  </td></tr>
                </table>
            """.trimIndent(),
        ),
        Mail(
            id = "ridgeline-111",
            from = "hello@ridgeline.press",
            fromName = "The Ridgeline",
            subject = "The Ridgeline — No. 111",
            agoMinutes = 30 * 60,
            pile = "NOTICE",
            reason = "has an unsubscribe link",
            rule = "list-unsubscribe",
            score = 0.3,
            unread = false,
            unsubscribe = "<https://ridgeline.press/u/9f31b>",
            oneClick = true,
            text = """
                No. 111 — What the tide leaves

                A week on the estuary with a man who has counted the same birds since 1988,
                and what his notebooks say that the surveys do not.
            """.trimIndent(),
        ),
        Mail(
            id = "library-hold",
            from = "holds@citylibrary.org",
            fromName = "City Library",
            subject = "Your hold is ready",
            agoMinutes = 34 * 60,
            pile = "NOTICE",
            reason = "was generated automatically",
            rule = "auto-submitted",
            score = 0.18,
            unread = false,
            text = """
                The book you reserved is waiting at the desk.

                It will be put back on the shelf on Friday.
            """.trimIndent(),
        ),
        Mail(
            id = "aeris-checkin",
            from = "noreply@flyaeris.com",
            fromName = "Aeris",
            subject = "Check in for AE 214",
            agoMinutes = 46 * 60,
            pile = "NOTICE",
            reason = "comes from an address that takes no replies",
            rule = "no-reply",
            score = 0.22,
            unread = false,
            text = """
                Check-in for AE 214 is open.

                Departs Friday 07:55. One bag, no seat chosen.
            """.trimIndent(),
        ),
    )

    /* -------------------------------------------------------- archive, sent, draft */

    private val archived = listOf(
        Mail(
            id = "arch-quote",
            from = "hello@mereworks.com",
            fromName = "Mereworks",
            subject = "Your quote, as discussed",
            agoMinutes = 8 * 24 * 60,
            pile = "LETTER",
            reason = "was addressed to you directly",
            rule = "addressed-to-me",
            unread = false,
            readHere = true,
            archived = true,
            text = "The number we talked about, in writing, with the caveats underneath it.",
        ),
        Mail(
            id = "arch-invoice",
            from = "billing@fieldingwater.com",
            fromName = "Fielding Water",
            subject = "August statement",
            agoMinutes = 34 * 24 * 60,
            pile = "NOTICE",
            reason = "comes from billing@, which nobody reads",
            rule = "robot-sender",
            unread = false,
            archived = true,
            text = "Your August statement is ready to view.",
        ),
        Mail(
            id = "arch-ridgeline",
            from = "hello@ridgeline.press",
            fromName = "The Ridgeline",
            subject = "The Ridgeline — No. 110",
            agoMinutes = 37 * 24 * 60,
            pile = "NOTICE",
            reason = "has an unsubscribe link",
            rule = "list-unsubscribe",
            unread = false,
            archived = true,
            text = "No. 110 — Nine miles of fence and the man who mends it.",
        ),
        Mail(
            id = "arch-mara",
            from = "mara@ellisworks.com",
            fromName = "Mara Ellis",
            subject = "the thing in August",
            agoMinutes = 41 * 24 * 60,
            pile = "LETTER",
            reason = "you've replied to this address 4 times",
            rule = "replied-before",
            unread = false,
            readHere = true,
            archived = true,
            text = "It went fine. Nobody fell in.",
        ),
    )

    /*
     * Sent mail, and it is the one list that is not a query.
     *
     * On a real account these are read off the server's Sent folder each time the screen
     * opens and stored nowhere. Here they are rows like any other, carrying the pile
     * "SENT" — which matches no query in the app, so they appear on exactly one screen and
     * nowhere else. See `Repo.sent`.
     */
    private val sent = listOf(
        Mail(
            id = "sent-mara",
            from = "mara@ellisworks.com",
            fromName = "mara",
            subject = "Re: Saturday",
            agoMinutes = 44,
            pile = "SENT",
            reason = "",
            rule = "sent",
            unread = false,
            readHere = true,
            threadRoot = SATURDAY_ROOT,
            text = "One is fine. Eight is fine. Bring the chairs.",
        ),
        Mail(
            id = "sent-toby",
            from = "toby@vanepartners.com",
            fromName = "toby",
            subject = "Re: Signed and scanned",
            agoMinutes = 220,
            pile = "SENT",
            reason = "",
            rule = "sent",
            unread = false,
            readHere = true,
            text = "Got both, thank you. Invoice goes out Monday.",
        ),
    )

    /** Everything that goes in the messages table, in one list. */
    fun mail(): List<Mail> = letters + notices + archived + sent

    fun draft() = Draft(
        accountId = ACCOUNT_ID,
        to = "june@harts.cc",
        cc = "",
        subject = "Re: you left your jacket",
        body = "I'll come by for it Thursday if you're in. If you're not I'll",
        inReplyTo = null,
        references = null,
        threadId = null,
        updatedAt = System.currentTimeMillis() - 3 * HOUR,
    )

    /* ----------------------------------------------------------------------- pdf */

    /**
     * A real one-page PDF, built here rather than shipped as an asset.
     *
     * The attachment has to open, or the demo quietly stops being a demo of the thing it
     * is showing off. A minimal PDF is a few hundred bytes of text — a catalog, a page, a
     * font and a content stream — and the only fiddly part is the cross-reference table,
     * which holds the **byte offset** of every object and has to be exact. So the offsets
     * are measured while the file is assembled rather than written by hand.
     *
     * Latin-1, not UTF-8: the offsets are byte counts, and a multi-byte character anywhere
     * above the xref would shift every entry below it.
     */
    fun pdf(lines: List<String>): ByteArray {
        val content = buildString {
            append("BT\n/F1 13 Tf\n")
            lines.forEachIndexed { i, line ->
                append("1 0 0 1 64 ${770 - i * 22} Tm (${escapePdf(line)}) Tj\n")
            }
            append("ET\n")
        }
        val objects = listOf(
            "<</Type/Catalog/Pages 2 0 R>>",
            "<</Type/Pages/Kids[3 0 R]/Count 1>>",
            "<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]" +
                "/Resources<</Font<</F1 4 0 R>>>>/Contents 5 0 R>>",
            "<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>",
            "<</Length ${content.toByteArray(Charsets.ISO_8859_1).size}>>\nstream\n" +
                content + "endstream",
        )

        val out = StringBuilder("%PDF-1.4\n")
        val offsets = ArrayList<Int>(objects.size)
        objects.forEachIndexed { i, body ->
            offsets.add(out.toString().toByteArray(Charsets.ISO_8859_1).size)
            out.append("${i + 1} 0 obj\n").append(body).append("\nendobj\n")
        }
        val startXref = out.toString().toByteArray(Charsets.ISO_8859_1).size
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (off in offsets) out.append("%010d 00000 n \n".format(off))
        out.append("trailer\n<</Size ${objects.size + 1}/Root 1 0 R>>\n")
            .append("startxref\n$startXref\n%%EOF\n")
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** Parentheses and backslashes end a PDF string early unless they are escaped. */
    private fun escapePdf(s: String): String =
        s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

    /** The bytes behind the one attachment in this mailbox. */
    fun attachmentBytes(): ByteArray = pdf(
        listOf(
            "SERVICES AGREEMENT",
            "",
            "Between Vane Partners and the undersigned.",
            "",
            "Start date: the 2nd.",
            "Term: twelve months.",
            "Notice: thirty days, in writing, by either party.",
            "",
            "This document is a demonstration. It agrees to nothing.",
        ),
    )
}
