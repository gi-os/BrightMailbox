package com.gios.brightmailbox.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanner hands this whatever the camera thought it saw, so the interesting cases
 * are the malformed ones. A credential screen that crashes on a bad frame is worse than
 * one that cannot scan at all.
 */
class QrSignInTest {

    private fun ok(raw: String): QrSignIn.Result.Ok =
        QrSignIn.parse(raw) as? QrSignIn.Result.Ok
            ?: throw AssertionError("expected Ok for: $raw — got ${QrSignIn.parse(raw)}")

    private fun bad(raw: String): String =
        (QrSignIn.parse(raw) as? QrSignIn.Result.Bad)?.why
            ?: throw AssertionError("expected Bad for: $raw")

    @Test fun `the page's own payload parses`() {
        val r = ok("brightmailbox://signin?s=google&e=you%40gmail.com&p=abcdefghijklmnop")
        assertEquals(Service.GOOGLE, r.service)
        assertEquals("you@gmail.com", r.email)
        assertEquals("abcdefghijklmnop", r.password)
    }

    @Test fun `an address with a plus tag survives decoding`() {
        assertEquals(
            "alex+mail@gmail.com",
            ok("brightmailbox://signin?s=google&e=alex%2Bmail%40gmail.com&p=aaaabbbbccccdddd").email,
        )
    }

    @Test fun `addresses are lowercased but the password is not`() {
        val r = ok("brightmailbox://signin?s=google&e=Alex%40Gmail.com&p=AbCdEfGhIjKlMnOp")
        assertEquals("alex@gmail.com", r.email)
        assertEquals("AbCdEfGhIjKlMnOp", r.password)
    }

    @Test fun `spaces in the password are stripped, as Google prints them`() {
        assertEquals(
            "abcdefghijklmnop",
            ok("brightmailbox://signin?s=google&e=you%40gmail.com&p=abcd%20efgh%20ijkl%20mnop").password,
        )
    }

    @Test fun `parameter order does not matter`() {
        assertEquals(
            "you@gmail.com",
            ok("brightmailbox://signin?p=abcdefghijklmnop&e=you%40gmail.com&s=google").email,
        )
    }

    @Test fun `surrounding whitespace from the decoder is tolerated`() {
        ok("  brightmailbox://signin?s=google&e=you%40gmail.com&p=abcdefghijklmnop\n")
    }

    /* ------------------------------------------------------------------ refusals */

    @Test fun `another app's code is refused by name`() {
        assertTrue(bad("brightfantasy://setup?league=12345").contains("not a Mailbox"))
        assertTrue(bad("WIFI:S=cafe;T=WPA;P=hunter2;;").contains("not a Mailbox"))
        assertTrue(bad("https://example.com").contains("not a Mailbox"))
    }

    @Test fun `Outlook is told to sign in in the app`() {
        assertTrue(
            bad("brightmailbox://signin?s=microsoft&e=you%40outlook.com&p=x")
                .contains("in the app"),
        )
    }

    @Test fun `a missing or unknown field is named`() {
        assertTrue(bad("brightmailbox://signin?s=google&p=abcdefghijklmnop").contains("email"))
        assertTrue(bad("brightmailbox://signin?s=google&e=you%40gmail.com").contains("app password"))
        assertTrue(bad("brightmailbox://signin?s=yahoo&e=you%40y.com&p=x").contains("does not know"))
    }

    @Test fun `something that is not an address is not accepted as one`() {
        assertTrue(bad("brightmailbox://signin?s=google&e=notanaddress&p=abcd").contains("email"))
        assertTrue(bad("brightmailbox://signin?s=google&e=two%20words%40x.com&p=abcd").contains("email"))
    }

    @Test fun `a password of only whitespace counts as absent`() {
        assertTrue(bad("brightmailbox://signin?s=google&e=you%40gmail.com&p=%20%20").contains("app password"))
    }

    /**
     * A half-read frame is the realistic failure, and URLDecoder throws on a stray '%'.
     * These must come back as a sentence, never as an exception.
     */
    @Test fun `a mangled scan does not throw`() {
        for (raw in listOf(
            "brightmailbox://signin?s=google&e=you%4&p=abcd",
            "brightmailbox://signin?s=google&e=%%%&p=%",
            "brightmailbox://signin?",
            "brightmailbox://signin?&&&=&=x&",
            "brightmailbox://signin?s=google&e=you%40gmail.com&p=abcdefghijklmnop%",
            "",
            "brightmailbox://",
        )) {
            QrSignIn.parse(raw) // must not throw
        }
    }

    @Test fun `a trailing stray percent leaves the password usable`() {
        // URLDecoder would throw; the fallback keeps the raw text and the value stands.
        assertEquals(
            "abcdefghijklmnop%",
            ok("brightmailbox://signin?s=google&e=you%40gmail.com&p=abcdefghijklmnop%").password,
        )
    }
}
