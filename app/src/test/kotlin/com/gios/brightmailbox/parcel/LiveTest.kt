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

    @Test
    fun `spacing and punctuation in the number do not matter`() {
        val page = """
            Tracking Details
            1Z 999 AA1 01 2345 6784
            Delivered
        """.trimIndent()
        assertEquals(Parcels.State.DELIVERED, Live.read("1Z999AA10123456784", page)!!.state)
    }
}
