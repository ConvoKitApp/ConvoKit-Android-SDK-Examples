package app.convokit.example

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.convokit.example.databinding.ActivityMainBinding
import app.convokit.sdk.ConvoKitClient
import app.convokit.sdk.ConvoKitException
import app.convokit.sdk.TokenProvider
import app.convokit.ui.client.DefaultConvoKitUiClient
import app.convokit.ui.components.ConvoKitConversation
import app.convokit.ui.theme.ConvoKitTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

/** The host owns login and room joining; the UI package owns the conversation. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val httpClient = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private var busy = false
    private val convoKitDelegate = lazy {
        ConvoKitClient(
            clientId = BuildConfig.CONVOKIT_CLIENT_ID,
            tokenProvider = TokenProvider(::issueUserToken),
            httpClient = httpClient,
        )
    }
    private val convoKit by convoKitDelegate
    private val replyProbe by lazy { QuotedReplyProbe(ConvoKitReplySurface(convoKit)) }
    private var joinedRoomId: String? = null
    private val roomBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = leaveRoom()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            // The host has accounted for these; do not apply them twice in Compose.
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)
        binding.chatContent.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        onBackPressedDispatcher.addCallback(this, roomBack)
        binding.joinButton.setOnClickListener { joinRoom() }
        binding.replyDemoButton.setOnClickListener { runReplyDemo() }
        binding.reactionDemoButton.setOnClickListener { runReactionDemo() }
    }

    private fun joinRoom() {
        if (busy) return
        val userId = binding.userId.text?.toString()?.trim().orEmpty()
        val roomId = binding.roomId.text?.toString()?.trim().orEmpty()
        if (userId.isEmpty() || roomId.isEmpty()) {
            binding.status.text = getString(R.string.enter_both)
            return
        }
        setBusy(true)
        binding.status.text = getString(R.string.connecting)
        lifecycleScope.launch {
            try {
                joinDemoChatroom(roomId, userId)
                convoKit.connectUser(userId)
                convoKit.getConversation(roomId)
                // Recreate after every successful login, even for the same user.
                val uiClient = DefaultConvoKitUiClient(convoKit)
                binding.chatContent.setContent {
                    ConvoKitTheme {
                        ConvoKitConversation(
                            client = uiClient,
                            conversationId = roomId,
                            modifier = Modifier.fillMaxSize(),
                            onBack = ::leaveRoom,
                        )
                    }
                }
                binding.joinForm.visibility = View.GONE
                binding.room.visibility = View.VISIBLE
                joinedRoomId = roomId
                binding.replyDemoStatus.setText(R.string.reply_demo_hint)
                binding.replyDemoButton.isEnabled = true
                binding.reactionDemoButton.isEnabled = true
                roomBack.isEnabled = true
            } catch (cause: Throwable) {
                withContext(NonCancellable) { convoKit.disconnectUser() }
                if (cause is CancellationException) throw cause
                showError(cause)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun leaveRoom() {
        if (busy || !roomBack.isEnabled) return
        roomBack.isEnabled = false
        // Dispose old room state before permitting a replacement login.
        binding.chatContent.disposeComposition()
        binding.room.visibility = View.GONE
        binding.replyDemoButton.isEnabled = false
        binding.reactionDemoButton.isEnabled = false
        joinedRoomId = null
        binding.joinForm.visibility = View.VISIBLE
        binding.status.text = getString(R.string.disconnecting)
        setBusy(true)
        lifecycleScope.launch {
            try {
                convoKit.disconnectUser()
                binding.status.text = getString(R.string.not_connected)
            } catch (cause: Throwable) {
                if (cause is CancellationException) throw cause
                showError(cause)
            } finally {
                setBusy(false)
            }
        }
    }

    /** Host-owned tour of the 0.9 core surface; the room itself needs no host code for it. */
    private fun runReplyDemo() {
        val roomId = joinedRoomId ?: return
        binding.replyDemoButton.isEnabled = false
        binding.replyDemoStatus.setText(R.string.reply_demo_running)
        lifecycleScope.launch {
            try {
                val report = replyProbe.run(roomId).joinToString("\n\n")
                // A report that outlived its room belongs to nobody; drop it.
                if (joinedRoomId == roomId) binding.replyDemoStatus.text = report
            } catch (cause: Throwable) {
                if (cause is CancellationException) throw cause
                if (joinedRoomId == roomId) binding.replyDemoStatus.setText(R.string.reply_demo_failed)
            } finally {
                binding.replyDemoButton.isEnabled = joinedRoomId != null
            }
        }
    }

    /** Direct core API tour. The Compose conversation handles its own picker and chips. */
    private fun runReactionDemo() {
        val roomId = joinedRoomId ?: return
        binding.reactionDemoButton.isEnabled = false
        binding.reactionDemoStatus.setText(R.string.reaction_demo_running)
        lifecycleScope.launch {
            try {
                val newest = convoKit.getMessages(roomId, limit = 1).firstOrNull()
                val report = if (newest == null) {
                    getString(R.string.reaction_demo_empty)
                } else {
                    val emoji = "👍🏽" // Preserve the exact Unicode sequence, including its skin tone.
                    val added = convoKit.addReaction(newest.id, emoji)
                    val summary = convoKit.getReactionSummaries(roomId, listOf(newest.id))
                        .firstOrNull()?.reactions?.find { it.emoji == emoji }
                    val reactors = convoKit.listReactionUsers(newest.id, emoji, limit = 10)
                    val removed = convoKit.removeReaction(newest.id, emoji)
                    "Added $emoji to ${newest.id}: changed=${added.changed}; " +
                        "count=${summary?.count ?: 0}, reactedByMe=${summary?.reactedByMe ?: false}; " +
                        "reactors on first page=${reactors.data.size}, next page=${reactors.nextCursor != null}; " +
                        "removed: changed=${removed.changed}."
                }
                if (joinedRoomId == roomId) binding.reactionDemoStatus.text = report
            } catch (cause: Throwable) {
                if (cause is CancellationException) throw cause
                if (joinedRoomId == roomId) binding.reactionDemoStatus.setText(R.string.reaction_demo_failed)
            } finally {
                binding.reactionDemoButton.isEnabled = joinedRoomId != null
            }
        }
    }

    private suspend fun issueUserToken(appUserId: String): String {
        val response = postJson(
            "${BuildConfig.DEMO_BACKEND_URL}/api/auth/token".toHttpUrl(),
            buildJsonObject { put("appUserId", appUserId) }.toString(),
        )
        return response["data"]?.jsonObject?.get("token")?.jsonPrimitive?.content
            ?: error("The demo token endpoint returned no token")
    }

    private suspend fun joinDemoChatroom(chatroomId: String, appUserId: String) {
        val url = BuildConfig.DEMO_BACKEND_URL.toHttpUrl().newBuilder()
            .addPathSegments("api/chatrooms").addPathSegment(chatroomId).addPathSegment("join").build()
        postJson(url, buildJsonObject { put("appUserId", appUserId) }.toString())
    }

    private suspend fun postJson(url: okhttp3.HttpUrl, body: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("x-client-id", BuildConfig.CONVOKIT_CLIENT_ID)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Demo backend failed (${response.code})")
            json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
        }
    }

    private fun showError(cause: Throwable) {
        binding.status.text = if (cause is ConvoKitException) {
            getString(R.string.sdk_error, cause.code)
        } else getString(R.string.connection_failed)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.joinButton.isEnabled = !value
        binding.userId.isEnabled = !value
        binding.roomId.isEnabled = !value
    }

    override fun onDestroy() {
        binding.chatContent.disposeComposition()
        lifecycleScope.launch(NonCancellable) {
            try {
                if (convoKitDelegate.isInitialized()) convoKit.disconnectUser()
            } finally {
                httpClient.dispatcher.cancelAll()
                httpClient.connectionPool.evictAll()
                httpClient.dispatcher.executorService.shutdown()
            }
        }
        super.onDestroy()
    }
}
