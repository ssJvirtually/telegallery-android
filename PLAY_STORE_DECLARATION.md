# TGPix — Google Play Store Foreground Service Declaration

This document contains the copy-pasteable justification text needed when submitting **TGPix** to the Google Play Console for approval of the `dataSync` Foreground Service type usage.

---

## 1. Foreground Service Type
Select the following type in the Console:
* **dataSync** (Data synchronization — upload/download of files, metadata, backup/restore)

---

## 2. Core Feature Justification
*Copy and paste the following description into the justification form:*

TGPix is a privacy-first, offline-first personal photo and video gallery. A core feature of the application is the "Private Vault Synchronization", which encrypts and backs up the user's local camera roll media files directly to their private Telegram cloud storage channels, as well as restoring media thumbnails and full-resolution files.

---

## 3. Why Foreground Service is Required
*Copy and paste the following description into the justification form:*

Media synchronization involves encrypting, chunking, and uploading large collections of high-resolution photos and videos. When the user exits the application, this process must run to completion to prevent half-uploaded or corrupted media payloads. Standard background tasks are subject to aggressive system sleep states and low-memory kills which corrupt native JNI socket connections to the cloud. A foreground service with the `dataSync` type is required to display a progress notification to the user, ensuring system transparency and keeping the CPU/network socket active until the sync batch completes safely.

---

## 4. User-Initiated Trigger Description
*Copy and paste the following description into the justification form:*

The backup pipeline is initiated in one of two ways:
1. **Explicitly**: The user taps the "Backup Now" or "Sync Now" button in the gallery dashboard or vault settings screen.
2. **Automatically**: The user enables the "Auto-Vault" option in settings, which registers a background listener that detects when new local photos/videos are added to the device's MediaStore, triggering a synchronization batch run.
