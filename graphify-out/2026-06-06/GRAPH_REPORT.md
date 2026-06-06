# Graph Report - .  (2026-06-06)

## Corpus Check
- 53 files · ~199,535 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 424 nodes · 735 edges · 34 communities (22 shown, 12 thin omitted)
- Extraction: 89% EXTRACTED · 11% INFERRED · 0% AMBIGUOUS · INFERRED: 78 edges (avg confidence: 0.86)
- Token cost: 0 input · 0 output

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

## God Nodes (most connected - your core abstractions)
1. `PreferencesManager` - 36 edges
2. `TdlibManager` - 33 edges
3. `Context` - 32 edges
4. `PhotosGridScreen()` - 21 edges
5. `BackupManager` - 20 edges
6. `AlbumDao` - 14 edges
7. `CloudPhotoDao` - 13 edges
8. `AlbumsScreen()` - 13 edges
9. `PhotoViewerScreen()` - 13 edges
10. `Context` - 12 edges

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

## Communities (34 total, 12 thin omitted)

### Community 0 - "Database and DAO Schema"
Cohesion: 0.06
Nodes (23): Boolean, Int, List, Long, String, Album Photo Schema Refactoring, Destructive Migration Policy Review, Stable Primary Key for Uploads (+15 more)

### Community 1 - "Application Preferences"
Cohesion: 0.12
Nodes (8): android, Boolean, Context, Int, List, Long, String, PreferencesManager

### Community 2 - "Authentication and Navigation UI"
Cohesion: 0.06
Nodes (30): String, String, String, File, Float, String, TGPix README, OtpVerifyScreen() (+22 more)

### Community 3 - "Background Backup Workers"
Cohesion: 0.07
Nodes (27): Context, Result, ForegroundInfo, Result, String, ForegroundInfo, Result, String (+19 more)

### Community 4 - "Telegram Client Integration"
Cohesion: 0.13
Nodes (17): app/build.gradle, Boolean, Context, List, Long, String, TdApi, Client (+9 more)

### Community 5 - "Albums Screen UI and Utils"
Cohesion: 0.11
Nodes (30): androidx, Context, Int, List, LocalPhoto, Long, String, Boolean (+22 more)

### Community 6 - "Photos Grid and Search UI"
Cohesion: 0.08
Nodes (28): androidx, Boolean, Context, Int, List, LocalPhoto, String, androidx (+20 more)

### Community 7 - "Database Backup Manager"
Cohesion: 0.22
Nodes (9): Boolean, Context, File, Int, List, Long, String, TdApi (+1 more)

### Community 8 - "Image Upload Manager"
Cohesion: 0.18
Nodes (14): Boolean, Context, File, Float, Int, List, LocalPhoto, Long (+6 more)

### Community 9 - "Photo Viewer Screen UI"
Cohesion: 0.19
Nodes (17): Boolean, Context, Int, List, LocalPhoto, String, CloudPhotoEntity, Double (+9 more)

### Community 10 - "Main Activity UI Lifecycle"
Cohesion: 0.18
Nodes (8): androidx, List, LocalPhoto, Bundle, ComponentActivity, IntentSenderRequest, ManagedActivityResultLauncher, MainActivity

### Community 11 - "App Update Manager"
Cohesion: 0.22
Nodes (7): Boolean, Context, File, String, In-App Auto-Update Flow, UpdateInfo, UpdateManager

### Community 12 - "Media Store Scanner"
Cohesion: 0.27
Nodes (9): Context, List, Long, String, Multi-level Photo Date Fallback Resolution, getFingerprint(), getPartialHash(), LocalPhoto (+1 more)

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
Cohesion: 0.50
Nodes (5): Catalog Backup & Recovery Flow, Database Sync & Backup Mechanism, Disaster Recovery & Restore Strategy, Recovery Priority Sequence, Phase 3: Backup Engine

### Community 17 - "Setup Flows Documentation"
Cohesion: 0.67
Nodes (3): Auto Vault Setup Flow, Telegram Authentication Flow, Phase 1: Authentication

### Community 18 - "Background Sync Flow Documentation"
Cohesion: 0.67
Nodes (3): Background Sync & Image Backup Flow, Background Synchronization Engine, Phase 7: Background Sync Service

## Knowledge Gaps
- **102 isolated node(s):** `ManagedActivityResultLauncher`, `IntentSenderRequest`, `androidx`, `Bundle`, `List` (+97 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **12 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `PhotosGridScreen()` connect `Photos Grid and Search UI` to `Database and DAO Schema`, `Image Upload Manager`, `Authentication and Navigation UI`, `Albums Screen UI and Utils`?**
  _High betweenness centrality (0.191) - this node is a cross-community bridge._
- **Why does `AppNavigation()` connect `Authentication and Navigation UI` to `Main Activity UI Lifecycle`, `Telegram Client Integration`?**
  _High betweenness centrality (0.159) - this node is a cross-community bridge._
- **Why does `AutoVaultSetupScreen()` connect `Telegram Client Integration` to `Application Preferences`, `Authentication and Navigation UI`?**
  _High betweenness centrality (0.148) - this node is a cross-community bridge._
- **Are the 10 inferred relationships involving `PhotosGridScreen()` (e.g. with `AlbumsScreen()` and `SearchScreen()`) actually correct?**
  _`PhotosGridScreen()` has 10 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ManagedActivityResultLauncher`, `IntentSenderRequest`, `androidx` to the rest of the system?**
  _116 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Database and DAO Schema` be split into smaller, more focused modules?**
  _Cohesion score 0.06493506493506493 - nodes in this community are weakly interconnected._
- **Should `Application Preferences` be split into smaller, more focused modules?**
  _Cohesion score 0.12073170731707317 - nodes in this community are weakly interconnected._