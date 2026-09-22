package app.convokit.example

import app.convokit.sdk.ConvoKitException
import app.convokit.sdk.Message
import app.convokit.sdk.MessageContextPage
import app.convokit.sdk.ReplyPreview
import app.convokit.sdk.SendMessageInput
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.*
import org.junit.Test

/**
 * Pins what the host panel actually asks the 0.9 core for. The client is final
 * with an internal constructor, so the probe is typed against [ReplySurface] and
 * this fake records every call.
 */
class QuotedReplyProbeTest {

    @Test fun `the send quotes the newest row and no edit can move the reference`() {
        val core = FakeReplySurface(page = quotingPage())
        val report = runBlocking { QuotedReplyProbe(core).run(ROOM) }

        val sent = core.sent.single()
        assertEquals(ROOM, sent.conversationId)
        assertEquals("m-4", sent.replyToMessageId)
        assertTrue(report[0], report[0].startsWith("Quote the newest message: the reply came back carrying"))
    }

    @Test fun `a whole page of references costs exactly one preview call, never one per row`() {
        val core = FakeReplySurface(
            page = quotingPage(),
            previews = listOf(preview("m-1"), preview("m-2")),
        )
        val report = runBlocking { QuotedReplyProbe(core).run(ROOM) }

        assertEquals(listOf(listOf("m-1", "m-2", "m-1")), core.previewRequestsFor(ROOM))
        assertTrue(report[1], report[1].contains("(3 rows quoted, de-duplicated) in one call"))
    }

    @Test fun `an empty room stops before sending anything`() {
        val core = FakeReplySurface(page = emptyList())
        val report = runBlocking { QuotedReplyProbe(core).run(ROOM) }

        assertEquals(listOf("Read history: the room has no messages yet, so there is nothing to quote."), report)
        assertEquals(listOf("getMessages"), core.calls)
        assertTrue(core.sent.isEmpty())
    }

    @Test fun `a page with no quoted rows issues no preview request`() {
        val core = FakeReplySurface(page = listOf(message("m-1")))
        val report = runBlocking { QuotedReplyProbe(core).run(ROOM) }

        assertTrue(core.previewRequestsFor(ROOM).isEmpty())
        assertEquals("Resolve the page's quoted rows: no row on this page quotes another, so no request was made.", report[1])
    }

    @Test fun `the window is centred on the quoted target and bounded`() {
        val core = FakeReplySurface(
            page = quotingPage(),
            context = MessageContextPage(listOf(message("m-4")), olderCursor = "older", newerCursor = null),
        )
        val report = runBlocking { QuotedReplyProbe(core).run(ROOM) }

        assertEquals("m-4" to 10, core.contextRequests.first())
        assertTrue(report[2], report[2].startsWith("Centre a window on the quote: 1 message newest first, centred on the target."))
    }

    @Test fun `every step is reported once, in order, including the two 404s a host tells apart`() {
        val core = FakeReplySurface(page = quotingPage(), previews = listOf(preview("m-1"), preview("m-2")))
        val report = runBlocking { QuotedReplyProbe(core).run(ROOM) }

        assertEquals(5, report.size)
        assertTrue(report[3], report[3].startsWith("Centre a window on a message that is gone: MESSAGE_NOT_FOUND — "))
        assertTrue(report[4], report[4].startsWith("Resolve previews in a room you are not in: CONVERSATION_NOT_FOUND — "))
    }

    @Test fun `an uncoded 404 reading history is a membership failure, never an older backend`() {
        val core = FakeReplySurface(page = quotingPage(), historyFailure = uncoded404())
        val report = runBlocking { QuotedReplyProbe(core).run(ROOM) }

        val line = report.single()
        assertFalse(line, line.contains("older than 0.9"))
        assertTrue(line, line.startsWith("Read history: 404 without a code"))
        assertTrue(line, line.contains("membership lapsed"))
        assertTrue(line, line.contains("Keep the reply and jump affordances"))
    }

    @Test fun `an uncoded 404 on the send is a membership failure, never an older backend`() {
        val core = FakeReplySurface(page = quotingPage(), sendFailure = uncoded404())
        val report = runBlocking { QuotedReplyProbe(core).run(ROOM) }

        assertFalse(report[0], report[0].contains("older than 0.9"))
        assertTrue(report[0], report[0].startsWith("Quote the newest message: 404 without a code"))
    }

    @Test fun `an uncoded 404 from the previews route is the older backend, because that route is new`() {
        val core = FakeReplySurface(page = quotingPage(), previewFailure = uncoded404())
        val report = runBlocking { QuotedReplyProbe(core).run(ROOM) }

        assertTrue(report[1], report[1].startsWith("Resolve the page's quoted rows: 404 without a code"))
        assertTrue(report[1], report[1].contains("older than 0.9 and has neither route"))
    }

    private fun quotingPage() = listOf(
        message("m-4", replyToMessageId = "m-1"),
        message("m-3", replyToMessageId = "m-2"),
        message("m-2", replyToMessageId = "m-1"),
        message("m-1"),
    )

    private fun uncoded404() =
        ConvoKitException("ConvoKit request failed with status 404", code = "HTTP_ERROR", status = 404)

    /** Records every call the probe makes and answers the two deliberate failures by argument. */
    private class FakeReplySurface(
        private val page: List<Message> = emptyList(),
        private val previews: List<ReplyPreview> = emptyList(),
        private val context: MessageContextPage = MessageContextPage(emptyList()),
        private val historyFailure: ConvoKitException? = null,
        private val sendFailure: ConvoKitException? = null,
        private val previewFailure: ConvoKitException? = null,
    ) : ReplySurface {
        val calls = mutableListOf<String>()
        val sent = mutableListOf<SendMessageInput>()
        val previewRequests = mutableListOf<Pair<String, List<String>>>()
        val contextRequests = mutableListOf<Pair<String, Int>>()

        /** Every preview call made against [conversationId], in order — one per page load, never one per row. */
        fun previewRequestsFor(conversationId: String): List<List<String>> =
            previewRequests.filter { it.first == conversationId }.map { it.second }

        override suspend fun getMessages(conversationId: String, limit: Int): List<Message> {
            calls += "getMessages"
            historyFailure?.let { throw it }
            return page
        }

        override suspend fun sendMessage(input: SendMessageInput): Message {
            calls += "sendMessage"
            sent += input
            sendFailure?.let { throw it }
            return message(input.conversationId + "-reply", replyToMessageId = input.replyToMessageId)
        }

        override suspend fun getReplyPreviews(conversationId: String, messageIds: List<String>): List<ReplyPreview> {
            calls += "getReplyPreviews"
            previewRequests += conversationId to messageIds
            // Membership is decided before any id is read, so a room the user is not in answers first.
            if (conversationId != ROOM) {
                throw ConvoKitException("Conversation not found", code = "CONVERSATION_NOT_FOUND", status = 404)
            }
            previewFailure?.let { throw it }
            return previews.filter { it.id in messageIds }
        }

        override suspend fun getMessageContext(conversationId: String, messageId: String, limit: Int): MessageContextPage {
            calls += "getMessageContext"
            contextRequests += messageId to limit
            if (messageId !in page.map { it.id }) {
                throw ConvoKitException("Message not found", code = "MESSAGE_NOT_FOUND", status = 404)
            }
            return context
        }
    }

    private companion object {
        const val ROOM = "room-1"
        val CREATED_AT: Instant = Instant.parse("2026-09-22T10:00:00Z")

        fun message(id: String, replyToMessageId: String? = null) = Message(
            id = id,
            conversationId = ROOM,
            senderId = "maya",
            text = "hello",
            media = emptyList(),
            createdAt = CREATED_AT,
            updatedAt = null,
            replyToMessageId = replyToMessageId,
        )

        fun preview(id: String) = ReplyPreview(
            id = id,
            conversationId = ROOM,
            senderId = "maya",
            text = "Let's ship it",
            createdAt = CREATED_AT,
        )
    }
}
