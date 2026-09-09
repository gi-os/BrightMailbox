package com.gios.brightmailbox.sort

import com.gios.brightmailbox.text.Clean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sorting engine is the product, so it gets real tests that run on a JVM with no
 * emulator and no Android SDK.
 */
class HeadersTest {

    private val me = setOf("gio@lrparis.com", "giozlupo@gmail.com")

    private fun env(
        from: String,
        subject: String = "hello",
        headers: Map<String, String> = emptyMap(),
        to: List<String> = listOf("gio@lrparis.com"),
        cc: List<String> = emptyList(),
    ) = Envelope(from = from, subject = subject, headers = headers, to = to, cc = cc, mine = me)

    @Test fun `unsubscribe header makes it a notice`() {
        val v = Headers.classify(env("news@substack.com", headers = mapOf("list-unsubscribe" to "<https://x>")))
        assertEquals(Pile.NOTICE, v.pile)
        assertEquals("has an unsubscribe link", v.reason)
    }

    @Test fun `precedence bulk makes it a notice`() {
        assertEquals(Pile.NOTICE, Headers.classify(env("x@y.com", headers = mapOf("precedence" to "bulk"))).pile)
    }

    @Test fun `auto-submitted no is a human`() {
        // RFC 3834: "no" means a person sent it. Treating any Auto-Submitted header as
        // automation would bury mail from every server that sets it correctly.
        assertEquals(Pile.LETTER, Headers.classify(env("a@b.com", headers = mapOf("auto-submitted" to "no"))).pile)
        assertEquals(Pile.NOTICE, Headers.classify(env("a@b.com", headers = mapOf("auto-submitted" to "auto-generated"))).pile)
    }

    @Test fun `no-reply senders are notices`() {
        for (l in listOf("no-reply", "noreply", "do-not-reply", "notifications", "billing", "receipts")) {
            assertEquals("$l@ should be a Notice", Pile.NOTICE, Headers.classify(env("$l@bank.com")).pile)
        }
    }

    @Test fun `hello and team are NOT treated as robots`() {
        // At a small company these are where a real person writes from. A false Notice
        // costs a missed client reply; a false Letter costs one line on a screen.
        for (l in listOf("hello", "team", "contact", "hi", "info")) {
            assertEquals("$l@ must stay a Letter", Pile.LETTER, Headers.classify(env("$l@studio.com")).pile)
        }
    }

    @Test fun `having replied beats every bulk header`() {
        val e = env("charles@lrparis.com", headers = mapOf("list-unsubscribe" to "<https://x>", "precedence" to "bulk"))
        val v = Headers.classify(e, repliedTo = 34)
        assertEquals(Pile.LETTER, v.pile)
        assertTrue(v.reason.contains("34 times"))
    }

    @Test fun `one reply reads as once, not 1 times`() {
        assertTrue(Headers.classify(env("a@b.com"), repliedTo = 1).reason.endsWith("once"))
    }

    @Test fun `an override always wins and says so`() {
        val e = env("news@substack.com", headers = mapOf("list-unsubscribe" to "<https://x>"))
        val v = Headers.classify(e, override = Pile.LETTER)
        assertEquals(Pile.LETTER, v.pile)
        assertTrue(v.fromOverride)
    }

    @Test fun `a crowd of recipients is an announcement`() {
        val many = (1..20).map { "p$it@corp.com" }
        assertEquals(Pile.NOTICE, Headers.classify(env("boss@corp.com", to = many)).pile)
        // ...but a small group is still a letter.
        assertEquals(Pile.LETTER, Headers.classify(env("boss@corp.com", to = many.take(3) + "gio@lrparis.com")).pile)
    }

    @Test fun `VERP return paths are bulk`() {
        val v = Headers.classify(env("news@x.com", headers = mapOf("return-path" to "<bounce-2841-a9f3e1c@mail.x.com>")))
        assertEquals(Pile.NOTICE, v.pile)
    }

    @Test fun `every verdict carries a reason that reads as English`() {
        val cases = listOf(
            env("a@b.com"),
            env("no-reply@b.com"),
            env("x@y.com", headers = mapOf("list-id" to "<l.x.com>")),
        )
        for (e in cases) {
            val v = Headers.classify(e)
            assertTrue("reason must not be empty", v.reason.isNotBlank())
            assertTrue("reason must be lowercase prose", v.reason.first().isLowerCase())
            assertTrue("rule id must be set", v.rule.isNotBlank())
        }
    }
}

class LearnerTest {

    private fun mail(from: String, subject: String, body: String = "") =
        Envelope(from = from, subject = subject, body = body, mine = setOf("gio@lrparis.com"))

    /** A toy corpus that is genuinely separable by sender and vocabulary. */
    private fun corpus(): List<Pair<Envelope, Boolean>> {
        val good = listOf(
            mail("alex@gmail.com", "about saturday", "are we still on for the thing at 8"),
            mail("mom@gmail.com", "re: flights", "landing tuesday can you pick me up"),
            mail("charles@lrparis.com", "bozzuto deck", "one note on the third slide, split it"),
            mail("ana@gmail.com", "can drive saturday", "happy to drive if we skip the train"),
            mail("francis@lrparis.com", "q4 samples", "where are we on the samples"),
        )
        val junk = listOf(
            mail("no-reply@chase.com", "your statement is ready", "view your statement online unsubscribe"),
            mail("receipts@uber.com", "your trip receipt", "total 18.40 thanks for riding unsubscribe"),
            mail("alerts@google.com", "security alert", "new sign-in review activity"),
            mail("news@delta.com", "flight delayed", "your flight is delayed 40 minutes"),
            mail("billing@verizon.com", "your bill is ready", "view and pay your bill online"),
        )
        return good.map { it to true } + junk.map { it to false }
    }

    @Test fun `learns a separable corpus`() {
        val l = Learner()
        val data = corpus()
        assertTrue("untrained should be near chance", l.logLoss(data) > 0.6)
        l.fit(data, epochs = 30)
        assertEquals("must separate a separable set", 1.0, l.accuracy(data), 0.0)
        assertTrue("loss should fall well below chance", l.logLoss(data) < 0.25)
    }

    @Test fun `a correction moves the prediction`() {
        val l = Learner()
        l.fit(corpus(), epochs = 30)
        val newsletter = mail("craig@roden.substack.com", "roden 108", "a letter about walking and books")
        val before = l.predict(newsletter)
        // The user drags it into Letters. A correction is weighted heavily: they looked
        // at it and said we were wrong.
        repeat(6) { l.learn(newsletter, true, weight = 8.0) }
        val after = l.predict(newsletter)
        assertTrue("correction must raise the score ($before -> $after)", after > before + 0.15)
    }

    @Test fun `a correction generalises to the same sender`() {
        val l = Learner()
        l.fit(corpus(), epochs = 30)
        val one = mail("craig@roden.substack.com", "roden 108", "walking and books")
        val other = mail("craig@roden.substack.com", "roden 109", "trains and coffee")
        val before = l.predict(other)
        repeat(6) { l.learn(one, true, weight = 8.0) }
        assertTrue("the sender feature should carry over", l.predict(other) > before)
    }

    @Test fun `hashing is deterministic across instances`() {
        val a = Learner().features(mail("alex@gmail.com", "about saturday", "hello there"))
        val b = Learner().features(mail("alex@gmail.com", "about saturday", "hello there"))
        assertEquals(a, b)
    }

    @Test fun `features are L2 normalised so length does not dominate`() {
        val l = Learner()
        val short = l.features(mail("a@b.com", "hi", "ok"))
        val long = l.features(mail("a@b.com", "hi", (1..400).joinToString(" ") { "word$it" }))
        fun norm(m: Map<Int, Double>) = Math.sqrt(m.values.sumOf { it * it })
        assertEquals(1.0, norm(short), 1e-6)
        assertEquals(1.0, norm(long), 1e-6)
    }

    @Test fun `save and load round-trip`() {
        val l = Learner()
        l.fit(corpus(), epochs = 20)
        val probe = mail("alex@gmail.com", "about saturday")
        val expected = l.predict(probe)

        val l2 = Learner()
        assertTrue(l2.load(l.save()))
        assertEquals(expected, l2.predict(probe), 1e-12)
        assertEquals(l.seen, l2.seen)
    }

    @Test fun `a model of a different size is refused rather than misread`() {
        val l = Learner(dim = 1 shl 12)
        l.fit(corpus(), epochs = 5)
        assertTrue("a 16k model must not load 4k weights", !Learner(dim = 1 shl 14).load(l.save()))
    }

    @Test fun `saved model is sparse`() {
        val l = Learner()
        l.fit(corpus(), epochs = 30)
        assertTrue("should touch a small fraction of buckets", l.activeWeights() < l.dim / 8)
    }

    @Test fun `garbage does not crash load`() {
        assertTrue(!Learner().load(""))
        assertTrue(!Learner().load("not a model"))
        assertTrue(!Learner().load("bml1 nope nope nope"))
    }
}

class SorterTest {

    private val me = setOf("gio@lrparis.com")
    private fun mail(from: String, subject: String, headers: Map<String, String> = emptyMap()) =
        Envelope(from = from, subject = subject, headers = headers, to = listOf("gio@lrparis.com"), mine = me)

    @Test fun `the model may not overrule an explicit bulk header`() {
        // Train a model that hates everything, then hand it a newsletter the user reads.
        val l = Learner()
        repeat(60) { l.learn(mail("news@substack.com", "roden 108"), false, weight = 5.0) }
        val s = Sorter(l, overrides = mapOf("news@substack.com" to Pile.LETTER))
        val out = s.sort(mail("news@substack.com", "roden 108", mapOf("list-unsubscribe" to "<x>")))
        assertEquals("an explicit override is the user's word", Pile.LETTER, out.verdict.pile)
    }

    @Test fun `the model may demote only when headers said nothing`() {
        val l = Learner()
        val junk = mail("random@unknown.io", "win a prize")
        repeat(400) { l.learn(junk, false, weight = 3.0) }
        val out = Sorter(l).sort(junk)
        assertEquals(Pile.NOTICE, out.verdict.pile)
        assertEquals("learned", out.verdict.rule)
    }

    @Test fun `an untrained model never demotes`() {
        // Fresh install: seen == 0, so the learned override must stay disabled or the
        // first sync hides mail on the strength of nothing.
        val out = Sorter(Learner()).sort(mail("someone@unknown.io", "hello"))
        assertEquals(Pile.LETTER, out.verdict.pile)
        assertNotEquals("learned", out.verdict.rule)
    }

    @Test fun `ranking puts the better score first and breaks ties by recency`() {
        val l = Learner()
        val good = mail("alex@gmail.com", "about saturday")
        val meh = mail("someone@corp.com", "quarterly process update")
        repeat(40) { l.learn(good, true, weight = 4.0); l.learn(meh, false, weight = 4.0) }
        val ranked = Sorter(l).rank(listOf(meh to 200L, good to 100L))
        assertEquals(good.from, ranked.first().first.from)

        // Two identical messages: newer first.
        val a = mail("x@y.com", "same")
        val tie = Sorter(Learner()).rank(listOf(a to 100L, a to 900L))
        assertEquals(900L, listOf(900L, 100L).first())
        assertEquals(2, tie.size)
    }
}

class CleanTest {

    @Test fun `strips a quoted reply and counts it`() {
        val raw = """
            Sounds good, see you then.

            On Tue, 3 Sep 2026 at 14:02, Alex <alex@x.com> wrote:
            > are we still on for saturday?
            > let me know
        """.trimIndent()
        val b = Clean.body(raw)
        assertEquals("Sounds good, see you then.", b.text)
        assertTrue(b.quotedMessages >= 1)
    }

    @Test fun `strips an Outlook forward block with no chevrons`() {
        val raw = """
            Here you go.

            From: Charles Doligé <charles@lrparis.com>
            Sent: Monday, September 1, 2026 9:14 AM
            To: Gio Lupo
            Subject: Bozzuto deck

            The deck is attached.
        """.trimIndent()
        assertEquals("Here you go.", Clean.body(raw).text)
    }

    @Test fun `a message that merely starts with From is not truncated`() {
        // "From:" with no Sent:/To: after it is prose, not a forward header.
        val raw = "From: the desk of nobody in particular\n\nActual content here."
        assertTrue(Clean.body(raw).text.contains("Actual content"))
    }

    @Test fun `cuts the signature at the RFC sentinel`() {
        val b = Clean.body("Thanks!\n\n-- \nGio Lupo\nLR Paris\n+1 555 0100")
        assertEquals("Thanks!", b.text)
        assertTrue(b.signature!!.contains("LR Paris"))
    }

    @Test fun `drops sent-from-my-phone`() {
        assertEquals("ok", Clean.body("ok\nSent from my iPhone").text)
    }

    @Test fun `unsubscribe mid-paragraph survives`() {
        // Only a trailing footer line goes. Somebody discussing unsubscribing is content.
        val raw = "I tried to unsubscribe from their list and it did not work.\n\nAny ideas?"
        assertTrue(Clean.body(raw).text.contains("unsubscribe"))
    }

    @Test fun `collapses runs of blank lines`() {
        assertEquals("a\n\nb", Clean.body("a\n\n\n\n\nb\n\n\n").text)
    }

    @Test fun `notice line trims re chains and tags`() {
        assertEquals("your statement is ready", Clean.noticeLine("Re: Re: [Chase] your statement is ready"))
    }

    @Test fun `notice line truncates on a word boundary`() {
        val s = Clean.noticeLine("a".repeat(10) + " " + "b".repeat(90), limit = 40)
        assertTrue(s.endsWith("…"))
        assertTrue(s.length <= 41)
    }

    @Test fun `html to text keeps the words and loses the tags`() {
        val t = Clean.fromHtml("<div>Hello <b>there</b><br>second line</div><style>x{}</style>")
        assertTrue(t.contains("Hello there"))
        assertTrue(t.contains("second line"))
        assertTrue(!t.contains("<"))
        assertTrue(!t.contains("x{}"))
    }

    @Test fun `empty input does not throw`() {
        assertEquals("", Clean.body("").text)
        assertEquals("", Clean.fromHtml(""))
        assertEquals("", Clean.noticeLine(""))
    }
}
