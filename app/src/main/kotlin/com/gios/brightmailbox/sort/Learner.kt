package com.gios.brightmailbox.sort

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Tier 1 — logistic regression over hashed n-grams. About 200 KB of weights.
 *
 * This is the "local model" in the plan, and it is deliberately not a language model.
 * A Gemma-class transformer on the LP3's Snapdragon 4 Gen 2 needs two to five seconds of
 * prefill per message; a first sync of 200 messages would spend ten minutes pegging the
 * CPU against a 1800 mAh battery, and it would still be worse at this than the headers
 * are. A hashed linear model trains from a mailbox in about a second, predicts in a
 * dot product, and — the part that actually matters — learns *this user* rather than
 * users in general.
 *
 * Two jobs:
 *   1. Rank Letters, so the daily five are the five worth reading.
 *   2. Absorb corrections, so a misfile is permanent knowledge instead of an annoyance.
 */
class Learner(
    /** Power of two. 2^14 floats = 64 KB in memory, ~200 KB on disk as text. */
    val dim: Int = 1 shl 14,
    private var rate: Double = 0.12,
    private val l2: Double = 1e-6,
) {
    var weights = DoubleArray(dim)
        private set
    var bias = 0.0
        private set

    /** Per-feature squared-gradient sum for AdaGrad. */
    private var accum = DoubleArray(dim)
    var seen: Long = 0L
        private set

    /* ------------------------------------------------------------------ features */

    /**
     * Hash a token into a bucket, with the sign trick.
     *
     * The second hash decides +1/-1 so that two different tokens landing in the same
     * bucket tend to cancel rather than reinforce. Without it a 16k-bucket model on a
     * real mailbox degrades noticeably; with it, collisions are noise instead of bias.
     */
    private fun bucket(token: String): Int {
        var h = -0x7ee3623b  // FNV-ish offset
        for (c in token) {
            h = h xor c.code
            h *= 0x01000193
        }
        return (h and 0x7fffffff) % dim
    }

    private fun sign(token: String): Double {
        var h = 0x811c9dc5.toInt()
        for (c in token) {
            h = (h + c.code) * 0x27220A95
        }
        return if (h and 1 == 0) 1.0 else -1.0
    }

    /**
     * Turn an envelope into sparse features.
     *
     * Structured tokens are prefixed so `from=alex@x.com` can never collide with a body
     * word "alex". The sender address is repeated three times because who sent it is
     * worth more than any single word in it, and repetition is how you weight a feature
     * in a bag-of-words model without a separate weighting scheme.
     */
    fun features(e: Envelope): Map<Int, Double> {
        val out = HashMap<Int, Double>(96)

        fun add(token: String, w: Double = 1.0) {
            val b = bucket(token)
            out[b] = (out[b] ?: 0.0) + w * sign(token)
        }

        add("from=${e.from}", 3.0)
        add("dom=${e.domain}")
        add("local=${e.localPart}")
        if (e.fromName.isNotBlank()) {
            add("name=${e.fromName.lowercase()}", 2.0)
            // A display name with a space in it is usually a person, not a brand.
            if (e.fromName.trim().contains(' ')) add("name-has-space")
        }
        add("nrecip=${bucketCount(e.otherRecipients)}")
        if (e.addressedToMe) add("to-me")
        for (h in listOf("list-unsubscribe", "precedence", "auto-submitted", "list-id")) {
            if (e.has(h)) add("hdr=$h")
        }

        words(e.subject).forEach { add("s=$it") }
        bigrams(words(e.subject)).forEach { add("s2=$it", 0.5) }
        // Body is capped: past a couple of hundred words a newsletter is just more of
        // itself, and the tail only adds collisions.
        words(e.body).take(220).forEach { add("b=$it", 0.35) }

        // L2-normalize. Otherwise a long newsletter takes a bigger gradient step than a
        // three-line note purely for being long, and the model drifts toward the junk.
        val norm = sqrt(out.values.sumOf { it * it }).takeIf { it > 1e-9 } ?: 1.0
        return out.mapValues { it.value / norm }
    }

    private fun bucketCount(n: Int) = when {
        n == 0 -> "0"
        n <= 2 -> "1-2"
        n <= 6 -> "3-6"
        n <= 20 -> "7-20"
        else -> "many"
    }

    /* ------------------------------------------------------------------- scoring */

    fun score(f: Map<Int, Double>): Double {
        var z = bias
        for ((i, v) in f) z += weights[i] * v
        return z
    }

    /** Probability that this is worth reading. */
    fun predict(f: Map<Int, Double>): Double = sigmoid(score(f))

    fun predict(e: Envelope): Double = predict(features(e))

    private fun sigmoid(z: Double): Double =
        if (z >= 0) 1.0 / (1.0 + exp(-z)) else exp(z) / (1.0 + exp(z))

    /* ------------------------------------------------------------------ training */

    /**
     * One AdaGrad step.
     *
     * AdaGrad rather than plain SGD because the feature scales here differ by orders of
     * magnitude — `from=` fires on every message from one sender, a body word fires
     * twice ever — and a single global learning rate either crawls on the rare features
     * or diverges on the common ones.
     *
     * @param weight >1 for a user correction. A correction is worth many passive
     *   observations: the user has looked at this and said the model was wrong.
     */
    fun learn(f: Map<Int, Double>, label: Boolean, weight: Double = 1.0) {
        val y = if (label) 1.0 else 0.0
        val err = (predict(f) - y) * weight
        for ((i, v) in f) {
            val g = err * v + l2 * weights[i]
            accum[i] += g * g
            weights[i] -= rate * g / (sqrt(accum[i]) + 1e-8)
        }
        bias -= rate * err * 0.1
        seen++
    }

    fun learn(e: Envelope, label: Boolean, weight: Double = 1.0) = learn(features(e), label, weight)

    /**
     * Train from a whole mailbox.
     *
     * Shuffled with a fixed seed and run for a few epochs. Fixed rather than random
     * because a build has to be reproducible — two runs over the same mailbox must give
     * the same model, or a bug report about ranking is impossible to chase.
     */
    fun fit(data: List<Pair<Envelope, Boolean>>, epochs: Int = 4, seed: Long = 7L) {
        if (data.isEmpty()) return
        val cached = data.map { features(it.first) to it.second }
        val rnd = java.util.Random(seed)
        val order = MutableList(cached.size) { it }
        repeat(epochs) {
            for (i in order.indices.reversed()) {
                val j = rnd.nextInt(i + 1)
                val t = order[i]; order[i] = order[j]; order[j] = t
            }
            for (i in order) {
                val (f, y) = cached[i]
                learn(f, y)
            }
        }
    }

    /** Mean log-loss. Lower is better; 0.693 is a coin flip. */
    fun logLoss(data: List<Pair<Envelope, Boolean>>): Double {
        if (data.isEmpty()) return 0.0
        var s = 0.0
        for ((e, y) in data) {
            val p = predict(e).coerceIn(1e-9, 1 - 1e-9)
            s += if (y) -ln(p) else -ln(1 - p)
        }
        return s / data.size
    }

    fun accuracy(data: List<Pair<Envelope, Boolean>>): Double {
        if (data.isEmpty()) return 0.0
        return data.count { (e, y) -> (predict(e) >= 0.5) == y }.toDouble() / data.size
    }

    /* -------------------------------------------------------------- persistence */

    /**
     * Serialize. Only non-zero weights are written — a model trained on a few hundred
     * messages touches maybe 4% of the buckets, so the file is a fraction of `dim`.
     */
    fun save(): String = buildString {
        append("bml1 ").append(dim).append(' ').append(bias).append(' ').append(seen).append('\n')
        for (i in weights.indices) {
            if (abs(weights[i]) > 1e-7) {
                append(i).append(' ').append(weights[i]).append(' ').append(accum[i]).append('\n')
            }
        }
    }

    fun load(text: String): Boolean {
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext()) return false
        val head = lines.next().split(' ')
        if (head.size < 4 || head[0] != "bml1") return false
        if (head[1].toIntOrNull() != dim) return false      // a resized model is not this model
        bias = head[2].toDoubleOrNull() ?: return false
        seen = head[3].toLongOrNull() ?: 0L
        weights = DoubleArray(dim)
        accum = DoubleArray(dim)
        while (lines.hasNext()) {
            val p = lines.next().split(' ')
            if (p.size < 3) continue
            val i = p[0].toIntOrNull() ?: continue
            if (i !in 0 until dim) continue
            weights[i] = p[1].toDoubleOrNull() ?: 0.0
            accum[i] = p[2].toDoubleOrNull() ?: 0.0
        }
        return true
    }

    /** How many buckets carry weight. Diagnostic for the CHECK page. */
    fun activeWeights(): Int = weights.count { abs(it) > 1e-7 }
}
