# Photo Backup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloaded Android app that backs up all photos/videos to the ASUS RT-AX86U's Samba drive when it's reachable, verifies each copy, helps free phone space, and has a combined phone + drive gallery with a looping, speed-adjustable player.

**Architecture:** Single Gradle app module, packages split by feature. All SMB access goes through a `RemoteStore` interface, so the backup engine, path planner and free-up rules are plain-JVM-testable against `FakeRemoteStore`. `smbj` is only touched in `SmbjRemoteStore`. A Room ledger is the source of truth for what's on the drive and verified.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.x, JDK 17, Compose (BOM 2024.10.01, Material 3, Navigation), smbj 0.13.0, Room 2.6.1, WorkManager 2.9.1, DataStore 1.1.1, Media3 1.4.1, Coil 2.7.0, JmDNS 3.5.9. Tests: JUnit 4, kotlinx-coroutines-test, Robolectric 4.13 (Room/MediaStore only).

**Spec:** `docs/superpowers/specs/2026-11-22-photo-backup-design.md`

## Global Constraints

- minSdk **30**, compileSdk/targetSdk **35**. Package `dev.photobackup`. App name "Photo Backup".
- The app never deletes or overwrites a drive file, **except** its own ledger-tracked `.part` files and a just-renamed copy that failed verification.
- An item is only offered for removal from the phone when its status is `VERIFIED` **and** the ledger's size + date modified still match the phone's current version.
- No location permission. The home check is "can connect and log in to the configured share".
- Background work uses unmetered networks only. Default: no charging requirement.
- Drive layout: `<destination>/<deviceName>/<sourceFolder>/YYYY/<fileName>`. Clash: `name (1).ext`, `name (2).ext`, …
- Free-up rule default: older than **6** months, sorted by size descending.
- Phone source folders (default): `DCIM/Camera/` and any `.../Screenshots/` relative path.
- Player: loop default **on**, remembered. Speeds `[0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0]`, pitch kept, resets to 1.0 on app launch.
- Use `createDeleteRequest`, not `createTrashRequest`.
- Credentials are encrypted with an Android Keystore AES/GCM key and never logged.

## Review Focus

1. **Wi-Fi drops mid-upload:** the item stays not-verified, a `.part` is left on the drive, and the next run deletes that `.part` and re-uploads. → Task 4 test `interruptedUploadLeavesItemPendingAndNextRunCleansPart`.
2. **Bad password / share gone / SMB1-only router:** the run stops, records a user-visible error (not endless silent retries), and Home shows it. → Task 4 test `authFailureStopsRunAndRecordsError`. Task 6 maps it to a notification.
3. **Photo deleted from the phone before it's uploaded:** it's skipped and the ledger row removed, with no crash. → Task 4 test `itemMissingOnPhoneIsSkipped`.
4. **Drive copy deleted or changed after verification:** it's excluded from free-up. → Task 8 test `recheckDropsItemsWhoseRemoteIsMissingOrWrongSize`.
5. **File names with characters SMB rejects** (`\ / : * ? " < > |`, trailing dot/space): they're sanitised, not failed. → Task 2 test `sanitisesIllegalSmbCharacters`.

---

### Task 0: Toolchain and project skeleton

**Machine state found:** only Java 8 is installed, with no Android SDK, Gradle or Docker. **Ask the owner before installing anything.**

**Files:**
- Create: `photo-backup/settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/dev/photobackup/MainActivity.kt`, `app/src/test/java/dev/photobackup/SmokeTest.kt`, `.gitignore`, `local.properties` (gitignored)

- [ ] **Step 1:** With the owner's OK, install Temurin JDK 17 (`winget install EclipseAdoptium.Temurin.17.JDK`) and Android command-line tools into `%LOCALAPPDATA%\Android\Sdk`. Then run `sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"`. Set `ANDROID_HOME`. Point `JAVA_HOME` at JDK 17 for Gradle.
- [ ] **Step 2:** `git init` in `photo-backup/`. Create the Gradle wrapper (8.10.2), version catalog with every library in Tech Stack, and a Compose `MainActivity` showing "Photo Backup".
- [ ] **Step 3:** Write `SmokeTest.smokeTestRuns()` asserting `1 + 1 == 2`.
- [ ] **Step 4:** Run `./gradlew testDebugUnitTest assembleDebug`. Expected: BUILD SUCCESSFUL, with `app/build/outputs/apk/debug/app-debug.apk` present.
- [ ] **Step 5:** Commit `chore: project skeleton`.

---

### Task 1: Ledger (Room) and state rules

**Files:**
- Create: `app/src/main/java/dev/photobackup/ledger/{BackupStatus.kt, LedgerEntry.kt, LedgerDao.kt, AppDatabase.kt, LedgerRules.kt}`
- Test: `app/src/test/java/dev/photobackup/ledger/{LedgerRulesTest.kt, LedgerDaoTest.kt}` (DAO test uses Robolectric + in-memory Room)

**Interfaces:**
- Produces:
  - `enum class BackupStatus { PENDING, UPLOADING, VERIFIED, FAILED, REMOVED_FROM_PHONE }`
  - `@Entity data class LedgerEntry(@PrimaryKey val mediaId: Long, val localUri: String, val displayName: String, val relativePath: String, val sizeBytes: Long, val dateTakenMs: Long, val dateModifiedSec: Long, val isVideo: Boolean, val localSha256: String?, val remotePath: String?, val partPath: String?, val status: BackupStatus, val lastError: String?, val updatedAtMs: Long)`
  - `LedgerDao`: `suspend fun upsert(e: LedgerEntry)`, `suspend fun get(mediaId: Long): LedgerEntry?`, `suspend fun byStatus(s: BackupStatus): List<LedgerEntry>`, `suspend fun withPartPath(): List<LedgerEntry>`, `suspend fun delete(mediaId: Long)`, `fun observeCounts(): Flow<Map<BackupStatus, Int>>`, `fun observeAll(): Flow<List<LedgerEntry>>`
  - `object LedgerRules { fun needsUpload(existing: LedgerEntry?, phone: PhoneMedia): Boolean; fun isSafeToRemove(e: LedgerEntry, phone: PhoneMedia?): Boolean }` (`PhoneMedia` is defined in Task 5. Define it here in `media/PhoneMedia.kt` so this task compiles: `data class PhoneMedia(val mediaId: Long, val uri: String, val displayName: String, val relativePath: String, val sizeBytes: Long, val dateTakenMs: Long, val dateAddedSec: Long, val dateModifiedSec: Long, val isVideo: Boolean, val mimeType: String)`)

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun newItemNeedsUpload() = assertTrue(LedgerRules.needsUpload(null, phone()))
@Test fun verifiedUnchangedDoesNot() = assertFalse(LedgerRules.needsUpload(entry(VERIFIED), phone()))
@Test fun editedAfterVerifyNeedsUpload() = assertTrue(LedgerRules.needsUpload(entry(VERIFIED), phone(dateModifiedSec = 999)))
@Test fun failedNeedsUpload() = assertTrue(LedgerRules.needsUpload(entry(FAILED), phone()))
@Test fun safeOnlyWhenVerifiedAndUnchanged() {
  assertTrue(LedgerRules.isSafeToRemove(entry(VERIFIED), phone()))
  assertFalse(LedgerRules.isSafeToRemove(entry(PENDING), phone()))
  assertFalse(LedgerRules.isSafeToRemove(entry(VERIFIED), phone(sizeBytes = 1)))
  assertFalse(LedgerRules.isSafeToRemove(entry(VERIFIED), null))
}
// LedgerDaoTest: upsert→get round-trips; byStatus filters; withPartPath returns only rows with partPath != null
```

- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "dev.photobackup.ledger.*"`. Expected: FAIL (unresolved references).
- [ ] **Step 3:** Implement. "Changed" means size **or** `dateModifiedSec` differs.
- [ ] **Step 4:** Run the same tests. Expected: PASS.
- [ ] **Step 5:** Commit `feat: backup ledger and state rules`.

---

### Task 2: Remote path planner

**Files:**
- Create: `app/src/main/java/dev/photobackup/backup/RemotePathPlanner.kt`
- Test: `app/src/test/java/dev/photobackup/backup/RemotePathPlannerTest.kt`

**Interfaces:**
- Consumes: `PhoneMedia`
- Produces:
  - `object RemotePathPlanner { fun targetDir(destination: String, deviceName: String, m: PhoneMedia, zone: ZoneId): String; fun sanitise(name: String): String; fun clashName(fileName: String, n: Int): String; fun partName(fileName: String): String; const val FAT32_MAX_BYTES = 4_294_967_295L }`
  - Paths use `\` separators (SMB) with no leading separator, e.g. `Backups\Pixel\2026\11`.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun usesDateTakenYearMonth() = assertEquals("Backups\\Pixel\\2026\\03", targetDir("Backups", "Pixel", phone(dateTakenMs = ms(2026,3,14)), UTC))
@Test fun fallsBackToDateAddedWhenTakenZero() = assertEquals("Backups\\Pixel\\2025\\12", targetDir("Backups", "Pixel", phone(dateTakenMs = 0, dateAddedSec = sec(2025,12,1)), UTC))
@Test fun clashNameKeepsExtension() { assertEquals("IMG_1 (1).jpg", clashName("IMG_1.jpg", 1)); assertEquals("noext (2)", clashName("noext", 2)) }
@Test fun partName() = assertEquals("IMG_1.jpg.part", partName("IMG_1.jpg"))
@Test fun sanitisesIllegalSmbCharacters() { assertEquals("a_b_c_.jpg", sanitise("a:b?c*.jpg")); assertEquals("trail", sanitise("trail. ")) ; assertEquals("日本.jpg", sanitise("日本.jpg")) }
@Test fun sanitisesDeviceName() = assertEquals("My_Phone", sanitise("My/Phone"))
```

- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*RemotePathPlannerTest"`. Expected: FAIL.
- [ ] **Step 3:** Implement. Replace `\ / : * ? " < > |` and control characters with `_`. Trim trailing dots and spaces. `targetDir` sanitises `deviceName`.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit `feat: remote path planner`.

---

### Task 3: RemoteStore abstraction, fake, and smbj implementation

**Files:**
- Create: `app/src/main/java/dev/photobackup/smb/{RemoteStore.kt, SmbConfig.kt, SmbjRemoteStore.kt, RemoteErrors.kt}`
- Create: `app/src/test/java/dev/photobackup/smb/FakeRemoteStore.kt` (test fixture shared by later tasks)
- Test: `app/src/test/java/dev/photobackup/smb/FakeRemoteStoreTest.kt`, `app/src/test/java/dev/photobackup/smb/SmbjRemoteStoreIT.kt` (skipped unless env `PB_SMB_HOST`, `PB_SMB_SHARE`, optional `PB_SMB_USER`/`PB_SMB_PASS` are set)

**Interfaces:**
- Produces:
  - `data class SmbConfig(val host: String, val share: String, val username: String?, val password: String?)` (null user means guest)
  - `data class RemoteEntry(val name: String, val path: String, val isDirectory: Boolean, val sizeBytes: Long, val modifiedMs: Long)`
  - `data class DriveInfo(val freeBytes: Long, val fileSystem: String)` (`fileSystem` is uppercase, e.g. `"FAT32"`, `"EXFAT"`, `"NTFS"`, `"UNKNOWN"`)
  - `sealed class RemoteException(msg: String) : IOException(msg)` with subclasses `AuthFailed`, `Unreachable`, `ShareNotFound`, `Smb1Only`, `DriveFull`
  - `interface RemoteStore : Closeable { suspend fun connect(): DriveInfo; suspend fun list(dir: String): List<RemoteEntry>; suspend fun stat(path: String): RemoteEntry?; suspend fun mkdirs(dir: String); suspend fun openWrite(path: String): OutputStream /* fails if exists */; suspend fun openRead(path: String, offset: Long = 0): InputStream; suspend fun rename(from: String, to: String) /* fails if `to` exists */; suspend fun delete(path: String) }`
  - `FakeRemoteStore(var failOn: ((op: String, path: String) -> RemoteException?)? = null, var freeBytes: Long = Long.MAX_VALUE, var fileSystem: String = "EXFAT", var truncateWritesAfter: Long? = null)`, plus `files: MutableMap<String, ByteArray>` for assertions

- [ ] **Step 1:** Write `FakeRemoteStoreTest`. Cases: `openWrite` on an existing path throws; `rename` onto an existing path throws; `truncateWritesAfter = 10` makes the stream throw `IOException` after 10 bytes and leaves the partial file in `files`.
- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement the fake. Implement `SmbjRemoteStore(config: SmbConfig)`:
  - Map smbj `STATUS_LOGON_FAILURE`/`STATUS_ACCESS_DENIED` to `AuthFailed`, `STATUS_BAD_NETWORK_NAME` to `ShareNotFound`, connect timeout/`ConnectException` to `Unreachable`, `STATUS_DISK_FULL` to `DriveFull`.
  - If negotiation fails because the server only offers SMB1, throw `Smb1Only`.
  - `openWrite` uses `FILE_CREATE` (never `OVERWRITE`).
  - `rename` uses `replaceIfExists = false`.
  - `connect()` reads free space and FS name via `share.getFileSystemInformation`/`FileFsFullSizeInformation`.
  - Connect timeout 5 s.
- [ ] **Step 4:** Run `./gradlew testDebugUnitTest --tests "dev.photobackup.smb.*"`. Expected: PASS. The IT test is skipped unless env vars are set. If the owner provides the router details, run it once and record the result.
- [ ] **Step 5:** Commit `feat: RemoteStore with smbj implementation`.

---

### Task 4: Backup engine (core safety logic)

**Files:**
- Create: `app/src/main/java/dev/photobackup/backup/{BackupEngine.kt, Hashing.kt, BackupResult.kt}`
- Test: `app/src/test/java/dev/photobackup/backup/BackupEngineTest.kt` (fake ledger via in-memory map implementation of a `LedgerStore` interface over `LedgerDao` methods. Define `interface LedgerStore` with the same suspend methods as `LedgerDao` and have the DAO implement it)

**Interfaces:**
- Consumes: `RemoteStore`, `LedgerStore`, `RemotePathPlanner`, `LedgerRules`, `PhoneMedia`
- Produces:
  - `fun interface LocalOpener { fun open(m: PhoneMedia): InputStream? /* null if gone from phone */ }`
  - `data class BackupSettings(val destination: String, val deviceName: String, val zone: ZoneId)`
  - `sealed interface BackupResult { data class Done(val uploaded: Int, val skipped: Int, val failed: Int): BackupResult; data class Stopped(val reason: RemoteException): BackupResult }`
  - `class BackupEngine(store: RemoteStore, ledger: LedgerStore, opener: LocalOpener, settings: BackupSettings, clock: () -> Long) { suspend fun run(items: List<PhoneMedia>, onProgress: (done: Int, total: Int, bytes: Long) -> Unit = { _, _, _ -> }): BackupResult }`

Per-item algorithm (order matters):
1. `connect()` once. Any `RemoteException` → return `Stopped`.
2. Clean leftovers: for each `withPartPath()` row, `delete(partPath)` if `stat` finds it. Then clear `partPath` and set the status to `PENDING`.
3. For each item where `needsUpload`:
   - If `DriveInfo.fileSystem == "FAT32"` and `size > FAT32_MAX_BYTES`, mark `FAILED`, `lastError = "too big for this drive's format (FAT32, 4 GB limit)"`.
   - If `size > freeBytes`, return `Stopped(DriveFull)`.
4. Pick a name. `sanitise(displayName)`. If `stat(dir\name)` exists with the same size, hash the remote: if the SHA matches, mark `VERIFIED` with that path and upload nothing. Otherwise try `clashName(n=1..)` until a free name or a matching one is found.
5. Upload. Set the ledger `UPLOADING` with `partPath`. Stream the local file into `openWrite(part)`, computing SHA-256. If `opener` returns null, delete the ledger row, delete the part, and count it skipped.
6. `rename(part, final)`, then clear `partPath`.
7. Verify. Re-read the remote and compare SHA-256. On a match, `VERIFIED` with `localSha256` and `remotePath`. On a mismatch, `delete(final)`, `FAILED`, `lastError = "verification failed"`.
8. Mid-item `RemoteException` of type `AuthFailed`/`Unreachable`/`ShareNotFound`/`DriveFull` → leave the row as-is (`UPLOADING` with `partPath`) and return `Stopped`. Any other `IOException` → `FAILED` with the message, and continue.

- [ ] **Step 1: Write failing tests** (each uses `FakeRemoteStore` + in-memory ledger)

```kotlin
@Test fun uploadsAndVerifiesNewItem()            // files["B\\Dev\\2026\\03\\IMG.jpg"] == bytes; ledger VERIFIED; sha set; no .part remains
@Test fun neverOverwritesDifferentFileWithSameName() // pre-seed different bytes at IMG.jpg → upload lands at "IMG (1).jpg"; original bytes unchanged
@Test fun identicalExistingFileIsAdoptedNotReuploaded() // pre-seed same bytes → VERIFIED, remotePath = IMG.jpg, no writes recorded
@Test fun interruptedUploadLeavesItemPendingAndNextRunCleansPart() // truncateWritesAfter + failOn throws Unreachable → Stopped; row UPLOADING w/ partPath; second run (no faults) deletes .part, ends VERIFIED
@Test fun verificationMismatchDeletesCopyAndFails() // fake corrupts on read-back → FAILED "verification failed"; final file deleted
@Test fun authFailureStopsRunAndRecordsError()   // failOn connect → AuthFailed → Stopped(AuthFailed); no ledger changes
@Test fun itemMissingOnPhoneIsSkipped()          // opener returns null → row removed, Done(skipped=1)
@Test fun fat32OversizeIsFailedWithReason()      // fileSystem="FAT32", size=5 GB → FAILED with FAT32 message, nothing written
@Test fun driveFullStops()                       // freeBytes < size → Stopped(DriveFull)
@Test fun editedItemUploadsAsNewCopy()           // VERIFIED row, phone dateModified changed, new bytes → new file "IMG (1).jpg", old file untouched
```

- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*BackupEngineTest"`. Expected: FAIL.
- [ ] **Step 3:** Implement per the algorithm above. Use a 1 MiB copy buffer, and run on `Dispatchers.IO`.
- [ ] **Step 4:** Run. Expected: all 10 PASS.
- [ ] **Step 5:** Commit `feat: backup engine with verify and leftover cleanup`.

---

### Task 5: Phone media source and settings store

**Files:**
- Create: `app/src/main/java/dev/photobackup/media/LocalMediaSource.kt`, `app/src/main/java/dev/photobackup/settings/{SettingsStore.kt, CredentialCipher.kt, AppSettings.kt}`
- Test: `app/src/test/java/dev/photobackup/media/SourceFolderFilterTest.kt`, `app/src/test/java/dev/photobackup/settings/SettingsStoreTest.kt` (Robolectric. Mock the cipher with an identity implementation through the `CredentialCipher` interface)

**Interfaces:**
- Produces:
  - `class LocalMediaSource(context: Context) { suspend fun query(folders: SourceFolders): List<PhoneMedia>; fun opener(): LocalOpener; fun contentUris(): List<Uri> /* images+videos EXTERNAL, for WorkManager triggers */ }`
  - `data class SourceFolders(val camera: Boolean = true, val screenshots: Boolean = true, val extraRelativePaths: Set<String> = emptySet()) { fun matches(relativePath: String): Boolean }`
  - `data class AppSettings(val smb: SmbConfig?, val destination: String?, val deviceName: String, val chargingOnly: Boolean = false, val freeUpAgeMonths: Int = 6, val folders: SourceFolders = SourceFolders(), val loopVideos: Boolean = true)`
  - `interface CredentialCipher { fun encrypt(plain: String): String; fun decrypt(cipher: String): String }`, with `KeystoreCredentialCipher` using AES/GCM/NoPadding and the key alias `pb_cred`
  - `class SettingsStore(context: Context, cipher: CredentialCipher) { val settings: Flow<AppSettings>; suspend fun update(transform: (AppSettings) -> AppSettings) }`
  - `deviceName` defaults to `Build.MODEL`

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun cameraMatches() = assertTrue(SourceFolders().matches("DCIM/Camera/"))
@Test fun screenshotsMatchAnyParent() { assertTrue(SourceFolders().matches("Pictures/Screenshots/")); assertTrue(SourceFolders().matches("DCIM/Screenshots/")) }
@Test fun whatsappOffByDefault() = assertFalse(SourceFolders().matches("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
@Test fun extraPathMatches() = assertTrue(SourceFolders(extraRelativePaths = setOf("Movies/")).matches("Movies/"))
@Test fun passwordStoredEncrypted() // SettingsStoreTest: update with password "pw" → raw DataStore value != "pw"; settings flow returns "pw"
@Test fun defaultsAreSpecValues()   // freeUpAgeMonths == 6, loopVideos == true, chargingOnly == false
```

- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement. Query `MediaStore.Images` and `MediaStore.Video` with `MediaStore.VOLUME_EXTERNAL`. Columns are `_ID, DISPLAY_NAME, RELATIVE_PATH, SIZE, DATE_TAKEN, DATE_ADDED, DATE_MODIFIED, MIME_TYPE`. Filter with `SourceFolders.matches`. The opener uses `contentResolver.openInputStream` and returns null on `FileNotFoundException`.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit `feat: phone media source and encrypted settings`.

---

### Task 6: Background worker and scheduling

**Files:**
- Create: `app/src/main/java/dev/photobackup/backup/{BackupWorker.kt, BackupScheduler.kt, BackupNotifications.kt, BackupStatusRepository.kt}`; modify `AndroidManifest.xml` (permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE` maxSdk 32, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`; WorkManager `SystemForegroundService` with `foregroundServiceType="dataSync"`)
- Test: `app/src/test/java/dev/photobackup/backup/BackupWorkerTest.kt` (Robolectric + `work-testing` `TestListenableWorkerBuilder`, with the engine injected through a factory)

**Interfaces:**
- Consumes: `BackupEngine`, `LocalMediaSource`, `SettingsStore`, `LedgerStore`
- Produces:
  - `object BackupScheduler { fun scheduleAll(context: Context, chargingOnly: Boolean); fun runNow(context: Context) }`
    - Content-URI trigger work (unique name `pb_trigger`, re-enqueued after each run)
    - Periodic every 6 h (`pb_periodic`)
    - `runNow` is expedited, unique `pb_now`, `KEEP`
    - All use `NetworkType.UNMETERED` and `requiresCharging(chargingOnly)`
  - `data class BackupStatusSnapshot(val lastRunMs: Long?, val lastResult: String?, val lastError: String?, val inProgress: Boolean, val done: Int, val total: Int, val etaSec: Long?)` exposed as `BackupStatusRepository.status: Flow<BackupStatusSnapshot>` (persisted in DataStore)
  - The worker calls `setForeground` with an ongoing progress notification ("Backing up 12 of 340").
    - `Stopped(Unreachable)` → `Result.success()` with status "Drive not reachable — not at home?" and no error notification.
    - `Stopped(AuthFailed | ShareNotFound | Smb1Only | DriveFull)` → `Result.success()` plus an error notification with the specific text. `Smb1Only` text: "Turn on SMB2 on the router: USB Application → Network Place (Samba) Share".
    - Any other exception → `Result.retry()` (exponential backoff, 15 min initial).

- [ ] **Step 1:** Write failing tests: `unreachableRecordsStatusWithoutErrorNotification`, `authFailedPostsErrorNotification`, `smb1OnlyMessageMentionsRouterSetting`, `unexpectedExceptionRetries`, `successUpdatesLastRun`.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*BackupWorkerTest"`. Expected: FAIL.
- [ ] **Step 3:** Implement. ETA = remaining bytes / average bytes-per-second of the current run.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit `feat: background backup worker and scheduling`.

---

### Task 7: Drive discovery

**Files:**
- Create: `app/src/main/java/dev/photobackup/discovery/{HostDiscovery.kt, NetBios.kt, SubnetScan.kt}`
- Test: `app/src/test/java/dev/photobackup/discovery/{NetBiosTest.kt, SubnetScanTest.kt}`

**Interfaces:**
- Produces:
  - `data class FoundHost(val name: String?, val address: String)`
  - `class HostDiscovery(context: Context) { fun discover(timeoutMs: Long = 4000): Flow<FoundHost> }` merges three sources, deduped by address, and holds a `WifiManager.MulticastLock` during mDNS:
    - JmDNS `_smb._tcp.local.`
    - NetBIOS broadcast name query (UDP 137, wildcard `*`) to the subnet broadcast address
    - TCP 445 connect probe across the /24 (64 parallel, 300 ms timeout)
  - `object NetBios { fun wildcardQueryPacket(txId: Short): ByteArray; fun parseNodeStatusName(packet: ByteArray): String? }`
  - `object SubnetScan { fun hostsIn(ip: String, prefix: Int): List<String> }` (caps at /24 even if the prefix is wider)
  - `class ShareLister { suspend fun shares(host: String, user: String?, pass: String?): List<String> }` lists shares via smbj + `smbj-rpc` (`com.rapid7.client:dcerpc`) `ServerService.getShares1()`, excluding names ending in `$`

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun hostsInSlash24() { val h = hostsIn("192.168.50.17", 24); assertEquals(254, h.size); assertEquals("192.168.50.1", h.first()); assertEquals("192.168.50.254", h.last()) }
@Test fun widerPrefixCappedAt24() = assertEquals(254, hostsIn("10.0.5.9", 16).size)
@Test fun queryPacketHasWildcardName() // bytes 12.. encode "*" padded, QTYPE=0x0021 NBSTAT
@Test fun parsesNameFromCapturedReply() // fixture bytes of an RT-AX86U-style reply → "RT-AX86U"
```

- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement. Add the `dcerpc` dependency (`com.rapid7.client:dcerpc:0.12.13`) to the catalog.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit `feat: SMB host discovery and share listing`.

---

### Task 8: Free-up suggestions and removal

**Files:**
- Create: `app/src/main/java/dev/photobackup/freeup/{SuggestionRule.kt, FreeUpRepository.kt, FreeUpViewModel.kt, FreeUpScreen.kt}`
- Test: `app/src/test/java/dev/photobackup/freeup/{SuggestionRuleTest.kt, FreeUpRepositoryTest.kt}`

**Interfaces:**
- Consumes: `LedgerStore`, `LedgerRules.isSafeToRemove`, `RemoteStore`, `LocalMediaSource`
- Produces:
  - `object SuggestionRule { fun suggest(entries: List<LedgerEntry>, phone: Map<Long, PhoneMedia>, nowMs: Long, ageMonths: Int, zone: ZoneId): List<LedgerEntry> }` returns verified, safe items with `dateTaken` (else date added) older than `ageMonths` calendar months, sorted by `sizeBytes` desc
  - `class FreeUpRepository(...) { suspend fun recheck(selected: List<LedgerEntry>, store: RemoteStore): List<LedgerEntry>; fun deleteRequest(context: Context, items: List<LedgerEntry>): IntentSender; suspend fun markRemoved(items: List<LedgerEntry>) }`
    - `recheck` keeps an item only if `stat(remotePath)` exists with size == `sizeBytes`
    - `deleteRequest` = `MediaStore.createDeleteRequest(resolver, uris).intentSender`
  - The screen shows thumbnails, sizes, a select-all checkbox, a "Remove N items from phone (X GB)" button, and a header with the total. It launches the sender with `rememberLauncherForActivityResult(StartIntentSenderForResult)`. `RESULT_OK` → `markRemoved` (status `REMOVED_FROM_PHONE`). If the drive isn't reachable, the button is disabled with "Connect to home Wi-Fi to free up space" (recheck needs the drive).

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun onlyOlderThanSixMonthsAndVerified()   // now=2026-11-22: item 2026-05-21 included, 2026-05-23 excluded, PENDING excluded
@Test fun sortedBiggestFirst()
@Test fun excludesEditedSinceBackup()           // phone size differs → excluded
@Test fun recheckDropsItemsWhoseRemoteIsMissingOrWrongSize()
@Test fun markRemovedSetsStatus()
```

- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit `feat: free-up suggestions and removal`.

---

### Task 9: Setup, Home, Settings, navigation

**Files:**
- Create: `app/src/main/java/dev/photobackup/ui/{AppNav.kt, theme/Theme.kt}`, `ui/setup/{SetupViewModel.kt, SetupScreen.kt, FolderPicker.kt}`, `ui/home/{HomeViewModel.kt, HomeScreen.kt}`, `ui/settings/SettingsScreen.kt`, `app/PhotoBackupApp.kt` (manual DI container: `AppContainer` holding the singletons)
- Test: `app/src/test/java/dev/photobackup/ui/setup/SetupViewModelTest.kt`

**Interfaces:**
- Consumes: `HostDiscovery`, `ShareLister`, `RemoteStore`, `SettingsStore`, `BackupScheduler`, `BackupStatusRepository`, `LedgerDao.observeCounts`
- Produces:
  - Routes: `setup`, `home`, `gallery`, `browse?path={path}`, `viewer/{source}/{index}`, `freeup`, `settings`. Start is `setup` if `settings.smb == null || destination == null`, else `home`.
  - `FolderPicker(store: RemoteStore, initialPath: String, onPicked: (String) -> Unit)` is a composable that browses dirs only and has a "New folder" button (`mkdirs`). It's reused by Settings to change the destination. Changing the destination only updates settings; it never touches the ledger.
  - **Setup steps:**
    1. Discover: live list plus "Enter address manually"
    2. Share list
    3. Credentials: Guest toggle, or user/pass, then "Test connection" maps `RemoteException` to a message
    4. FolderPicker
    5. Permissions: media read + notifications
    6. Done: `scheduleAll` + `runNow`
  - **Home:** status card (reachable / last backup / error text from `BackupStatusSnapshot.lastError`), counts (waiting / backed up / failed), progress + ETA when running, buttons "Back up now", "Free up X GB" (from `SuggestionRule`), "Gallery", "Browse drive".
  - **Settings:** drive/share/credentials (re-runs the test), destination (FolderPicker), device name, charging-only, free-up age (1–36 months), source folders (Camera, Screenshots, + add relative path).

- [ ] **Step 1:** Write failing tests:
  - `testConnectionMapsAuthFailedToMessage` (expects "Wrong username or password")
  - `smb1OnlyShowsRouterHint`
  - `finishingSetupSavesSettingsAndSchedules` (fake scheduler records the calls)
- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `./gradlew testDebugUnitTest assembleDebug`. Expected: PASS + BUILD SUCCESSFUL.
- [ ] **Step 5:** Commit `feat: setup flow, home and settings`.

---

### Task 10: Gallery and drive browser

**Files:**
- Create: `app/src/main/java/dev/photobackup/gallery/{GalleryItem.kt, GalleryRepository.kt, GalleryScreen.kt, BrowseScreen.kt, CopyToPhone.kt}`, `gallery/SmbCoilFetcher.kt`
- Test: `app/src/test/java/dev/photobackup/gallery/GalleryRepositoryTest.kt`

**Interfaces:**
- Consumes: `LocalMediaSource`, `LedgerStore.observeAll`, `RemoteStore`
- Produces:
  - `enum class Location { PHONE, DRIVE, BOTH }`
  - `data class GalleryItem(val key: String, val displayName: String, val dateMs: Long, val isVideo: Boolean, val sizeBytes: Long, val location: Location, val phoneUri: String?, val remotePath: String?)`
  - `object GalleryMerge { fun merge(phone: List<PhoneMedia>, ledger: List<LedgerEntry>): List<GalleryItem> }`, sorted by date desc:
    - Phone + `VERIFIED` → `BOTH`
    - `REMOVED_FROM_PHONE` → `DRIVE`
    - Phone with any other status → `PHONE`
  - `SmbCoilFetcher` is a Coil `Fetcher` for `smb://host/share/path` data. It decodes thumbnails with `inSampleSize` and caches to Coil's disk cache by key `path+size`. For video thumbnails it uses `MediaMetadataRetriever.setDataSource(MediaDataSource)` backed by `RemoteStore.openRead(offset)`.
  - Drive-only items render greyed out with "Drive not reachable" when `connect()` fails, using cached thumbnails.
  - `CopyToPhone.copy(context, store, remotePath, isVideo): Uri` inserts into MediaStore `Pictures/Photo Backup/` or `Movies/Photo Backup/` using `IS_PENDING`.

- [ ] **Step 1:** Write failing tests:
  - `verifiedOnPhoneIsBoth`
  - `removedIsDriveOnly`
  - `pendingIsPhoneOnly`
  - `phoneItemNotInLedgerIsPhone`
  - `sortedNewestFirst`
- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit `feat: combined gallery and drive browser`.

---

### Task 11: Viewer and player

**Files:**
- Create: `app/src/main/java/dev/photobackup/player/{SmbDataSource.kt, PlayerPrefs.kt, ViewerScreen.kt, PhotoPage.kt, VideoPage.kt, SpeedControl.kt}`
- Test: `app/src/test/java/dev/photobackup/player/{SmbDataSourceTest.kt, PlayerPrefsTest.kt}`

**Interfaces:**
- Consumes: `GalleryItem`, `RemoteStore`, `SettingsStore` (`loopVideos`)
- Produces:
  - `class SmbDataSource(store: RemoteStore) : BaseDataSource(isNetwork = true)`. It supports `DataSpec.position` / `length` via `openRead(path, offset)`. URI form: `smb://host/share/<path>`. `SmbDataSource.Factory`.
  - `object PlayerPrefs { val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f) }`, plus `class PlayerSession { var speed: Float = 1f }`. This is an in-memory singleton, so it resets on launch.
  - `ViewerScreen(items: List<GalleryItem>, startIndex: Int)` is a `HorizontalPager`:
    - Photo pages: zoomable (pinch + double-tap)
    - Video pages: an ExoPlayer per visible page, released off-screen
      - `repeatMode = if (loop) REPEAT_MODE_ONE else REPEAT_MODE_OFF`
      - `playbackParameters = PlaybackParameters(speed)` (pitch kept: pitch = 1f)
    - Overlay: loop toggle (persists to settings), speed chip menu, standard seek bar
    - Source choice: `phoneUri` if present, else `remotePath` via `SmbDataSource`

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun readsFromRequestedOffset()  // FakeRemoteStore file 0..999; open(DataSpec(uri, position=500, length=100)) → reads bytes 500..599, returns C.RESULT_END_OF_INPUT after
@Test fun unboundedLengthReadsToEnd()
@Test fun speedsMatchSpec() = assertEquals(listOf(0.25f,0.5f,0.75f,1f,1.25f,1.5f,2f), PlayerPrefs.SPEEDS)
@Test fun sessionSpeedDefaultsToOne() = assertEquals(1f, PlayerSession().speed)
```

- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `./gradlew testDebugUnitTest assembleDebug`. Expected: PASS + BUILD SUCCESSFUL.
- [ ] **Step 5:** Commit `feat: viewer with looping, speed-adjustable player`.

---

### Task 12: Release build and on-device check

**Files:**
- Modify: `app/build.gradle.kts` (release signing from `keystore.properties`, gitignored; R8 on, keep rules for smbj/bouncycastle/dcerpc/jmdns)
- Create: `photo-backup/README.md` (install via `adb install` or copy APK; router SMB2 toggle; known limits)

- [ ] **Step 1:** Generate a release keystore (`keytool`). Store its path and passwords in `keystore.properties` only. Ask the owner to choose the passwords themselves.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest assembleRelease`. Expected: BUILD SUCCESSFUL, with `app-release.apk` present.
- [ ] **Step 3:** On-device checklist, with the owner's phone and router. Record pass/fail for each in README:
  - Discovery lists the router
  - Guest or credential login works
  - Choosing and creating a folder works
  - A new photo backs up within minutes on home Wi-Fi
  - It doesn't back up on mobile data
  - Turning Wi-Fi off mid-video, then back on, completes cleanly with no `.part` left
  - Free-up removes only the chosen items after Android's prompt
  - A removed video plays from the drive with loop and 2× speed working
- [ ] **Step 4:** Commit `chore: release build and README`.
