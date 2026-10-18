# Spool Scanner

Tap a filament spool with your Android phone. Spool Scanner finds it in your self-hosted
[Spoolman](https://github.com/Donkie/Spoolman), or creates it, and lets you weigh it, log usage,
set its location or archive it. It also prints a spool label on a Brother QL printer through
[labelito](https://github.com/chiva/labelito).

It works with:
- **Genuine Bambu Lab spools**: the two MIFARE Classic RFID tags on the spool are decoded (material,
  colour, weight, diameter, temperatures, tray UUID).
- **Any other spool**: stick an **NTAG215** NFC sticker on it. The app writes the spool's Spoolman code
  (`WEB+SPOOLMAN:S-<id>`) to the sticker and reads it back on the next tap.

Fork of [GraafG/bambu-spool-manager](https://github.com/GraafG/bambu-spool-manager) (MIT). Its Bambu tag
reading is kept unchanged. Firebase, Google sign-in, the web dashboard and Fichero printing were replaced
by Spoolman and labelito.

## How it works

| You tap | The app |
|---|---|
| A Bambu tag on a spool Spoolman knows | Shows grams left, location and actions. Matches on the tray UUID, which both tags on a spool share. It also matches spools created by [Bambuddy](https://github.com/maziggy/bambuddy), which stores the same UUID in the spool's `tag` field. |
| A Bambu tag Spoolman doesn't know | Opens a create form prefilled from the tag. On "Create", it matches or creates vendor "Bambu Lab", then matches the filament (material + colour + diameter, then Bambu material id or product name) or creates it, then creates the spool. Then it offers to print a label. |
| A sticker written by this app | Opens the spool by the id in its URI. Spoolman's own QR payloads, including the `…/spool/show/<id>` URL form, are accepted too. |
| A blank or unknown sticker | "New spool…" (manual form) or "Link to existing spool…". Then "Write NFC sticker" writes the URI on your next tap. |

Spool actions: **Weigh** (`PUT /spool/{id}/measure`, gross weight; Spoolman subtracts the empty-spool weight),
**Use** (`PUT /spool/{id}/use`, grams), **Location**, **Archive/Unarchive**, **Print label**, and
**Write NFC sticker** for non-Bambu spools. The Inventory tab lists every spool from Spoolman and keeps an
offline copy, shown when Spoolman can't be reached.

### Pixel phones and Bambu tags

Reading MIFARE Classic needs an NXP NFC controller, and Pixels don't have one. On a Pixel a Bambu tag
shows up as "Bambu tag this phone can't decode". You can still create the spool by hand or link the
tag to an existing spool. That records the tag's UID in the spool's `nfc_uid` field, so later taps find the spool.
When a phone that *can* decode Bambu tags creates the spool, the UID is recorded automatically.
NTAG215 stickers work on every NFC phone.

## Spoolman setup

No auth (Spoolman's default). On every start the app makes sure these extra fields exist, and creates only
the missing ones (`GET /api/v1/field/{entity}`, then `POST` per missing key):

| Entity | Key | Holds |
|---|---|---|
| spool | `bambu_tray_uuid` | Bambu tray UUID (32 hex chars), shared by both tags on a spool |
| spool | `nfc_uid` | Comma-separated tag UIDs seen for this spool |
| filament | `bambu_material_id` | Bambu material id from the tag, e.g. `GFA01` |

If Bambuddy's spool fields `tag` and `bambu_color_name` exist, new spools get those set too, so Bambuddy
recognises them in the AMS rather than adding duplicates. Extra-field values are JSON-encoded strings, as
Spoolman requires.

## labelito setup

The app prints with labelito's `spool-label` template (62 mm DK-2205 continuous roll), defined in the
homelab repo. It sends:

```json
POST /preview   →  image/png, shown for confirmation
POST /print
{"template": "spool-label",
 "fields": {"material": "PLA Matte", "color": "Charcoal", "vendor": "Bambu Lab",
            "spool_id": "#12", "qr": "WEB+SPOOLMAN:S-12"}}
```

The `qr` value is the same string Spoolman 0.26.1 puts in its own label QR codes, so Spoolman's scanner
can read it. Errors are reported explicitly:

| labelito says | App shows |
|---|---|
| 401 | Bad API token: fix it in Settings |
| 409 | Printer refused the job: wrong roll loaded (with labelito's detail) |
| timeout, connection refused, 502/503/504 | labelito unreachable: the gaming PC hosting the printer is probably asleep |
| DNS failure | `*.lab.apj.dev` only resolves on the home Wi-Fi |

## Settings

| Setting | Default |
|---|---|
| Spoolman URL | `https://spoolman.lab.apj.dev` |
| labelito URL | `https://labelito.lab.apj.dev` |
| labelito API token | none. Stored in EncryptedSharedPreferences |

The phone must be on the home Wi-Fi: `*.lab.apj.dev` resolves to a private address.

## Install with Obtainium

Signed APKs are published as GitHub Release assets. [Obtainium](https://github.com/ImranR98/Obtainium)
installs them and keeps them updated:

1. Install Obtainium (from its GitHub releases or F-Droid).
2. **Add App** → App source URL `https://github.com/lordmuffin/spool-scanner`.
3. If the repo is private, open the GitHub source settings and add a personal access token with read access to the repo.
4. **Add**, then **Install**. Obtainium checks for new releases and offers updates.

## Releasing

`.github/workflows/release.yml` runs on a `v*` tag. It runs the unit tests, builds a release APK signed with
our own keystore, and attaches `spool-scanner-<tag>.apk` to a GitHub Release.

One-time keystore setup:

```bash
keytool -genkeypair -v -keystore spool-scanner.jks -alias spool-scanner -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 spool-scanner.jks > spool-scanner.jks.b64
```

Add repo secrets `KEYSTORE_BASE64` (contents of the `.b64` file), `KEYSTORE_PASSWORD`, `KEY_ALIAS`
(`spool-scanner`) and `KEY_PASSWORD`. Keep the keystore safe (e.g. in 1Password). Android refuses
updates signed with a different key.

To release, bump `versionCode`/`versionName` in `app/build.gradle.kts`, then:

```bash
git tag v2.0.0 && git push origin v2.0.0
```

## Development

Requirements: JDK 17 or 21 (Android Studio's bundled JBR works) and the Android SDK (platform 36).

```bash
./gradlew testDebugUnitTest   # JVM unit tests: tag parser, key derivation, NDEF codec, URI parsing,
                              # matching rules, label fields, Spoolman + labelito clients (MockWebServer)
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug
```

Optional live test against a **throwaway** Spoolman (never your real one):

```bash
docker run -d --rm --name spoolman-test -p 7912:8000 ghcr.io/donkie/spoolman:0.26.1
SPOOLMAN_LIVE_URL=http://localhost:7912 ./gradlew testDebugUnitTest --tests '*SpoolmanLiveTest*'
docker stop spoolman-test
```

### Layout

```
app/src/main/java/com/bambu/nfc/
  data/nfc/        KeyDerivation, BambuTagReader, BambuTagParser (upstream, unchanged)
                   BambuTrayUuid, NdefCodec, SpoolUri, TagScanner (read Bambu/sticker, write sticker)
  data/spoolman/   Retrofit API, DTOs, ExtraValue (JSON-encoded extras), SpoolmanRepository
  data/labelito/   LabelitoClient (preview/print + error mapping), SpoolLabel (template fields)
  data/settings/   SettingsStore (EncryptedSharedPreferences)
  data/local/      Room offline cache of the last spool list
  domain/          SpoolMatcher, NewSpoolSpec, BambuColors
  ui/              scan, spool (panel + actions + print dialog), inventory, detail, settings
```

### Dependencies

| Library | Why |
|---|---|
| Retrofit + kotlinx.serialization converter | Spoolman REST client |
| OkHttp (+ logging, MockWebServer for tests) | HTTP for both services; fakes servers in tests |
| kotlinx.serialization | JSON DTOs and JSON-encoded extra-field values |
| androidx.security:security-crypto | EncryptedSharedPreferences for the labelito token |
| Room | Offline cache of the spool list |
| Hilt, Compose, Navigation | As upstream |

## NFC tag format (Bambu Lab, MIFARE Classic 1K)

Sector keys are derived from the tag UID with HKDF-SHA256 (salt `9a759cf2c4f7caff222cb9769b41bc96`,
info `"RFID-A\0"`, 96 bytes = 16 keys × 6). Blocks used: 1 material id, 2 type, 4 detailed type,
5 colour/weight/diameter, 6 temperatures, 9 tray UUID, 12 production date, 14 length. See the
[Bambu Research Group RFID guide](https://github.com/Bambu-Research-Group/RFID-Tag-Guide).

## License

MIT. See [LICENSE](LICENSE). Original work © GraafG.
