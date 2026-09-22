package app.convokit.example

import app.convokit.sdk.ConvoKitClient
import app.convokit.sdk.ConvoKitException
import app.convokit.sdk.Message
import app.convokit.sdk.MessageContextPage
import app.convokit.sdk.ReplyPreview
import app.convokit.sdk.SendMessageInput

/**
 * The four core calls the probe makes, as a host-owned seam.
 *
 * [ConvoKitClient] is final with an internal constructor, so no test can stand
 * one in. Typing the probe against this interface is what lets the rules the
 * sample is here to demonstrate — the reference travelling on the send, one
 * preview call for a whole page — be pinned by tests rather than only by prose.
 */
internal interface ReplySurface {

    suspend fun getMessages(conversationId: String, limit: Int): List<Message>

    suspend fun sendMessage(input: SendMessageInput): Message

    suspend fun getReplyPreviews(conversationId: String, messageIds: List<String>): List<ReplyPreview>

    suspend fun getMessageContext(conversationId: String, messageId: String, limit: Int): MessageContextPage
}

/** The live surface: every call goes straight to the 0.9 core client. */
internal class ConvoKitReplySurface(private val convoKit: ConvoKitClient) : ReplySurface {

    override suspend fun getMessages(conversationId: String, limit: Int): List<Message> =
        convoKit.getMessages(conversationId, limit = limit)

    override suspend fun sendMessage(input: SendMessageInput): Message = convoKit.sendMessage(input)

    override suspend fun getReplyPreviews(conversationId: String, messageIds: List<String>): List<ReplyPreview> =
        convoKit.getReplyPreviews(conversationId, messageIds)

    override suspend fun getMessageContext(conversationId: String, messageId: String, limit: Int): MessageContextPage =
        convoKit.getMessageContext(conversationId, messageId = messageId, limit = limit)
}

/**
 * The 0.9 core surface, driven by the host instead of the UI package: quoting a
 * message on send, resolving the quoted rows behind a whole rendered page with
 * one call, and centring a bounded window on a message so a jump never
 * downloads history.
 *
 * The embedded room already does all of this for the user. This probe exists so
 * the sample shows the calls themselves, their argument rules and the two 404s
 * a host has to tell apart, without maintaining a second message renderer.
 */
internal class QuotedReplyProbe(private val core: ReplySurface) {

    /** Runs every step against [conversationId] and returns the report, one entry per step. */
    suspend fun run(conversationId: String): List<String> {
        val page = try {
            core.getMessages(conversationId, limit = PAGE_LIMIT)
        } catch (cause: ConvoKitException) {
            return listOf(QuotedReplyReport.legacyFailure(READ_HISTORY, cause))
        }
        val target = page.firstOrNull() ?: return listOf(EMPTY_ROOM)
        return listOf(
            quoteNewestMessage(conversationId, target),
            resolvePagePreviews(conversationId),
            centreWindowOnQuote(conversationId, target.id),
            centreWindowOnMissingMessage(conversationId),
            resolvePreviewsInUnjoinedRoom(),
        )
    }

    /**
     * The reference travels on the send input and is written once: no edit can
     * move it and deleting the quoted message leaves it in place.
     */
    private suspend fun quoteNewestMessage(conversationId: String, target: Message): String = try {
        val reply = core.sendMessage(
            SendMessageInput(
                conversationId = conversationId,
                text = REPLY_TEXT,
                replyToMessageId = target.id,
            ),
        )
        "$QUOTE_NEWEST: the reply came back carrying replyToMessageId " +
            "${QuotedReplyReport.shortId(reply.replyToMessageId)}, which no later edit can move."
    } catch (cause: ConvoKitException) {
        // Sending predates 0.9, so an uncoded 404 here is the shipped membership answer.
        QuotedReplyReport.legacyFailure(QUOTE_NEWEST, cause)
    }

    /**
     * One call for the whole page, never one per row: the SDK de-duplicates the
     * ids in first-seen order and splits them into requests of at most 50.
     */
    private suspend fun resolvePagePreviews(conversationId: String): String {
        val quoted = try {
            core.getMessages(conversationId, limit = PAGE_LIMIT).mapNotNull { it.replyToMessageId }
        } catch (cause: ConvoKitException) {
            // Still the pre-0.9 history route; only the preview call below is new.
            return QuotedReplyReport.legacyFailure(RE_READ_PAGE, cause)
        }
        if (quoted.isEmpty()) {
            return "$RESOLVE_PREVIEWS: no row on this page quotes another, so no request was made."
        }
        return try {
            QuotedReplyReport.previews(quoted, core.getReplyPreviews(conversationId, quoted))
        } catch (cause: ConvoKitException) {
            QuotedReplyReport.failure(RESOLVE_PREVIEWS, cause)
        }
    }

    /** The window a jump-to-message loads: centred on the target, newest first. */
    private suspend fun centreWindowOnQuote(conversationId: String, messageId: String): String = try {
        QuotedReplyReport.window(
            core.getMessageContext(conversationId, messageId = messageId, limit = WINDOW_LIMIT),
            messageId,
        )
    } catch (cause: ConvoKitException) {
        QuotedReplyReport.failure(CENTRE_WINDOW, cause)
    }

    /** A target that is gone answers the coded 404, the only proof the row is not there. */
    private suspend fun centreWindowOnMissingMessage(conversationId: String): String =
        QuotedReplyReport.expecting(MISSING_TARGET, "MESSAGE_NOT_FOUND") {
            core.getMessageContext(conversationId, messageId = ABSENT_MESSAGE_ID, limit = DEFAULT_WINDOW_LIMIT)
        }

    /** Membership is decided before any message id is read, and answers its own code. */
    private suspend fun resolvePreviewsInUnjoinedRoom(): String =
        QuotedReplyReport.expecting(UNJOINED_ROOM, "CONVERSATION_NOT_FOUND") {
            core.getReplyPreviews(UNJOINED_CONVERSATION_ID, listOf(ABSENT_MESSAGE_ID))
        }

    private companion object {
        const val PAGE_LIMIT = 30
        const val WINDOW_LIMIT = 10

        /** The core's own default window size, so this step changes no argument. */
        const val DEFAULT_WINDOW_LIMIT = 30
        const val REPLY_TEXT = "Quoting the newest message from the Android SDK sample."

        /** A well-formed ID that no room contains, so the backend answers its own 404. */
        const val ABSENT_MESSAGE_ID = "00000000-0000-4000-8000-000000000000"

        /** A room this user is not an active member of, which is decided before any ID is read. */
        const val UNJOINED_CONVERSATION_ID = "00000000-0000-4000-8000-0000000000ff"

        const val READ_HISTORY = "Read history"
        const val QUOTE_NEWEST = "Quote the newest message"
        const val RE_READ_PAGE = "Re-read the page for its quoted rows"
        const val RESOLVE_PREVIEWS = "Resolve the page's quoted rows"
        const val CENTRE_WINDOW = "Centre a window on the quote"
        const val MISSING_TARGET = "Centre a window on a message that is gone"
        const val UNJOINED_ROOM = "Resolve previews in a room you are not in"
        const val EMPTY_ROOM = "$READ_HISTORY: the room has no messages yet, so there is nothing to quote."
    }
}

/**
 * Host prose for what the 0.9 core surface answered. Kept free of the client and
 * of Android so the wording — and, more importantly, which error code means
 * what — can be pinned by host tests.
 */
internal object QuotedReplyReport {

    /** Enough of an ID to recognise it in a report without filling the panel. */
    fun shortId(id: String?): String = id?.take(SHORT_ID_LENGTH)?.plus("…") ?: "none"

    /** What one quoted row says above its reply. */
    fun describe(preview: ReplyPreview): String {
        val body = when {
            preview.text != null ->
                "\"${preview.text}\"" + if (preview.textTruncated) ", cut at 500 characters" else ""
            preview.mediaCount > 0 -> "${count(preview.mediaCount, "attachment")} and no text"
            else -> "no text"
        }
        val edited = if (preview.revision > 0) ", edited since" else ""
        return "${preview.senderId} said $body$edited"
    }

    /**
     * Absence from a resolved call is the only deletion signal: the reference
     * stays on the reply and the row reads "Original message unavailable".
     */
    fun previews(requested: List<String>, resolved: List<ReplyPreview>): String {
        val distinct = requested.distinct()
        val byId = resolved.associateBy { it.id }
        val unavailable = distinct.count { it !in byId }
        val first = distinct.firstNotNullOfOrNull { byId[it] }
        return buildString {
            append("Resolve the page's quoted rows: ${resolved.size} of ${count(distinct.size, "reference")}")
            if (requested.size != distinct.size) append(" (${count(requested.size, "row")} quoted, de-duplicated)")
            append(" in one call")
            if (first != null) append(" — ${describe(first)}")
            append(".")
            if (unavailable > 0) {
                append(" $unavailable unavailable: absence from a resolved call is the only")
                append(" deletion signal, so the reference stays and the row reads")
                append(" \"Original message unavailable\".")
            }
        }
    }

    /** Both cursors are always present and nullable; a null newer cursor is a hint, not a lock. */
    fun window(page: MessageContextPage, messageId: String): String {
        val centred = page.messages.count { it.id == messageId } == 1
        return buildString {
            append("Centre a window on the quote: ${count(page.messages.size, "message")} newest first")
            append(if (centred) ", centred on the target" else ", but the target is not in it")
            append(". Older paging ")
            append(if (page.olderCursor != null) "continues" else "is at the start of the conversation")
            append(", newer paging ")
            append(if (page.newerCursor != null) "continues" else "touched the newest message as of the query")
            append(".")
        }
    }

    /** Runs a call that is meant to fail, and says whether the promised code came back. */
    suspend fun expecting(step: String, code: String, call: suspend () -> Unit): String = try {
        call()
        "$step: succeeded, but $code was expected."
    } catch (cause: ConvoKitException) {
        if (cause.code == code) "$step: $code — ${explain(code)}" else failure(step, cause)
    }

    /**
     * How a host should read a failure from one of the **two routes 0.9 adds**.
     * Only there is an uncoded 404 evidence of the backend, because only there
     * does an older backend answer Express's unmatched-route 404.
     */
    fun failure(step: String, cause: ConvoKitException): String =
        if (isUncodedNotFound(cause)) {
            "$step: 404 without a code — this backend is older than 0.9 and has neither route." +
                " Hide the reply and jump affordances instead of retrying; an uncoded 404 is" +
                " never proof that a message is gone."
        } else {
            coded(step, cause)
        }

    /**
     * The same failure from a route every backend already has — reading history,
     * sending a message. Those answer an uncoded 404 for a caller who is not an
     * active member, so here it says nothing at all about the backend's version.
     */
    fun legacyFailure(step: String, cause: ConvoKitException): String =
        if (isUncodedNotFound(cause)) {
            "$step: 404 without a code — this route is not one of the two 0.9 adds, so this is" +
                " the room being unreadable for this user (membership lapsed, or the room is" +
                " gone), not a backend that lacks the route. Keep the reply and jump affordances."
        } else {
            coded(step, cause)
        }

    private fun isUncodedNotFound(cause: ConvoKitException): Boolean =
        cause.code == "HTTP_ERROR" && cause.status == NOT_FOUND

    private fun coded(step: String, cause: ConvoKitException): String =
        "$step: ${cause.code}${cause.status?.let { " ($it)" }.orEmpty()} — ${cause.message}"

    private fun explain(code: String): String = when (code) {
        "MESSAGE_NOT_FOUND" ->
            "the target does not exist, was deleted or belongs to another room; keep the" +
                " reference and render \"Original message unavailable\"."
        "CONVERSATION_NOT_FOUND" ->
            "membership is checked before any message id is read, so a room you cannot see" +
                " never tells you which messages exist in it."
        else -> "the backend refused the call."
    }

    private fun count(value: Int, noun: String): String = "$value $noun" + if (value == 1) "" else "s"

    private const val SHORT_ID_LENGTH = 8
    private const val NOT_FOUND = 404
}
