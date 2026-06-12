# TGPix — Thumbnail Loading Strategy Review
### Technical Audit & Improvement Suggestions

> **Guide:**
> - 🔴 **Critical** — Technically wrong or will cause bugs
> - 🟡 **Important** — Inaccurate, incomplete, or missing edge case
> - 🟢 **Improvement** — Clarity, correctness, or future-proofing gain

---

## Section 1 — Architecture Overview

### 🟡 Sequence Diagram Missing All Error Paths

The diagram shows only the happy path. In production, each tier can fail and the
fallback behavior must be defined:

```
Tier 3 (GetMessage) can fail:
  - TDLib not yet authenticated (app just started)
  - Network unavailable
  - Message deleted from Telegram by user
  - chatId stale (vault channel deleted)

Tier 4 (DownloadFile) can fail:
  - Network timeout
  - Disk full
  - TDLib flood wait
  - File deleted from Telegram server
```

Add failure paths to the diagram and document what the UI shows in each case.
The minimum viable error states are:

```kotlin
sealed class ThumbnailState {
    object Loading : ThumbnailState()
    data class Ready(val path: String) : ThumbnailState()
    object Unavailable : ThumbnailState()   // message deleted from Telegram
    object NetworkError : ThumbnailState()  // retry possible
    object DiskFull : ThumbnailState()      // user action needed
}
```

---

### 🟡 Diagram Shows GetMessage Called Even When Tier 2 Has Cached Path

Step 6 in the sequence diagram shows `GetMessage` being called after Tier 2 returns
a path. This contradicts the logic — if Tier 2 returns a valid local file path and
the file exists on disk, GetMessage should never be called. The file is already
downloaded.

**Fix:** Add an explicit branch in the diagram:

```
Tier 2 hit AND file exists on disk → return path directly → DONE (no GetMessage)
Tier 2 miss OR file missing on disk → proceed to Tier 3 (GetMessage)
```

This is likely how the code works already — the diagram just doesn't show it correctly.

---

## Section 2 — Multi-Tiered Cache Architecture

### 🔴 Coil's Own Cache Layer Is Completely Missing

The document describes a 4-tier cache but omits the most important cache layer that
already exists — **Coil's memory and disk cache**.

When `AsyncImage` loads a bitmap from a local file path, Coil:
1. Checks its **memory cache** (decoded `Bitmap` objects in RAM) — ~0ms
2. Checks its **disk cache** (encoded file data on disk) — ~1ms
3. Reads from the provided file path — ~5ms

TGPix's `ThumbnailWriteBuffer` (Tier 1) caches the **file path** (a String).
Coil's memory cache caches the **decoded Bitmap**.

These are different things and both are needed. The document should acknowledge
this and show the complete picture:

```
Actual cache hierarchy:
  Tier 0: Coil memory cache (decoded Bitmap)        ~0ms   ← NOT DOCUMENTED
  Tier 1: ThumbnailWriteBuffer (file path string)   ~0ms
  Tier 2: SQLite cloud_photos (persisted file path) ~2ms
  Tier 3: TDLib local DB (session file IDs)         ~50ms
  Tier 4: Telegram network (binary download)        variable
```

Coil's cache is the first thing checked before any of TGPix's tiers. If a thumbnail
was recently displayed, Coil serves it from decoded bitmap memory without touching
ThumbnailWriteBuffer, SQLite, or TDLib at all.

Make sure Coil is configured correctly to take full advantage of this:

```kotlin
// In TGPixApplication.kt — ensure Coil cache is sized appropriately
ImageLoader.Builder(context)
    .memoryCache {
        MemoryCache.Builder(context)
            .maxSizePercent(0.25)   // 25% of available RAM for decoded bitmaps
            .build()
    }
    .diskCache {
        DiskCache.Builder()
            .directory(context.cacheDir.resolve("coil_thumbnails"))
            .maxSizeBytes(100 * 1024 * 1024)   // 100MB disk cache
            .build()
    }
    .build()
```

---

### 🟡 Tier 3 Cost Claim is Misleading — Two Very Different Scenarios

```
| Tier 3 | TDLib Database | ~15-50ms |
```

This is only accurate when TDLib has the message **already cached in its local
C++ SQLite database**. There are two distinct scenarios:

| Scenario | Cost | When |
|---|---|---|
| TDLib has message cached locally | ~15–50ms | Normal operation |
| TDLib must fetch from Telegram network | 200ms–2s+ | Cold start, session reset, message not yet synced |

The document should acknowledge both:

> "GetMessage resolves from TDLib's local C++ cache in ~15–50ms under normal
> operation. On first run, after session reset, or for very old messages not yet
> in TDLib's local database, the call fetches from Telegram servers and may take
> 200ms–2 seconds or longer depending on network conditions."

---

## Section 3 — Session-Scoped File IDs

### 🟡 "Always Calls GetMessage First" Contradicts Tier 2 Logic

Section 3 states:

> "It always calls `TdApi.GetMessage(chatId, messageId)` first."

But Section 2's Tier 2 returns the cached local file path directly to the UI
**without calling GetMessage** when the file exists on disk.

These two statements contradict each other. The correct rule is:

```
IF local file exists on disk (Tier 1 or Tier 2 cache hit):
    → Return path directly. GetMessage is NEVER called.
    → The fileId is irrelevant — we have the actual bytes already.

IF local file does NOT exist on disk:
    → Call GetMessage to get a fresh fileId for this session.
    → Call DownloadFile with the fresh fileId.
    → Cache the resulting path.
```

The "always GetMessage first" rule only applies when a download is needed.
Clarify this in Section 3.

---

### 🟢 `fileIdCachedAt` Column Interaction Not Documented

The `cloud_photos` table has a `fileIdCachedAt` column specifically to track
when a session fileId was last refreshed. The thumbnail loading strategy should
reference this:

```kotlin
// Before calling GetMessage, check if cached fileId is still fresh
fun isFileIdFresh(entity: CloudPhotoEntity): Boolean {
    val age = System.currentTimeMillis() - (entity.fileIdCachedAt ?: 0L)
    return age < 30 * 60 * 1000L  // treat as stale after 30 minutes
}

// Only call GetMessage if fileId is stale or null
val fileId = if (isFileIdFresh(entity)) {
    entity.telegramThumbnailFileId   // reuse cached fileId
} else {
    val msg = tdClient.send(TdApi.GetMessage(chatId, entity.messageId))
    // extract fresh fileId, update fileIdCachedAt in DB
    extractAndCacheFreshFileId(msg, entity.messageId)
}
```

This avoids unnecessary GetMessage calls when the cached fileId is still valid,
reducing TDLib JNI overhead significantly during normal scrolling.

---

## Section 4 — Performance Guardrails

### 🔴 ThumbnailWriteBuffer Loses All Pending Paths on App Kill

The buffer flushes every 2 seconds. If the app is killed (OOM, user swipe, crash)
while there are pending entries, all paths in the buffer are lost.

**Impact:** On next launch, the grid re-downloads thumbnails it already downloaded in
the previous session. For a user who scrolled through 500 thumbnails and then
the app was killed, all 500 re-download on next open.

**Fix — Persist the buffer to a lightweight fast store before each flush:**

Option A — Accept the loss (simplest):
Document it as a known limitation. The thumbnails will just re-download. No data
is lost, only bandwidth and time.

Option B — Write to Coil's disk cache instead of a custom buffer:
Coil's disk cache survives app kill. After downloading a thumbnail, write it to
Coil's cache with the messageId as the key. On next launch, Coil serves it from
disk cache without needing to re-download. The SQLite path write can be deferred.

```kotlin
// After TDLib download completes:
val bitmap = BitmapFactory.decodeFile(downloadedPath)

// Write to Coil disk cache — survives app kill
val cacheKey = MemoryCache.Key("thumb_${messageId}")
imageLoader.memoryCache?.set(cacheKey, MemoryCache.Value(bitmap))

// Also queue the SQLite write (best effort, 2s flush)
thumbnailWriteBuffer.enqueue(messageId, downloadedPath)
```

---

### 🟡 "95% Reduction" Claim Has No Evidence

> "Reduces database write and invalidation operations by up to 95% during rapid scrolling."

This is stated as a fact but has no benchmark data or methodology to support it.
Either remove the percentage claim or replace with a measured observation:

> "During testing, batching thumbnail writes reduced Room Flow invalidations from
> approximately one per thumbnail download to one per 2-second flush cycle,
> significantly reducing grid recomposition frequency during fast scrolling."

---

### 🟡 Raw SQLite in `runInTransaction` Mixes Abstractions

```kotlin
db.runInTransaction {
    val sqLite = db.openHelper.writableDatabase
    sqLite.execSQL("UPDATE cloud_photos SET localCachedThumbnailPath = ? ...", args)
}
```

Mixing Room's `runInTransaction` with raw `openHelper.writableDatabase` access is
a code smell that bypasses Room's entity validation and type converters. It also
makes the flush logic incompatible with any future Room migration that renames
columns.

**Prefer a Room DAO method inside a transaction:**

```kotlin
// In CloudPhotoDao
@Transaction
suspend fun batchUpdateThumbnailPaths(updates: List<ThumbnailPathUpdate>) {
    updates.forEach { updateThumbnailPath(it.messageId, it.path) }
}

// ThumbnailWriteBuffer flush
suspend fun flush(db: UploadDatabase) = withContext(Dispatchers.IO) {
    if (pending.isEmpty()) return@withContext
    val snapshot = pending.entries.map { ThumbnailPathUpdate(it.key, it.value) }
    pending.clear()
    db.cloudDao().batchUpdateThumbnailPaths(snapshot)
}
```

This keeps all DB access through Room's type-safe layer.

---

### 🟢 Concurrent Download Race — Same Thumbnail Requested Twice

What happens when two grid cells request the same `messageId` thumbnail
simultaneously? (This happens when the user scrolls back to a recently visible
cell before the first download completes.)

Currently:
- Cell A requests messageId=123 → starts GetMessage + DownloadFile
- Cell B requests messageId=123 → also starts GetMessage + DownloadFile
- Both downloads run in parallel → same file downloaded twice

**Fix — Track in-flight downloads:**

```kotlin
// In GalleryUtils or ThumbnailLoader
private val inFlightDownloads = ConcurrentHashMap<Long, Deferred<String?>>()

suspend fun getOrDownloadThumbnail(messageId: Long): String? {
    // Return existing in-flight download if one is already running
    inFlightDownloads[messageId]?.let { return it.await() }

    val deferred = coroutineScope {
        async {
            performThumbnailDownload(messageId)
        }
    }
    inFlightDownloads[messageId] = deferred
    return try {
        deferred.await()
    } finally {
        inFlightDownloads.remove(messageId)
    }
}
```

---

## Section 5 — Concurrency Control

### 🔴 `@Transaction` on Large Flow Queries Blocks Writers During Mapping

The document states:

> "This freezes the database view (WAL read snapshot) for the entire duration of
> the query and mapping sequence"

This is accurate but incomplete. For `getAllFlow()` returning 26,000 rows, the
transaction is held open for the entire duration of:
1. Executing the SELECT query
2. Reading all rows into CursorWindow chunks
3. Mapping every row to a `CloudPhotoEntity` object

For 26,000 rows this mapping can take **100–500ms**. During this window, all
write operations (thumbnail path writes, new uploads being indexed) are **blocked**.

This is an acceptable tradeoff for correctness (preventing the CursorWindow crash)
but the document should acknowledge it. The proper production solution for
galleries of this size is **Paging 3**:

```kotlin
// Room Paging 3 — only loads visible rows, no transaction held for full dataset
@Query("SELECT * FROM cloud_photos WHERE isTrashed = 0 ORDER BY dateTaken DESC")
fun getAllPaged(): PagingSource<Int, CloudPhotoEntity>

// In ViewModel
val photos = Pager(PagingConfig(pageSize = 60)) {
    db.cloudDao().getAllPaged()
}.flow.cachedIn(viewModelScope)
```

Paging 3 loads only the rows needed for the current viewport (e.g., 60 at a time)
rather than all 26,000. No large transaction is held, no massive list in memory,
and grid scrolling is smooth at any library size. This is how Google Photos handles
100,000+ item grids.

Document this as the planned upgrade path for v2.

---

### 🟡 `@Transaction` Annotation Behavior on Flow Is Mischaracterized

The document says:

> "This freezes the database view for the entire duration of the query and mapping sequence"

This is true per **emission** — not for the entire lifetime of the Flow. Each time
the underlying data changes and Room re-emits, a new transaction is opened,
the query runs, mapping completes, and the transaction closes. The Flow itself
lives indefinitely but the transaction is scoped to each re-emission cycle.

The document implies the transaction is held for the Flow's entire lifetime which
would be catastrophic (blocking all writers forever). Clarify:

> "For each emission cycle triggered by a data change, `@Transaction` opens a
> read snapshot, executes the query, maps all rows to entities within that
> snapshot, then closes the transaction. The Flow remains active across multiple
> emission cycles, but each cycle opens and closes its own transaction independently."

---

## Video Support Compatibility

The document makes no mention of video. Since video support is planned, verify
that each tier handles video thumbnails correctly:

| Tier | Photo | Video | Gap |
|---|---|---|---|
| Tier 0 (Coil cache) | ✅ bitmap | ✅ bitmap (first frame) | None — same |
| Tier 1 (WriteBuffer) | ✅ path | ✅ path | None — same |
| Tier 2 (SQLite) | ✅ `localCachedThumbnailPath` | ✅ same column | None |
| Tier 3 (GetMessage) | ✅ `MessageDocument` | ✅ `MessageVideo` or `MessageDocument` | Parse both types |
| Tier 4 (DownloadFile) | ✅ thumbnail blob | ✅ thumbnail blob | None — same |

The only change needed for video is in the `parseAndIndexUploadedMessage` function
— it needs to handle `TdApi.MessageVideo` in addition to `TdApi.MessageDocument`
when extracting thumbnail file IDs.

Add this to the document as a forward-compatibility note.

---

## Missing Sections to Add

### 🟢 Cache Eviction Policy Not Documented

What happens when the device runs low on disk space? Thumbnails cached in
`localCachedThumbnailPath` accumulate indefinitely. The document should specify:

```
Cache directory: context.cacheDir/tgpix_thumbnails/
Max size: 200MB (configurable)
Eviction policy: LRU — oldest accessed files deleted first when limit reached
Android system: OS can also clear context.cacheDir under memory pressure
  → app handles this gracefully by treating missing files as Tier 2 miss
     and re-downloading from TDLib on next access
```

---

### 🟢 Thumbnail Size Constraints Not Documented

The thumbnail download strategy should specify the size constraints established
earlier:

```
Max dimensions: 320 × 320 px
Max file size: 200KB
Format: JPEG only
```

These constraints are enforced at upload time (when the thumbnail is created and
sent to Telegram). The download tier always receives a thumbnail within these
bounds.

---

## Summary Table

| # | Severity | Section | Issue |
|---|---|---|---|
| 1 | 🔴 Critical | Section 1 | Error paths missing from sequence diagram |
| 2 | 🔴 Critical | Section 2 | Coil's cache layer completely missing from hierarchy |
| 3 | 🔴 Critical | Section 4 | WriteBuffer loses all pending paths on app kill |
| 4 | 🔴 Critical | Section 5 | `@Transaction` blocks writers for 100–500ms on 26k row mapping |
| 5 | 🟡 Important | Section 1 | Diagram shows GetMessage called even on Tier 2 cache hit |
| 6 | 🟡 Important | Section 2 | Tier 3 cost claim misleading — two very different scenarios |
| 7 | 🟡 Important | Section 3 | "Always calls GetMessage first" contradicts Tier 2 logic |
| 8 | 🟡 Important | Section 4 | Raw SQLite in `runInTransaction` bypasses Room's type safety |
| 9 | 🟡 Important | Section 4 | Concurrent download of same messageId not prevented |
| 10 | 🟡 Important | Section 5 | `@Transaction` on Flow lifetime mischaracterized |
| 11 | 🟢 Improve | Section 3 | `fileIdCachedAt` interaction not documented |
| 12 | 🟢 Improve | Section 4 | "95% reduction" claim unsubstantiated |
| 13 | 🟢 Improve | Section 5 | Paging 3 as upgrade path not mentioned |
| 14 | 🟢 Improve | Missing | Cache eviction policy not documented |
| 15 | 🟢 Improve | Missing | Thumbnail size constraints not documented |
| 16 | 🟢 Improve | Missing | Video thumbnail compatibility not addressed |

---

**Highest priority fixes for your agent:**
1. Add Coil cache as Tier 0 in the hierarchy and configure it properly
2. Add in-flight download deduplication (issue 9) — this is actively causing
   duplicate TDLib calls during fast scroll
3. Clarify the `@Transaction` scope per emission vs per Flow lifetime (issue 10)
4. Plan Paging 3 migration for v2 — it solves both the transaction blocking
   issue and memory pressure on large libraries in one architectural change

*Review v1.0 — thumbnail_loading_strategy.md*
