package com.gios.brightmailbox.parcel

import com.gios.brightmailbox.sort.Envelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are the shapes carriers and shops actually send, written out by hand — the real
 * mail is Gio's, and is not in this repository. So these pin the rules, not the senders:
 * replace a fixture with a real message whenever one arrives and the rule still holds.
 *
 * The tests that matter are the ones asserting *nothing* is detected. A missed parcel is a
 * missing row; a false one is a row that never moves and a notification for a parcel that
 * does not exist.
 */
class ParcelsTest {

    private val ups = """
        From: "UPS" <mcinfo@ups.com>
        Subject: Your package from Example Goods is on its way

        Hello GIOVANNI LUPO,
        We have your shipment. Your package is on its way.
        Tracking Number: 1Z999AA10123456784
        Track your package: https://www.ups.com/track?trknbr=1Z999AA10123456784&loc=en_US
        Estimated Delivery: Friday, September 18 by 8:00 PM
    """.trimIndent()

    private fun bodyOf(mail: String) = mail.substringAfter("\n\n")
    private fun fromOf(mail: String) = mail.lines().first { it.startsWith("From:") }
    private fun subjectOf(mail: String) = mail.lines().first { it.startsWith("Subject:") }.removePrefix("Subject: ")

    /**
     * The fixture's `From:` line, split the way the sync pipeline splits a real one before
     * the sorter ever sees it. Spelled out here rather than calling the app's address
     * parser so this file has no dependency beyond the two objects under test.
     */
    private fun detect(mail: String): List<Parcels.Parcel> {
        val from = fromOf(mail).removePrefix("From: ")
        return Parcels.detect(
            Envelope(
                from = from.substringAfter('<').substringBefore('>').lowercase(),
                fromName = from.substringBefore('<').trim().trim('"'),
                subject = subjectOf(mail),
                body = bodyOf(mail),
            ),
        )
    }

    @Test
    fun `a ups mail is one parcel with a link`() {
        val p = detect(ups).single()
        assertEquals(Parcels.Carrier.UPS, p.carrier)
        assertEquals("1Z999AA10123456784", p.number)
        assertEquals(Parcels.State.SHIPPED, p.state)
        assertEquals("https://www.ups.com/track?trknbr=1Z999AA10123456784&loc=en_US", p.url)
        assertEquals("UPS:1Z999AA10123456784", p.id)
    }

    @Test
    fun `out for delivery is not delivered`() {
        val mail = ups
            .replace("on its way", "out for delivery")
            .replace(
                "Your package is on its way.",
                "Your package is out for delivery and will be delivered today.",
            )
        assertEquals(Parcels.State.OUT_FOR_DELIVERY, detect(mail).single().state)
    }

    @Test
    fun `delivered is delivered`() {
        val mail = ups.replace("is on its way", "was delivered").replace(
            "We have your shipment. Your package is on its way.",
            "Your package was delivered at 2:14 PM and left at the front door.",
        )
        assertEquals(Parcels.State.DELIVERED, detect(mail).single().state)
    }

    @Test
    fun `a return label is not an inbound parcel`() {
        val mail = """
            From: "UPS Returns" <returns@ups.com>
            Subject: Your return label for order 4471

            Print this label and drop the package at any UPS location.
            Tracking Number: 1Z999AA10123456784
        """.trimIndent()
        assertTrue(detect(mail).isEmpty())
    }

    @Test
    fun `a footer advertising returns does not veto a shipping mail`() {
        val mail = ups + "\n\nNeed to send something back? Start a return within 30 days."
        assertEquals(1, detect(mail).size)
    }

    @Test
    fun `a bare twelve digits is not a parcel`() {
        // No carrier named anywhere: 12 digits is FedEx or a USPS label or an order id.
        val mail = """
            From: "Example Goods" <orders@examplegoods.com>
            Subject: Order 447109283746 confirmed

            Thanks! Your tracking number will follow.
        """.trimIndent()
        assertTrue(detect(mail).isEmpty())
    }

    @Test
    fun `an order number in a dhl mail is not a tracking number`() {
        val mail = """
            From: "DHL" <noreply@dhl.com>
            Subject: Your DHL order is confirmed

            Order 4471092837469 has been received. We will email you when it ships.
        """.trimIndent()
        assertTrue(detect(mail).isEmpty())
    }

    @Test
    fun `a fedex number next to the word tracking is believed`() {
        val mail = """
            From: "FedEx" <tracking@fedex.com>
            Subject: Your package has shipped

            Tracking number: 449044304137
            https://www.fedex.com/fedextrack/?trknbr=449044304137
        """.trimIndent()
        val p = detect(mail).single()
        assertEquals(Parcels.Carrier.FEDEX, p.carrier)
        assertEquals("449044304137", p.number)
    }

    @Test
    fun `amazon logistics is detected without any api`() {
        val mail = """
            From: "Amazon.com" <ship-confirm@amazon.com>
            Subject: Your Amazon.com order of "Espresso Tamper" has shipped

            Tracking ID: TBA305678901234
            https://track.amazon.com/tracking/TBA305678901234
        """.trimIndent()
        val p = detect(mail).single()
        assertEquals(Parcels.Carrier.AMAZON, p.carrier)
        assertEquals("TBA305678901234", p.number)
        assertEquals("https://track.amazon.com/tracking/TBA305678901234", p.url)
    }

    @Test
    fun `the same number twice is one parcel`() {
        val mail = ups.replace(
            "Track your package: https://www.ups.com/track?trknbr=1Z999AA10123456784&loc=en_US",
            "Track your package: 1Z999AA10123456784\n" +
                "https://www.ups.com/track?trknbr=1Z999AA10123456784&loc=en_US",
        )
        assertEquals(1, detect(mail).size)
    }

    @Test
    fun `a mail that only names the number inside a button still counts`() {
        val mail = """
            From: "Example Goods" <support@fedex.com>
            Subject: Your order has shipped

            Your package is on its way.
            <https://www.fedex.com/fedextrack/?trknbr=449044304137821>
        """.trimIndent()
        assertEquals("449044304137821", detect(mail).single().number)
    }

    @Test
    fun `an unsubscribe link is not a tracking number`() {
        val mail = """
            From: "Example Goods" <hello@examplegoods.com>
            Subject: Your order is on its way

            Prefer fewer emails? <https://a.examplegoods.com/u?id=123456789012>
        """.trimIndent()
        assertTrue(detect(mail).isEmpty())
    }

    @Test
    fun `a s10 number is a postal parcel on its own`() {
        val mail = """
            From: "USPS" <usps@email.usps.com>
            Subject: Your package has shipped

            International tracking number: LZ123456789US
        """.trimIndent()
        assertEquals(Parcels.Carrier.USPS, detect(mail).single().carrier)
    }

    @Test
    fun `a number only in an image is undetectable, and says so by returning nothing`() {
        val mail = """
            From: "Example Goods" <support@examplegoods.com>
            Subject: Your order has shipped

            Your tracking number is shown in the image below.
            [image: tracking-barcode.png]
        """.trimIndent()
        assertTrue(detect(mail).isEmpty())
    }

    @Test
    fun `the shop is taken from the subject, not the carrier`() {
        val mail = """
            From: "Mercari" <no-reply@mercari.com>
            Subject: Your Mercari order has shipped

            Tracking number: 9400111899223197428490
            https://tools.usps.com/go/TrackConfirmAction?tLabels=9400111899223197428490
        """.trimIndent()
        val p = detect(mail).single()
        assertEquals(Parcels.Carrier.USPS, p.carrier)
        assertEquals("Mercari", p.merchant)
    }

    @Test
    fun `a carrier's own notice has no shop on it`() {
        assertNull(detect(ups).single().merchant)
    }

    @Test
    fun `an eta is kept as the mail words it`() {
        assertEquals("Estimated Delivery: Friday", detect(ups).single().eta)
    }

    @Test
    fun `a mail about nothing in particular yields nothing`() {
        val mail = """
            From: "Alex" <alex@example.com>
            Subject: dinner thursday?

            I made a reservation for 8. Call me if that is too late.
        """.trimIndent()
        assertTrue(detect(mail).isEmpty())
    }

    // ------------------------------------------------------------------- shops

    /**
     * The mails that made this necessary. Amazon states its tracking number behind a button
     * and often not in the text at all, so before order ids were read, an Amazon order that
     * reported every state change produced no row — the deepest hole in the list, because
     * Amazon is most of what a mailbox like this carries.
     */
    private val amazonShipped = """
        From: "Amazon.com" <shipment-tracking@amazon.com>
        Subject: Your Amazon.com order of "Anker USB-C Cable" has shipped

        Hello Giovanni,
        Your package has shipped with Amazon Logistics.
        Order #123-4567890-1234567
        Track your package: https://www.amazon.com/gp/your-account/order-details?orderID=123-4567890-1234567
    """.trimIndent()

    @Test
    fun `an amazon order with no tracking number is still a parcel`() {
        val p = detect(amazonShipped).single()
        assertEquals(Parcels.Carrier.AMAZON, p.carrier)
        assertEquals("123-4567890-1234567", p.number)
        assertEquals(Parcels.State.SHIPPED, p.state)
        // The subject names the thing, which is the most useful word this row can show.
        assertEquals("Anker USB-C Cable", p.item)
        // The shop's own link wins over anything this file could reconstruct.
        assertEquals(
            "https://www.amazon.com/gp/your-account/order-details?orderID=123-4567890-1234567",
            p.url,
        )
    }

    @Test
    fun `a mail that names no item falls back to the shop`() {
        assertNull(detect(ebayShipped).single().item)
        assertNull(detect(ups).single().item)
    }

    @Test
    fun `an ebay row never lands on seller hub`() {
        val mail = """
            From: "eBay" <ebay@ebay.com>
            Subject: Your order has shipped

            Order number: 14-11960-23534
        """.trimIndent()
        val url = detect(mail).single().url.orEmpty()
        assertEquals("https://www.ebay.com/mye/myebay/purchase", url)
        // /sh/ is Seller Hub, where a seller tracks what they shipped. A buyer's page is
        // never there, and it is the same host, so the mistake is invisible unless pinned.
        assertTrue(!url.contains("/sh/"))
    }

    @Test
    fun `an ebay mail linking to seller hub is not followed`() {
        val mail = """
            From: "eBay" <ebay@ebay.com>
            Subject: Your order has shipped

            Order number: 14-11960-23534
            Manage this order: https://www.ebay.com/sh/ord?orderid=14-11960-23534
        """.trimIndent()
        assertTrue(!detect(mail).single().url.orEmpty().contains("/sh/"))
    }

    @Test
    fun `an amazon mail carrying both ids is one parcel, keyed on the number`() {
        val mail = """
            From: "Amazon.com" <shipment-tracking@amazon.com>
            Subject: Your Amazon.com order of "Anker USB-C Cable" has shipped

            Order #123-4567890-1234567
            Tracking ID: TBA123456789012
        """.trimIndent()
        assertEquals("TBA123456789012", detect(mail).single().number)
    }

    private val ebayShipped = """
        From: "eBay" <ebay@ebay.com>
        Subject: Your order has shipped

        Good news — your order has shipped.
        Order number: 14-11960-23534
    """.trimIndent()

    @Test
    fun `an ebay order is a parcel with no carrier number at all`() {
        val p = detect(ebayShipped).single()
        assertEquals(Parcels.Carrier.EBAY, p.carrier)
        assertEquals("14-11960-23534", p.number)
        assertEquals(Parcels.State.SHIPPED, p.state)
        assertEquals("eBay", p.merchant)
    }

    @Test
    fun `an ebay mail that states the carrier keeps the carrier's number`() {
        val mail = """
            From: "eBay" <ebay@ebay.com>
            Subject: Your order has shipped

            Order number: 14-11960-23534
            Tracking number: 9400111899223456789012
            Shipped with USPS.
        """.trimIndent()
        assertEquals(Parcels.Carrier.USPS, detect(mail).single().carrier)
    }

    @Test
    fun `an order id from anyone but the shop that issued it is not a parcel`() {
        val mail = """
            From: "Someone" <news@example.com>
            Subject: Confirmation

            Order 123-4567890-1234567 is confirmed. Thanks for your purchase.
        """.trimIndent()
        assertTrue(detect(mail).isEmpty())
    }
}