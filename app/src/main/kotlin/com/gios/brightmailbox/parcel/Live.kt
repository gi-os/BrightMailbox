package com.gios.brightmailbox.parcel

/**
 * What a carrier's own tracking page says, read out of its text.
 *
 * The parcel list is built from email and costs nothing, which is the whole design — but
 * email only says what the carrier decided to announce, and it says it when the carrier
 * decided to send it. A parcel that went out for delivery an hour ago is still "shipped"
 * here until the mail arrives, if it ever does.
 *
 * So this is the one place the app goes and looks. No carrier API: four developer
 * registrations, and Amazon Logistics has no public API at all. The page is loaded in a
 * WebView because a plain HTTP request does not survive the bot walls — UPS, FedEx, USPS
 * and DHL all answer an ordinary client with "Access Denied" before they look at the
 * number — and a WebView is a real browser with a real fingerprint.
 *
 * **Text, not selectors.** A DOM query written against today's markup breaks the week a
 * carrier ships a redesign, and it breaks silently, into a screen that says nothing.
 * Every one of these pages puts a label on one line and its value on the next, and those
 * words change far more slowly than the markup around them.
 *
 * **Conservative on purpose.** Every unrecognized shape returns null, and null means the
 * screen says it could not read the page and offers to open it. A wrong status on a parcel
 * is worse than no status: nobody double-checks a screen that looks confident.
 */
object Live {

    /** What the page said, in the carrier's own words. */
    data class Status(
        /** The carrier's own status line — "Delivered", "On the way", "In Transit". */
        val headline: String,
        /**
         * The labelled facts under it, in the order the page gave them.
         *
         * Pairs rather than named fields because the interesting ones differ per carrier
         * and per state: UPS says "Delivered To" and "Received By" only once it is
         * delivered, and "Estimated Delivery" only before. A fixed record would be mostly
         * nulls and would drop whatever the next carrier calls its best fact.
         */
        val facts: List<Pair<String, String>>,
        /** Mapped onto the app's own vocabulary, so a reading can advance a row. */
        val state: Parcels.State,
    )

    /**
     * @param number the tracking number the page was asked about. It has to appear on the
     *   page or we are reading something else — a redirect to a marketing homepage renders
     *   perfectly well and contains the word "delivered" more than once.
     */
    fun read(number: String, text: String): Status? {
        val lines = clean(text)
        if (lines.isEmpty()) return null
        if (NOT_FOUND.any { it.containsMatchIn(text) }) return null

        val window = window(lines, number) ?: return null

        val headline = window.firstNotNullOfOrNull { line ->
            // A label is never a headline. "Delivered To" is one character class away from
            // "Delivered", and reading the label as the status puts a delivered parcel on
            // the screen the moment the page merely mentions where it is going.
            if (line.trimEnd(':').lowercase() in LABELS) return@firstNotNullOfOrNull null
            HEADLINES.firstOrNull { (re, _) -> re.matches(line) }?.let { line }
        }
        val facts = facts(window)
        if (headline == null && facts.isEmpty()) return null

        return Status(
            headline = headline ?: facts.first().second,
            facts = facts,
            state = headline?.let { stateOf(it) } ?: Parcels.State.UNKNOWN,
        )
    }

    /* ------------------------------------------------------------------ cleaning */

    /**
     * Material icon ligatures land in `innerText` as words.
     *
     * "Delivered check_circle", "Show Details keyboard_arrow_down", and — with no space at
     * all — "Tips to Avoid Fraudchevron_right". Every one of them is lowercase with an
     * underscore in it, which no word on a tracking page is, so one rule removes the lot
     * rather than a list that has to be maintained per carrier.
     */
    private val LIGATURE = Regex("""[a-z]+_[a-z_]+""")

    private fun clean(text: String): List<String> =
        text.lineSequence()
            .map { LIGATURE.replace(it, " ").replace('\u00a0', ' ').trim() }
            .map { it.replace(Regex("""\s{2,}"""), " ") }
            .filter { it.isNotBlank() }
            .toList()

    /**
     * The handful of lines around the tracking number, and nothing else.
     *
     * Every one of these pages is mostly navigation, cookie notice, careers advert and
     * footer, and all four put the status directly under the number. Bounding the scan
     * this way throws away the rest generically — no per-carrier list of things to ignore,
     * which is the kind of list that rots.
     *
     * Null when the number is nowhere on the page: that is the redirect case, and reading
     * on would produce a confident answer about a different parcel or no parcel at all.
     */
    private fun window(lines: List<String>, number: String): List<String>? {
        val wanted = number.filter { it.isLetterOrDigit() }.uppercase()
        if (wanted.isBlank()) return null
        val at = lines.indexOfFirst {
            it.filter { c -> c.isLetterOrDigit() }.uppercase().contains(wanted)
        }
        if (at < 0) return null
        return lines.subList(at, minOf(lines.size, at + WINDOW))
    }

    private const val WINDOW = 24

    /* ------------------------------------------------------------------ the words */

    /**
     * Said by a page that looked and found nothing.
     *
     * Checked before anything else, because these pages are otherwise perfectly normal —
     * they carry the number, they carry the word "tracking", and a hopeful parser will
     * happily return a status from one.
     */
    private val NOT_FOUND = listOf(
        Regex("""tracking is not available""", RegexOption.IGNORE_CASE),
        Regex("""tracking number can.?t be found""", RegexOption.IGNORE_CASE),
        Regex("""no record of this tracking number""", RegexOption.IGNORE_CASE),
        Regex("""could not (be )?locate|not found in our system""", RegexOption.IGNORE_CASE),
        Regex("""we found multiple numbers""", RegexOption.IGNORE_CASE),
        Regex("""access denied|blocked|unusual activity""", RegexOption.IGNORE_CASE),
    )

    /**
     * Status lines, strongest first, matched against a WHOLE line.
     *
     * Whole-line on purpose: "delivered" appears in a paragraph on every one of these
     * pages ("...has not yet been delivered"), and a substring match reads the small print
     * as the answer.
     */
    private val HEADLINES: List<Pair<Regex, Parcels.State>> = listOf(
        Regex("""delivered.{0,24}""", RegexOption.IGNORE_CASE) to Parcels.State.DELIVERED,
        Regex("""out for delivery.{0,24}""", RegexOption.IGNORE_CASE) to Parcels.State.OUT_FOR_DELIVERY,
        Regex("""(delivery )?(exception|attempted|failed|refused|delayed).{0,24}""", RegexOption.IGNORE_CASE)
            to Parcels.State.DELAYED,
        Regex("""(in transit|on the way|arrived at.*|departed.*|shipment picked up|picked up)""", RegexOption.IGNORE_CASE)
            to Parcels.State.SHIPPED,
        Regex("""(shipping )?label created|pre-?shipment|order processed|shipment information sent.*""", RegexOption.IGNORE_CASE)
            to Parcels.State.UNKNOWN,
    )

    private fun stateOf(headline: String): Parcels.State =
        HEADLINES.firstOrNull { it.first.matches(headline) }?.second ?: Parcels.State.UNKNOWN

    /**
     * Label lines whose value is the line under them.
     *
     * Exact matches, because "Delivered To" as a label and "Delivered to a neighbour" as
     * prose are one substring apart.
     */
    private val LABELS = setOf(
        "latest update",
        "delivered to",
        "received by",
        "estimated delivery",
        "scheduled delivery",
        "expected delivery",
        "arriving",
        "last updated",
        "service",
        "status",
    )

    /** Values that are the page admitting it has nothing, which is not a fact worth a row. */
    private val NOISE = Regex(
        """^(no information available|not available|n/?a|-|—|unknown|pending)$""",
        RegexOption.IGNORE_CASE,
    )

    private fun facts(window: List<String>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(4)
        for (i in window.indices) {
            val label = window[i].trimEnd(':').lowercase()
            if (label !in LABELS) continue
            val value = window.getOrNull(i + 1)?.trim() ?: continue
            if (value.trimEnd(':').lowercase() in LABELS) continue
            if (NOISE.matches(value)) continue
            // A value long enough to be a paragraph is a paragraph: the label was prose.
            if (value.length > 60) continue
            out.add(window[i].trimEnd(':') to value)
            if (out.size == 4) break
        }
        return out
    }
}
