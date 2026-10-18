# Privacy Policy — Spool Scanner

Spool Scanner talks only to the two servers you configure in Settings: a Spoolman instance
(filament inventory) and a labelito instance (label printing). By default both are on a private
home network.

- **NFC tag data** (Bambu Lab spool tags, NTAG215 stickers) is sent to your Spoolman server to look
  up or create spools. Spool data from Spoolman is cached on the device for offline viewing.
- **The labelito API token** is stored on the device in EncryptedSharedPreferences and sent only
  to the configured labelito server.
- **No accounts, analytics, crash reporting or third-party services.** Nothing is sent anywhere else.
- App data is excluded from Android backup.
