package com.gios.brightmailbox.mail

import com.gios.brightmailbox.auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Outlook and Microsoft 365 over Graph.
 *
 * Graph is a friendlier API than Gmail's — JSON in, JSON out, no MIME to assemble — but
 * it hides the internet headers by default, and the sorter lives on those. Asking for
 * `internetMessageHeaders` in $select is what makes the whole Letters/Notices split work
 * on an Outlook account; without it every message looks header-less and lands in Letters.
 */
class Graph(
    override val accountId: String,
    private val auth: AuthManager,
    private val http: OkHttpClient,
) : MailService {

    private val base = "https://graph.microsoft.com/v1.0/me"

    private val select = listOf(
        "id", "conversationId", "subject", "bodyPreview", "receivedDateTime",
        "isRead", "from", "toRecipients", "ccRecipients", "hasAttachments",
        "internetMessageId", "internetMessageHeaders",
    ).joinToString(",")

    override suspend fun list(limit: Int, pageToken: String?): Pair<List<Message>, String?> {
        // pageToken is Graph's full @odata.nextLink, so it already carries $select etc.
        val url = pageToken
            ?: "$base/mailFolders/inbox/messages?\$top=$limit&\$select=$select&\$orderby=receivedDateTime desc"
        val page = getJson(url)
        val arr = page.optJSONArray("value") ?: JSONArray()
        val out = ArrayList<Message>(arr.length())
        for (i in 0 until arr.length()) out.add(parse(arr.getJSONObject(i)))
        return out to page.optString("@odata.nextLink").ifBlank { null }
    }

    private fun parse(m: JSONObject): Message {
        val headers = HashMap<String, String>()
        m.optJSONArray("internetMessageHeaders")?.let { arr ->
            for (i in 0 until arr.length()) {
                val h = arr.getJSONObject(i)
                val k = h.optString("name").lowercase()
                val v = h.optString("value")
                headers[k] = headers[k]?.plus(", ")?.plus(v) ?: v
            }
        }
        val fromObj = m.optJSONObject("from")?.optJSONObject("emailAddress")
        return Message(
            id = m.optString("id"),
            threadId = m.optString("conversationId"),
            accountId = accountId,
            from = fromObj?.optString("address").orEmpty().lowercase(),
            fromName = fromObj?.optString("name").orEmpty()
                .ifBlank { Addr.name(fromObj?.optString("address").orEmpty()) },
            to = recipients(m.optJSONArray("toRecipients")),
            cc = recipients(m.optJSONArray("ccRecipients")),
            subject = m.optString("subject"),
            snippet = m.optString("bodyPreview"),
            receivedAt = parseTime(m.optString("receivedDateTime")),
            unread = !m.optBoolean("isRead", false),
            headers = headers,
            messageId = m.optString("internetMessageId").ifBlank { null },
            references = headers["references"],
            hasAttachments = m.optBoolean("hasAttachments", false),
        )
    }

    private fun recipients(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull {
            a.optJSONObject(it)?.optJSONObject("emailAddress")?.optString("address")
                ?.lowercase()?.takeIf { s -> s.contains('@') }
        }
    }

    private fun parseTime(s: String): Long = try {
        Instant.parse(s).toEpochMilli()
    } catch (_: DateTimeParseException) {
        System.currentTimeMillis()
    }

    override suspend fun content(id: String): Content {
        val m = getJson("$base/messages/$id?\$select=body,hasAttachments")
        val body = m.optJSONObject("body")
        val type = body?.optString("contentType").orEmpty().lowercase()
        val text = body?.optString("content").orEmpty()
        val attach = if (m.optBoolean("hasAttachments", false)) {
            val a = runCatching { getJson("$base/messages/$id/attachments?\$select=name") }.getOrNull()
            a?.optJSONArray("value")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
            } ?: emptyList()
        } else {
            emptyList()
        }
        return if (type == "html") Content(null, text, attach) else Content(text, null, attach)
    }

    override suspend fun markRead(ids: List<String>) {
        // Graph has no batchModify. $batch takes 20 per request, which is why this
        // chunks rather than firing one PATCH per message on a metered connection.
        ids.chunked(20).forEach { chunk ->
            val requests = JSONArray()
            chunk.forEachIndexed { i, id ->
                requests.put(
                    JSONObject()
                        .put("id", (i + 1).toString())
                        .put("method", "PATCH")
                        .put("url", "/me/messages/$id")
                        .put("headers", JSONObject().put("Content-Type", "application/json"))
                        .put("body", JSONObject().put("isRead", true)),
                )
            }
            postJson("https://graph.microsoft.com/v1.0/\$batch", JSONObject().put("requests", requests))
        }
    }

    override suspend fun archive(ids: List<String>) {
        ids.chunked(20).forEach { chunk ->
            val requests = JSONArray()
            chunk.forEachIndexed { i, id ->
                requests.put(
                    JSONObject()
                        .put("id", (i + 1).toString())
                        .put("method", "POST")
                        .put("url", "/me/messages/$id/move")
                        .put("headers", JSONObject().put("Content-Type", "application/json"))
                        .put("body", JSONObject().put("destinationId", "archive")),
                )
            }
            postJson("https://graph.microsoft.com/v1.0/\$batch", JSONObject().put("requests", requests))
        }
    }

    override suspend fun send(msg: Outgoing) {
        fun recips(list: List<String>) = JSONArray().apply {
            list.forEach { put(JSONObject().put("emailAddress", JSONObject().put("address", it))) }
        }
        val message = JSONObject()
            .put("subject", msg.subject)
            .put("body", JSONObject().put("contentType", "Text").put("content", msg.body))
            .put("toRecipients", recips(msg.to))
        if (msg.cc.isNotEmpty()) message.put("ccRecipients", recips(msg.cc))

        // A reply must go through /reply to stay in the conversation. Sending a fresh
        // message with In-Reply-To set threads correctly for the recipient but leaves a
        // detached copy in the user's own Outlook, which reads as a bug from their side.
        if (msg.inReplyTo != null && msg.threadId != null) {
            val original = findInConversation(msg.threadId)
            if (original != null) {
                postJson(
                    "$base/messages/$original/reply",
                    JSONObject().put("message", JSONObject().put("body", message.getJSONObject("body"))),
                )
                return
            }
        }
        postJson(
            "$base/sendMail",
            JSONObject().put("message", message).put("saveToSentItems", true),
        )
    }

    private suspend fun findInConversation(conversationId: String): String? = runCatching {
        val q = "$base/messages?\$filter=conversationId eq '$conversationId'" +
            "&\$top=1&\$select=id&\$orderby=receivedDateTime desc"
        getJson(q).optJSONArray("value")?.optJSONObject(0)?.optString("id")?.ifBlank { null }
    }.getOrNull()

    override suspend fun sentTo(limit: Int): List<String> {
        val url = "$base/mailFolders/sentitems/messages?\$top=$limit&\$select=toRecipients,ccRecipients"
        val arr = getJson(url).optJSONArray("value") ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            out.addAll(recipients(m.optJSONArray("toRecipients")))
            out.addAll(recipients(m.optJSONArray("ccRecipients")))
        }
        return out.toList()
    }

    /* ------------------------------------------------------------------ plumbing */

    private suspend fun getJson(url: String): JSONObject = call(Request.Builder().url(url).get())

    private suspend fun postJson(url: String, body: JSONObject): JSONObject =
        call(Request.Builder().url(url).post(body.toString().toRequestBody(JSON)))

    private suspend fun call(b: Request.Builder): JSONObject = withContext(Dispatchers.IO) {
        repeat(2) { attempt ->
            val req = b.header("Authorization", "Bearer " + auth.token(accountId)).build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.isSuccessful) {
                    return@withContext if (text.isBlank()) JSONObject() else JSONObject(text)
                }
                if (r.code == 401 && attempt == 0) {
                    auth.invalidate(accountId)
                } else {
                    throw IOException("graph HTTP ${r.code}: ${text.take(200)}")
                }
            }
        }
        throw IOException("graph: unauthorized after refresh")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
