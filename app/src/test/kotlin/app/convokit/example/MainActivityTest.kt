package app.convokit.example

import android.view.View
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    @Test fun `initial view shows only the host join form`() = withActivity { activity ->
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.join_form).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.chat_content).visibility)
        assertEquals(activity.getString(R.string.not_connected), activity.findViewById<TextView>(R.id.status).text)
        assertTrue(activity.findViewById<View>(R.id.join_button).isEnabled)
    }

    @Test fun `missing room is rejected before attempting a connection`() = withActivity { activity ->
        activity.findViewById<View>(R.id.join_button).performClick()
        assertEquals(activity.getString(R.string.enter_both), activity.findViewById<TextView>(R.id.status).text)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.chat_content).visibility)
        assertTrue(activity.findViewById<View>(R.id.join_button).isEnabled)
    }

    @Test fun `host consumes system cutout and keyboard insets without accumulating padding`() = withActivity { activity ->
        val root = activity.findViewById<View>(R.id.chat_content).parent as View
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 18))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(12, 0, 0, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 220))
            .build()
        repeat(2) {
            assertTrue(ViewCompat.dispatchApplyWindowInsets(root, insets).isConsumed)
            assertEquals(listOf(12, 24, 0, 220), listOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom))
        }
    }

    private fun withActivity(check: (MainActivity) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try { check(controller.get()) } finally { controller.pause().stop().destroy() }
    }
}
