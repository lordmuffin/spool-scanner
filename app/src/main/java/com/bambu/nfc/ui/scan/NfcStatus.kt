package com.bambu.nfc.ui.scan

/** Whether this phone can scan right now. Re-checked on every resume and on adapter state changes. */
enum class NfcStatus {
    /** No NFC hardware at all. */
    Unsupported,
    /** NFC hardware present but switched off in system settings. */
    Disabled,
    Enabled;

    companion object {
        fun of(hasAdapter: Boolean, isEnabled: Boolean): NfcStatus = when {
            !hasAdapter -> Unsupported
            !isEnabled -> Disabled
            else -> Enabled
        }
    }
}

/** What the scan screen shows about NFC; null when there's nothing to warn about. */
data class NfcBanner(val title: String, val message: String, val showSettingsButton: Boolean)

fun NfcStatus.banner(): NfcBanner? = when (this) {
    NfcStatus.Enabled -> null
    NfcStatus.Disabled -> NfcBanner(
        title = "NFC is off",
        message = "Turn NFC on to scan spool tags and stickers.",
        showSettingsButton = true,
    )
    NfcStatus.Unsupported -> NfcBanner(
        title = "This device does not support NFC",
        message = "Spool tags can't be scanned on this device. Inventory and label printing still work.",
        showSettingsButton = false,
    )
}
