package de.heute.nachrichten.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import de.heute.nachrichten.data.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import kotlin.math.abs

/** Pull-to-refresh must work in every state, not only on a loaded list (#360). */
@RunWith(RobolectricTestRunner::class)
class HomeContentTest {

    // An empty rule plus a Robolectric-built activity, not createComposeRule(): that one needs
    // ComponentActivity in the manifest, which ui-test-manifest only adds to debug builds, and
    // CI runs testReleaseUnitTest.
    @get:Rule
    val compose = createEmptyComposeRule()

    private var refreshes = 0

    private fun show(state: UiState) {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            HomeContent(
                state = state,
                isRefreshing = false,
                resolving = emptySet(),
                currentTime = LocalDateTime.of(2026, 10, 10, 20, 0),
                onRefresh = { refreshes++ },
                onOpen = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    private fun pullDown() {
        compose.onRoot().performTouchInput { swipeDown() }
        compose.waitForIdle()
    }

    /** The message must stay in the middle of the screen, not jump to the top. */
    private fun assertVerticallyCentred(text: String) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        val offCentre = abs(node.center.y - root.center.y)
        assertTrue(
            "\"$text\" is ${offCentre}px off centre (root ${root.height}px high)",
            offCentre < root.height * 0.1f,
        )
    }

    @Test
    fun error_pullDown_refreshes() {
        show(UiState.Error("Keine Verbindung"))
        pullDown()
        assertEquals(1, refreshes)
    }

    @Test
    fun emptyList_pullDown_refreshes() {
        show(UiState.Success(emptyList()))
        pullDown()
        assertEquals(1, refreshes)
    }

    /** Control: the case that already worked, so a failure above is the state, not the gesture. */
    @Test
    fun list_pullDown_refreshes() {
        show(
            UiState.Success(
                listOf(
                    Episode(
                        title = "heute 19:00 Uhr",
                        canonical = "heute-19-uhr-vom-10-oktober-2026-100",
                        sharingUrl = null,
                        ptmdTemplate = "/tmd/2/{playerId}/vod/ptmd/mediathek/x/1",
                        vodMediaType = "DEFAULT",
                        date = "2026-10-10T19:00:00+02:00",
                    ),
                ),
            ),
        )
        pullDown()
        assertEquals(1, refreshes)
    }

    @Test
    fun error_staysCentred() {
        show(UiState.Error("Keine Verbindung"))
        assertVerticallyCentred("Keine Verbindung")
    }

    @Test
    fun emptyList_staysCentred() {
        show(UiState.Success(emptyList()))
        assertVerticallyCentred("Keine Sendungen gefunden.")
    }
}
