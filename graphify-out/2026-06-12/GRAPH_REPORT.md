# Graph Report - telegallery-calude  (2026-06-12)

## Corpus Check
- 55 files · ~217,333 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1113 nodes · 1811 edges · 80 communities (62 shown, 18 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 86 edges (avg confidence: 0.86)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `321a5d2a`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_Database and DAO Schema|Database and DAO Schema]]
- [[_COMMUNITY_Application Preferences|Application Preferences]]
- [[_COMMUNITY_Community 2|Community 2]]
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
- [[_COMMUNITY_Community 31|Community 31]]
- [[_COMMUNITY_Community 32|Community 32]]
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
- [[_COMMUNITY_Community 57|Community 57]]
- [[_COMMUNITY_Community 58|Community 58]]
- [[_COMMUNITY_Community 59|Community 59]]
- [[_COMMUNITY_Community 60|Community 60]]
- [[_COMMUNITY_Community 61|Community 61]]
- [[_COMMUNITY_Community 64|Community 64]]
- [[_COMMUNITY_Community 65|Community 65]]
- [[_COMMUNITY_Community 66|Community 66]]
- [[_COMMUNITY_Community 67|Community 67]]
- [[_COMMUNITY_Community 68|Community 68]]
- [[_COMMUNITY_Community 69|Community 69]]
- [[_COMMUNITY_Community 70|Community 70]]
- [[_COMMUNITY_Community 71|Community 71]]
- [[_COMMUNITY_Community 72|Community 72]]
- [[_COMMUNITY_Community 73|Community 73]]
- [[_COMMUNITY_Community 74|Community 74]]
- [[_COMMUNITY_Community 75|Community 75]]
- [[_COMMUNITY_Community 76|Community 76]]
- [[_COMMUNITY_Community 77|Community 77]]
- [[_COMMUNITY_Community 78|Community 78]]
- [[_COMMUNITY_Community 79|Community 79]]

## God Nodes (most connected - your core abstractions)
1. `PreferencesManager` - 54 edges
2. `Context` - 51 edges
3. `Context` - 48 edges
4. `TdlibManager` - 47 edges
5. `BackupManager` - 37 edges
6. `Long` - 35 edges
7. `TGPix — Implementation Plan` - 28 edges
8. `PhotosGridScreen()` - 24 edges
9. `List` - 23 edges
10. `CloudPhotoDao` - 23 edges

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

## Communities (80 total, 18 thin omitted)

### Community 0 - "Database and DAO Schema"
Cohesion: 0.13
Nodes (5): Boolean, Int, CloudPhotoDao, CloudPhotoEntity, ThumbnailPathUpdate

### Community 1 - "Application Preferences"
Cohesion: 0.09
Nodes (10): android, android, Boolean, Context, Int, List, Long, Set (+2 more)

### Community 2 - "Community 2"
Cohesion: 0.06
Nodes (35): 1. Unresolved Issues From Previous Review, 2. Critical New Issues, 3. Important New Issues, 4.1 App Startup & Routing Flow, 4.2 Authentication Flow, 4.3 Auto Vault Setup Flow, 4.4 Background Sync & Upload Flow, 4.5 Backup & Recovery Flow (+27 more)

### Community 3 - "Background Backup Workers"
Cohesion: 0.07
Nodes (27): Context, Result, String, ForegroundInfo, Int, Result, ForegroundInfo, Result (+19 more)

### Community 4 - "Telegram Client Integration"
Cohesion: 0.11
Nodes (25): app/build.gradle, Boolean, Context, Int, List, Long, StateFlow, String (+17 more)

### Community 5 - "Albums Screen UI and Utils"
Cohesion: 0.06
Nodes (60): androidx, Context, dev, Int, List, LocalPhoto, Long, String (+52 more)

### Community 6 - "Photos Grid and Search UI"
Cohesion: 0.06
Nodes (34): androidx, Boolean, Context, dev, Int, List, LocalPhoto, Set (+26 more)

### Community 7 - "Database Backup Manager"
Cohesion: 0.09
Nodes (43): Boolean, Context, File, Int, List, Long, String, TdApi (+35 more)

### Community 8 - "Image Upload Manager"
Cohesion: 0.19
Nodes (14): Boolean, Context, File, Float, Int, List, LocalPhoto, Long (+6 more)

### Community 9 - "Photo Viewer Screen UI"
Cohesion: 0.04
Nodes (46): 10. Revised Architecture Diagram, 1. Critical Issues, 2. Important Issues, 3. Schema Improvements, 4. Backup Pipeline Hardening, 5. Restore Pipeline Hardening, 6. Upload Worker Hardening, 7. Security Hardening (+38 more)

### Community 10 - "Main Activity UI Lifecycle"
Cohesion: 0.14
Nodes (10): androidx, android, androidx, List, LocalPhoto, ComponentActivity, IntentSenderRequest, LocalPhoto (+2 more)

### Community 11 - "App Update Manager"
Cohesion: 0.23
Nodes (7): Boolean, Context, File, String, In-App Auto-Update Flow, UpdateInfo, UpdateManager

### Community 12 - "Media Store Scanner"
Cohesion: 0.05
Nodes (42): 1. Schema Issues, 2. Architecture Section Issues, 3. Backup & Recovery Section Issues, 4. Upload Engine Section Issues, 5. Missing Sections — Not Documented, 6.10 — 5-Second Upload Delay Still Applies to Video, 6.1 — `cloud_photos` Table Rename and `mediaType` Column, 6.2 — New Columns Required in `cloud_photos` for Video (+34 more)

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

### Community 29 - "Database Restore Logic"
Cohesion: 0.04
Nodes (45): Albums, Another Production Trick, Better Strategy, Biggest Problem In Your Current Design, Delete Event, Event Messages?, Flood Wait Concern, For Albums (+37 more)

### Community 31 - "Community 31"
Cohesion: 0.17
Nodes (11): Boolean, Context, Int, Long, String, TdApi, lastMessageId, recoveredCount (+3 more)

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
Cohesion: 0.07
Nodes (21): File, Application, Boolean, CloudPhotoEntity, Flow, List, LocalPhoto, SearchItem (+13 more)

### Community 57 - "Community 57"
Cohesion: 0.09
Nodes (21): AlbumEntity, AlbumPhotoEntity, AndroidViewModel, Context, Flow, List, LocalPhoto, Long (+13 more)

### Community 58 - "Community 58"
Cohesion: 0.27
Nodes (7): CloudPhotoEntity, List, LocalPhoto, String, UploadEntity, MergeResult, PhotosRepository

### Community 59 - "Community 59"
Cohesion: 0.24
Nodes (10): Context, List, Long, String, java, Multi-level Photo Date Fallback Resolution, getFingerprint(), getPartialHash() (+2 more)

### Community 60 - "Community 60"
Cohesion: 0.17
Nodes (11): 1. Project Overview & Pitch, 2. Core Architecture System, 3. Database Schema & FTS Searching, 4. Key Flows & Code Entry Points, 5. Coding Guidelines & Guardrails, 6. Project Knowledge Graph, A. Media Matching & Deduplication Flow, Architecture Layers & Responsibilities: (+3 more)

### Community 61 - "Community 61"
Cohesion: 0.07
Nodes (28): Album Event Model, Core Philosophy, Delete Events, Device Registry, Executive Summary, Final Design Principles, Flood Wait Strategy, High Level Architecture (+20 more)

### Community 65 - "Community 65"
Cohesion: 0.17
Nodes (4): Long, String, AlbumDao, AlbumEntity

### Community 66 - "Community 66"
Cohesion: 0.21
Nodes (10): Context, java, BackupEventDao, BackupEventEntity, CloudPhotoFtsEntity, getDatabase(), migrate(), recordEvent() (+2 more)

### Community 67 - "Community 67"
Cohesion: 0.27
Nodes (4): Flow, List, Flow, AlbumPhotoEntity

### Community 68 - "Community 68"
Cohesion: 0.22
Nodes (6): Album Photo Schema Refactoring, Destructive Migration Policy Review, Stable Primary Key for Uploads, Database Migration Policy, RoomDatabase, UploadDatabase

### Community 70 - "Community 70"
Cohesion: 0.40
Nodes (3): String, ErrorMonitor, Throwable

### Community 71 - "Community 71"
Cohesion: 0.08
Nodes (24): 🟡 "95% Reduction" Claim Has No Evidence, 🟡 "Always Calls GetMessage First" Contradicts Tier 2 Logic, 🟢 Cache Eviction Policy Not Documented, 🔴 Coil's Own Cache Layer Is Completely Missing, 🟢 Concurrent Download Race — Same Thumbnail Requested Twice, 🟡 Diagram Shows GetMessage Called Even When Tier 2 Has Cached Path, 🟢 `fileIdCachedAt` Column Interaction Not Documented, Missing Sections to Add (+16 more)

### Community 72 - "Community 72"
Cohesion: 0.22
Nodes (9): Boolean, String, FolderInfo, SettingsScreen(), Backup Settings Screen Screenshot, Backup Configurations Interface, Active JNI Developer Logs Console, Backup Target - Private Vault (+1 more)

### Community 73 - "Community 73"
Cohesion: 0.29
Nodes (3): String, OtpVerifyScreen(), AuthManager

### Community 74 - "Community 74"
Cohesion: 0.25
Nodes (7): File, Float, String, UpdateDialog(), UpdateState, UpdateInfo, UpdateState

### Community 75 - "Community 75"
Cohesion: 0.25
Nodes (7): TGPix README, PhoneLoginScreen(), TeleGallery Phone Number Login Screen Screenshot, TeleGallery Shutter Logo with Paper Plane, OTP SMS Verification Form, Alternative TeleGallery Logo Design Asset, Alternative Three-Petal Purple Logo Design

### Community 76 - "Community 76"
Cohesion: 0.33
Nodes (5): String, Bundle, Triple, AppNavigation(), MainAppLayout()

### Community 77 - "Community 77"
Cohesion: 0.50
Nodes (4): LocalPhoto, GalleryViewModel, toLocalPhoto(), TrashScreen()

### Community 78 - "Community 78"
Cohesion: 0.11
Nodes (18): 1. Architectural Overview & Data Flow, 2. The Multi-Tiered Cache Architecture, 3. Tier 0: Coil Configuration, 4. Session-Scoped File IDs, 5. Performance Guardrails (Scrolling Safety), 6. Concurrency Control & Database Integrity, 7. Video Support Compatibility, 8. Cache Eviction & Constraints (+10 more)

## Knowledge Gaps
- **446 isolated node(s):** `String`, `Throwable`, `ManagedActivityResultLauncher`, `IntentSenderRequest`, `androidx` (+441 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **18 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `PhotosGridScreen()` connect `Photos Grid and Search UI` to `Community 65`, `Community 67`, `Community 69`, `Albums Screen UI and Utils`, `Image Upload Manager`, `Community 72`, `Community 76`?**
  _High betweenness centrality (0.071) - this node is a cross-community bridge._
- **Why does `TdlibManager` connect `Telegram Client Integration` to `Background Backup Workers`, `Database Backup Manager`?**
  _High betweenness centrality (0.066) - this node is a cross-community bridge._
- **Why does `AutoVaultSetupScreen()` connect `Telegram Client Integration` to `Application Preferences`, `Community 76`?**
  _High betweenness centrality (0.060) - this node is a cross-community bridge._
- **What connects `String`, `Throwable`, `ManagedActivityResultLauncher` to the rest of the system?**
  _460 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Database and DAO Schema` be split into smaller, more focused modules?**
  _Cohesion score 0.12681159420289856 - nodes in this community are weakly interconnected._
- **Should `Application Preferences` be split into smaller, more focused modules?**
  _Cohesion score 0.08514013749338974 - nodes in this community are weakly interconnected._
- **Should `Community 2` be split into smaller, more focused modules?**
  _Cohesion score 0.05555555555555555 - nodes in this community are weakly interconnected._