package app.convokit.example

import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import app.convokit.example.databinding.ActivityMainBinding
import app.convokit.sdk.ConvoKitClient
import app.convokit.sdk.ConvoKitException
import app.convokit.sdk.FileMedia
import app.convokit.sdk.ImageMedia
import app.convokit.sdk.LocationMedia
import app.convokit.sdk.Message
import app.convokit.sdk.SendMessageInput
import app.convokit.sdk.TokenProvider
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val httpClient = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val renderedMessageIds = linkedSetOf<String>()
    private var roomId: String? = null
    private var messageSubscription: Job? = null
    private var readSubscription: Job? = null

    private val convoKit by lazy {
        ConvoKitClient(
            clientId = BuildConfig.CONVOKIT_CLIENT_ID,
            tokenProvider = TokenProvider(::issueUserToken),
            httpClient = httpClient,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.joinButton.setOnClickListener { joinRoom() }
        binding.sendButton.setOnClickListener { sendMessage() }
    }

    private fun joinRoom() {
        val userId = binding.userId.text?.toString()?.trim().orEmpty()
        val nextRoomId = binding.roomId.text?.toString()?.trim().orEmpty()
        if (userId.isEmpty() || nextRoomId.isEmpty()) {
            binding.status.text = "Enter both a user ID and room ID."
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            runCatching {
                joinDemoChatroom(nextRoomId, userId)
                convoKit.connectUser(userId)
                val conversation = convoKit.getConversation(nextRoomId)
                val history = convoKit.getMessages(nextRoomId, limit = 100)
                Triple(conversation.displayTitle, history, nextRoomId)
            }.onSuccess { (title, history, connectedRoomId) ->
                roomId = connectedRoomId
                renderedMessageIds.clear()
                binding.messageList.removeAllViews()
                history.forEach(::renderMessage)
                binding.status.text = "Connected to $title"
                binding.messageInput.isEnabled = true
                binding.sendButton.isEnabled = true
                subscribe(connectedRoomId)
                binding.messageScroll.post { binding.messageScroll.fullScroll(TextView.FOCUS_DOWN) }
            }.onFailure(::showError)
            setBusy(false)
        }
    }

    private fun subscribe(conversationId: String) {
        messageSubscription?.cancel()
        readSubscription?.cancel()
        messageSubscription = lifecycleScope.launch {
            convoKit.realtime.onMessage(conversationId).collect { message ->
                renderMessage(message)
                convoKit.markConversationRead(conversationId)
            }
        }
        readSubscription = lifecycleScope.launch {
            convoKit.realtime.onReadReceipt(conversationId).collect { event ->
                binding.status.text = "${event.userId} read through ${event.readAt}"
            }
        }
    }

    private fun sendMessage() {
        val conversationId = roomId ?: return
        val text = binding.messageInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        binding.sendButton.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                convoKit.sendMessage(SendMessageInput(conversationId = conversationId, text = text))
            }.onSuccess { message ->
                binding.messageInput.text?.clear()
                renderMessage(message)
            }.onFailure(::showError)
            binding.sendButton.isEnabled = true
        }
    }

    private fun renderMessage(message: Message) {
        if (!renderedMessageIds.add(message.id)) return
        val attachments = message.media.joinToString(separator = "\n") { media ->
            when (media) {
                is ImageMedia -> "Image: ${media.name ?: media.url}"
                is FileMedia -> "File: ${media.name ?: media.url}"
                is LocationMedia -> "Location: ${media.latitude}, ${media.longitude}"
                else -> "Contact attachment"
            }
        }
        val body = listOfNotNull(message.text, attachments.takeIf(String::isNotBlank)).joinToString("\n")
        val messageView = TextView(this).apply {
            text = "${message.senderId}\n$body"
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant))
            setPadding(14.dp)
        }
        binding.messageList.addView(
            messageView,
            ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8.dp
            },
        )
        binding.messageScroll.post { binding.messageScroll.fullScroll(TextView.FOCUS_DOWN) }
    }

    private suspend fun issueUserToken(appUserId: String): String {
        val response = postJson(
            url = "${BuildConfig.DEMO_BACKEND_URL}/api/auth/token".toHttpUrl(),
            body = buildJsonObject { put("appUserId", appUserId) }.toString(),
        )
        return response["data"]?.jsonObject?.get("token")?.jsonPrimitive?.content
            ?: error("The demo token endpoint returned no token")
    }

    private suspend fun joinDemoChatroom(chatroomId: String, appUserId: String) {
        val url = BuildConfig.DEMO_BACKEND_URL.toHttpUrl().newBuilder()
            .addPathSegments("api/chatrooms")
            .addPathSegment(chatroomId)
            .addPathSegment("join")
            .build()
        postJson(
            url = url,
            body = buildJsonObject { put("appUserId", appUserId) }.toString(),
        )
    }

    private suspend fun postJson(url: okhttp3.HttpUrl, body: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("x-client-id", BuildConfig.CONVOKIT_CLIENT_ID)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Demo backend failed (${response.code})")
            json.parseToJsonElement(responseBody).jsonObject
        }
    }

    private fun showError(cause: Throwable) {
        val detail = when (cause) {
            is ConvoKitException -> cause.message
            else -> cause.message ?: "Unexpected error"
        }
        binding.status.text = detail
        binding.messageInput.isEnabled = false
        binding.sendButton.isEnabled = false
    }

    private fun setBusy(busy: Boolean) {
        binding.joinButton.isEnabled = !busy
        if (busy) binding.status.text = "Connecting…"
    }

    override fun onDestroy() {
        messageSubscription?.cancel()
        readSubscription?.cancel()
        lifecycleScope.launch { convoKit.disconnectUser() }
        super.onDestroy()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
