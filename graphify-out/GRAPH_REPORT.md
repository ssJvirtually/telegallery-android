# Graph Report - telegallery-calude  (2026-06-07)

## Corpus Check
- 43 files · ~202,349 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 678 nodes · 1013 edges · 58 communities (40 shown, 18 thin omitted)
- Extraction: 92% EXTRACTED · 8% INFERRED · 0% AMBIGUOUS · INFERRED: 79 edges (avg confidence: 0.86)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `7bbd4678`
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
- [[_COMMUNITY_Community 58|Community 58]]

## God Nodes (most connected - your core abstractions)
1. `PreferencesManager` - 40 edges
2. `Context` - 36 edges
3. `TdlibManager` - 36 edges
4. `TGPix — Implementation Plan` - 28 edges
5. `BackupManager` - 23 edges
6. `PhotosGridScreen()` - 23 edges
7. `PhotoViewerScreen()` - 18 edges
8. `GalleryViewModel` - 16 edges
9. `SearchScreen()` - 15 edges
10. `📝 TGPix Android — Version Release Changelog` - 15 edges

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

## Communities (58 total, 18 thin omitted)

### Community 0 - "Database and DAO Schema"
Cohesion: 0.06
Nodes (24): Boolean, Flow, Int, List, Long, String, Album Photo Schema Refactoring, Destructive Migration Policy Review (+16 more)

### Community 1 - "Application Preferences"
Cohesion: 0.11
Nodes (8): android, Boolean, Context, Int, List, Long, String, PreferencesManager

### Community 2 - "Authentication and Navigation UI"
Cohesion: 0.07
Nodes (24): String, String, File, Float, String, TGPix README, OtpVerifyScreen(), PhoneLoginScreen() (+16 more)

### Community 3 - "Background Backup Workers"
Cohesion: 0.07
Nodes (27): Context, Result, ForegroundInfo, Result, String, ForegroundInfo, Result, String (+19 more)

### Community 4 - "Telegram Client Integration"
Cohesion: 0.12
Nodes (19): app/build.gradle, Boolean, Context, Int, List, Long, StateFlow, String (+11 more)

### Community 5 - "Albums Screen UI and Utils"
Cohesion: 0.06
Nodes (55): androidx, Context, dev, Int, List, LocalPhoto, Long, String (+47 more)

### Community 6 - "Photos Grid and Search UI"
Cohesion: 0.07
Nodes (33): androidx, Boolean, Context, dev, Int, List, LocalPhoto, Set (+25 more)

### Community 7 - "Database Backup Manager"
Cohesion: 0.14
Nodes (15): Boolean, Context, File, Int, List, Long, String, TdApi (+7 more)

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
Cohesion: 0.12
Nodes (13): AndroidViewModel, Boolean, CloudPhotoEntity, Flow, List, LocalPhoto, StateFlow, String (+5 more)

### Community 58 - "Community 58"
Cohesion: 0.28
Nodes (6): CloudPhotoEntity, List, LocalPhoto, String, MergeResult, PhotosRepository

## Knowledge Gaps
- **270 isolated node(s):** `ManagedActivityResultLauncher`, `IntentSenderRequest`, `androidx`, `Bundle`, `List` (+265 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **18 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `PhotosGridScreen()` connect `Photos Grid and Search UI` to `Database and DAO Schema`, `Image Upload Manager`, `Authentication and Navigation UI`, `Albums Screen UI and Utils`?**
  _High betweenness centrality (0.087) - this node is a cross-community bridge._
- **Why does `AppNavigation()` connect `Authentication and Navigation UI` to `Main Activity UI Lifecycle`, `Telegram Client Integration`?**
  _High betweenness centrality (0.076) - this node is a cross-community bridge._
- **Why does `AutoVaultSetupScreen()` connect `Telegram Client Integration` to `Application Preferences`, `Authentication and Navigation UI`?**
  _High betweenness centrality (0.072) - this node is a cross-community bridge._
- **What connects `ManagedActivityResultLauncher`, `IntentSenderRequest`, `androidx` to the rest of the system?**
  _284 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Database and DAO Schema` be split into smaller, more focused modules?**
  _Cohesion score 0.06428988895382817 - nodes in this community are weakly interconnected._
- **Should `Application Preferences` be split into smaller, more focused modules?**
  _Cohesion score 0.11212121212121212 - nodes in this community are weakly interconnected._
- **Should `Authentication and Navigation UI` be split into smaller, more focused modules?**
  _Cohesion score 0.06818181818181818 - nodes in this community are weakly interconnected._