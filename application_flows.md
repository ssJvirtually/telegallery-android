m# TGPix Application Flows & Architecture

This document provides a detailed breakdown of all the logical flows and architectural designs within the TGPix application. TGPix is a privacy-first, on-device Google Photos-like backup manager that uses **Telegram API (via TDLib)** as a free, encrypted cloud storage vault.

---

## 1. App Startup & Routing Flow

When the app is opened, `MainActivity` initializes TDLib and decides which screen to show based on two main conditions: **Telegram Authentication State** and **Vault Configuration State**.

```mermaid
graph TD
    A[App Startup] --> B[Initialize TDLib core]
    B --> C{TDLib Auth State?}
    C -->|WaitPhoneNumber| D[Phone Login Screen]
    C -->|WaitCode| E[OTP Verification Screen]
    C -->|Ready| F{Vault Chat Configured?}
    F -->|No / chatId = 0| G[Auto Vault Setup Screen]
    F -->|Yes / chatId != 0| H[Main Dashboard / Timeline Grid]
```

### Flow Steps:
1. **TDLib Initialization:** [TdlibManager](file:///E:/telegallery-calude/app/src/main/java/dev/ssjvirtually/tgpix/telegram/TdlibManager.kt) initializes the TDLib native client database, folders, and logging options.
2. **Auth Verification:** Reads `authState` flow. If authentication is not complete, users are guided through Phone/OTP login.
3. **Preferences Lookup:** Checks `PreferencesManager.getChatId()`.
   * If `0`, redirects user to set up a backup channel.
   * If a valid ID is present, opens the timeline and schedules the background `UploadWorker` if background syncing is enabled.

---

## 2. Telegram Authentication Flow

Handles secure authentication directly with Telegram servers. Supports phone number inputs, OTP code delivery, and two-factor authentication (2FA) password prompts.

```mermaid
sequenceDiagram
    actor User
    participant UI as Login/Verify Screen
    participant Manager as TdlibManager
    participant TDLib as Native TDLib Client
    participant TG as Telegram Servers

    User->>UI: Enter Phone Number
    UI->>Manager: sendPhoneNumber(phone)
    Manager->>TDLib: setAuthenticationPhoneNumber
    TDLib->>TG: Request Login OTP
    TG-->>User: SMS or Telegram App Code

    User->>UI: Enter OTP Code
    UI->>Manager: sendCode(code)
    Manager->>TDLib: checkAuthenticationCode
    alt 2FA Password Required
        TG-->>UI: Request Password
        User->>UI: Enter 2FA Password
        UI->>Manager: sendPassword(password)
        Manager->>TDLib: checkAuthenticationPassword
    end
    TG-->>Manager: AuthStateReady
    Manager-->>UI: Transition to Vault Setup
```

### Key Components:
* **[PhoneLoginScreen.kt](file:///E:/telegallery-calude/app/src/main/java/dev/ssjvirtually/tgpix/ui/screens/PhoneLoginScreen.kt):** Handles country code selection and standardizes international phone number formatting.
* **[OtpVerifyScreen.kt](file:///E:/telegallery-calude/app/src/main/java/dev/ssjvirtually/tgpix/ui/screens/OtpVerifyScreen.kt):** Handles OTP digits entry and optional 2FA password prompts if security layers are enabled.

---

## 3. Auto Vault Setup Flow

Automatically discovers an existing TGPix vault chat on the user's Telegram account or provisions a new one.

### Verification Criteria:
To distinguish the TGPix vault from standard chats:
1. Search public/private channels matching the target name `TGPix Vault`.
2. Inspect the channel description or verify a pinned setup message to match signature properties.

```mermaid
graph TD
    A[Start Setup] --> B[Search Telegram Chats for 'TGPix Vault']
    B --> C{Found candidate chat?}
    C -->|Yes| D[Verify Chat Description / Pinned Message]
    D --> E{Valid Signature?}
    E -->|Yes| F[Pair with existing Chat ID]
    E -->|No| H[Create New Channel]
    C -->|No| H
    H --> I[Configure channel description 'TGPix Secure Vault']
    I --> J[Send Setup Pinned Verification Message]
    J --> K[Save Chat ID & Title in Preferences]
    F --> K
    K --> L[Start Database Restore check]
```

---

## 4. Background Sync & Image Backup Flow

Ensures newly captured photos are automatically uploaded to the Telegram Vault under specific battery and connection constraints.

```mermaid
sequenceDiagram
    participant OS as Android System / WorkManager
    participant Worker as UploadWorker
    participant DB as Room Database (UploadDatabase)
    participant MS as MediaStore Scanner
    participant Upload as UploadManager
    participant TG as Telegram Vault

    OS->>Worker: Trigger Run (Constraints met)
    Worker->>MS: scanLocalPhotos()
    MS-->>Worker: Return LocalPhoto List
    Worker->>DB: querySyncedPhotos()
    DB-->>Worker: Return Synced Photo List
    Note over Worker: Filters out photos already uploaded<br/>(Deduplication check by name, size, date)
    
    loop For Each New Photo
        Worker->>Upload: uploadPhoto(LocalPhoto)
        Upload->>TG: Send Photo Message
        TG-->>Upload: Return Telegram Message ID
        Upload-->>Worker: Upload Success (msgId)
        Worker->>DB: insertUploadedPhoto(LocalPhoto + msgId)
    end
    Worker->>OS: Return Result.success()
```

### Performance & Data Safeguards:
* **Deduplication:** Queries the database using file criteria (name, size, timestamp) so that files aren't uploaded multiple times if paths or indexes change.
* **WorkManager Constraints:** Syncing is run selectively based on settings (e.g. only on Wi-Fi, only when charging, or allowed on mobile networks).
* **HD Backup Mode:** If disabled, uploads are compressed to save cloud space. If enabled, files are sent as uncompressed raw documents (`sendDocument`).

---

## 5. Catalog Backup & Recovery Flow (Disaster Recovery)

TGPix backs up its metadata catalog (synced history index and album setups) directly to Telegram. This allows full database state recovery when logging in on a new phone.

```mermaid
graph LR
    subgraph Backup Flow
    A[Trigger Backup] --> B[Export Room DB File + SharedPrefs]
    B --> C[Compress into ZIP file tgpix_backup.db]
    C --> D[Upload Document to Telegram Vault Chat]
    D --> E[Pin Backup Document Message]
    end

    subgraph Restore Flow
    F[Scan Vault for Pinned Backup] --> G[Download Backup Document]
    G --> H[Validate Backup Checksum & Schema]
    H --> I[Replace Local Room DB File & Restore Prefs]
    I --> J[Re-initialize Media Scanning]
    end
```

* **Safety:** Prevents rebuilding a catalog from scratch, retaining remote file mappings and local album assignments across re-installations.
* **Location:** Uses [BackupManager.kt](file:///E:/telegallery-calude/app/src/main/java/dev/ssjvirtually/tgpix/storage/BackupManager.kt) for execution.

---

## 6. On-Device Search & ML Labeling Flow

Indexes local photos by their actual contents using Google ML Kit. Everything happens strictly on the device to maintain complete user privacy.

```mermaid
graph TD
    A[New Photo Detected] --> B[Request ML Kit Image Labeler]
    B --> C[Analyze Bitmap image pixels]
    C --> D[Identify features e.g. 'Dog', 'Mountain', 'Food']
    D --> E[Generate label tags with confidence values]
    E --> F[Store tags associated with Photo URI in Room DB]
    F --> G[Search Screen queries tags in DB to filter grids]
```

* **No Server Required:** Search relies completely on the local database index built inside the app background tasks.
* **Location:** Triggered during grid loads and background passes inside [SearchScreen.kt](file:///E:/telegallery-calude/app/src/main/java/dev/ssjvirtually/tgpix/ui/screens/SearchScreen.kt).

---

## 7. In-App Auto-Update Flow

This flow keeps the app up to date with the latest releases hosted on the GitHub repository.

```mermaid
sequenceDiagram
    participant App as TGPix Client
    participant Git as GitHub Raw Repo (version.json)
    participant UI as UpdateDialog UI
    participant Net as GitHub Releases Asset
    participant OS as Package Installer (OS)

    App->>Git: Fetch version.json
    Git-->>App: Return latest version metadata (code, url, notes)
    Note over App: Compare latestCode > local versionCode
    
    alt Update Available
        App->>UI: Show Update Available Overlay
        User->>UI: Clicks "Update Now"
        UI->>Net: Download TGPix.apk to app cache folder
        Net-->>UI: Return download progress (0% - 100%)
        
        alt OS Package Installer Permission Granted
            UI->>OS: Launch ACTION_VIEW Intent with apkUri
        else Permission Denied
            UI->>UI: Show Install Permission Guide Card
            User->>UI: Taps "Open Settings"
            UI->>OS: Launch ACTION_MANAGE_UNKNOWN_APP_SOURCES Settings
            OS-->>UI: User toggles permission & resumes App
            UI->>OS: Launch ACTION_VIEW Intent with apkUri
        end
        OS->>OS: Installs updated app
    end
```

### Components Involved:
* **[version.json](file:///E:/telegallery-calude/version.json):** Configuration describing the update.
* **[UpdateManager.kt](file:///E:/telegallery-calude/app/src/main/java/dev/ssjvirtually/tgpix/update/UpdateManager.kt):** Checks versioning, fetches the APK file stream, and initiates the OS Package Installer intent.
* **[UpdateDialog.kt](file:///E:/telegallery-calude/app/src/main/java/dev/ssjvirtually/tgpix/update/UpdateDialog.kt):** Handles the progressive dialog state UI (Checking, Ready, Downloading, Settings Request, Error, Complete).
