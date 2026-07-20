package de.heute.nachrichten.ui

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.heute.nachrichten.R
import de.heute.nachrichten.data.Episode
import de.heute.nachrichten.player.PlayerLauncher
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HeuteViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val resolving by viewModel.resolving.collectAsStateWithLifecycle()
    val currentTime by viewModel.currentTime.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Re-anchor the clock every time the app returns to the foreground, so the relative
    // labels and "recent" highlight are recomputed against the real now instead of a value
    // captured when the (retained) ViewModel was first created.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.syncTime() }

    HandlePlayEvents(viewModel, context, snackbarHostState)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = viewModel::refresh) {
                        Text(stringResource(R.string.refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (val s = state) {
                    is UiState.Loading ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))

                    is UiState.Error ->
                        ErrorView(
                            message = s.message,
                            onRetry = viewModel::refresh,
                            modifier = Modifier.align(Alignment.Center),
                        )

                    is UiState.Success ->
                        if (s.episodes.isEmpty()) {
                            Text(
                                text = stringResource(R.string.empty),
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            EpisodeList(
                                episodes = s.episodes,
                                resolving = resolving,
                                currentTime = currentTime,
                                contentPadding = PaddingValues(0.dp),
                                onClick = viewModel::openEpisode,
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun HandlePlayEvents(
    viewModel: HeuteViewModel,
    context: Context,
    snackbarHostState: SnackbarHostState,
) {
    val noPlayer = stringResource(R.string.no_player)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PlayEvent.Launch ->
                    if (!PlayerLauncher.open(context, event.url)) {
                        snackbarHostState.showSnackbar(noPlayer)
                    }

                is PlayEvent.Failed ->
                    snackbarHostState.showSnackbar(event.message)
            }
        }
    }
}

@Composable
private fun EpisodeList(
    episodes: List<Episode>,
    resolving: Set<String>,
    currentTime: LocalDateTime,
    contentPadding: PaddingValues,
    onClick: (Episode) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        items(episodes, key = { it.canonical }) { episode ->
            EpisodeCard(
                episode = episode,
                isResolving = episode.canonical in resolving,
                currentTime = currentTime,
                onClick = { onClick(episode) },
            )
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: Episode,
    isResolving: Boolean,
    currentTime: LocalDateTime,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .border(
                width = 3.dp,
                color = if (isRecentBroadcast(episode.date ?: "", currentTime))
                    MaterialTheme.colorScheme.primary
                else
                    Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = !isResolving, onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            episode.date?.let { date ->
                Text(
                    text = relativeDateLabel(date, currentTime.toLocalDate()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (isResolving) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/**
 * Render an episode's editorialDate (ISO-8601, e.g. "2026-06-18T19:00:00+02:00") as a German
 * relative label: Heute / Gestern / Vorgestern, the weekday for the rest of the past week, and
 * a plain dd.MM.yyyy date beyond that. Falls back to the bare date string if parsing fails.
 */
internal fun relativeDateLabel(
    isoDate: String,
    today: LocalDate = LocalDate.now(),
): String {
    val date = try {
        LocalDate.parse(isoDate.substringBefore('T'))
    } catch (e: java.time.format.DateTimeParseException) {
        return isoDate.substringBefore('T')
    }
    return when (ChronoUnit.DAYS.between(date, today)) {
        0L -> "Heute"
        1L -> "Gestern"
        2L -> "Vorgestern"
        in 3L..6L -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.GERMAN)
        else -> date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }
}

/**
 * Determine if an episode's broadcast time is within the last 12 hours.
 * Parses the ISO-8601 editorialDate string and compares against the given (or current) time.
 * Returns false on any parsing error.
 */
internal fun isRecentBroadcast(
    isoDate: String,
    now: LocalDateTime = LocalDateTime.now(),
): Boolean {
    if (isoDate.isBlank()) return false
    return try {
        val zonedBroadcast = ZonedDateTime.parse(isoDate)
        val broadcastLocal = zonedBroadcast.toLocalDateTime()
        val minutesSinceBroadcast = ChronoUnit.MINUTES.between(broadcastLocal, now)
        minutesSinceBroadcast in 0..(12 * 60)
    } catch (e: Exception) {
        false
    }
}

@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}
