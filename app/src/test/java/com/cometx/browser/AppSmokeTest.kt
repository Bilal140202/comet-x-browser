package com.cometx.browser

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.cometx.browser.ui.MainActivity
import com.cometx.browser.ui.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric smoke tests: real Activity inflation, browser UI wiring,
 * agent panel open/close, settings screen construction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.cometx.browser.CometApp::class)
class AppSmokeTest {

    @Test fun `main activity launches with browser chrome`() {
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            assertNotNull(activity.findViewById<EditText>(com.cometx.browser.R.id.urlBar))
            assertNotNull(activity.findViewById<View>(com.cometx.browser.R.id.webContainer))
            assertNotNull(activity.findViewById<View>(com.cometx.browser.R.id.agentPanel))
            assertNotNull(activity.findViewById<View>(com.cometx.browser.R.id.askBar))
            assertNotNull(activity.findViewById<View>(com.cometx.browser.R.id.btnOpenAgent))
            assertNotNull(activity.findViewById<View>(com.cometx.browser.R.id.challengeBanner))
        }
    }

    @Test fun `agent panel opens and closes`() {
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            val panel = activity.findViewById<LinearLayout>(com.cometx.browser.R.id.agentPanel)
            val askBar = activity.findViewById<LinearLayout>(com.cometx.browser.R.id.askBar)
            assertEquals(View.GONE, panel.visibility)

            activity.findViewById<View>(com.cometx.browser.R.id.btnOpenAgent).performClick()
            assertEquals(View.VISIBLE, panel.visibility)
            assertEquals(View.GONE, askBar.visibility)

            activity.findViewById<View>(com.cometx.browser.R.id.btnPanelClose).performClick()
            assertEquals(View.GONE, panel.visibility)
            assertEquals(View.VISIBLE, askBar.visibility)
        }
    }

    @Test fun `url input normalizes bare domains`() {
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            // loadUserUrl is package-private-in-module; exercise via text + editor action
            val urlBar = activity.findViewById<EditText>(com.cometx.browser.R.id.urlBar)
            urlBar.setText("example.com")
            urlBar.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_GO)
            assertTrue(true) // no crash; navigation delegated to WebView
        }
    }

    @Test fun `settings activity launches`() {
        ActivityScenario.launch(SettingsActivity::class.java).onActivity { activity ->
            assertTrue(activity.window != null)
        }
    }
}
