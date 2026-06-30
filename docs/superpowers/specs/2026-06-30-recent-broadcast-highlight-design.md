# Recent Broadcast Highlight Design

**Date:** 2026-06-30  
**Feature:** Visually highlight episodes broadcast within the last 12 hours  
**Component:** `HomeScreen.kt` (specifically `EpisodeCard` composable)

---

## Overview

Episodes that aired within 12 hours of the current time should be visually distinct to help users quickly identify the most recent content. This is achieved via a colored border around the episode card using the app's accent color (primary color from Material theme).

---

## Requirements

- **Recency threshold:** 12 hours from broadcast time (not from fetch time)
- **Visual treatment:** Colored rounded border using `MaterialTheme.colorScheme.primary`
- **Border styling:** 3dp thickness, rounded to match card corners (12dp radius)
- **Date label:** No change to existing relative date display ("Hoje", "Gestern", etc.)
- **Scope:** UI only; no ViewModel or data model changes

---

## Implementation

### 1. New Helper Function: `isRecentBroadcast()`

**Location:** `HomeScreen.kt`

**Purpose:** Determine if an episode's broadcast time is within 12 hours of now.

**Signature:**
```kotlin
internal fun isRecentBroadcast(
    isoDate: String,
    now: LocalDateTime = LocalDateTime.now(),
): Boolean
```

**Logic:**
1. Parse the ISO-8601 `editorialDate` string (e.g., `"2026-06-18T19:00:00+02:00"`) into a `LocalDateTime` with timezone info
2. Compare the parsed datetime against `now`
3. Return `true` if the episode broadcast within the last 12 hours; otherwise `false`
4. On parsing error (malformed date), return `false` (treat as not recent)

**Implementation notes:**
- Use `java.time.ZonedDateTime.parse()` to handle the timezone in the ISO string
- Convert to `LocalDateTime` for comparison (the app doesn't need timezone-aware duration math for this use case)
- The `now` parameter is injectable for testing (allows fixed time in unit tests)

### 2. Modify `EpisodeCard` Composable

**Changes:**
1. Call `isRecentBroadcast(episode.date ?: "")` to determine if the card should highlight
2. Apply a border conditionally:
   - If recent: `Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))`
   - If not recent: no border
3. Chain the border modifier before `.clickable()` so it remains visible even when disabled (during resolve)

**Example structure:**
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
            shape = RoundedCornerShape(12.dp)
        )
        .clickable(enabled = !isResolving, onClick = onClick),
)
```

---

## Testing

**Unit tests** in `HomeScreenTest.kt` (if it exists, or create it):
- Test `isRecentBroadcast()` with a fixed `now` parameter:
  - Episode 30 minutes ago: `true`
  - Episode 6 hours ago: `true`
  - Episode 12 hours ago (exactly): `true` (inclusive boundary)
  - Episode 12.5 hours ago: `false`
  - Episode 24 hours ago: `false`
  - Malformed date: `false`
  - Null/empty date: `false`

**Manual verification:**
1. Run the app
2. Observe that episodes broadcast within the last 12 hours have a colored border
3. Verify that older episodes have no border
4. Tap a recent episode and confirm the border remains visible during loading

---

## Visual Examples

- **Recent episode (< 12h):** Card with a 3dp primary-colored rounded border
- **Older episode:** Card with no border (existing appearance)

---

## No Breaking Changes

- Episode data model (`Episode.kt`) is unchanged
- ViewModel (`HeuteViewModel.kt`) is unchanged
- Date label rendering (`relativeDateLabel()`) is unchanged
- Existing episode list and refresh behavior are unchanged

---

## Future Considerations

- If users request it, we could add a small badge or text label (e.g., "NEW") alongside the border
- The 12-hour threshold could be made configurable (user settings) if needed
