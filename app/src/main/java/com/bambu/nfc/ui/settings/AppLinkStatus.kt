package com.bambu.nfc.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Whether Spoolman spool links (label QR codes) open in this app, per host. */
enum class LinkState(val label: String) {
    Verified("Verified: opens in this app"),
    Selected("Approved by you: opens in this app"),
    NotYet("Not yet: links open in the browser. Tap below and add the link"),
    Disabled("Off: opening supported links is turned off for this app"),
    Unknown("Can't check on this Android version (needs Android 12)");
}

object AppLinkStatus {
    /** Hosts declared in the manifest's App Link intent filter. */
    val HOSTS = listOf("spoolman.lab.apj.dev", "spoolman.apj.dev")

    /** [domainState] is a DomainVerificationUserState.DOMAIN_STATE_* value (null: host not declared). */
    fun stateOf(domainState: Int?, linkHandlingAllowed: Boolean): LinkState = when {
        !linkHandlingAllowed -> LinkState.Disabled
        domainState == DomainVerificationUserState.DOMAIN_STATE_VERIFIED -> LinkState.Verified
        domainState == DomainVerificationUserState.DOMAIN_STATE_SELECTED -> LinkState.Selected
        else -> LinkState.NotYet
    }

    fun read(context: Context): Map<String, LinkState> {
        if (Build.VERSION.SDK_INT < 31) return HOSTS.associateWith { LinkState.Unknown }
        val state = runCatching {
            context.getSystemService(DomainVerificationManager::class.java)
                ?.getDomainVerificationUserState(context.packageName)
        }.getOrNull() ?: return HOSTS.associateWith { LinkState.Unknown }
        return HOSTS.associateWith { host -> stateOf(state.hostToStateMap[host], state.isLinkHandlingAllowed) }
    }

    /**
     * The system "Open by default" page for this app, where a domain Android couldn't verify (the
     * host is LAN-only, so Google's verifier may not reach assetlinks.json) can be approved by hand.
     */
    fun openSettings(context: Context) {
        val uri = Uri.parse("package:${context.packageName}")
        val intent = if (Build.VERSION.SDK_INT >= 31) {
            Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, uri)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
