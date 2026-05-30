# 📝 TeleGallery Android — Version Release Changelog

This changelog documents the version releases, features, bug fixes, and architectural enhancements made to **TeleGallery** — the premium, lightweight Google Photos-like Android application that utilizes private Telegram channels for unlimited, secure media backup.

---

## 🚀 Release v2.2.0 — Custom Premium Launcher Icon & Density Mipmaps
* **Features & Technical Updates:**
  * **Custom Premium Icon:** Replaced the default application launcher icon with a custom-designed, gorgeous premium branding asset blending Telegram's paper airplane and Google Photos pinwheel colors.
  * **Density Mipmaps Generation:** Used advanced Lanczos downsampling to generate and register standard density launcher icons across all standard Android device directories (`mipmap-mdpi` [48x48], `mipmap-hdpi` [72x72], `mipmap-xhdpi` [96x96], `mipmap-xxhdpi` [144x144], `mipmap-xxxhdpi` [192x192]).
  * **Manifest Icon Mapping:** Updated `AndroidManifest.xml` launcher icon and round icon attributes to point cleanly to `@mipmap/ic_launcher` instead of legacy single-density drawables, enabling standard scaling on modern home screens.

---

## 🚀 Release v2.1.0 — Premium Light Theme & Smooth Fast Scrollbar
* **Features & Technical Updates:**
  * **Premium Light Theme:** Transitioned the entire app to a beautiful, clean light aesthetic blending Telegram Blue (`#2481CC`) and clean Google Photos surfaces. Uses clean off-white background (`#F4F6F9`), pure white surfaces (`#FFFFFF`), light blue-grey variants (`#E8EDF5`), and refined cool-grey typography.
  * **Continuous Smooth Fast Scrollbar:** Engineered a sub-pixel precision linear fast scrollbar. Replaced discrete integer jumps with a continuous row-based scrolling formula using first-visible-item pixel offset ratio: `smoothIndex = firstVisibleIndex + itemOffsetFraction * columns`.
  * **Seamless Drag Gestures:** Configured linear finger-grabbing math mapping coordinate ratios directly to item indices and sub-pixel offsets `(gridState.scrollToItem(targetIndex, scrollOffset))`, delivering buttery-smooth scrolling with zero stutter.
  * **Glassmorphic Floating Date Bubble:** Integrated a floating month/year bubble that slides perfectly in sync next to the scrollbar pill.
  * **Polished Light Components:** Revamped card shapes, log console, buttons, and switches with a refined, premium light-mode appearance.

---

## 🚀 Release v2.0.0 — Unified Local & Cloud Photos Sync
* **Features & Technical Updates:**
  * **Unified Grid Engine:** Combines local MediaStore photos and indexed vault channel logs in a single seamless grid. Deduplicates synchronized photos (showing a green checkmark badge) and handles cloud-only items (showing a premium blue cloud download badge).
  * **Room Database Integration:** Enhanced `UploadDatabase` to support `CloudPhotoEntity` cache, with a automatic destructive migration protocol.
  * **History Crawling Engine:** Added synchronous and paginated server sweeps pulling from `GetChatHistory` to scan, resolve, and cache file metadata upon onboarding or startup.
  * **On-Demand Loading Pipeline:** Features lazy JNI download queries `TdApi.DownloadFile` for thumbnails and full-resolution images, streaming high-res photos on-demand in the detail view with circular spinners.
  * **Public Device Downloads:** Integrated a "Save to Device" drawer button, allowing users to save files directly into the Android standard public `Downloads` directory.

---

## 🚀 Release v1.9.0 — Clean Logout Lifecycle
* **Features & Technical Updates:**
  * Implemented an automated client lifecycle re-initialization routine to prevent logout hang issues.
  * Captures the TDLib `AuthorizationStateClosed` update inside `TdlibManager.kt` upon session termination.
  * Resets the JNI client instance to `null` and launches a detached coroutine with a `500ms` teardown delay. This grace period guarantees that the JNI JNI/SQLite database locks are cleanly released by the OS before starting the new client.
  * Programmatically re-initializes a fresh client instance, which immediately receives `WaitTdlibParameters` and automatically transitions the user back to the **Phone Sign Up (OTP) Screen**.

---

## 🚀 Release v1.8.0 — Guaranteed Welcome Message Pinning
* **Features & Technical Updates:**
  * Resolved welcome message pinning failures caused by JNI temporary ID mismatches.
  * Replaced immediate pinning with a suspending handler: `sendMessageAndWait()`.
  * The handler registers a continuation in `TdlibManager.pendingUploads` and suspends execution, waiting for TDLib's native `UpdateMessageSendSucceeded` event.
  * Once the Telegram server receives the welcome message and assigns a **positive server-side message ID**, the continuation resumes. The app then requests the pin using the official server ID, guaranteeing that the sync welcome card is **pinned successfully every time**.

---

## 🚀 Release v1.7.0 — Multi-Device Recovery & UI Thread Fixes
* **Features & Technical Updates:**
  * Cured sign-up setup crashes caused by Android thread policy violations. Wrapped WorkManager Toast triggers inside `runOnUiThread { ... }` so they always execute safely on the UI Main thread, eliminating the `Can't toast on a thread that has not called Looper.prepare()` fatal crash.
  * Replaced local client-side `SearchChats()` with server-side `TdApi.SearchChatsOnServer()` during recovery. Since fresh logins on new devices start with an empty local cache, a server-side search ensures that pre-existing `"TeleGallery"` channels are successfully retrieved directly from Telegram's servers.

---

## 🚀 Release v1.6.0 — Cryptographic Vault Signature Matching
* **Features & Technical Updates:**
  * Introduced a mathematically secure, deterministic vault verification system to prevent duplicate channels when multiple private channels share the same `"TeleGallery"` name.
  * **Deterministic SHA-256 Hashing:** Computes a unique, identical signature by hashing the user's Telegram User ID (retrieved via `TdApi.GetMe()`) with a secure salt. Because the Telegram User ID is identical across reinstalls and separate devices, this hash is fully reproducible.
  * **Signature Pinned Verification:** Appends the signature (`TG-SIG-<hash>`) to the pinned welcome message in the channel. During recovery onboarding, the app fetches the channel's pinned message and validates the hash before linking the vault.

---

## 🚀 Release v1.5.0 — Automated Recovery & Duplication Prevention
* **Features & Technical Updates:**
  * Implemented an automated vault scan to prevent creating a new channel if the user has already onboarded previously.
  * Queries the user's top 100 recent chats using `TdApi.GetChats()` to check for pre-existing channels named `"TeleGallery"`.
  * If found, the app automatically link-syncs the pre-existing channel and immediately opens the gallery grid, bypassing new channel generation.

---

## 🚀 Release v1.4.0 — Automated Vault Onboarding
* **Features & Technical Updates:**
  * Removed manual chat selection picker screens during signup onboarding.
  * Programmatically creates a secure private channel named `"TeleGallery"` on Telegram under the user's account.
  * Generates a unique welcome sync key, posts it to the channel, pins the message, and automatically saves it as the target sync destination.

---

## 🚀 Release v1.3.0 — Sequential Upload Throttle & Cache Protection
* **Features & Technical Updates:**
  * Added a strict **5-second sequential delay** (`delay(5000)`) between uploads to prevent triggering `FLOOD_WAIT` rate-limiting bans on Telegram servers.
  * Embedded cache file deletions inside robust `try-finally` blocks within `UploadManager.uploadPhoto()`, ensuring that copied cache media files are immediately and unconditionally deleted from internal storage whether the upload succeeds, fails, or is cancelled.

---

## 🚀 Release v1.2.0 — Target Chat picker Search
* **Features & Technical Updates:**
  * Added a dynamic search bar inside the Target Chat picker dialog.
  * Allows users with large chat directories to instantly search and locate groups, channels, or Saved Messages by typing.

---

## 🚀 Release v1.1.0 — Sync Toggles Settings Dashboard
* **Features & Technical Updates:**
  * Built the Backup Settings dashboard with three primary preference toggles:
    1. **Active Backup Sync:** Instantly enqueues or cancels background backups.
    2. **Wifi Only:** Dynamically schedules WorkManager constraints to use cellular data or only unmetered Wi-Fi.
    3. **Send Photos in HD Quality:** Toggles between standard compressed photo uploads and uncompressed lossless Document uploads.

---

## 🚀 Release v1.0.0 — Initial Application Foundation
* **Features & Technical Updates:**
  * Set up Jetpack Compose Material You neutral grey timeline grid with sticky date headers.
  * Set up `Room` local SQLite database schema to map local MediaStore photo paths to Telegram sync states.
  * Set up TDLib JNI client initialization, phone verification login (OTP), and basic sequential WorkManager synchronization.
