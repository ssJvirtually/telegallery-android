**Top Improvements (Prioritized)**

1. **High: dedup logic can skip/overwrite wrong photos when names collide**
   - You’re matching cloud/local by `fileName` only (`findByFileName`, `associateBy { it.name }`), so `IMG_0001.jpg` from different folders/devices can be treated as same file.
   - References: [UploadWorker.kt:79](/E:/telegallery-calude/app/src/main/java/com/example/tguploader/worker/UploadWorker.kt:79), [PhotosGridScreen.kt:226](/E:/telegallery-calude/app/src/main/java/com/example/tguploader/ui/screens/PhotosGridScreen.kt:226), [SearchScreen.kt:122](/E:/telegallery-calude/app/src/main/java/com/example/tguploader/ui/screens/SearchScreen.kt:122), [UploadDatabase.kt:65](/E:/telegallery-calude/app/src/main/java/com/example/tguploader/storage/UploadDatabase.kt:65)
   - Improve by using a stable content fingerprint (`sha256(size + bytes)` or at least `size + dateTaken + displayName`) and index on that.

2. **High: destructive migration can wipe sync history**
   - `fallbackToDestructiveMigration()` will drop DB on schema bumps, which is risky for backup catalogs.
   - Reference: [UploadDatabase.kt:93](/E:/telegallery-calude/app/src/main/java/com/example/tguploader/storage/UploadDatabase.kt:93)
   - Add explicit Room migrations and migration tests.

3. **High: app startup clears entire cache directory**
   - Deleting all `cacheDir` files can remove unrelated caches and race with in-flight tasks.
   - Reference: [TdlibManager.kt:54](/E:/telegallery-calude/app/src/main/java/com/example/tguploader/telegram/TdlibManager.kt:54)
   - Use a dedicated subfolder for upload temp files and only clean those.

4. **High: `GlobalScope` in TDLib lifecycle can leak work**
   - Re-init logic uses `GlobalScope.launch`, which is not lifecycle-bound.
   - Reference: [TdlibManager.kt:94](/E:/telegallery-calude/app/src/main/java/com/example/tguploader/telegram/TdlibManager.kt:94)
   - Replace with an app-level `CoroutineScope(SupervisorJob + Dispatchers.Default)` managed by `TdlibManager`.

5. **Medium: upload success handling can leave hanging continuations**
   - `pendingUploads` depends on update callbacks; if process dies/network edge cases occur, continuations can leak or never resume.
   - References: [UploadManager.kt:172](/E:/telegallery-calude/app/src/main/java/com/example/tguploader/telegram/UploadManager.kt:172), [TdlibManager.kt:34](/E:/telegallery-calude/app/src/main/java/com/example/tguploader/telegram/TdlibManager.kt:34)
   - Add timeout/cancellation cleanup and retry policy around send completion.

6. **Medium: metadata in caption is brittle and privacy-sensitive**
   - EXIF + JSON metadata in message caption is parse-fragile and exposes camera/device info.
   - References: [UploadManager.kt:123](/E:/telegallery-calude/app/src/main/java/com/example/tguploader/telegram/UploadManager.kt:123), [UploadManager.kt:149](/E:/telegallery-calude/app/src/main/java/com/example/tguploader/telegram/UploadManager.kt:149)
   - Prefer compact machine tag + encrypted/serialized metadata blob (or local-only metadata store).

7. **Medium: backup/restore flow should verify integrity**
   - DB backup upload/restore has no checksum/signature verification.
   - References: [BackupManager.kt:29](/E:/telegallery-calude/app/src/main/java/com/example/tguploader/storage/BackupManager.kt:29), [BackupManager.kt:112](/E:/telegallery-calude/app/src/main/java/com/example/tguploader/storage/BackupManager.kt:112)
   - Add hash verification before replacing local DB.

8. **Medium: mixed Material/AppCompat theme setup**
   - Manifest activity uses AppCompat theme while UI is Compose Material3.
   - References: [AndroidManifest.xml:16](/E:/telegallery-calude/app/src/main/AndroidManifest.xml:16), [AndroidManifest.xml:22](/E:/telegallery-calude/app/src/main/AndroidManifest.xml:22)
   - Move to a Material3 Compose-first theme to avoid style inconsistencies.

9. **Medium: `allowBackup=true` may expose app data**
   - For a sensitive media index app, default Android backup can be risky.
   - Reference: [AndroidManifest.xml:11](/E:/telegallery-calude/app/src/main/AndroidManifest.xml:11)
   - Consider `android:allowBackup="false"` (or define precise backup rules).

10. **Low: API credentials embedded in `BuildConfig`**
   - Telegram API ID/hash are compiled into app constants.
   - Reference: [app/build.gradle:34](/E:/telegallery-calude/app/build.gradle:34)
   - Treat as public-ish client secrets and reduce abuse risk via backend-issued short-lived config or stricter account controls.

**What’s already strong**
- Clear separation of scanner/storage/upload/worker modules.
- Good use of WorkManager constraints.
- Thoughtful UX features (selection mode, zoomable grid, fast scroll behavior).

**Testing gaps to add next**
1. Dedup correctness tests (same filename, different content).
2. Room migration tests for every schema version step.
3. Upload retry/timeout tests for TDLib callback failure paths.
4. Backup/restore integrity tests (corrupt backup detection).
