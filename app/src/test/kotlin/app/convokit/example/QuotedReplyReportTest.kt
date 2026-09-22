package app.convokit.example

import app.convokit.sdk.ConvoKitException
import app.convokit.sdk.Message
import app.convokit.sdk.MessageContextPage
import app.convokit.sdk.ReplyPreview
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.*
import org.junit.Test

/** Pins what the host tells the reader about each answer of the 0.9 core surface. */
class QuotedReplyReportTest {

    @Test fun `a resolved preview names the quoted author and its truncated text`() {
        val line = QuotedReplyReport.describe(preview("m-1", text = "Let's ship it", truncated = false))
        assertEquals("maya said \"Let's ship it\"", line)
    }

    @Test fun `a truncated preview says where the text was cut`() {
        val line = QuotedReplyReport.describe(preview("m-1", text = "A very long note", truncated = true))
        assertTrue(line, line.endsWith("cut at 500 characters"))
    }

    @Test fun `a media-only preview is described by its attachment count`() {
        val line = QuotedReplyReport.describe(preview("m-1", text = null, mediaCount = 2))
        assertEquals("maya said 2 attachments and no text", line)
    }

    @Test fun `an edited preview says so, because a preview re-read shows the new text`() {
        val line = QuotedReplyReport.describe(preview("m-1", text = "Fixed", revision = 3))
        assertTrue(line, line.endsWith(", edited since"))
    }

    @Test fun `the page's references are resolved in one call and de-duplicated`() {
        val line = QuotedReplyReport.previews(
            requested = listOf("m-1", "m-1", "m-2"),
            resolved = listOf(preview("m-1", text = "Let's ship it"), preview("m-2", text = "Agreed")),
        )
        assertTrue(line, line.startsWith("Resolve the page's quoted rows: 2 of 2 references"))
        assertTrue(line, line.contains("(3 rows quoted, de-duplicated) in one call"))
    }

    @Test fun `a reference with no preview reads as unavailable, never as an error`() {
        val line = QuotedReplyReport.previews(
            requested = listOf("m-1", "m-gone"),
            resolved = listOf(preview("m-1", text = "Let's ship it")),
        )
        assertTrue(line, line.contains("1 unavailable"))
        assertTrue(line, line.contains("\"Original message unavailable\""))
    }

    @Test fun `a centred window reports both cursors, and a null newer cursor as the tail`() {
        val line = QuotedReplyReport.window(
            MessageContextPage(
                messages = listOf(message("m-2"), message("m-1")),
                olderCursor = "opaque-older",
                newerCursor = null,
            ),
            messageId = "m-1",
        )
        assertTrue(line, line.startsWith("Centre a window on the quote: 2 messages newest first, centred on the target."))
        assertTrue(line, line.contains("Older paging continues"))
        assertTrue(line, line.contains("newer paging touched the newest message as of the query"))
    }

    @Test fun `a window at the start of history says so`() {
        val line = QuotedReplyReport.window(
            MessageContextPage(messages = listOf(message("m-1")), olderCursor = null, newerCursor = "opaque-newer"),
            messageId = "m-1",
        )
        assertTrue(line, line.contains("Older paging is at the start of the conversation"))
        assertTrue(line, line.contains("newer paging continues"))
    }

    @Test fun `a gone target is the coded message 404, which is proof the row is not there`() = runBlocking {
        val line = QuotedReplyReport.expecting("Jump", "MESSAGE_NOT_FOUND") {
            throw ConvoKitException("Message not found", code = "MESSAGE_NOT_FOUND", status = 404)
        }
        assertTrue(line, line.startsWith("Jump: MESSAGE_NOT_FOUND — "))
        assertTrue(line, line.contains("\"Original message unavailable\""))
    }

    @Test fun `a room you are not in is the coded conversation 404, decided before any id is read`() = runBlocking {
        val line = QuotedReplyReport.expecting("Previews", "CONVERSATION_NOT_FOUND") {
            throw ConvoKitException("Conversation not found", code = "CONVERSATION_NOT_FOUND", status = 404)
        }
        assertTrue(line, line.startsWith("Previews: CONVERSATION_NOT_FOUND — "))
        assertTrue(line, line.contains("membership is checked before any message id is read"))
    }

    @Test fun `an uncoded 404 is an older backend, never a missing message`() {
        val line = QuotedReplyReport.failure(
            "Previews",
            ConvoKitException("ConvoKit request failed with status 404", code = "HTTP_ERROR", status = 404),
        )
        assertTrue(line, line.contains("older than 0.9 and has neither route"))
        assertTrue(line, line.contains("never proof that a message is gone"))
    }

    @Test fun `an uncoded 404 from a route that predates 0_9 is membership, not an older backend`() {
        val line = QuotedReplyReport.legacyFailure(
            "Send",
            ConvoKitException("Conversation not found", code = "HTTP_ERROR", status = 404),
        )
        assertFalse(line, line.contains("older than 0.9"))
        assertTrue(line, line.contains("membership lapsed"))
        assertTrue(line, line.contains("Keep the reply and jump affordances"))
    }

    @Test fun `a coded failure from a route that predates 0_9 still reads as its code`() {
        val line = QuotedReplyReport.legacyFailure(
            "Send",
            ConvoKitException("Message not found", code = "MESSAGE_NOT_FOUND", status = 404),
        )
        assertEquals("Send: MESSAGE_NOT_FOUND (404) — Message not found", line)
    }

    @Test fun `a coded failure keeps the code and status the host should branch on`() {
        val line = QuotedReplyReport.failure(
            "Quote",
            ConvoKitException("Message not found", code = "MESSAGE_NOT_FOUND", status = 404),
        )
        assertEquals("Quote: MESSAGE_NOT_FOUND (404) — Message not found", line)
    }

    @Test fun `an unexpected success is reported rather than passing silently`() = runBlocking {
        val line = QuotedReplyReport.expecting("Jump", "MESSAGE_NOT_FOUND") { }
        assertEquals("Jump: succeeded, but MESSAGE_NOT_FOUND was expected.", line)
    }

    @Test fun `a reply with no reference reads as none`() {
        assertEquals("none", QuotedReplyReport.shortId(null))
        assertEquals("00000000…", QuotedReplyReport.shortId("00000000-0000-4000-8000-000000000000"))
    }

    private fun preview(
        id: String,
        text: String?,
        truncated: Boolean = false,
        revision: Int = 0,
        mediaCount: Int = 0,
    ) = ReplyPreview(
        id = id,
        conversationId = ROOM,
        senderId = "maya",
        text = text,
        textTruncated = truncated,
        createdAt = CREATED_AT,
        revision = revision,
        mediaCount = mediaCount,
    )

    private fun message(id: String) = Message(
        id = id,
        conversationId = ROOM,
        senderId = "maya",
        text = "hello",
        media = emptyList(),
        createdAt = CREATED_AT,
        updatedAt = null,
    )

    private companion object {
        const val ROOM = "room-1"
        val CREATED_AT: Instant = Instant.parse("2026-09-22T10:00:00Z")
    }
}
