# CLAUDE.md — Project Instructions

## Project
**Spool Scanner**: an Android app that taps a filament spool's NFC tag, finds or creates the spool in a
self-hosted [Spoolman](https://github.com/Donkie/Spoolman) (0.26.1), manages its inventory (weigh, use,
location, archive), and prints a spool label through [labelito](https://github.com/chiva/labelito) on a
Brother QL-800. Fork of [GraafG/bambu-spool-manager](https://github.com/GraafG/bambu-spool-manager) (MIT):
its Bambu tag decoding is kept; Firebase, Google sign-in, the web dashboard and Fichero printing were removed.

Keep README.md current with every significant change, and document why any new dependency is needed.

## Code Style & Conventions
- Kotlin, Jetpack Compose + Material 3, MVVM (ViewModel + StateFlow), Hilt
- Spoolman is the source of truth. Room is only an offline cache of the last spool listing.
- Retrofit + kotlinx.serialization for Spoolman, plain OkHttp for labelito
- Min SDK 24, Target SDK 36. Namespace `com.bambu.nfc` (kept from upstream), applicationId `dev.apj.spoolscanner`
- Pure logic (matching, NDEF codec, URI parsing, label fields) lives outside Android classes and has JVM unit tests

## Do not change
`data/nfc/KeyDerivation.kt`, `BambuTagReader.kt`, `BambuTagParser.kt`: upstream's verified Bambu MIFARE
Classic reading (HKDF-SHA256 of the 4-byte UID; salt `9a759cf2c4f7caff222cb9769b41bc96`, info `"RFID-A\0"`,
96 bytes = 16 sector keys × 6). Derive extra values from the raw blocks elsewhere (see `BambuTrayUuid.kt`).

## Key facts
- **NFC dispatch**: `NfcAdapter.enableReaderMode` in `MainActivity` (NFC-A/B, platform NDEF check kept because
  `TagScanner` reads stickers via the Ndef tech; no platform sound, `Haptics` vibrates instead). The manifest
  `NDEF_DISCOVERED` filter for `web+spoolman` only covers launching from a cold sticker tap.
- **Bambu tags**: two per spool, different UIDs, same tray UUID (block 9, 16 bytes → 32 hex chars = AMS
  `tray_uuid`). Not every phone can read MIFARE Classic (it depends on the NFC controller; don't assume either way for a given Pixel). When it can't, the app falls back to the UID.
- **Stickers**: NTAG215 with one NDEF URI record `WEB+SPOOLMAN:S-<id>`, the exact string Spoolman 0.26.1 puts in
  its own QR labels (`spoolQrCodePrintingDialog.tsx`); parsed case-insensitively, plus `…/spool/show/<id>` URLs.
- **Spoolman extra fields** (created on start if missing): spool `bambu_tray_uuid`, `nfc_uid` (comma-separated
  UIDs); filament `bambu_material_id`. Values are JSON-encoded strings (`"\"abc\""`). Spoolman merges `extra` per
  key on PATCH. Bambuddy also writes spool `tag` (= tray UUID) and `bambu_color_name`; we match on `tag` and set
  both when those fields exist.
- **labelito**: `POST /preview` (PNG) and `POST /print` with `{"template":"spool-label","fields":{material,color,
  vendor,spool_id,qr}}`, bearer token. 401 = bad token, 409 = wrong roll / printer fault, timeout or 502/503/504 =
  the gaming PC hosting the USB printer is asleep.
- Both default URLs are on `*.lab.apj.dev`, which only resolves on the home Wi-Fi.

## Build & Test
```bash
./gradlew testDebugUnitTest   # JVM unit tests
./gradlew assembleDebug       # debug APK
```
Never create test data in the real Spoolman. `SpoolmanRepositoryTest` uses MockWebServer; the optional live test
(`SPOOLMAN_LIVE_URL`) is meant for a throwaway container (see README).
