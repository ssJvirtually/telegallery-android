# <img src="screenshots/logo.png" width="48" align="center" /> TeleGallery

**TeleGallery** is a premium, lightweight, and modern Google Photos-like Android application that uses private, secure Telegram channels for unlimited cloud media backups. It beautifully blends the clean, minimalist surfaces of **Google Photos** with the styling cues, authentication backend, and lightning-fast media transport of **Telegram**.

---

> [!CAUTION]
> ### ⚠️ Critical Account & Storage Disclaimer
> **TeleGallery** stores your backed-up media exclusively in a private Telegram supergroup/channel mapped to your active Telegram account.
> * **If you lose access to your Telegram account (due to an official ban, suspension, loss of your active SIM card/phone number, or manual deletion), you will lose access to all backed-up cloud photos.**
> * There is no secondary storage server or recovery pathway. Always ensure your Telegram account is secure and has up-to-date recovery methods.

---

## 🌟 Key Features

* **🎨 Sleek Premium Light Theme:** A beautifully polished light-mode aesthetic that blends Telegram Blue (`#2481CC`) with clean, off-white Google Photos surfaces (`#F4F6F9`) and dynamic cool-grey typography.
* **⚡ Continuous Smooth Fast Scrollbar:** A high-precision, sub-pixel vertical fast scrollbar matching the physics of Telegram and Google Photos. Uses a custom row-based layout calculation (`firstVisibleIndex + itemOffsetFraction * columns`) to completely eliminate dragging stutters or releasing jumps.
* **📅 Floating Date Bubble:** A glassmorphic month/year indicator bubble that slides dynamically next to the user's finger during dragging.
* **🔄 Intelligent Deduplication Sync:** Keeps track of uploaded images in a reactive local SQLite Room database. Safely bypasses duplicates even when you manually trigger a force synchronization.
* **☁️ Multi-Device Vault Onboarding:** On first login, the app determinants a secure SHA-256 vault signature mapped to your Telegram user ID, auto-creates a private channel named `"TeleGallery"`, and pins the welcome sync keys. Logging in on a fresh secondary device immediately scans Telegram's servers, recovers the pre-existing channel, and populates the grid.
* **🖼️ Immersive Detail View:** Swipe through your pictures in full resolution with Compose `HorizontalPager`. Features safe single-photo manual backups, JNI file downsampling to prevent memory crashes, native sharing, local device deletions, and saving cloud items back to the public `Downloads` folder.

---

## 📱 Application Screens

| 🔐 1. OTP Phone Login | 📅 2. Timeline Grid View |
|:---:|:---:|
| ![Login Screen](screenshots/login_screen.png) | ![Gallery Timeline](screenshots/gallery_timeline.png) |

| 🖼️ 3. Immersive Detail View | ⚙️ 4. Settings Dashboard |
|:---:|:---:|
| ![Photo Viewer](screenshots/photo_viewer.png) | ![Settings Screen](screenshots/settings_screen.png) |

---

## 🛠️ Tech Stack & Architecture

| Layer | Component | Description |
|---|---|---|
| **Language** | Kotlin + Coroutines | Fast, safe asynchronous threading and reactive sequences. |
| **UI Engine** | Jetpack Compose (M3) | State-driven declarative UI with clean Material You design. |
| **Core Client** | Telegram TDLib JNI | Pure native MTProto client handling OTP signups and reliable transport. |
| **Image Loading** | Coil Compose | Memory-sensitive image decoder with strict thumbnail size constraints. |
| **Database** | Room + SQLite | Relational mappings storing local sync statuses and cloud logs. |
| **Background Sync** | WorkManager | System-scheduled periodic backups that survive device restarts. |

---

## 🚀 How to Run Locally

Follow these instructions to set up the build environment and compile TeleGallery on your own computer.

### 📋 Prerequisites
1. **Java JDK 17:** Ensure Java 17 is installed on your path.
2. **Android SDK (API 34):** Ensure you have the Android SDK installed with compile tools.
3. **Telegram API ID & API Hash:**
   * Go to [my.telegram.org](https://my.telegram.org), log in, create a developer application, and obtain your `api_id` and `api_hash`.
   * Open the project and add them inside the configuration files (`TdlibManager.kt` or preferences) to initialize TDLib.

### 💻 Local Compilation Steps

#### Option 1: Command Line (Windows PowerShell)
You can build the debug package directly via Gradle command line using the local SDK configuration:

1. Open PowerShell in the project root directory.
2. Configure your environment paths (pointing to your Java 17 and Android SDK locations):
   ```powershell
   $env:JAVA_HOME="C:\Users\jskr4\.jdks\azul-17.0.13"
   $env:ANDROID_HOME="E:\android-build-tools\android-sdk"
   ```
3. Run the Gradle debug build:
   ```powershell
   & "E:\android-build-tools\gradle-8.7\bin\gradle.bat" assembleDebug
   ```
4. Once completed successfully, the debug APK is located at:
   `app/build/outputs/apk/debug/app-debug.apk`

#### Option 2: Android Studio
1. Open Android Studio.
2. Select **File > Open** and choose the `telegallery-calude` folder.
3. Wait for the Gradle project sync to complete.
4. Go to **File > Project Structure > SDK Location** and verify that your **JDK 17** is selected.
5. Connect your Android device or start an emulator.
6. Click the **Run** button (green play icon) in the toolbar to compile and install the application directly.
