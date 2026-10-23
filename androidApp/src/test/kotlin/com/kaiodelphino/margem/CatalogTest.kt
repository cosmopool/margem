package com.kaiodelphino.margem

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The component catalog (architecture §7: every book component is checked there by eye, light and dark) has
 * a section for each book section the app draws. Only presence is asserted: the look is checked on the emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h2400dp")
class CatalogTest {

    @get:Rule val compose = createAndroidComposeRule<CatalogActivity>()

    private fun has(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the catalog has the previsto section of book 8_11`() {
        compose.waitForIdle()
        assertTrue("o catálogo abriu", has("8.10"))
        assertTrue("seção 8.11", has("8.11"))
        // The NoticeCard recognized as a previsto, with its InfoBand.
        assertTrue("recado reconhecido", has("É o previsto"))
    }
}
