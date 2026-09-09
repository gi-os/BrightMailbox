package com.gios.brightmailbox.mail

import android.util.Base64
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

/**
 * Gmail over its REST API.
 *
 * REST rather than IMAP because Jakarta/Angus Mail targets Java SE — java.beans, mailcap
 * resolution — and the last real Android port of JavaMail died around 2017. Four
 * endpoints over OkHttp instead of a dependency that does not run here.
 *
 * REST rather than the official client library because google-api-client drags in half
 * of GAX for four HTTP calls.
 */
class Gmail(
    override val accountId: String,
    private val auth: AuthManager,
    private val http: OkHttpClient,
) : MailService {

    private val base = "https://gmail.googleapis.com/gmail/v1/users/me"

    override suspend fun list(limit: Int, pageToken: String?): Pair<List<Message>, String?> {
        val url = StringBuilder("$base/messages?maxResults=$limit&q=in:inbox")
        pageToken?.let { url.append("&pageToken=").append(it) }
        val page = getJson(url.toString())
        val ids = page.optJSONArray("messages") ?: JSONArray()

        // metadataHeaders keeps the response small: the sorter needs headers, and the
        // body can wait until the message is actually opened. Fetching full bodies for a
        // whole inbox is what makes a first sync take four minutes instead of forty.
        val wanted = listOf(
            "From", "To", "Cc", "Subject", "Date", "Message-ID", "References",
            "List-Unsubscribe", "List-Id", "List-Post", "Precedence", "Auto-Submitted",
            "X-Auto-Response-Suppress", "Return-Path", "Feedback-ID", "X-Campaign-Id",
            "X-Mailchimp-Id", "X-SES-Outgoing", "X-SG-EID", "X-PM-Message-Id",
            "X-Mandrill-User",
        ).joinToString("") { "&metadataHeaders=$it" }

        val out = ArrayList<Message>(ids.length())
        for (i in 0 until ids.length()) {
            val id = ids.getJSONObject(i).optString("id").ifBlank { continue }
            val m = runCatching {
                getJson("$base/messages/$id?format=metadata$wanted")
            }.getOrNull() ?: continue
            out.add(parse(m))
        }
        return out to page.optString("nextPageToken").ifBlank { null }
    }

    private fun parse(m: JSONObject): Message {
        val payload = m.optJSONObject("payload") ?: JSONObject()
        val headers = HashMap<String, String>()
        payload.optJSONArray("headers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val h = arr.getJSONObject(i)
                val k = h.optString("name").lowercase()
                val v = h.optString("value")
                headers[k] = headers[k]?.plus(", ")?.plus(v) ?: v
            }
        }
        val fromRaw = headers["from"].orEmpty()
        val labels = m.optJSONArray("labelIds")?.let { a ->
            (0 until a.length()).map { a.optString(it) }
        } ?: emptyList()

        return Message(
            id = m.optString("id"),
            threadId = m.optString("threadId"),
            accountId = accountId,
            from = Addr.address(fromRaw) ?: "",
            fromName = Addr.decodeWords(Addr.name(fromRaw)),
            to = Addr.addresses(headers["to"]),
            cc = Addr.addresses(headers["cc"]),
            subject = Addr.decodeWords(headers["subject"].orEmpty()),
            snippet = m.optString("snippet"),
            receivedAt = m.optString("internalDate").toLongOrNull() ?: System.currentTimeMillis(),
            unread = labels.contains("UNREAD"),
            headers = headers,
            messageId = headers["message-id"],
            references = headers["references"],
        )
    }

    override suspend fun content(id: String): Content {
        val m = getJson("$base/messages/$id?format=full")
        val payload = m.optJSONObject("payload") ?: return Content(null, null)
        var text: String? = null
        var html: String? = null
        val attach = ArrayList<String>()

        fun walk(part: JSONObject) {
            val mime = part.optString("mimeType")
            val filename = part.optString("filename")
            val data = part.optJSONObject("body")?.optString("data").orEmpty()
            if (filename.isNotBlank()) attach.add(filename)
            if (data.isNotBlank()) {
                val decoded = runCatching {
                    // Gmail uses base64URL, not standard base64. Decoding with the wrong
                    // alphabet produces mojibake in exactly the messages that contain a
                    // '-' or '_' in their encoded body, which looks like a charset bug.
                    String(Base64.decode(data, Base64.URL_SAFE), Charsets.UTF_8)
                }.getOrNull()
                if (decoded != null) {
                    when {
                        mime == "text/plain" && text == null -> text = decoded
                        mime == "text/html" && html == null -> html = decoded
                    }
                }
            }
            part.optJSONArray("parts")?.let { arr ->
                for (i in 0 until arr.length()) walk(arr.getJSONObject(i))
            }
        }
        walk(payload)
        return Content(text, html, attach)
    }

    override suspend fun markRead(ids: List<String>) {
        if (ids.isEmpty()) return
        postJson(
            "$base/messages/batchModify",
            JSONObject()
                .put("ids", JSONArray(ids))
                .put("removeLabelIds", JSONArray(listOf("UNREAD"))),
        )
    }

    override suspend fun archive(ids: List<String>) {
        if (ids.isEmpty()) return
        // Archiving in Gmail is removing INBOX, not adding a folder. Nothing is deleted.
        postJson(
            "$base/messages/batchModify",
            JSONObject()
                .put("ids", JSONArray(ids))
                .put("removeLabelIds", JSONArray(listOf("INBOX"))),
        )
    }

    override suspend fun send(msg: Outgoing) {
        val me = auth.accounts().firstOrNull { it.id == accountId }?.email.orEmpty()
        val raw = Base64.encodeToString(
            Addr.rfc5322(me, msg).toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP,
        )
        val body = JSONObject().put("raw", raw)
        msg.threadId?.let { body.put("threadId", it) }
        postJson("$base/messages/send", body)
    }

    override suspend fun sentTo(limit: Int): List<String> {
        val page = getJson("$base/messages?maxResults=$limit&q=in:sent")
        val ids = page.optJSONArray("messages") ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until ids.length()) {
            val id = ids.getJSONObject(i).optString("id").ifBlank { continue }
            val m = runCatching {
                getJson("$base/messages/$id?format=metadata&metadataHeaders=To&metadataHeaders=Cc")
            }.getOrNull() ?: continue
            m.optJSONObject("payload")?.optJSONArray("headers")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val h = arr.getJSONObject(j)
                    if (h.optString("name").lowercase() in setOf("to", "cc")) {
                        out.addAll(Addr.addresses(h.optString("value")))
                    }
                }
            }
        }
        return out.toList()
    }

    /* ------------------------------------------------------------------ plumbing */

    private suspend fun getJson(url: String): JSONObject = call(Request.Builder().url(url).get())

    private suspend fun postJson(url: String, body: JSONObject): JSONObject =
        call(Request.Builder().url(url).post(body.toString().toRequestBody(JSON)))

    /**
     * One request, with a single retry after a 401.
     *
     * A 401 here means the cached access token expired between the check and the call,
     * which happens whenever a sync runs long. Invalidating and retrying once is the
     * whole fix; retrying more would loop on a genuinely revoked grant.
     */
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
                    throw IOException("gmail HTTP ${r.code}: ${text.take(200)}")
                }
            }
        }
        throw IOException("gmail: unauthorized after refresh")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
