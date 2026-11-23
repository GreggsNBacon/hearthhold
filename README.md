# Hearthhold

Android app that backs up your phone's photos and videos over SMB2 to a USB drive plugged into your home router. No cloud, no accounts. Browse and play back from the drive in the app, and free up phone space once files are safely copied.

## Install

- Enable USB debugging, then: `adb install app/build/outputs/apk/release/app-release.apk`
- Or copy the APK to the phone and open it (allow "install unknown apps" for your file manager).
- Unsigned builds (`app-release-unsigned.apk`) must be signed before installing; see Build.

## Router setup

- Plug the USB drive into the router.
- Enable the router's USB/Samba share and make sure **SMB2 (or SMB3) is enabled**. SMB1 only is not supported.
- Note the share name; the drive is reached at the router's share (e.g. `\router\share`).

## First run

- Grant photo/video and notification permissions.
- Discovery lists the router (or enter its address manually).
- Log in as guest or with credentials.
- Choose or create the destination folder on the drive.
- Pick which phone folders (sources) to back up, then enable backup.

## How backups behave

- Runs on home Wi-Fi only, never on mobile data.
- Gentle pacing so the router and phone are not overloaded.
- Parallel setting: number of simultaneous uploads (lower is gentler).
- Overnight window: optionally restrict backups to set hours.
- Notifications show progress and problems.
- Interrupted copies resume cleanly; no `.part` files are left behind.
- **Free up space**: removes only items you choose, after a full hash re-check against the drive copy and Android's own confirmation prompt.
- Removed videos play from the drive, with loop and 2x speed.

## Drive layout

`<dest>/<device>/<source>/YYYY/`

## Known limits

- Google Photos Locked Folder is not accessible to the app.
- FAT32 drives cannot hold files over 4 GB; use exFAT/NTFS/ext4.
- The first backup can take a long time.

## Build

- JDK 17. `./gradlew assembleRelease`
- Signing: create `keystore.properties` in the repo root (gitignored, as are `*.jks`):

```
storeFile=C:/path/to/your-release.jks
storePassword=CHANGE_ME
keyAlias=your-alias
keyPassword=CHANGE_ME
```

- With this file present the output is `app/build/outputs/apk/release/app-release.apk`; without it, `app-release-unsigned.apk`.
- Release builds use R8 shrinking; keep rules are in `app/proguard-rules.pro`.

## Device test checklist

| # | Check | Result |
|---|-------|--------|
| 1 | Discovery lists the router | |
| 2 | Guest or credential login works | |
| 3 | Choosing and creating a folder works | |
| 4 | A new photo backs up within minutes on home Wi-Fi | |
| 5 | It doesn't back up on mobile data | |
| 6 | Turning Wi-Fi off mid-video, then back on, completes cleanly with no `.part` left | |
| 7 | Free-up removes only the chosen items after Android's prompt | |
| 8 | A removed video plays from the drive with loop and 2x speed working | |
