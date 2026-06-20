# ReelVault Privacy Policy

*Last updated: June 2026*

ReelVault ("the app") is a video library management tool. This policy explains what data the app accesses, how it is processed, and your rights under applicable privacy laws including the EU General Data Protection Regulation (GDPR).

## Summary

- **No telemetry or third-party tracking** — No analytics, crash reporters, or external SDKs.
- **Your data stays local** — Videos and metadata are stored on your device or your own server.
- **Fully transparent** — All processing happens on-device or infrastructure you control.

## Personal Data We Process

ReelVault processes the following personal data:

### Video Files & Metadata
- Video files themselves (if they contain identifiable individuals)
- Technical metadata extracted via FFmpeg/FFprobe:
  - Duration, codec, resolution, frame rate, bitrate
  - Color space, dynamic range (HDR), audio channels
  - Creation/modification timestamps
- **Sensitive metadata**:
  - GPS coordinates and location data (if embedded in the video)
  - Camera model, lens model, aperture, ISO
  - Timecode and frame numbers

### User-Created Data
- Tags you assign to videos
- Collections (custom groupings)
- Notes and annotations
- Search history (stored locally)

### Device & Pairing Data
- Pairing tokens (encrypted device identifiers used to authenticate connections between your client and server)
- Device names and addresses (during local network discovery via mDNS)

## Data Processing by Feature

### Local Library (Desktop, iOS Local Mode, Android Local Mode)
- All videos and metadata are stored **on your device only**.
- Metadata extraction (EXIF, GPS, technical specs) happens on-device.
- No data leaves your device unless you explicitly upload.

### Remote Library (iOS Remote, Android Remote, Desktop Remote Mode)
- Your client connects to a ReelVault server **you control** over your local network.
- Communication is encrypted using TLS 1.2+.
- Pairing tokens are stored encrypted on your device and server.
- The server stores all video files and metadata on infrastructure you own.
- The developer has no access to your server, its contents, or your data.

### Uploading Videos (iOS Local → Server, Android Local → Server)
- Videos are uploaded from your device to your ReelVault server over TLS.
- Uploads are sent to the last-paired server endpoint you configured.
- The developer does not intercept, store, or access uploaded videos.

## How We Use Data

- **Cataloging & Organization**: Extracting and indexing metadata to enable fast search and browsing.
- **Discovery**: Grouping videos by metadata (location, camera, date) for discovery features.
- **App Performance**: Storing your tags, collections, and preferences locally.
- **Connection Management**: Using pairing tokens to authenticate your client to your server.

## Data Retention

- **Local metadata**: Retained as long as the video is in your library. Deleted when you remove the video.
- **Tags, collections, notes**: Retained until you delete them.
- **Pairing tokens**: Retained until you unpair a device or revoke access via "Forget This Server."
- **Temporary files**: Cache files and temp data are cleaned up automatically (default retention: application-dependent, usually < 1 week).

## Legal Basis (GDPR)

ReelVault processes your data based on:
- **Legitimate Interest**: Providing core features (cataloging, search, organization).
- **Your Consent**: When you explicitly upload data or configure remote access.

## Your Rights

Under GDPR and similar privacy laws, you have the right to:

### Right to Access
You can request all personal data ReelVault holds about you. Contact us via GitHub issues; we will provide a machine-readable export (JSON) within 30 days.

### Right to Deletion
You can request deletion of all your data at any time:
- **Local library**: Delete videos and metadata directly in the app.
- **Remote library**: Delete your server instance or contact your server administrator.
- **Pairing tokens**: Revoke via "Forget This Server" in the app settings.
- **Account deletion**: Contact us via GitHub issues for complete data deletion assistance.

### Right to Portability
You can export all your user data (tags, collections, metadata) in JSON format via the app's export feature.

### Right to Withdraw Consent
If you consented to any optional processing (e.g., analytics, if we later add it), you can opt out at any time without penalty.

To exercise any right, open an issue at https://github.com/brianm998/ReelVault/issues and we will respond within 30 days.

## Security

- **Encryption in transit**: All communication between your client and server uses TLS 1.2 or higher.
- **Pairing tokens**: Stored encrypted on your device and server.
- **Local storage**: Your device's file system protects local metadata using OS-level access controls.
- **Server storage**: All data on your ReelVault server is protected by infrastructure you control.

## Third Parties

ReelVault does not share your data with third parties. However:
- **Open-source dependencies** (FFmpeg, SQLite, etc.) are used for core functionality but do not receive your personal data.
- **Your ReelVault server** (if you run one) is your responsibility to secure and back up.

## Data Breach Notification

If we discover a security incident affecting your data in any component operated by the developer, we will notify you as soon as possible. However, since you control your server infrastructure, you are responsible for monitoring and securing your own ReelVault instance.

## Policy Changes

We may update this policy as ReelVault evolves. We will notify you of material changes and give you the opportunity to object. Your continued use constitutes acceptance of updates.

## Contact & Requests

For privacy questions, data access requests, or deletion requests:
- Open an issue at https://github.com/brianm998/ReelVault/issues
- Email: brian@preffect.com

We will respond within 30 days.
