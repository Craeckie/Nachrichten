package de.heute.nachrichten.ui

import android.os.Looper
import de.heute.nachrichten.data.Episode
import de.heute.nachrichten.data.ZdfRepository
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Runs on Robolectric's main looper, which is what `viewModelScope` dispatches to; each load
 * waits on a [CompletableDeferred] the test completes, so no network is touched.
 */
@RunWith(RobolectricTestRunner::class)
class HeuteViewModelTest {

    private class FakeRepository : ZdfRepository() {
        val loads = ArrayDeque<CompletableDeferred<List<Episode>>>()

        override suspend fun loadEpisodes(pageUrl: String, limit: Int): List<Episode> =
            CompletableDeferred<List<Episode>>().also { loads.addLast(it) }.await()

        fun answer(episodes: List<Episode>) {
            loads.removeFirst().complete(episodes)
            idle()
        }

        fun fail(message: String) {
            loads.removeFirst().completeExceptionally(Exception(message))
            idle()
        }
    }

    private val repo = FakeRepository()

    private fun newViewModel() = HeuteViewModel(RuntimeEnvironment.getApplication(), repo).also { idle() }

    @Test
    fun firstLoad_showsCentreSpinner_notPullIndicator() {
        val vm = newViewModel()
        assertEquals(UiState.Loading, vm.state.value)
        assertFalse(vm.isRefreshing.value)
    }

    @Test
    fun refreshFromError_showsPullIndicator_andKeepsErrorUntilAnswer() {
        val vm = newViewModel()
        repo.fail("Keine Verbindung")
        assertEquals(UiState.Error("Keine Verbindung"), vm.state.value)

        vm.refresh()
        idle()
        assertTrue("pull indicator should show", vm.isRefreshing.value)
        assertEquals(UiState.Error("Keine Verbindung"), vm.state.value)

        repo.answer(emptyList())
        assertEquals(UiState.Success(emptyList()), vm.state.value)
        assertFalse(vm.isRefreshing.value)
    }

    @Test
    fun refreshFromError_thatFailsAgain_endsWithNewError() {
        val vm = newViewModel()
        repo.fail("first")
        vm.refresh()
        repo.fail("second")
        assertEquals(UiState.Error("second"), vm.state.value)
        assertFalse(vm.isRefreshing.value)
    }
}

private fun idle() = shadowOf(Looper.getMainLooper()).idle()
