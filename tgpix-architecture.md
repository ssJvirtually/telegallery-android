# TGPix Production Metadata, Sync and Backup Architecture

## Executive Summary

This document replaces the earlier distributed database lock design.

The original design attempted to coordinate multiple devices through a Telegram-based mutex using pinned messages.

After reviewing the design from a distributed systems perspective, the mutex approach was rejected because:

* Telegram provides no atomic compare-and-swap operation
* Lock acquisition is not truly atomic
* Clock skew can cause lock theft
* Pinned message propagation is eventually consistent
* Lock renewal introduces additional failure modes
* Frequent lock operations increase Telegram traffic
* Increased probability of FLOOD_WAIT errors

Instead, TGPix will adopt a distributed architecture based on immutable media, metadata events, and periodic snapshots.

---

# Core Philosophy

## Source of Truth

Telegram Vault Channel is the source of truth.

Not:

* Room
* SQLite
* Snapshots

The vault channel is authoritative.

Every uploaded photo or video exists there permanently.

---

## Local Database

Room Database is a cache.

Room Database is not authoritative.

If the database is lost:

* Delete it
* Rebuild it

The system must continue functioning.

---

## Snapshots

Snapshots exist only to accelerate recovery.

They are optimization.

Not authority.

---

# High Level Architecture

```text
                ┌─────────────────────┐
                │ Telegram Vault      │
                │ Photos + Videos     │
                └──────────┬──────────┘
                           │
                           │ UpdateNewMessage
                           ▼
                ┌─────────────────────┐
                │ Room Database       │
                │ Disposable Cache    │
                └──────────┬──────────┘
                           │
                           ▼
                ┌─────────────────────┐
                │ Gallery UI          │
                └─────────────────────┘

                ┌─────────────────────┐
                │ Metadata Channel    │
                └──────────┬──────────┘
                           │
                           ▼
                Album Events
                Delete Events
                Device Registry
                Snapshot Registry

                ┌─────────────────────┐
                │ Snapshot Storage    │
                └─────────────────────┘
```

---

# Vault Channel

Stores:

* Photos
* Videos

Nothing else.

Every media upload becomes a Telegram message.

Example:

```text
Message 1001
photo.jpg

Message 1002
video.mp4
```

These messages already act as upload events.

No additional upload event messages are required.

---

# Why Upload Events Are Not Needed

Uploading:

```text
Photo
+
Upload Event
```

duplicates information.

The Telegram message itself already contains:

* message id
* timestamp
* media
* sender
* metadata

Therefore:

```text
Media Message = Upload Event
```

This cuts metadata traffic in half.

---

# Metadata Channel

Stores only data that cannot be reconstructed from media messages.

Allowed event types:

DELETE

ALBUM_CREATE

ALBUM_RENAME

ALBUM_DELETE

ALBUM_ADD

ALBUM_REMOVE

DEVICE_REGISTER

SNAPSHOT_CREATED

Nothing else.

---

# Delete Events

Deletes are special.

When a photo is deleted:

```json
{
  "type":"DELETE",
  "messageId":1001,
  "timestamp":1718234567,
  "deviceId":"pixel8"
}
```

Store this event inside Metadata Channel.

Reason:

Once a Telegram message is deleted:

```text
Message 1001
gone forever
```

Future devices cannot know:

* uploaded then deleted
* never uploaded
* missing

Delete events preserve history.

---

# Soft Delete

Never permanently delete immediately.

Move media into:

Trash State

Retention:

30 days

After retention expires:

Permanent delete

Benefits:

* Undo support
* Multi-device consistency
* Reduced accidental data loss

---

# Album Event Model

Albums are metadata.

Albums do not exist inside Telegram.

Therefore:

Album operations become events.

Examples:

```json
{
  "type":"ALBUM_CREATE",
  "albumId":"vacation",
  "name":"Vacation"
}
```

```json
{
  "type":"ALBUM_ADD",
  "albumId":"vacation",
  "messageId":1001
}
```

```json
{
  "type":"ALBUM_REMOVE",
  "albumId":"vacation",
  "messageId":1001
}
```

---

# Device Registry

Each installation registers itself.

Example:

```json
{
  "type":"DEVICE_REGISTER",
  "deviceId":"uuid",
  "deviceName":"Pixel 8",
  "version":"1.0.0"
}
```

Purpose:

* diagnostics
* leader election
* future sync analytics

---

# Real Time Sync

Every device subscribes to:

UpdateNewMessage

Whenever:

Device A uploads photo

Device B immediately receives event.

Room updates.

UI updates.

Exactly like Telegram.

No polling.

No database restore.

No manual refresh.

---

# Snapshot Architecture

Snapshots accelerate recovery.

Snapshots do not represent ownership.

Snapshots never overwrite each other.

Example:

snapshot_v001.db.gz

snapshot_v002.db.gz

snapshot_v003.db.gz

Keep last 10 snapshots.

Never keep only one.

---

# Why Immutable Snapshots

Traditional design:

```text
latest.db
overwrite
overwrite
overwrite
```

Problems:

* races
* corruption
* ownership conflicts

Immutable snapshots:

```text
snapshot_001
snapshot_002
snapshot_003
```

No overwrite.

No lock.

No conflict.

---

# Snapshot Metadata

Each snapshot contains:

```json
{
  "snapshotVersion":103,
  "deviceId":"pixel8",
  "recordCount":26184,
  "schemaVersion":7,
  "sha256":"..."
}
```

---

# Snapshot Selection

Recovery chooses:

Highest Snapshot Version

or

Latest Timestamp

after checksum validation.

---

# Why No Distributed Lock

Distributed lock introduces:

* race conditions
* lock expiry
* clock skew
* lock stealing
* pin synchronization issues
* flood waits

Snapshots are immutable.

Therefore:

No lock is necessary.

---

# Multi Device Scenario

Device A uploads photos.

Device B uploads photos.

Device C uploads photos.

Result:

All photos exist in Vault Channel.

All devices receive updates.

No ownership conflict exists.

---

# Snapshot Creation Strategy

Only one device should normally create snapshots.

Leader Election:

Lowest Device ID

or

Oldest Registered Device

becomes Snapshot Leader.

Leader creates snapshots.

Followers do not.

This dramatically reduces:

* bandwidth
* duplicate backups
* Telegram traffic

---

# Recovery Process

New Device

↓

Download latest snapshot

↓

Restore Room

↓

Replay Metadata Events

↓

Resume live updates

---

# Snapshot Missing

If no snapshot exists:

Rebuild from:

Vault Channel

*

Metadata Channel

This is slower but still works.

---

# Flood Wait Strategy

Avoid:

* lock messages
* heartbeat messages
* ownership messages

Only:

* media uploads
* metadata events
* periodic snapshots

This minimizes Telegram API pressure.

---

# Video Support

No architectural changes required.

Video uploads use:

same vault

same metadata

same snapshots

same recovery process

---

# Scalability Targets

Supported:

100,000 Photos

20,000 Videos

5 Devices

Millions of metadata events

without requiring a backend server.

---

# Final Design Principles

1. Telegram Vault is source of truth.
2. Room database is disposable.
3. Uploads are represented by media messages.
4. Only non-reconstructable actions become metadata events.
5. Deletes are events.
6. Album operations are events.
7. Snapshots are immutable.
8. Snapshots never overwrite each other.
9. No distributed mutex.
10. No pinned message ownership model.
11. One elected snapshot leader.
12. System must survive complete database loss.

This architecture aligns with modern distributed system principles and provides a scalable, fault-tolerant foundation for TGPix without introducing backend infrastructure.
