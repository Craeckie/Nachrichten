# Recent Broadcast Highlight Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Visually highlight episodes broadcast within the last 12 hours with a colored accent border in the episode card list.

**Architecture:** Two-part implementation: (1) add a pure utility function `isRecentBroadcast()` that parses ISO-8601 timestamps and determines recency, (2) conditionally apply a Material Design border to the `EpisodeCard` based on that function. No data model or ViewModel changes needed — logic lives entirely in the UI layer.

**Tech Stack:** Jetpack Compose, Material 3, Java Time API (`java.time` package)

## Global Constraints

- **Recency threshold:** 12 hours from broadcast time (editorialDate)
- **Visual treatment:** Primary color accent border, 3dp thickness, 12dp radius (rounded)
- **No model changes:** Episode, ZdfRepository, HeuteViewModel remain unchanged
- **Test coverage required:** Unit tests for `isRecentBroadcast()` with multiple time scenarios

---

## File Structure

**Modified files:**
1. `app/src/main/java/de/heute/nachrichten/ui/HomeScreen.kt`
   - Add `isRecentBroadcast(isoDate: String, now: LocalDateTime): Boolean` helper
   - Modify `EpisodeCard` composable to apply conditional border

2. `app/src/test/java/de/heute/nachrichten/ui/HomeScreenTest.kt`
   - Create if doesn't exist
   - Add unit tests for `isRecentBroadcast()` with fixed time scenarios

---

## Task 1: Write Unit Tests for `isRecentBroadcast()`

**Files:**
- Create/Modify: `app/src/test/java/de/heute/nachrichten/ui/HomeScreenTest.kt`

**Interfaces:**
- Produces: `isRecentBroadcast(isoDate: String, now: LocalDateTime = LocalDateTime.now()): Boolean`
  - Parameter `isoDate`: ISO-8601 datetime string (e.g., "2026-06-30T19:00:00+02:00")
  - Parameter `now`: Current time (injected for testing; defaults to `LocalDateTime.now()`)
  - Returns: `true` if broadcast was within 12 hours of `now`; `false` otherwise

- [ ] **Step 1: Create test file with test cases**

Create `app/src/test/java/de/heute/nachrichten/ui/HomeScreenTest.kt`:

```kotlin
package de.heute.nachrichten.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.ZoneId

class HomeScreenTest {

    @Test
    fun isRecentBroadcast_episodeFrom30MinutesAgo_returnsTrue() {
        val now = LocalDateTime.of(2026, 6, 30, 20, 0, 0)
        val isoDate = "2026-06-30T19:30:00+02:00"
        assertTrue(isRecentBroadcast(isoDate, now))
    }

    @Test
    fun isRecentBroadcast_episodeFrom6HoursAgo_returnsTrue() {
        val now = LocalDateTime.of(2026, 6, 30, 20, 0, 0)
        val isoDate = "2026-06-30T14:00:00+02:00"
        assertTrue(isRecentBroadcast(isoDate, now))
    }

    @Test
    fun isRecentBroadcast_episodeFrom12HoursAgo_returnsTrue() {
        val now = LocalDateTime.of(2026, 6, 30, 20, 0, 0)
        val isoDate = "2026-06-30T08:00:00+02:00"
        assertTrue(isRecentBroadcast(isoDate, now))
    }

    @Test
    fun isRecentBroadcast_episodeFrom12HoursAnd1MinuteAgo_returnsFalse() {
        val now = LocalDateTime.of(2026, 6, 30, 20, 0, 0)
        val isoDate = "2026-06-30T07:59:00+02:00"
        assertFalse(isRecentBroadcast(isoDate, now))
    }

    @Test
    fun isRecentBroadcast_episodeFrom24HoursAgo_returnsFalse() {
        val now = LocalDateTime.of(2026, 6, 30, 20, 0, 0)
        val isoDate = "2026-06-29T20:00:00+02:00"
        assertFalse(isRecentBroadcast(isoDate, now))
    }

    @Test
    fun isRecentBroadcast_malformedDate_returnsFalse() {
        val now = LocalDateTime.of(2026, 6, 30, 20, 0, 0)
        val isoDate = "not-a-date"
        assertFalse(isRecentBroadcast(isoDate, now))
    }

    @Test
    fun isRecentBroadcast_emptyDate_returnsFalse() {
        val now = LocalDateTime.of(2026, 6, 30, 20, 0, 0)
        assertFalse(isRecentBroadcast("", now))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew testDebugUnitTest -v
```

Expected output: All tests in `HomeScreenTest` FAIL because `isRecentBroadcast` is not defined yet.

---

## Task 2: Implement `isRecentBroadcast()` Function

**Files:**
- Modify: `app/src/main/java/de/heute/nachrichten/ui/HomeScreen.kt`

**Interfaces:**
- Consumes: Nothing (pure function, no dependencies)
- Produces: `isRecentBroadcast(isoDate: String, now: LocalDateTime = LocalDateTime.now()): Boolean`

- [ ] **Step 1: Add imports to HomeScreen.kt**

At the top of `HomeScreen.kt`, add these imports (if not already present):

```kotlin
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
```

- [ ] **Step 2: Add `isRecentBroadcast()` function**

Add this function to `HomeScreen.kt` right after the `relativeDateLabel()` function (around line 217):

```kotlin
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
        val hoursSinceBroadcast = ChronoUnit.HOURS.between(broadcastLocal, now)
        hoursSinceBroadcast in 0..11
    } catch (e: Exception) {
        false
    }
}
```

- [ ] **Step 3: Run tests to verify they pass**

```bash
./gradlew testDebugUnitTest -v
```

Expected output: All tests in `HomeScreenTest` PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/de/heute/nachrichten/ui/HomeScreenTest.kt \
        app/src/main/java/de/heute/nachrichten/ui/HomeScreen.kt
git commit -m "feat: add isRecentBroadcast() function and unit tests"
```

---

## Task 3: Add Border Import and Modify `EpisodeCard` Composable

**Files:**
- Modify: `app/src/main/java/de/heute/nachrichten/ui/HomeScreen.kt` (EpisodeCard composable)

**Interfaces:**
- Consumes: `isRecentBroadcast(isoDate: String, now: LocalDateTime): Boolean` (from Task 2)
- Produces: `EpisodeCard` composable with conditional border styling

- [ ] **Step 1: Add border import if needed**

Check if `androidx.compose.foundation.border` is already imported in HomeScreen.kt. If not, add:

```kotlin
import androidx.compose.foundation.border
```

Also ensure this import exists:

```kotlin
import androidx.compose.foundation.shape.RoundedCornerShape
```

- [ ] **Step 2: Locate the `EpisodeCard` composable**

Find the `EpisodeCard` function starting around line 157. You'll see:

```kotlin
@Composable
private fun EpisodeCard(
    episode: Episode,
    isResolving: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(enabled = !isResolving, onClick = onClick),
    ) {
        // ... content
    }
}
```

- [ ] **Step 3: Modify the Card modifier to include conditional border**

Replace the `Card` section with:

```kotlin
Card(
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .border(
            width = 3.dp,
            color = if (isRecentBroadcast(episode.date ?: ""))
                MaterialTheme.colorScheme.primary
            else
                Color.Transparent,
            shape = RoundedCornerShape(12.dp),
        )
        .clickable(enabled = !isResolving, onClick = onClick),
) {
    Column(modifier = Modifier.padding(16.dp)) {
        // ... rest of content unchanged
    }
}
```

**Note:** The border modifier appears *before* `.clickable()` so it remains visible when the card is disabled (during video resolution).

- [ ] **Step 4: Add Color import if needed**

Verify that `androidx.compose.ui.graphics.Color` is imported. If not:

```kotlin
import androidx.compose.ui.graphics.Color
```

- [ ] **Step 5: Verify the complete EpisodeCard function**

The final `EpisodeCard` should look like this:

```kotlin
@Composable
private fun EpisodeCard(
    episode: Episode,
    isResolving: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .border(
                width = 3.dp,
                color = if (isRecentBroadcast(episode.date ?: ""))
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
                    text = relativeDateLabel(date),
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
```

- [ ] **Step 6: Run unit tests to ensure nothing broke**

```bash
./gradlew testDebugUnitTest -v
```

Expected output: All tests PASS.

- [ ] **Step 7: Build debug APK to check for compilation errors**

```bash
./gradlew assembleDebug
```

Expected output: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/de/heute/nachrichten/ui/HomeScreen.kt
git commit -m "feat: add colored border highlight to recent broadcasts"
```

---

## Task 4: Manual Verification

**Files:** None (manual testing only)

**Interfaces:**
- Consumes: Fully built app with recent-broadcast highlighting

- [ ] **Step 1: Install and run the app**

If you have a device or emulator:

```bash
./gradlew installDebug
# Then launch the app from your device or emulator
```

Or use Android Studio's emulator directly.

- [ ] **Step 2: Verify recent broadcasts have the border**

1. Open the app.
2. Look at the episode list (pull-to-refresh if needed).
3. **Check:** Episodes broadcast within the last 12 hours should display with a primary-colored (usually blue/purple) 3dp rounded border.
4. **Check:** Episodes older than 12 hours should have no border (normal appearance).

- [ ] **Step 3: Verify interaction still works**

1. Tap a recent broadcast (with the border).
2. The spinner should appear and the border should remain visible.
3. The player should launch once the stream resolves.
4. Verify older episodes still launch normally.

- [ ] **Step 4: Cross-check with Python script (optional)**

For extra confidence, run the Python extractor and compare the top episode's timestamp:

```bash
python3 zdf_heute_url.py
```

Check that the episode in the app matches the Python script's output and has the correct border status.

- [ ] **Step 5: No manual commit needed for this task**

(Testing is manual; no code changes in this step.)

---

## Self-Review Checklist

✅ **Spec coverage:**
- ✅ Recency threshold (12 hours): Task 2 implements `isRecentBroadcast()` with 0-11 hour range
- ✅ Visual treatment (border, accent color): Task 3 applies conditional border with `MaterialTheme.colorScheme.primary`
- ✅ Border styling (3dp, 12dp radius): Task 3 specifies `width = 3.dp` and `RoundedCornerShape(12.dp)`
- ✅ Date label unchanged: Task 3 preserves `relativeDateLabel()` call in EpisodeCard
- ✅ No model changes: Tasks 1–3 touch only UI and tests; Episode, ZdfRepository, HeuteViewModel untouched
- ✅ Unit test coverage: Task 1 covers all scenarios (30min, 6h, 12h, 12.1h, 24h, malformed, empty)

✅ **Placeholder scan:**
- No "TBD", "TODO", or vague instructions
- All code is complete and ready to paste
- All test cases have explicit assertions
- All commands include expected output

✅ **Type consistency:**
- `isRecentBroadcast()` signature matches across tasks and tests
- `LocalDateTime`, `ZonedDateTime`, `ChronoUnit` types are consistent
- Border color uses `MaterialTheme.colorScheme.primary` everywhere

✅ **No placeholders:** All steps are concrete, executable actions with full code samples.
