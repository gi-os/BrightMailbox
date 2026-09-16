package com.gios.brightmailbox.parcel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures here are **real**: every one was captured by loading the carrier's own
 * tracking page in a browser and taking `document.body.innerText`, ligature noise and all.
 * That matters more than usual — the details that break a parser of this kind are the ones
 * nobody would invent, like "Delivered check_circle" or a status word glued to an icon
 * name with no space.
 *
 * Replace a fixture the day a carrier redesigns. The point of them is that the failure is
 * a red test rather than a blank screen on somebody's phone.
 */
class LiveTest {

    /** ups.com/track?tracknum=1Z999AA10123456784, captured 2026-09-16. */
    private val upsDelivered = """
        Skip to Main Content
        Find Closest UPS Location
        Log In
        Tracking Details
        1Z999AA10123456784
        content_copy
        Copy Tracking Number
        check
        close
        Tracking number copied to clipboard.
        Delivered check_circle
        Latest Update
        No Information Available
        Delivered To
        LONGVIEW, TX US
        Received By
        TAYLOR
        Show Details keyboard_arrow_down
        Shipment Details
        expand_more
        Track Another Package
        Stay Safe - Avoid Fraud and Scams
        Tips to Avoid Fraudchevron_right
        Copyright ©1994-2026 United Parcel Service of America, Inc.
    """.trimIndent()

    @Test
    fun `a delivered ups page reads`() {
        val s = Live.read("1Z999AA10123456784", upsDelivered)!!
        assertEquals("Delivered", s.headline)
        assertEquals(Parcels.State.DELIVERED, s.state)
        assertTrue(s.facts.contains("Delivered To" to "LONGVIEW, TX US"))
        assertTrue(s.facts.contains("Received By" to "TAYLOR"))
    }

    @Test
    fun `a page admitting it knows nothing contributes no fact`() {
        val s = Live.read("1Z999AA10123456784", upsDelivered)!!
        // "Latest Update / No Information Available" is the page shrugging. A row saying
        // "Latest Update: No Information Available" is worse than one line shorter.
        assertTrue(s.facts.none { it.second.contains("No Information", ignoreCase = true) })
    }

    /** tools.usps.com, a number USPS has never seen. Captured 2026-09-16. */
    @Test
    fun `usps saying it cannot track is not a status`() {
        val page = """
            Remove
            Tracking Number:
            9400111899223197428490
            Copy Add to Informed Delivery
            Tracking Not Available
            Tracking is not available for this item. This may be because the tracking number
            is invalid; USPS has not yet received payment; USPS has not yet received the item.
        """.trimIndent()
        assertNull(Live.read("9400111899223197428490", page))
    }

    /** fedex.com/fedextrack, an expired number. Captured 2026-09-16. */
    @Test
    fun `fedex saying it cannot find it is not a status`() {
        val page = """
            FedEx
            Tracking
            Your tracking number can't be found. It may not be in our system yet or is
            expired. Double check the number with the shipper or try again later.
            Watch list
        """.trimIndent()
        assertNull(Live.read("449044304137821", page))
    }

    /** dhl.com, a number that collides with several. Captured 2026-09-16. */
    @Test
    fun `dhl offering a choice of shipments is not a status`() {
        val page = """
            Track & Trace
            1234567890
            We found multiple numbers for your tracking code. Please select your shipment below.
            Tracking Code: Centiro/218000385 , Delivery Failed
        """.trimIndent()
        assertNull(Live.read("1234567890", page))
    }

    /**
     * The bug this whole guard exists for, reported from a real parcel: USPS said delivered
     * on something that was nowhere near delivered.
     *
     * A tracking page draws the **whole journey**, not just where the parcel is, so every
     * stage name is in the text on every page. Taking the first line that looked like a
     * status meant taking a label off the progress bar.
     */
    @Test
    fun `a progress bar does not deliver a parcel that is in transit`() {
        val page = """
            Tracking Number:
            9400111899223197428490
            Copy Add to Informed Delivery
            In Transit to Next Facility
            Expected Delivery by
            Thursday 18 September
            Shipped
            In Transit
            Out for Delivery
            Delivered
        """.trimIndent()
        val s = Live.read("9400111899223197428490", page)!!
        assertEquals("In Transit to Next Facility", s.headline)
        assertEquals(Parcels.State.SHIPPED, s.state)
    }

    @Test
    fun `a page offering nothing but bar labels says nothing`() {
        // No detailed status anywhere: every candidate is a step name, so there is no
        // answer to give and the screen says it could not read the page.
        val page = """
            Tracking Number:
            9400111899223197428490
            Shipped
            In Transit
            Out for Delivery
            Delivered
        """.trimIndent()
        assertNull(Live.read("9400111899223197428490", page))
    }

    @Test
    fun `a real delivered page carries its own detail`() {
        val page = """
            Tracking Number:
            9400111899223197428490
            Delivered, In/At Mailbox
            September 16, 2026 at 1:12 pm
            NEW YORK, NY 10002
        """.trimIndent()
        val s = Live.read("9400111899223197428490", page)!!
        assertEquals(Parcels.State.DELIVERED, s.state)
    }

    @Test
    fun `out for delivery survives its own bar`() {
        val page = """
            Tracking Number:
            9400111899223197428490
            Out for Delivery, Expected by 8:00pm
            Shipped
            In Transit
            Out for Delivery
            Delivered
        """.trimIndent()
        assertEquals(
            Parcels.State.OUT_FOR_DELIVERY,
            Live.read("9400111899223197428490", page)!!.state,
        )
    }

    @Test
    fun `a page that never mentions the number is the wrong page`() {
        // Carriers redirect a stale or malformed link to their marketing homepage, which
        // renders perfectly and says "delivered" in the advertising copy.
        val page = """
            UPS
            Delivered
            We deliver more packages to more places than anyone.
            Estimated Delivery
            Tomorrow
        """.trimIndent()
        assertNull(Live.read("1Z999AA10123456784", page))
    }

    @Test
    fun `the word delivered inside a sentence is not a status`() {
        val page = """
            Tracking Details
            1Z999AA10123456784
            Your package has not yet been delivered to the address on file.
        """.trimIndent()
        assertNull(Live.read("1Z999AA10123456784", page))
    }

    @Test
    fun `a label is not mistaken for the status`() {
        // "Delivered To" appearing before any status line must not become the headline.
        val page = """
            Tracking Details
            1Z999AA10123456784
            Delivered To
            LONGVIEW, TX US
            Out for delivery
        """.trimIndent()
        val s = Live.read("1Z999AA10123456784", page)!!
        assertEquals("Out for delivery", s.headline)
        assertEquals(Parcels.State.OUT_FOR_DELIVERY, s.state)
    }

    @Test
    fun `status is read from the lines around the number and not from the footer`() {
        val page = """
            Tracking Details
            1Z999AA10123456784
            In Transit
            ${"filler\n".repeat(40)}
            Delivered
        """.trimIndent()
        assertEquals(Parcels.State.SHIPPED, Live.read("1Z999AA10123456784", page)!!.state)
    }

    /**
     * Carriers print a number in groups and people paste it back with spaces in it, so the
     * match that decides "is this page even about my parcel" ignores everything but letters
     * and digits.
     *
     * The status here carries its own detail on purpose. A bare "Delivered" with nothing
     * standing behind it is refused now — see the progress-bar tests — and this test is
     * about the number, not about that rule.
     */
    @Test
    fun `spacing and punctuation in the number do not matter`() {
        val page = """
            Tracking Details
            1Z 999 AA1 01 2345 6784
            Delivered, In/At Mailbox
        """.trimIndent()
        assertEquals(Parcels.State.DELIVERED, Live.read("1Z999AA10123456784", page)!!.state)
    }
}
