# Graph Report - telegallery-calude  (2026-06-07)

## Corpus Check
- 41 files · ~201,229 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 636 nodes · 945 edges · 62 communities (45 shown, 17 thin omitted)
- Extraction: 92% EXTRACTED · 8% INFERRED · 0% AMBIGUOUS · INFERRED: 78 edges (avg confidence: 0.86)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `daa26712`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_Database and DAO Schema|Database and DAO Schema]]
- [[_COMMUNITY_Application Preferences|Application Preferences]]
- [[_COMMUNITY_Authentication and Navigation UI|Authentication and Navigation UI]]
- [[_COMMUNITY_Background Backup Workers|Background Backup Workers]]
- [[_COMMUNITY_Telegram Client Integration|Telegram Client Integration]]
- [[_COMMUNITY_Albums Screen UI and Utils|Albums Screen UI and Utils]]
- [[_COMMUNITY_Photos Grid and Search UI|Photos Grid and Search UI]]
- [[_COMMUNITY_Database Backup Manager|Database Backup Manager]]
- [[_COMMUNITY_Image Upload Manager|Image Upload Manager]]
- [[_COMMUNITY_Photo Viewer Screen UI|Photo Viewer Screen UI]]
- [[_COMMUNITY_Main Activity UI Lifecycle|Main Activity UI Lifecycle]]
- [[_COMMUNITY_App Update Manager|App Update Manager]]
- [[_COMMUNITY_Media Store Scanner|Media Store Scanner]]
- [[_COMMUNITY_Folder Scanner|Folder Scanner]]
- [[_COMMUNITY_Backup Design Philosophy|Backup Design Philosophy]]
- [[_COMMUNITY_Version Metadata Configuration|Version Metadata Configuration]]
- [[_COMMUNITY_Catalog Backup and Recovery Flow|Catalog Backup and Recovery Flow]]
- [[_COMMUNITY_Setup Flows Documentation|Setup Flows Documentation]]
- [[_COMMUNITY_Background Sync Flow Documentation|Background Sync Flow Documentation]]
- [[_COMMUNITY_ML Labeling Flow|ML Labeling Flow]]
- [[_COMMUNITY_Graphify Rules and Workflows|Graphify Rules and Workflows]]
- [[_COMMUNITY_Project Build Script|Project Build Script]]
- [[_COMMUNITY_Database Schema Configuration|Database Schema Configuration]]
- [[_COMMUNITY_Security and Privacy Configuration|Security and Privacy Configuration]]
- [[_COMMUNITY_Release Change Logs|Release Change Logs]]
- [[_COMMUNITY_Gradle Project Settings|Gradle Project Settings]]
- [[_COMMUNITY_Database Restore Logic|Database Restore Logic]]
- [[_COMMUNITY_System Share Intent|System Share Intent]]
- [[_COMMUNITY_MainActivity Batch Delete|MainActivity Batch Delete]]
- [[_COMMUNITY_MainActivity Trigger Delete|MainActivity Trigger Delete]]
- [[_COMMUNITY_Version Manifest Metadata|Version Manifest Metadata]]
- [[_COMMUNITY_Community 34|Community 34]]
- [[_COMMUNITY_Community 35|Community 35]]
- [[_COMMUNITY_Community 36|Community 36]]
- [[_COMMUNITY_Community 37|Community 37]]
- [[_COMMUNITY_Community 38|Community 38]]
- [[_COMMUNITY_Community 39|Community 39]]
- [[_COMMUNITY_Community 40|Community 40]]
- [[_COMMUNITY_Community 41|Community 41]]
- [[_COMMUNITY_Community 42|Community 42]]
- [[_COMMUNITY_Community 43|Community 43]]
- [[_COMMUNITY_Community 44|Community 44]]
- [[_COMMUNITY_Community 45|Community 45]]
- [[_COMMUNITY_Community 46|Community 46]]
- [[_COMMUNITY_Community 47|Community 47]]
- [[_COMMUNITY_Community 48|Community 48]]
- [[_COMMUNITY_Community 49|Community 49]]
- [[_COMMUNITY_Community 50|Community 50]]
- [[_COMMUNITY_Community 51|Community 51]]
- [[_COMMUNITY_Community 52|Community 52]]
- [[_COMMUNITY_Community 53|Community 53]]
- [[_COMMUNITY_Community 54|Community 54]]
- [[_COMMUNITY_Community 55|Community 55]]
- [[_COMMUNITY_Community 56|Community 56]]
- [[_COMMUNITY_Community 57|Community 57]]
- [[_COMMUNITY_Community 58|Community 58]]
- [[_COMMUNITY_Community 59|Community 59]]
- [[_COMMUNITY_Community 60|Community 60]]
- [[_COMMUNITY_Community 61|Community 61]]

## God Nodes (most connected - your core abstractions)
1. `PreferencesManager` - 36 edges
2. `TdlibManager` - 35 edges
3. `Context` - 32 edges
4. `TGPix — Implementation Plan` - 28 edges
5. `BackupManager` - 21 edges
6. `PhotosGridScreen()` - 21 edges
7. `📝 TGPix Android — Version Release Changelog` - 15 edges
8. `CloudPhotoDao` - 14 edges
9. `AlbumDao` - 14 edges
10. `TGPix — Backup Strategy Production Review` - 14 edges

## Surprising Connections (you probably didn't know these)
- `BackupManager` --references--> `Backup Target - Private Vault`  [INFERRED]
  app/src/main/java/dev/ssjvirtually/tgpix/storage/BackupManager.kt → screenshots/settings_screen.png
- `Album Photo Schema Refactoring` --rationale_for--> `UploadDatabase`  [INFERRED]
  BACKUP_STRATEGY_REVIEW.md → app/src/main/java/dev/ssjvirtually/tgpix/storage/UploadDatabase.kt
- `Destructive Migration Policy Review` --rationale_for--> `UploadDatabase`  [INFERRED]
  BACKUP_STRATEGY_REVIEW.md → app/src/main/java/dev/ssjvirtually/tgpix/storage/UploadDatabase.kt
- `Stable Primary Key for Uploads` --rationale_for--> `UploadDatabase`  [INFERRED]
  BACKUP_STRATEGY_REVIEW.md → app/src/main/java/dev/ssjvirtually/tgpix/storage/UploadDatabase.kt
- `AlbumsScreen()` --implements--> `Albums Grid Layout`  [INFERRED]
  app/src/main/java/dev/ssjvirtually/tgpix/ui/screens/AlbumsScreen.kt → error-sc/Screenshot_20260603_192103.png

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Authentication Flow** — screens_phoneloginscreen_phoneloginscreen, screens_otpverifyscreen_otpverifyscreen, telegram_authmanager_authmanager [EXTRACTED 1.00]
- **Database Access Layer** — storage_uploaddatabase_uploaddatabase, storage_uploaddatabase_uploaddao, storage_uploaddatabase_cloudphotodao, storage_uploaddatabase_albumdao [EXTRACTED 1.00]
- **Gallery Screens** — screens_photosgridscreen_photosgridscreen, screens_searchscreen_searchscreen, screens_albumsscreen_albumsscreen, screens_photoviewerscreen_photoviewerscreen [EXTRACTED 1.00]
- **Background Workers Pipeline** — worker_databasebackupworker_databasebackupworker, worker_shareworker_shareworker, worker_uploadworker_uploadworker [EXTRACTED 1.00]
- **Application Update Infrastructure** — update_updatedialog_updatedialog, update_updatemanager_updatemanager, version_version_metadata [EXTRACTED 1.00]
- **Telegram Backup Storage Topology** — db_and_album_backup_strategy_vault_channel, db_and_album_backup_strategy_dedicated_backup_channel, db_and_album_backup_strategy_saved_messages_chat [EXTRACTED 1.00]
- **User Authentication Flow** — screens_phoneloginscreen_phoneloginscreen, screens_otpverifyscreen_otpverifyscreen, screenshots_login_screen_otp_login_form [INFERRED 0.85]
- **Media Backup and Sync Ecosystem** — screenshots_settings_screen_private_vault, screenshots_gallery_timeline_sync_badges, screenshots_photo_viewer_action_sheet, storage_backupmanager_backupmanager [INFERRED 0.85]

## Communities (62 total, 17 thin omitted)

### Community 0 - "Database and DAO Schema"
Cohesion: 0.06
Nodes (23): Boolean, Int, List, Long, String, Album Photo Schema Refactoring, Destructive Migration Policy Review, Stable Primary Key for Uploads (+15 more)

### Community 1 - "Application Preferences"
Cohesion: 0.12
Nodes (8): android, Boolean, Context, Int, List, Long, String, PreferencesManager

### Community 2 - "Authentication and Navigation UI"
Cohesion: 0.08
Nodes (20): String, String, TGPix README, OtpVerifyScreen(), PhoneLoginScreen(), SettingsScreen(), TeleGallery Phone Number Login Screen Screenshot, TeleGallery Shutter Logo with Paper Plane (+12 more)

### Community 3 - "Background Backup Workers"
Cohesion: 0.07
Nodes (27): Context, Result, ForegroundInfo, Result, String, ForegroundInfo, Result, String (+19 more)

### Community 4 - "Telegram Client Integration"
Cohesion: 0.12
Nodes (18): app/build.gradle, Boolean, Context, Int, List, Long, String, TdApi (+10 more)

### Community 5 - "Albums Screen UI and Utils"
Cohesion: 0.18
Nodes (18): Boolean, CloudPhotoEntity, Context, Int, List, LocalPhoto, String, CloudPhotoEntity (+10 more)

### Community 6 - "Photos Grid and Search UI"
Cohesion: 0.15
Nodes (15): androidx, Boolean, Context, Int, List, LocalPhoto, String, getItemIndexAt() (+7 more)

### Community 7 - "Database Backup Manager"
Cohesion: 0.21
Nodes (9): Boolean, Context, File, Int, List, Long, String, TdApi (+1 more)

### Community 8 - "Image Upload Manager"
Cohesion: 0.18
Nodes (14): Boolean, Context, File, Float, Int, List, LocalPhoto, Long (+6 more)

### Community 9 - "Photo Viewer Screen UI"
Cohesion: 0.04
Nodes (46): 10. Revised Architecture Diagram, 1. Critical Issues, 2. Important Issues, 3. Schema Improvements, 4. Backup Pipeline Hardening, 5. Restore Pipeline Hardening, 6. Upload Worker Hardening, 7. Security Hardening (+38 more)

### Community 10 - "Main Activity UI Lifecycle"
Cohesion: 0.18
Nodes (8): androidx, List, LocalPhoto, Bundle, ComponentActivity, IntentSenderRequest, ManagedActivityResultLauncher, MainActivity

### Community 11 - "App Update Manager"
Cohesion: 0.22
Nodes (7): Boolean, Context, File, String, In-App Auto-Update Flow, UpdateInfo, UpdateManager

### Community 12 - "Media Store Scanner"
Cohesion: 0.24
Nodes (10): Context, List, Long, String, java, Multi-level Photo Date Fallback Resolution, getFingerprint(), getPartialHash() (+2 more)

### Community 13 - "Folder Scanner"
Cohesion: 0.33
Nodes (4): Context, List, DocumentFile, FolderScanner

### Community 14 - "Backup Design Philosophy"
Cohesion: 0.33
Nodes (6): App Startup & Routing Flow, TGPix Architectural Philosophy, Dedicated Backup Channel, Saved Messages Chat, Vault Channel, Architecture Overview

### Community 15 - "Version Metadata Configuration"
Cohesion: 0.33
Nodes (5): apkUrl, forceUpdate, releaseNotes, versionCode, versionName

### Community 16 - "Catalog Backup and Recovery Flow"
Cohesion: 0.25
Nodes (8): Catalog Backup & Recovery Flow, Database Sync & Backup Mechanism, Disaster Recovery & Restore Strategy, 💻 Code Implementation Details, ⚙️ Recovery Priority Sequence, 📷 System Architecture, TGPix Database & Album Backup Strategy, Phase 3: Backup Engine

### Community 17 - "Setup Flows Documentation"
Cohesion: 0.67
Nodes (3): Auto Vault Setup Flow, Telegram Authentication Flow, Phase 1: Authentication

### Community 18 - "Background Sync Flow Documentation"
Cohesion: 0.67
Nodes (3): Background Sync & Image Backup Flow, Background Synchronization Engine, Phase 7: Background Sync Service

### Community 34 - "Community 34"
Cohesion: 0.06
Nodes (34): 1. Prioritized Restore Resolution, 1. Security Architecture, 1. `uploads`, 1. WAL Checkpointing (TRUNCATE), 2. `cloud_photos`, 2. Connection Swapping and WAL Drop, 2. Known Constraints & Vulnerabilities, 2. The Local Record Count "Safety Lock" (+26 more)

### Community 35 - "Community 35"
Cohesion: 0.12
Nodes (15): 1. App Startup & Routing Flow, 2. Telegram Authentication Flow, 3. Auto Vault Setup Flow, 4. Background Sync & Image Backup Flow, 5. Catalog Backup & Recovery Flow (Disaster Recovery), 6. On-Device Search & ML Labeling Flow, 7. In-App Auto-Update Flow, Components Involved: (+7 more)

### Community 36 - "Community 36"
Cohesion: 0.12
Nodes (15): 🚀 Release v1.0.0 — Initial Application Foundation, 🚀 Release v1.1.0 — Sync Toggles Settings Dashboard, 🚀 Release v1.2.0 — Target Chat picker Search, 🚀 Release v1.3.0 — Sequential Upload Throttle & Cache Protection, 🚀 Release v1.4.0 — Automated Vault Onboarding, 🚀 Release v1.5.0 — Automated Recovery & Duplication Prevention, 🚀 Release v1.6.0 — Cryptographic Vault Signature Matching, 🚀 Release v1.7.0 — Multi-Device Recovery & UI Thread Fixes (+7 more)

### Community 37 - "Community 37"
Cohesion: 0.13
Nodes (14): 1. Setup API Keys, 2. Set Environment Variables, 3. Compile the Debug APK, 📱 Application Screens, 💻 Build Instructions (Gradle Wrapper), 🤝 Contributing, Forking & License, 📲 Download & Installation, 🚀 How to Build Locally (+6 more)

### Community 38 - "Community 38"
Cohesion: 0.13
Nodes (14): 19. Manifest Overview, 1. Project Overview, 20. Dependency List (Gradle), 21. Risk & Mitigation Table, 22. Delivery Milestones, 2. Architecture Overview, 3. Tech Stack, 4. Project Structure (+6 more)

### Community 39 - "Community 39"
Cohesion: 0.50
Nodes (4): 10. Phase 6 — Settings Screen, Chat Picker Flow, Goal, Settings Options

### Community 40 - "Community 40"
Cohesion: 0.50
Nodes (4): 12. Phase 8 — UI/UX Polish (Google Photos Feel), Design Targets, Full-Screen Viewer, Scrolling Performance

### Community 41 - "Community 41"
Cohesion: 0.50
Nodes (4): 13. Phase 9 — Memory & Crash Safety, ANR Prevention, Crash-Free Checklist, Rules

### Community 42 - "Community 42"
Cohesion: 0.50
Nodes (4): 14. Phase 10 — Testing & QA, Instrumentation Tests, Manual QA Checklist, Unit Tests

### Community 43 - "Community 43"
Cohesion: 0.50
Nodes (4): 7. Phase 3 — Backup Engine, Design Principles, Goal, Steps

### Community 44 - "Community 44"
Cohesion: 0.50
Nodes (4): 9. Phase 5 — Backup Status Overlay (Badge Icons), Badge States, Goal, Implementation

### Community 45 - "Community 45"
Cohesion: 0.67
Nodes (3): 11. Phase 7 — Background Sync Service, Approach, Goal

### Community 46 - "Community 46"
Cohesion: 0.67
Nodes (3): 16. Database Schema (Room), DAO, Table: `photo_backup_status`

### Community 47 - "Community 47"
Cohesion: 0.67
Nodes (3): 5. Phase 1 — Authentication (Telegram Login), Goal, Steps

### Community 48 - "Community 48"
Cohesion: 0.67
Nodes (3): 6. Phase 2 — Local Gallery, Goal, Steps

### Community 49 - "Community 49"
Cohesion: 0.67
Nodes (3): 8. Phase 4 — Telegram Storage Integration, Goal, Steps

### Community 55 - "Community 55"
Cohesion: 0.18
Nodes (17): androidx, Context, Int, List, LocalPhoto, Long, String, Albums Screen Sharing Error Screenshot (+9 more)

### Community 56 - "Community 56"
Cohesion: 0.23
Nodes (13): Boolean, Int, Long, String, Volatile File ID Refresh, Triple, GalleryItem, Header (+5 more)

### Community 57 - "Community 57"
Cohesion: 0.28
Nodes (8): androidx, Boolean, Int, List, LocalPhoto, getItemIndexAt(), SearchItem, SearchScreen()

### Community 58 - "Community 58"
Cohesion: 0.29
Nodes (5): CloudPhotoEntity, List, LocalPhoto, String, PhotosRepository

### Community 59 - "Community 59"
Cohesion: 0.25
Nodes (7): File, Float, String, UpdateDialog(), UpdateState, UpdateInfo, UpdateState

### Community 60 - "Community 60"
Cohesion: 0.40
Nodes (5): List, LocalPhoto, TdApi, ChatRow(), TelegramShareDialog()

### Community 61 - "Community 61"
Cohesion: 0.67
Nodes (3): String, AppNavigation(), MainAppLayout()

## Knowledge Gaps
- **252 isolated node(s):** `ManagedActivityResultLauncher`, `IntentSenderRequest`, `androidx`, `Bundle`, `List` (+247 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **17 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `PhotosGridScreen()` connect `Photos Grid and Search UI` to `Database and DAO Schema`, `Authentication and Navigation UI`, `Image Upload Manager`, `Community 55`, `Community 56`, `Community 57`, `Community 60`, `Community 61`?**
  _High betweenness centrality (0.087) - this node is a cross-community bridge._
- **Why does `AppNavigation()` connect `Community 61` to `Community 59`, `Main Activity UI Lifecycle`, `Authentication and Navigation UI`, `Telegram Client Integration`?**
  _High betweenness centrality (0.072) - this node is a cross-community bridge._
- **Why does `TdlibManager` connect `Telegram Client Integration` to `Background Backup Workers`?**
  _High betweenness centrality (0.068) - this node is a cross-community bridge._
- **What connects `ManagedActivityResultLauncher`, `IntentSenderRequest`, `androidx` to the rest of the system?**
  _266 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Database and DAO Schema` be split into smaller, more focused modules?**
  _Cohesion score 0.06412583182093164 - nodes in this community are weakly interconnected._
- **Should `Application Preferences` be split into smaller, more focused modules?**
  _Cohesion score 0.12073170731707317 - nodes in this community are weakly interconnected._
- **Should `Authentication and Navigation UI` be split into smaller, more focused modules?**
  _Cohesion score 0.07936507936507936 - nodes in this community are weakly interconnected._