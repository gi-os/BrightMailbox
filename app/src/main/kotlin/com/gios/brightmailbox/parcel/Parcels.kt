package com.gios.brightmailbox.parcel

import com.gios.brightmailbox.sort.Envelope

/**
 * The shipping half of a mailbox: which parcels are coming, and how far along they are.
 *
 * No carrier API is involved anywhere in this. Carriers and shops already mail every state
 * transition, so the state is in the mailbox before any app asks for it, and Amazon
 * Logistics (`TBA…`) has no public API at all — a mail-derived state is the only state that
 * reaches the largest single share of parcels anyway. Deriving it costs no keys, no
 * accounts, no polling and no background network.
 *
 * Everything here is pure text: no Android imports, so it unit-tests on the JVM like
 * `text/` and `sort/`. The caller passes the *cleaned* body — the same text the reader
 * shows — never raw HTML.
 *
 * What crosses to BrightParcel (if it is installed) is the fields on [Parcel], never the
 * message. Same line the crash reporter holds: a mail client does not hand anybody's
 * correspondence to another process.
 */
object Parcels {

    enum class Carrier(val label: String) {
        UPS("UPS"),
        FEDEX("FedEx"),
        USPS("USPS"),
        DHL("DHL"),
        AMAZON("Amazon"),
    }

    /**
     * The four transitions the mail actually carries.
     *
     * [UNKNOWN] is a real answer, not a failure: "your label has been created" is a parcel
     * with a number and no journey yet, and guessing [SHIPPED] for it would put a lie on
     * the screen. Ordered weakest to strongest; the strongest thing the mail says wins.
     */
    enum class State { UNKNOWN, SHIPPED, OUT_FOR_DELIVERY, DELAYED, DELIVERED }

    /**
     * One parcel. One order generates four or five mails and they all collapse onto one of
     * these — which is what [id] is for.
     */
    data class Parcel(
        val carrier: Carrier,
        val number: String,
        val state: State,
        val merchant: String? = null,
        val url: String? = null,
        val eta: String? = null,
    ) {
        /** Stable across every mail about this parcel, and safe as a database key. */
        val id: String get() = "${carrier.name}:$number"
    }

    /**
     * Read one message. Returns every parcel it is about — usually none, sometimes one,
     * occasionally several when a shop split one order across carriers.
     *
     * Takes the sorter's [Envelope] rather than a message: it already holds the parsed
     * sender, the decoded subject and the cleaned body, and nothing here should ever see
     * more of a letter than the sorter does.
     */
    fun detect(message: Envelope): List<Parcel> {
        val subject = message.subject
        val body = message.body

        // A return label is a tracking number for a parcel travelling away from you, and
        // without this it reads as an inbound parcel that never arrives. The subject is
        // where to look: "Your return has shipped", "Return label for order 12345".
        // Checking the body instead would veto genuine shipping mails, because their
        // footers all advertise "Start a return".
        if (RETURN.containsMatchIn(subject)) return emptyList()

        val text = "$subject\n$body"
        val urls = LINKS.findAll(text).map { it.value.trimEnd('.', ',', ')', ';') }.toList()
        val named = namedCarriers(message.domain, urls, text)

        val state = stateOf(subject, body)
        val merchant = merchantOf(message.fromName, message.domain, subject)
        val eta = etaOf(body)

        // Keyed by number, so a 20-digit USPS label cannot also be claimed as a FedEx 20.
        val found = LinkedHashMap<String, Parcel>()

        for (shape in SHAPES) {
            for (m in shape.re.findAll(text)) {
                val number = m.value
                if (found.containsKey(number)) continue
                // Carrier and format have to agree. A bare twelve digits is FedEx or a USPS
                // label or an order id, and guessing is how the list fills with junk.
                val agrees = shape.strong || shape.carrier in named
                if (!agrees || !guarded(text, m.range.first, shape.strong)) continue
                found[number] = Parcel(
                    shape.carrier,
                    number,
                    state,
                    merchant,
                    urlFor(shape.carrier, number, urls),
                    eta,
                )
            }
        }

        // The button case needs nothing extra: a tracking link carries its number as text,
        // so the scan above already reads numbers out of links. There is deliberately no
        // second pass over `?trknbr=` parameters — it would only ever find what text found.

        return found.values.toList()
    }

    // ---------------------------------------------------------------- carriers

    /** A host suffix that names a carrier, on the sender's address or on any link in the mail. */
    private val HOSTS = listOf(
        "ups.com" to Carrier.UPS,
        "upsmobile.com" to Carrier.UPS,
        "fedex.com" to Carrier.FEDEX,
        "usps.com" to Carrier.USPS,
        "usps.gov" to Carrier.USPS,
        "dhl.com" to Carrier.DHL,
        "amazon.com" to Carrier.AMAZON,
    )

    /** What a carrier is called in prose, when no domain gives it away. */
    private val NAMES = listOf(
        Carrier.UPS to Regex("""\bUPS\b""", RegexOption.IGNORE_CASE),
        Carrier.FEDEX to Regex("""\bFedEx\b|\bFederal Express\b""", RegexOption.IGNORE_CASE),
        Carrier.USPS to Regex(
            """\bUSPS\b|\bU\.?S\.? Postal\b|\bPostal Service\b|\bPriority Mail\b""",
            RegexOption.IGNORE_CASE,
        ),
        Carrier.DHL to Regex("""\bDHL\b""", RegexOption.IGNORE_CASE),
        Carrier.AMAZON to Regex("""\bAmazon Logistics\b|\bAmazon\b""", RegexOption.IGNORE_CASE),
    )

    private fun namedCarriers(domain: String, urls: List<String>, text: String): Set<Carrier> {
        val out = HashSet<Carrier>()
        val hosts = urls.map { host(it) } + domain.lowercase()
        for (h in hosts) HOSTS.firstOrNull { h == it.first || h.endsWith(".${it.first}") }?.let { out.add(it.second) }
        for ((carrier, re) in NAMES) if (re.containsMatchIn(text)) out.add(carrier)
        return out
    }

    /** The host out of a URL. Empty for anything that is not one. */
    private fun host(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?')
            .substringBefore('#').substringBefore(':').substringAfterLast('@').lowercase()

    // ---------------------------------------------------------------- shapes

    /**
     * [strong] shapes name their own carrier and need no evidence: nobody else issues
     * `1Z…`, `TBA…` or an S10 number. Everything else is a bare digit run that several
     * carriers and every order number in the world also fit, so it is only believed when
     * the mail names that carrier *and* the number sits next to a tracking word.
     */
    private class Shape(val carrier: Carrier, val re: Regex, val strong: Boolean)

    private val SHAPES = listOf(
        Shape(Carrier.UPS, Regex("""\b1Z[0-9A-Z]{16}\b"""), true),
        Shape(Carrier.AMAZON, Regex("""\bTBA\d{12}\b"""), true),
        Shape(Carrier.USPS, Regex("""\b[A-Z]{2}\d{9}US\b"""), true),
        Shape(Carrier.USPS, Regex("""\b9[1-5]\d{18,20}\b"""), false),
        Shape(Carrier.FEDEX, Regex("""\b(?:96\d{20}|\d{15}|\d{12})\b"""), false),
        Shape(Carrier.DHL, Regex("""\b(?:JJD?\d{14,20}|\d{10})\b"""), false),
    )

    private val TRACK_URL = mapOf(
        Carrier.UPS to "https://www.ups.com/track?tracknum=",
        Carrier.FEDEX to "https://www.fedex.com/fedextrack/?trknbr=",
        Carrier.USPS to "https://tools.usps.com/go/TrackConfirmAction?tLabels=",
        Carrier.DHL to "https://www.dhl.com/us-en/home/tracking.html?tracking-id=",
        Carrier.AMAZON to "https://track.amazon.com/tracking/",
    )

    /** A word that has to be within reach of a bare digit run for it to be a tracking number. */
    private val NEARBY = Regex(
        """track|shipment|shipped|package|parcel|label|consignment|delivery""",
        RegexOption.IGNORE_CASE,
    )

    private fun guarded(text: String, at: Int, strong: Boolean): Boolean =
        strong || NEARBY.containsMatchIn(
            text.substring(maxOf(0, at - 48), minOf(text.length, at + 48)),
        )

    private fun urlFor(carrier: Carrier, number: String, urls: List<String>): String? {
        val suffix = HOSTS.first { it.second == carrier }.first
        val onCarrier = urls.filter { h -> host(h).let { it == suffix || it.endsWith(".$suffix") } }
        // The link that already points at this parcel beats the carrier's front page.
        onCarrier.firstOrNull { it.contains(number) }?.let { return it }
        onCarrier.firstOrNull()?.let { return it }
        return TRACK_URL[carrier]?.plus(number)
    }

    // ---------------------------------------------------------------- reading

    /**
     * Return-ish subjects. Body-only returns — a neutral subject on a mail that happens to
     * contain a return label — still slip through; see where this stops being enough.
     */
    private val RETURN = Regex("""\b(return|returned|refund|RMA)\b""", RegexOption.IGNORE_CASE)

    private val LINKS = Regex("""https?://[^\s<>"')\]]+""")

    /** Delivered, said in the past tense, by any of the ways a carrier says it. */
    private val DELIVERED_PAST = Regex(
        """\b(was delivered|has been delivered|have been delivered|delivered (?:at|on|to)\b|""" +
            """was left|left at|was handed|has arrived|is in your mailbox)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DELIVERED = Regex("""\b(delivered|delivery complete)\b""", RegexOption.IGNORE_CASE)
    private val FUTURE = Regex(
        """\b(?:will be|will arrive|expected to be|scheduled to be|should be|to be|estimated|arriving)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val OUT_FOR_DELIVERY = Regex(
        """\b(out for delivery|being delivered today|arriving today|on the way to you)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DELAYED = Regex(
        """\b(delayed|delay in delivery|weather delay|delivery exception|rescheduled)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SHIPPED = Regex(
        """\b(shipped|has left|left our|dispatched|on its way|on the way|in transit|label created|we('ve| have) shipped)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Strongest thing said wins, so a mail that says both is read the way the customer
     * reads it. Two guards keep "out for delivery" out of "delivered": the future tense is
     * stripped ("your package will be delivered today" is not delivered), and a bare
     * "delivered" only counts after the explicit past-tense forms and after out-for-delivery
     * have had their say — "left at the front door" is a delivered mail, a passing
     * "out for delivery" in a tracking timeline is not.
     */
    private fun stateOf(subject: String, body: String): State {
        val said = "$subject\n${body.take(1200)}"
        val past = FUTURE.replace(said, " ")
        return when {
            DELIVERED_PAST.containsMatchIn(past) -> State.DELIVERED
            OUT_FOR_DELIVERY.containsMatchIn(past) -> State.OUT_FOR_DELIVERY
            DELIVERED.containsMatchIn(past) -> State.DELIVERED
            DELAYED.containsMatchIn(past) -> State.DELAYED
            SHIPPED.containsMatchIn(past) -> State.SHIPPED
            else -> State.UNKNOWN
        }
    }

    /**
     * The shop, not the carrier. A carrier's own notice names nobody, so the subject is
     * asked first ("Your Amazon.com order has shipped") and the sender's display name is
     * only believed when it is not a carrier's.
     */
    private fun merchantOf(fromName: String, domain: String, subject: String): String? {
        Regex("""(?i)\byour ([A-Za-z0-9&'’. -]{2,28}?) (?:order|package|parcel|shipment)\b""")
            .find(subject)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        // A carrier's own notice names no shop: "UPS" is not where the parcel came from.
        if (HOSTS.any { domain == it.first || domain.endsWith(".${it.first}") }) return null
        return fromName.takeIf { it.isNotBlank() && !it.contains('@') }
    }

    /**
     * An ETA, as the mail words it, for the list to show and nothing to compute with. Kept
     * short and shown verbatim because carriers write dates every way there is; a real date
     * parse belongs here only once something needs the date itself.
     */
    private val ETA = Regex(
        """(?i)\b(?:arriv\w*|deliver\w*|estimated (?:delivery|arrival)|expected|by)\b[^.!?\n]{0,48}?""" +
            """\b(\d{1,2}:\d{2}\s?(?:am|pm)?|\d{1,2}/\d{1,2}(?:/\d{2,4})?|""" +
            """jan\w*|feb\w*|mar\w*|apr\w*|may|jun\w*|jul\w*|aug\w*|sep\w*|oct\w*|nov\w*|dec\w*|""" +
            """monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon\.?|tue\w*\.?|wed\w*\.?|""" +
            """thu\w*\.?|fri\w*\.?|sat\w*\.?|sun\w*\.?|today|tomorrow)\b""",
    )

    private fun etaOf(body: String): String? =
        ETA.find(body)?.value?.replace(Regex("""\s+"""), " ")?.trim()?.take(64)
}