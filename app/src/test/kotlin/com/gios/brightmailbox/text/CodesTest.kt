package com.gios.brightmailbox.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodesTest {

    @Test
    fun `six digits in a verification subject`() {
        assertEquals("482913", Codes.find("482913 is your verification code"))
        assertEquals("482913", Codes.find("Your verification code is 482913"))
        assertEquals("482913", Codes.find("Verification code: 482913."))
        assertEquals("482913", Codes.find("Your code: 482913, valid for 10 minutes"))
    }

    @Test
    fun `the code word can be in the snippet`() {
        assertEquals("739201", Codes.find("Apple ID", "Your Apple ID code is: 739201. Do not share it."))
        assertEquals("30217", Codes.find("Security alert", "Use 30217 as your one-time passcode."))
    }

    @Test
    fun `a Google style prefixed code`() {
        assertEquals("482913", Codes.find("G-482913 is your Google verification code"))
    }

    @Test
    fun `dashed pairs`() {
        assertEquals("AB12-CD34", Codes.find("Your login code is AB12-CD34"))
        assertEquals("123-456", Codes.find("123-456 is your Signal verification code"))
    }

    @Test
    fun `four digit pin`() {
        assertEquals("4821", Codes.find("Your PIN is 4821"))
    }

    @Test
    fun `no code words means no code`() {
        assertNull(Codes.find("Your order 482913 has shipped"))
        assertNull(Codes.find("Meeting at 1030 tomorrow"))
        assertNull(Codes.find("482913"))
    }

    @Test
    fun `a year is not a code`() {
        assertNull(Codes.find("Verify your account before 2026"))
        assertNull(Codes.find("Your 2025 security review is ready"))
        // ...unless the sentence says the number is the code.
        assertEquals("2024", Codes.find("Your code is 2024"))
        assertEquals("2024", Codes.find("2024 is your code"))
    }

    @Test
    fun `a price is not a code`() {
        assertNull(Codes.find("Confirm your payment of \$1,250 today"))
        assertNull(Codes.find("Confirm your payment of €4200"))
        assertNull(Codes.find("Login discount: 1500% more"))
    }

    @Test
    fun `a phone number is not a code`() {
        assertNull(Codes.find("Call us at 555 123 4567 to verify"))
        assertNull(Codes.find("Call (800) 555-1234 to confirm"))
        assertNull(Codes.find("Text +1 415 555 0142 with your security question"))
        assertNull(Codes.find("Verification support: 0800 123 4567"))
    }

    @Test
    fun `an order or reference number is not a code`() {
        assertNull(Codes.find("Order #48291377 confirmation"))
        assertNull(Codes.find("Confirmation for booking 482913"))
        assertNull(Codes.find("Your ticket ref: 4412 - please confirm"))
        assertNull(Codes.find("Login attempt from account 88213344"))
    }

    @Test
    fun `too long or too short is not a code`() {
        assertNull(Codes.find("Your verification code 123456789"))
        assertNull(Codes.find("Your verification code 123"))
    }

    @Test
    fun `a date is not a code`() {
        assertNull(Codes.find("Confirm your appointment on 2026-09-21"))
        assertNull(Codes.find("Confirm your appointment on 21/09/2026"))
        assertNull(Codes.find("Your password expires 09.21.2026"))
    }

    @Test
    fun `the number nearest a code word wins`() {
        assertEquals(
            "582104",
            Codes.find("Your code is 582104", "Questions? Reference 77123456 or call 555 123 4567."),
        )
        assertEquals(
            "582104",
            Codes.find("Sign in to Acme", "Your one-time code: 582104. Sent 2026-09-21 10:41."),
        )
    }

    @Test
    fun `a hyphenated word is not a code`() {
        assertNull(Codes.find("Your ONE-TIME code will arrive shortly"))
    }

    @Test
    fun `blank input`() {
        assertNull(Codes.find(""))
        assertNull(Codes.find("", ""))
    }
}
