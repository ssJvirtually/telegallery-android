# TeleGallery — Implementation Plan
### A Google Photos–like Android App with Telegram as Storage Backend

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Architecture Overview](#2-architecture-overview)
3. [Tech Stack](#3-tech-stack)
4. [Project Structure](#4-project-structure)
5. [Phase 1 — Authentication (Telegram Login)](#5-phase-1--authentication-telegram-login)
6. [Phase 2 — Local Gallery](#6-phase-2--local-gallery)
7. [Phase 3 — Backup Engine](#7-phase-3--backup-engine)
8. [Phase 4 — Telegram Storage Integration](#8-phase-4--telegram-storage-integration)
9. [Phase 5 — Backup Status Overlay (Badge Icons)](#9-phase-5--backup-status-overlay-badge-icons)
10. [Phase 6 — Settings Screen](#10-phase-6--settings-screen)
11. [Phase 7 — Background Sync Service](#11-phase-7--background-sync-service)
12. [Phase 8 — UI/UX Polish (Google Photos Feel)](#12-phase-8--uiux-polish-google-photos-feel)
13. [Phase 9 — Memory & Crash Safety](#13-phase-9--memory--crash-safety)
14. [Phase 10 — Testing & QA](#14-phase-10--testing--qa)
15. [Data Models](#15-data-models)
16. [Database Schema (Room)](#16-database-schema-room)
17. [Key API Calls — Telegram Bot/MTProto](#17-key-api-calls--telegram-botmtproto)
18. [Permissions](#18-permissions)
19. [Manifest Overview](#19-manifest-overview)
20. [Dependency List (Gradle)](#20-dependency-list-gradle)
21. [Risk & Mitigation Table](#21-risk--mitigation-table)
22. [Delivery Milestones](#22-delivery-milestones)

---

## 1. Project Overview

**App Name:** TeleGallery (working title)

**Goal:** Build a lightweight Android application that:
- Mirrors the Google Photos gallery experience (grid view, timeline grouping, full-screen viewer, search)
- Authenticates users via Telegram (phone number OTP — no separate account needed)
- Backs up photos to a user-selected Telegram chat (private chat, channel, or Saved Messages) one photo at a time
- Shows a small cloud/tick badge on each thumbnail to reflect backup status
- Stays lightweight, memory-safe, and crash-free

**Out of scope for MVP:**
- Video backup (can be added in v2)
- AI-powered search / face recognition
- Shared albums
- Web viewer

---

## 2. Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│                        UI Layer                          │
│   GalleryFragment │ ViewerActivity │ SettingsFragment     │
└───────────────────────────┬──────────────────────────────┘
                            │ ViewModel (StateFlow)
┌───────────────────────────▼──────────────────────────────┐
│                    Domain / Use-Case Layer                │
│  GetLocalPhotosUC │ BackupPhotoUC │ SyncStatusUC          │
└───────────────────────────┬──────────────────────────────┘
                            │
          ┌─────────────────┴─────────────────┐
          │                                   │
┌─────────▼────────────┐          ┌───────────▼──────────┐
│  Local Data Layer    │          │  Remote / Telegram   │
│  MediaStore queries  │          │  TelegramRepository  │
│  Room DB (status)    │          │  (TDLib or Bot API)  │
│  DataStore (prefs)   │          └──────────────────────┘
└──────────────────────┘
          │
┌─────────▼──────────────────────────────────────────────┐
│              Background WorkManager Job                 │
│   BackupWorker (one photo per work unit, chained)       │
└────────────────────────────────────────────────────────┘
```

**Pattern:** MVVM + Repository + Clean Architecture (Use Cases)

---

## 3. Tech Stack

| Concern | Choice | Reason |
|---|---|---|
| Language | Kotlin | First-class Android, coroutines support |
| UI | Jetpack Compose | Declarative, modern, less boilerplate |
| Image loading | Coil 2 | Compose-native, memory-efficient |
| Local DB | Room | Stores backup status per photo ID |
| Preferences | DataStore (Proto) | Replaces SharedPreferences, non-blocking |
| Background work | WorkManager | Survives app restarts, system-managed |
| Telegram auth | TDLib (via JNI wrapper) | Full MTProto, supports phone login |
| Telegram upload | TDLib sendMessage + InputFileLocal | Native file upload via TDLib |
| DI | Hilt | Standard, well-supported |
| Navigation | Navigation Compose | Single-activity, type-safe |
| Async | Kotlin Coroutines + Flow | Memory-safe, structured concurrency |
| Permissions | Accompanist Permissions | Compose-friendly permission handling |

> **TDLib vs Bot API:** TDLib is recommended because it supports full Telegram user authentication (phone + OTP), allows sending to any chat, and doesn't require a bot. The Bot API is simpler but cannot log in as a real user. Use TDLib.

---

## 4. Project Structure

```
app/
├── src/main/
│   ├── java/com/telegallery/
│   │   ├── MainActivity.kt
│   │   ├── TeleGalleryApp.kt                  ← Hilt Application class
│   │   │
│   │   ├── data/
│   │   │   ├── local/
│   │   │   │   ├── db/
│   │   │   │   │   ├── AppDatabase.kt
│   │   │   │   │   ├── PhotoStatusDao.kt
│   │   │   │   │   └── PhotoStatusEntity.kt
│   │   │   │   ├── mediastore/
│   │   │   │   │   └── MediaStoreDataSource.kt
│   │   │   │   └── prefs/
│   │   │   │       └── UserPreferencesRepository.kt
│   │   │   │
│   │   │   ├── remote/
│   │   │   │   ├── telegram/
│   │   │   │   │   ├── TdLibClient.kt         ← TDLib wrapper
│   │   │   │   │   ├── TelegramAuthRepository.kt
│   │   │   │   │   └── TelegramUploadRepository.kt
│   │   │   │   └── model/
│   │   │   │       └── TelegramChat.kt
│   │   │   │
│   │   │   └── repository/
│   │   │       ├── PhotoRepository.kt
│   │   │       └── BackupRepository.kt
│   │   │
│   │   ├── domain/
│   │   │   ├── model/
│   │   │   │   ├── LocalPhoto.kt
│   │   │   │   └── BackupStatus.kt            ← Enum: PENDING, UPLOADING, DONE, FAILED
│   │   │   └── usecase/
│   │   │       ├── GetLocalPhotosUseCase.kt
│   │   │       ├── BackupPhotoUseCase.kt
│   │   │       └── GetBackupStatusUseCase.kt
│   │   │
│   │   ├── ui/
│   │   │   ├── auth/
│   │   │   │   ├── AuthViewModel.kt
│   │   │   │   ├── PhoneInputScreen.kt
│   │   │   │   └── OtpScreen.kt
│   │   │   │
│   │   │   ├── gallery/
│   │   │   │   ├── GalleryViewModel.kt
│   │   │   │   ├── GalleryScreen.kt           ← Main grid screen
│   │   │   │   └── components/
│   │   │   │       ├── PhotoGridItem.kt        ← Thumbnail + badge overlay
│   │   │   │       └── DateHeader.kt
│   │   │   │
│   │   │   ├── viewer/
│   │   │   │   ├── PhotoViewerViewModel.kt
│   │   │   │   └── PhotoViewerScreen.kt
│   │   │   │
│   │   │   ├── settings/
│   │   │   │   ├── SettingsViewModel.kt
│   │   │   │   └── SettingsScreen.kt
│   │   │   │
│   │   │   └── theme/
│   │   │       ├── Color.kt
│   │   │       ├── Type.kt
│   │   │       └── Theme.kt
│   │   │
│   │   ├── worker/
│   │   │   └── BackupWorker.kt
│   │   │
│   │   └── di/
│   │       ├── DatabaseModule.kt
│   │       ├── TelegramModule.kt
│   │       └── RepositoryModule.kt
│   │
│   ├── res/
│   │   ├── drawable/
│   │   │   ├── ic_backup_done.xml             ← Cloud with tick (white, 16dp)
│   │   │   ├── ic_backup_pending.xml          ← Cloud outline (white, 16dp)
│   │   │   └── ic_backup_failed.xml           ← Cloud with X (red tint, 16dp)
│   │   └── ...
│   └── AndroidManifest.xml
│
├── tdlib/                                      ← TDLib .aar or .so files
└── build.gradle.kts
```

---

## 5. Phase 1 — Authentication (Telegram Login)

### Goal
Allow users to log in with their Telegram account using phone number + OTP. No separate username/password.

### Steps

**5.1 — Integrate TDLib**

1. Download the prebuilt TDLib `.aar` for Android from the official TDLib releases or build from source.
2. Add the `.aar` to `libs/` and declare it in `build.gradle.kts`:
   ```kotlin
   implementation(files("libs/tdlib.aar"))
   ```
3. Add required native `.so` files inside the AAR or manually under `jniLibs/`.

**5.2 — TdLibClient Singleton**

```kotlin
// TdLibClient.kt
// Wraps TelegramClient.create()
// Exposes a send() suspending function using suspendCoroutine
// Handles all TdApi.Update dispatching via a SharedFlow
// Must be initialized in Application.onCreate() via Hilt
```

Key TDLib calls used:
- `TdApi.SetTdlibParameters` — set API_ID, API_HASH, database paths
- `TdApi.CheckDatabaseEncryptionKey`
- `TdApi.SetAuthenticationPhoneNumber`
- `TdApi.CheckAuthenticationCode`
- `TdApi.GetAuthorizationState` — poll to know current auth step

**5.3 — Auth State Machine**

```
IDLE → WAIT_PHONE_NUMBER → WAIT_CODE → WAIT_PASSWORD (2FA) → READY
```

Each state maps to a Compose screen.

**5.4 — Screens**

- `PhoneInputScreen`: Country code picker + phone number field. "Continue" button sends `SetAuthenticationPhoneNumber`.
- `OtpScreen`: 5-digit code input. Auto-advance on last digit. "Verify" button sends `CheckAuthenticationCode`.
- On `AuthorizationStateReady` → navigate to `GalleryScreen`.

**5.5 — Secure Storage**

- Store TDLib database in app's internal files directory (`context.filesDir/tdlib/`).
- TDLib handles its own session persistence — no token management needed.
- Never store phone numbers or OTPs in Room or DataStore.

---

## 6. Phase 2 — Local Gallery

### Goal
Display device photos in a scrollable grid grouped by date, identical in feel to Google Photos.

### Steps

**6.1 — MediaStore Query**

```kotlin
// MediaStoreDataSource.kt
// Query: MediaStore.Images.Media.EXTERNAL_CONTENT_URI
// Projection: _ID, DATE_TAKEN, DATE_MODIFIED, DISPLAY_NAME, BUCKET_DISPLAY_NAME, SIZE, MIME_TYPE, RELATIVE_PATH
// Sort: DATE_TAKEN DESC
// Return: List<LocalPhoto>
// Use ContentResolver.query() inside a Flow with ContentObserver for live updates
```

**6.2 — LocalPhoto Domain Model**

```kotlin
data class LocalPhoto(
    val id: Long,
    val uri: Uri,            // content://media/... URI
    val dateTaken: Long,     // epoch ms
    val dateModified: Long,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val bucketName: String
)
```

**6.3 — Gallery Screen Layout**

- `LazyVerticalGrid` with 3 columns (same as Google Photos default)
- Sticky date headers above each date group (use `stickyHeader` in `LazyVerticalGrid` via a mixed item list)
- Each cell: fixed aspect ratio 1:1, `AsyncImage` (Coil), with backup badge overlaid bottom-right

**6.4 — Grouping Logic**

```kotlin
// In GalleryViewModel:
// Group List<LocalPhoto> by formatted date string ("Today", "Yesterday", "May 2025")
// Emit List<GalleryItem> where GalleryItem is sealed class:
//   GalleryItem.Header(label: String)
//   GalleryItem.Photo(photo: LocalPhoto, status: BackupStatus)
```

**6.5 — Coil Configuration**

```kotlin
// In TeleGalleryApp.kt
ImageLoader.Builder(context)
    .memoryCache {
        MemoryCache.Builder(context)
            .maxSizePercent(0.20) // Only use 20% of available RAM
            .build()
    }
    .diskCache {
        DiskCache.Builder()
            .directory(context.cacheDir.resolve("image_cache"))
            .maxSizeBytes(50 * 1024 * 1024) // 50MB disk cache max
            .build()
    }
    .crossfade(true)
    .build()
```

---

## 7. Phase 3 — Backup Engine

### Goal
Back up photos one at a time, sequentially, using WorkManager so it survives app restarts and doesn't hog memory.

### Design Principles

- **One photo per WorkManager work request** — no batch uploads that hold multiple bitmaps in memory simultaneously.
- **Chained work** — after each photo succeeds, the next is enqueued.
- **Status tracked in Room** — every photo gets a `BackupStatus` row. The UI observes this via `Flow`.
- **Idempotent** — if a photo is already `DONE`, skip it without re-uploading.
- **Retry on failure** — WorkManager retries with exponential backoff; status set to `FAILED` after max retries.

### Steps

**7.1 — BackupWorker**

```kotlin
// BackupWorker.kt
// Input data: photoId (Long), photoUri (String), targetChatId (Long)
// Steps:
//   1. Mark status = UPLOADING in Room
//   2. Open InputStream from ContentResolver (do NOT read entire file into memory)
//   3. Write to a temp file in cacheDir (TDLib needs a file path, not a stream)
//   4. Call TelegramUploadRepository.uploadPhoto(filePath, chatId)
//   5. On success: delete temp file, mark status = DONE
//   6. On failure: delete temp file, mark status = FAILED, return Result.retry()
// Memory note: never hold a Bitmap object; use file path only
```

**7.2 — Enqueueing Work**

```kotlin
// BackupRepository.kt
// On app start or when new photos are detected:
//   1. Query MediaStore for all photo IDs
//   2. Cross-reference with Room — find IDs where status != DONE
//   3. Enqueue each as OneTimeWorkRequest with ExistingWorkPolicy.KEEP
//   4. Use unique work name = "backup_photo_${photoId}" to prevent duplicates
```

**7.3 — Constraints**

```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()
// Optional: add setRequiresCharging(true) — expose as a user toggle in Settings
```

**7.4 — Progress Reporting**

- WorkManager `setProgress()` updates a `Data` object with current photo name.
- GalleryViewModel observes `WorkManager.getWorkInfosByTagFlow("backup")` to drive a top notification or status bar indicator.

---

## 8. Phase 4 — Telegram Storage Integration

### Goal
Upload photos to the user's chosen Telegram chat using TDLib.

### Steps

**8.1 — TelegramUploadRepository**

```kotlin
// suspend fun uploadPhoto(localFilePath: String, chatId: Long): Long
// Uses TdApi.SendMessage with InputMessagePhoto
// InputFile = TdApi.InputFileLocal(localFilePath)
// Returns Telegram message ID on success
// Throws TelegramUploadException on failure
```

**8.2 — TdApi.SendMessage structure**

```kotlin
TdApi.SendMessage(
    chatId = targetChatId,
    messageThreadId = 0,
    replyTo = null,
    options = TdApi.MessageSendOptions(),
    replyMarkup = null,
    inputMessageContent = TdApi.InputMessagePhoto(
        photo = TdApi.InputFileLocal(filePath),
        thumbnail = null,
        addedStickerFileIds = IntArray(0),
        width = 0,
        height = 0,
        caption = TdApi.FormattedText("", emptyArray()),
        selfDestructType = null,
        hasSpoiler = false
    )
)
```

**8.3 — Upload Progress**

- TDLib fires `TdApi.UpdateFile` events with `file.remote.uploadedSize` vs `file.expectedSize`.
- TdLibClient's update SharedFlow can filter for these and emit progress.
- BackupWorker can collect this flow and call `setProgress()` to update WorkManager status.

**8.4 — Saved Messages Fallback**

- If no chat is selected in Settings, default to `Saved Messages` (chatId = user's own userId in TDLib).
- Retrieve it via `TdApi.GetMe` to get the user ID, then `TdApi.CreatePrivateChat(userId)`.

---

## 9. Phase 5 — Backup Status Overlay (Badge Icons)

### Goal
Show a small icon on each photo thumbnail that reflects its backup state, just like Google Photos.

### Badge States

| State | Icon | Description |
|---|---|---|
| `NOT_QUEUED` | _(no badge)_ | Photo not yet added to backup queue |
| `PENDING` | Cloud outline, white | Waiting in queue |
| `UPLOADING` | Animated spinner / progress arc | Currently uploading |
| `DONE` | Cloud + checkmark, white | Successfully backed up |
| `FAILED` | Cloud + X, red | Upload failed after retries |

### Implementation

**9.1 — PhotoGridItem Composable**

```kotlin
@Composable
fun PhotoGridItem(photo: LocalPhoto, status: BackupStatus, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().clickable { onClick() }) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(photo.uri)
                .size(300, 300)          // Only decode thumbnail size
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Gradient scrim at bottom-right for badge visibility
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(32.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent)
                    )
                )
        )
        // Badge icon
        BackupBadge(
            status = status,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .size(16.dp)
        )
    }
}
```

**9.2 — BackupBadge Composable**

```kotlin
@Composable
fun BackupBadge(status: BackupStatus, modifier: Modifier) {
    when (status) {
        BackupStatus.DONE -> Icon(
            painter = painterResource(R.drawable.ic_backup_done),
            tint = Color.White, contentDescription = "Backed up",
            modifier = modifier
        )
        BackupStatus.PENDING -> Icon(
            painter = painterResource(R.drawable.ic_backup_pending),
            tint = Color.White.copy(alpha = 0.7f), contentDescription = "Pending",
            modifier = modifier
        )
        BackupStatus.UPLOADING -> CircularProgressIndicator(
            modifier = modifier, strokeWidth = 1.5.dp, color = Color.White
        )
        BackupStatus.FAILED -> Icon(
            painter = painterResource(R.drawable.ic_backup_failed),
            tint = Color(0xFFFF5252), contentDescription = "Failed",
            modifier = modifier
        )
        BackupStatus.NOT_QUEUED -> { /* no badge */ }
    }
}
```

**9.3 — Reactive Updates**

- `GalleryViewModel` combines the `MediaStore Flow` with the `Room photoStatusDao.getAllStatuses()` Flow using `combine {}`.
- When Room updates a row (e.g., UPLOADING → DONE), the combined flow emits and Compose recomposes only the affected grid item.

---

## 10. Phase 6 — Settings Screen

### Goal
A clean Settings page with only the options needed for MVP.

### Settings Options

| Setting | Type | Description |
|---|---|---|
| Backup target chat | Tap to pick | Shows a list of user's Telegram chats; saves chatId to DataStore |
| Backup over Wi-Fi only | Toggle | Adds `UNMETERED` network constraint to WorkManager |
| Backup while charging | Toggle | Adds `requiresCharging = true` constraint |
| Backup now | Button | Immediately enqueues all pending photos |
| Account | Info row | Shows Telegram username/phone; Logout button |

### Chat Picker Flow

1. User taps "Backup target chat."
2. App calls `TdApi.GetChats` (limit 50, offset = null) to fetch recent chats.
3. A bottom sheet shows the list with chat names and avatars (loaded via TDLib's file system).
4. On selection, save `chatId: Long` to DataStore under key `backup_target_chat_id`.

**SettingsViewModel** observes the DataStore and exposes a `StateFlow<SettingsUiState>`.

---

## 11. Phase 7 — Background Sync Service

### Goal
Detect new photos even when app is closed and enqueue them automatically.

### Approach

**11.1 — ContentObserver + WorkManager**

```kotlin
// In a long-lived CoroutineScope tied to a ForegroundService or
// a periodic WorkManager job (every 15 min minimum):
//
// Option A (Recommended for battery): PeriodicWorkRequest every 15 minutes
//   - Scans MediaStore, diffs against Room, enqueues new photos
//
// Option B: Register ContentObserver in a lightweight foreground service
//   - More immediate but uses persistent notification
```

Use **Option A** for MVP (less battery drain, no persistent notification).

**11.2 — SyncWorker**

```kotlin
// SyncWorker.kt (PeriodicWorkRequest, 15 min interval)
// 1. Query all photo IDs from MediaStore
// 2. Query all IDs in Room with status = DONE
// 3. Diff = MediaStore IDs - DONE IDs
// 4. For each ID in diff: enqueue BackupWorker (KEEP policy)
// 5. Also check for deleted photos: IDs in Room but not in MediaStore → remove from Room
```

**11.3 — Enqueuing Periodic Sync**

```kotlin
// In MainActivity.onCreate() after auth:
WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "photo_sync",
    ExistingPeriodicWorkPolicy.KEEP,
    PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
        .setConstraints(networkConstraint)
        .build()
)
```

---

## 12. Phase 8 — UI/UX Polish (Google Photos Feel)

### Design Targets

- **Colors:** Dynamic color (Material You) on Android 12+; fallback to neutral grey on older versions.
- **Bottom navigation:** 3 tabs — Photos, Search (stub for v2), Library.
- **Top bar:** Transparent, shows "Photos" title + profile avatar (Telegram profile photo).
- **Pinch-to-zoom:** In the full-screen photo viewer, implement scale gesture with `detectTransformGestures`.
- **Swipe to dismiss:** In the photo viewer, swipe down to close (animate alpha + scale).
- **Haptic feedback:** Light haptic on long-press to enter selection mode.
- **Selection mode:** Long press a photo → enters multi-select mode (checkboxes appear). Action bar shows share/delete.

### Scrolling Performance

- `LazyVerticalGrid` with `rememberLazyGridState()`.
- Use `key = { item -> item.id }` on all grid items to prevent recomposition of unchanged items.
- Coil's `size(300, 300)` ensures only thumbnail-size bitmaps are decoded into memory.
- Avoid reading `items.size` in composition scope (causes full recomposition on any list change).

### Full-Screen Viewer

```
PhotoViewerScreen:
- HorizontalPager (Compose Pager) for swipe between photos
- AsyncImage with full resolution (no size override)
- Overlay controls fade after 2 seconds of inactivity
- Bottom bar: share, delete, info, backup status chip
```

---

## 13. Phase 9 — Memory & Crash Safety

This is critical for a lightweight app.

### Rules

1. **Never decode full Bitmaps in the grid.** Always use `ImageRequest.Builder().size(width, height)` with Coil. The grid cells are 300×300 max.
2. **TDLib temp files must be deleted.** BackupWorker must delete the temp file in both success and failure paths (use `try/finally`).
3. **ContentResolver streams must be closed.** Use `use {}` blocks when opening InputStreams from MediaStore.
4. **BackupWorker processes one file at a time.** WorkManager serializes workers per unique name; do not use parallelism for backup.
5. **Room queries on IO dispatcher.** All DAO calls in `withContext(Dispatchers.IO)`.
6. **ViewModel does not hold Context.** Pass `Application` via `AndroidViewModel` only if absolutely needed; prefer repositories.
7. **TDLib client is a singleton.** Never create multiple instances; all share one `TelegramClient`.
8. **Cache directory for temp files.** Use `context.cacheDir` (auto-cleared by system under memory pressure), not `filesDir`.

### Crash-Free Checklist

- [ ] Wrap all TDLib calls in `try/catch(TdException)` — network failures are common
- [ ] Handle `SecurityException` from MediaStore (user revokes permission mid-session)
- [ ] Handle `FileNotFoundException` if a photo is deleted while being uploaded
- [ ] Guard against null `targetChatId` in BackupWorker (check DataStore before starting)
- [ ] Handle WorkManager `ListenableFuture` cancellation gracefully
- [ ] Use `StrictMode` in debug builds to catch disk/network on main thread

### ANR Prevention

- **Never** call TDLib, Room, or MediaStore on the main thread.
- `GalleryViewModel` uses `viewModelScope` + `Dispatchers.IO` for data fetching.
- `Dispatchers.Default` for CPU-bound grouping/sorting logic.

---

## 14. Phase 10 — Testing & QA

### Unit Tests

| Class | What to Test |
|---|---|
| `GalleryViewModel` | Grouping logic produces correct date headers |
| `BackupRepository` | Skips already-DONE photos; correctly diffs MediaStore vs Room |
| `TelegramAuthRepository` | State machine transitions (mock TdLibClient) |
| `SyncWorker` | Correctly identifies new and deleted photos |

### Instrumentation Tests

| Test | Description |
|---|---|
| Gallery load | MediaStore returns photos → grid renders correct count |
| Badge update | Inserting a DONE status into Room → badge updates in Compose |
| Settings persistence | Selecting a chat → chatId persisted → correct after restart |

### Manual QA Checklist

- [ ] Cold start < 2 seconds on a mid-range device
- [ ] Scroll 500+ photos with no jank (Systrace / Perfetto)
- [ ] Upload 10 photos sequentially; confirm each appears in Telegram
- [ ] Kill app mid-upload; restart; confirm upload resumes
- [ ] Revoke storage permission; app shows permission rationale screen (no crash)
- [ ] No crash when network drops during upload (retries correctly)
- [ ] Badge shows correctly after reinstall (Room persists via device backup)
- [ ] Telegram logout clears session and returns to auth screen

---

## 15. Data Models

### Domain

```kotlin
// LocalPhoto.kt
data class LocalPhoto(
    val id: Long,
    val uri: Uri,
    val dateTaken: Long,
    val dateModified: Long,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val bucketName: String
)

// BackupStatus.kt
enum class BackupStatus {
    NOT_QUEUED,
    PENDING,
    UPLOADING,
    DONE,
    FAILED
}

// GalleryItem.kt
sealed class GalleryItem {
    data class Header(val label: String) : GalleryItem()
    data class Photo(
        val photo: LocalPhoto,
        val backupStatus: BackupStatus
    ) : GalleryItem()
}

// TelegramChat.kt
data class TelegramChat(
    val id: Long,
    val title: String,
    val photoPath: String?    // Local file path of chat avatar
)
```

---

## 16. Database Schema (Room)

### Table: `photo_backup_status`

```sql
CREATE TABLE photo_backup_status (
    media_id       INTEGER PRIMARY KEY NOT NULL,   -- MediaStore image _ID
    status         TEXT NOT NULL DEFAULT 'PENDING', -- BackupStatus enum name
    telegram_msg_id INTEGER,                        -- Telegram message ID after upload
    last_updated   INTEGER NOT NULL,               -- epoch ms
    retry_count    INTEGER NOT NULL DEFAULT 0
);
```

### DAO

```kotlin
@Dao
interface PhotoStatusDao {
    @Query("SELECT * FROM photo_backup_status")
    fun getAllStatuses(): Flow<List<PhotoStatusEntity>>

    @Query("SELECT status FROM photo_backup_status WHERE media_id = :id")
    suspend fun getStatus(id: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PhotoStatusEntity)

    @Query("UPDATE photo_backup_status SET status = :status, last_updated = :ts WHERE media_id = :id")
    suspend fun updateStatus(id: Long, status: String, ts: Long = System.currentTimeMillis())

    @Query("DELETE FROM photo_backup_status WHERE media_id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT media_id FROM photo_backup_status WHERE status = 'DONE'")
    suspend fun getDoneIds(): List<Long>
}
```

---

## 17. Key API Calls — Telegram Bot/MTProto

All via TDLib `TelegramClient.send()` wrapped in a `suspendCoroutine`.

```kotlin
// Get current user
TdApi.GetMe()
// → TdApi.User (use user.id as "Saved Messages" chatId)

// List chats (for settings picker)
TdApi.GetChats(chatList = TdApi.ChatListMain(), limit = 50)

// Create / get Saved Messages chat
TdApi.CreatePrivateChat(userId = me.id, force = false)
// → TdApi.Chat with chat.id

// Send photo
TdApi.SendMessage(chatId, 0, null, TdApi.MessageSendOptions(), null, inputMessagePhoto)

// Auth flow
TdApi.SetAuthenticationPhoneNumber(phoneNumber, settings)
TdApi.CheckAuthenticationCode(code)
TdApi.CheckAuthenticationPassword(password) // if 2FA enabled
```

### Error Handling for TDLib

```kotlin
// TdApi.Error has .code (Int) and .message (String)
// Common codes:
// 400 — bad request (wrong OTP, etc.)
// 401 — unauthorized (session expired)
// 420 — FLOOD_WAIT_X (rate limited; parse X seconds from message)
// 500 — internal TDLib error
```

---

## 18. Permissions

```xml
<!-- AndroidManifest.xml -->

<!-- Media access (Android 13+) -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />

<!-- Media access (Android 12 and below) -->
<uses-permission
    android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />

<!-- Network -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- WorkManager background work -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- Wake lock for background upload (WorkManager handles this internally) -->
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

### Runtime Permission Flow

1. On first launch after auth, check `READ_MEDIA_IMAGES` (Android 13+) or `READ_EXTERNAL_STORAGE`.
2. Use Accompanist Permissions to show rationale if denied.
3. If permanently denied, show a dialog linking to App Settings.
4. Register a `ContentObserver` only after permission is granted.

---

## 19. Manifest Overview

```xml
<application
    android:name=".TeleGalleryApp"
    android:label="TeleGallery"
    android:icon="@mipmap/ic_launcher"
    android:theme="@style/Theme.TeleGallery"
    android:hardwareAccelerated="true"
    android:largeHeap="false"              <!-- Keep this false — memory discipline -->
    android:allowBackup="true">

    <activity
        android:name=".MainActivity"
        android:exported="true"
        android:windowSoftInputMode="adjustResize">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>

    <!-- WorkManager worker declarations (auto via Hilt) -->

    <!-- FileProvider for sharing photos -->
    <provider
        android:name="androidx.core.content.FileProvider"
        android:authorities="${applicationId}.fileprovider"
        android:exported="false"
        android:grantUriPermissions="true">
        <meta-data
            android:name="android.support.FILE_PROVIDER_PATHS"
            android:resource="@xml/file_paths" />
    </provider>

</application>
```

---

## 20. Dependency List (Gradle)

```kotlin
// build.gradle.kts (app)

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Jetpack Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.04.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51")
    kapt("com.google.dagger:hilt-compiler:2.51")
    implementation("androidx.hilt:hilt-work:1.2.0")
    kapt("androidx.hilt:hilt-compiler:1.2.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Coil
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Accompanist (permissions)
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // Pager (for photo viewer)
    implementation("androidx.compose.foundation:foundation") // includes Pager

    // TDLib (add as local .aar file — see Phase 1)
    implementation(files("libs/tdlib.aar"))

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
```

---

## 21. Risk & Mitigation Table

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| TDLib integration complexity (JNI setup, build time) | High | High | Use prebuilt `.aar` from `https://github.com/nicagram/TDLib-Android`; avoid building from source for MVP |
| TDLib flood limits (FLOOD_WAIT) during bulk upload | Medium | Medium | Parse `FLOOD_WAIT_X` from TdApi.Error.message; pass as WorkManager backoff delay |
| Telegram changes file size limits (currently 2GB for bots, 4GB for users) | Low | Low | Photos are well under limit; log a warning if file > 10MB |
| User uploads to wrong chat by mistake | Medium | Low | Show a confirmation dialog on first-time setup; allow easy change in Settings |
| Background work killed by OEM battery optimizations (Xiaomi, Huawei) | High | Medium | Document "disable battery optimization" instructions in onboarding; use `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| MediaStore URI invalidated before upload | Low | Medium | BackupWorker catches `FileNotFoundException` and marks FAILED; user can retry |
| App crashes due to Coil loading too many bitmaps | Medium | High | Set memory cache to 20% of RAM; always specify thumbnail size in requests |
| 2FA users blocked at login | Medium | Medium | Implement `WAIT_PASSWORD` state in auth state machine |

---

## 22. Delivery Milestones

| Milestone | Deliverable | Estimated Effort |
|---|---|---|
| M1 | Project setup, TDLib integrated, phone login working, OTP screen | 3–4 days |
| M2 | Gallery screen rendering local photos in grid with date groups | 2–3 days |
| M3 | Room DB + BackupStatus model; badge overlay on thumbnails | 1–2 days |
| M4 | BackupWorker: upload one photo to Saved Messages successfully | 2–3 days |
| M5 | Settings screen: chat picker, Wi-Fi only, charging toggles | 1–2 days |
| M6 | SyncWorker (periodic background scan + enqueue) | 1–2 days |
| M7 | Full-screen photo viewer with swipe, pinch-to-zoom | 2 days |
| M8 | UI polish: Material You, animations, selection mode | 2–3 days |
| M9 | Memory audit, crash-free checklist, StrictMode review | 1–2 days |
| M10 | QA, fix regressions, Play Store build prep | 2–3 days |

**Total estimated effort: 17–26 developer-days**

---

## Appendix A — TDLib Setup Notes

1. Get `API_ID` and `API_HASH` from [https://my.telegram.org/apps](https://my.telegram.org/apps) — register your app there.
2. Store these as `BuildConfig` fields (never hardcode in source):
   ```kotlin
   // local.properties
   TELEGRAM_API_ID=123456
   TELEGRAM_API_HASH=abcdef1234567890abcdef
   ```
   ```kotlin
   // build.gradle.kts
   buildConfigField("int", "TELEGRAM_API_ID", project.properties["TELEGRAM_API_ID"].toString())
   buildConfigField("String", "TELEGRAM_API_HASH", "\"${project.properties["TELEGRAM_API_HASH"]}\"")
   ```
3. TDLib database path must be under `context.filesDir` — not external storage.
4. Call `TdApi.SetLogVerbosityLevel(0)` in release builds to silence TDLib logs.

## Appendix B — File Paths XML (FileProvider)

```xml
<!-- res/xml/file_paths.xml -->
<paths>
    <cache-path name="temp_uploads" path="telegram_uploads/" />
    <external-media-path name="shared_photos" path="." />
</paths>
```

## Appendix C — Recommended TDLib AAR Source

For Android prebuilt TDLib without compiling from source:
- Repository: `https://github.com/nicagram/TDLib-Android`
- Alternatively use Telegram's official instructions: `https://tdlib.github.io/td/build.html`

Always verify the TDLib version matches the TdApi class definitions you're using.

---

*End of TeleGallery Implementation Plan*
*Version 1.0 — Ready to feed to coding agent*
