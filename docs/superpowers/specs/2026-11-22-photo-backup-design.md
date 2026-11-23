# Photo Backup — Design

Date: 2026-11-22
Status: approved 2026-11-22

## Intent

The owner's phone is running out of space. They want an Android app that copies their photos and videos to the USB drive shared by their ASUS RT-AX86U router (Samba). The app copies only when the phone is on Wi-Fi and can reach the drive, which in practice means at home. Once a file is safely on the drive, the app helps them remove it from the phone. The drive should show up in the app the way it does in Windows' network list: found on the network, with no typing of IP addresses.

Remote access (Immich on a spare PC, router WireGuard VPN) and Google Photos are explicitly **later / out of scope** for this build.

## Goals

1. **Find the drive automatically** on the local network, then browse its shares and folders. Manual entry (host name or IP) is the fallback.
2. **User chooses the destination folder** on the drive: browse the share, pick any folder or create a new one, and change it later in Settings. After a change, new backups go to the new folder. Files already backed up stay where they are (the ledger keeps each file's remote path), so nothing is moved or re-uploaded.
3. **Back up all photos and videos automatically** when the phone is on Wi-Fi and the drive can be reached. *Owner's decision:* no Wi-Fi-name check and no location permission.
4. **Verify each copy** before it's ever offered for removal from the phone.
5. **"Free up space" screen.** Suggests verified files **older than N months (default 6), biggest first**, with the total space they'd free. The user approves; Android's own delete dialog confirms it.
6. **Browse the drive in the app.** Copy a file back to the phone.
7. **Built-in viewer and player** for photos and videos (see "Viewer / player" below). Full-screen, swipe between items, pinch-zoom photos. Videos **loop automatically** (on by default, toggle in the player) and have a **speed control** (0.25×, 0.5×, 0.75×, 1×, 1.25×, 1.5×, 2×). Videos stream straight from the drive; nothing has to be downloaded first.

## Non-goals

- Access from outside the home network, logins, or any server component.
- Google Photos integration.
- iOS. Publishing to the Play Store (the app is sideloaded as an APK).
- Mirroring deletions either way (deleting on the drive never touches the phone, and vice versa).
- Tracking "last played". Android doesn't record it. This was considered and dropped.

## Anti-goals (failure even if it "works")

- Removing anything from the phone that isn't verified on the drive.
- The app ever deleting or overwriting files on the drive that it didn't just write itself. *My call:* the only exceptions are its own temporary `.part` files and a just-written copy that failed verification. Both are tracked in the ledger, so the app can tell they're its own.
- Using mobile data, or draining the battery in the background.
- Silent failure: if backups stop (wrong Wi-Fi, drive unplugged, bad password), the user has to be able to see it.

## Constraints

- Server: ASUS RT-AX86U USB share (Asuswrt Samba). **SMB2 must be enabled** in the router (USB Application → Network Place (Samba) Share). The app tells the user this if it can only negotiate SMB1. The share can be guest or username/password.
- Router USB drives are slow-ish (tens of MB/s at best). Large videos take minutes.
- Development is on Windows 11. Build with Gradle / Android Studio.
- Phone make and model are unknown. Aggressive battery management (Samsung, Xiaomi) can delay background work.

## Approaches considered

| Approach | Trade-off | Outcome |
|---|---|---|
| **Native Kotlin app** (Jetpack Compose, WorkManager, smbj) | Android-only. Best control of background work, Wi-Fi detection and MediaStore delete flow | **Picked** |
| Flutter cross-platform | Could reach iPhone later, but its SMB libraries are weak and background work needs native code anyway | Rejected |
| Configure an existing app (FolderSync / PhotoSync) | Fastest, but discovery, the verify-then-free flow and the built-in browser depend on someone else's app | Rejected |

## Decided design

### Stack
- Kotlin, Jetpack Compose (Material 3), single-activity.
- **smbj** for SMB2/3. Note: the RT-AX86U supports SMB2.
- **WorkManager** for background backup, using a foreground notification during transfers.
- **Room** for the local backup ledger.
- **Media3 ExoPlayer** with a custom SMB `DataSource` to play video from the drive. **Coil** with a custom SMB fetcher for image thumbnails.
- *My call:* **minSdk 30 (Android 11)**, because `MediaStore.createDeleteRequest` (batch delete with one system confirmation) needs it. Target the latest SDK.

### Screens
1. **Setup** (first run): find the drive → pick share → log in (guest or user/password) → pick or create a destination folder → grant permissions (photos and videos, notifications).
2. **Home**: status (last backup, files waiting, files backed up, drive reachable yes/no), "Back up now", and "Free up X GB" button.
3. **Free up space**: list of verified items matching the rule (older than N months, sorted by size desc), with thumbnails, sizes, select all / individual, total, and a "Remove from phone" button that opens Android's delete confirmation.
4. **Gallery**: one date-sorted grid of everything, whether it's on the phone, on the drive, or both. A small badge shows where each item lives (phone / drive / both). Tap to open the viewer. Drive-only items appear only when the drive is reachable. The app keeps a cached list and thumbnails, so it can show "on drive (not reachable now)" greyed out.
5. **Browse drive**: folder view of the share, for files the app didn't put there. Tap opens the viewer. Overflow menu: "Copy to phone".
6. **Viewer / player**: see below.
7. **Settings**: drive/share/folder, credentials, charging-only toggle (default off), age threshold N, which phone folders to include.

### Backup behaviour
- **Source:** MediaStore images and videos. *My call:* by default include the camera folder (DCIM/Camera) and Screenshots. Other folders (e.g. WhatsApp) can be turned on in Settings.
- **Trigger:** WorkManager content-URI trigger on new media, plus a periodic job (every few hours) as a safety net. Constraints: unmetered network. Before uploading, the worker checks that it can connect and log in to the configured share. If it can't, it stops quietly and updates the status ("Drive not reachable — not at home?"). This reachability check is the only "am I home" test.
- **Layout on the drive** *(owner's decision, 2026-11-22)*: `<destination>/<device name>/<source folder>/YYYY/<original file name>`.
  - The year comes from date taken, falling back to date added.
  - `<source folder>` is the last segment of the phone's relative path, sanitised. For example, `DCIM/Camera/` → `Camera`, `Pictures/Screenshots/` → `Screenshots`, `…/WhatsApp Images/` → `WhatsApp Images`. If the path is empty, it's `Other`. Device name is editable in Settings.
- **Name clash:** if a file with the same name exists and has the same size and hash, mark it as already backed up. If it differs, upload as `name (1).ext` etc. Never overwrite.
- **Upload:** write to `<name>.part` and rename when complete, so a half-copied file never looks finished. Resume isn't required: an interrupted file restarts.
- **Verify:** compute SHA-256 of the local file while uploading. After the rename, read the remote file back and compare its SHA-256. Only a match marks the item **verified**.
- **Ledger (Room):** MediaStore id, local path, size, date taken, local hash, remote path, status (`pending` / `uploading` / `verified` / `failed` / `removed_from_phone`), last error, timestamps.
- *My call:* **Leftovers.** On restart, delete any `.part` file the ledger says this app started. If a renamed copy fails verification, delete it and retry under the same name.
- *My call:* **Edited files.** The ledger records MediaStore id + size + date modified. If a backed-up file changes on the phone, it goes back to `pending` and uploads as a new copy (`name (1).ext`). The old copy on the drive stays. "Verified" always means *the version on the phone right now* is on the drive.
- *My call:* **Drive limits.** Before uploading, check free space on the share (SMB query). On FAT32 drives, flag files over 4 GB as "too big for this drive's format" instead of failing again and again. Show both on Home.
- *My call:* **First backup.** The backlog can be large. Home shows progress and an estimated time left. A "Back up now" run keeps going in the foreground while the screen is on, so it doesn't depend on Android's background-time limits.
- **Errors:** retry with backoff. A persistent failure (bad password, drive missing, SMB1-only) shows on Home and in a notification.

### Viewer / player
- *Owner's decision:* covers **phone and drive media together**. It opens from the Gallery or Browse, and swiping moves through that list.
- **Photos:** full-screen, pinch-zoom, double-tap zoom.
- **Videos:** Media3 ExoPlayer. Phone files play locally. Drive files stream through the custom SMB `DataSource`, with seeking.
  - **Loop:** on by default, with a toggle in the player. The setting is remembered.
  - **Speed:** 0.25× / 0.5× / 0.75× / 1× / 1.25× / 1.5× / 2×. Pitch is kept. *My call:* the speed is remembered for the session and resets to 1× next launch.
- If a phone copy exists, the app uses it, because that's faster. Otherwise it streams from the drive.

### Freeing space
- The user picks the items. The app calls `createDeleteRequest`, and Android shows its own confirmation. On success, the ledger records `removed_from_phone`. The drive copy is untouched.
- *Owner's decision (2026-11-22): full integrity check before every deletion.* Before building the request, for each selected item:
  - re-check that it is still `verified`;
  - re-read the **drive copy in full** and compare its SHA-256 with the hash recorded at backup time;
  - re-hash the **phone file** and compare it with that same recorded hash.

  Only items that pass all three checks go into the delete request. A failing item is marked `failed` (the reason is shown on screen) and re-queued for backup, and it is never deleted. The screen shows progress ("Checking 12 of 40 · 3.1 GB") and can be cancelled. Checking is slower, but it is the guarantee.
- *My call:* use delete, not trash (`createTrashRequest`). Trash would keep using space for 30 days, which defeats the purpose.

### Discovery
- Find SMB hosts on the local subnet. Use mDNS (`_smb._tcp`) and NetBIOS name query broadcast, and fall back to probing TCP 445 across the /24. List them by name (e.g. `RT-AX86U`).
- Manual host/IP entry is always available.

### Remembering the login
*Owner's decision (2026-11-22):*
- **One saved login per drive.** The username and password entered once (in setup, or when listing shares) are reused for every share and every subfolder: the folder picker, Browse, the gallery, the player and background backups. Opening a subfolder never prompts.
- **Re-prompt only on authentication failure.** If any drive access fails with "wrong username or password", the app shows one "Re-enter drive password" dialog (username pre-filled). On a successful login, the new password is saved, and the action that failed is retried automatically. Cancelling leaves the old login in place.
- **Background failures.** If a background backup fails to log in, it posts the "Wrong username or password" notification. Tapping it opens the app straight to that dialog.
- If the saved password can't be decrypted (e.g. after a phone restore), the app treats it the same as an authentication failure.

### Security
- Credentials are encrypted with an Android Keystore-backed key and stored in DataStore. They're never logged.

## Left to the builder

- Exact discovery implementation and ordering. Which mDNS/NetBIOS library, or a hand-rolled one.
- Package name, app name and icon.
- Thumbnail caching strategy for the drive browser.
- Periodic job interval, and retry/backoff numbers.
- Test strategy. Unit tests for ledger state transitions, the name-clash logic and the suggestion rule are expected at minimum. The SMB parts can be tested against a local Samba (e.g. Docker) or the real router.
