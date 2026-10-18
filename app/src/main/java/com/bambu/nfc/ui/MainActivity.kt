package com.bambu.nfc.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bambu.nfc.data.qr.LinkIntents
import com.bambu.nfc.ui.navigation.AppNavGraph
import com.bambu.nfc.ui.scan.NfcStatus
import com.bambu.nfc.ui.scan.ScanViewModel
import com.bambu.nfc.ui.theme.BambuNfcTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private companion object {
        const val TAG = "MainActivity"

        // Bambu tags are MIFARE Classic and NTAG215 stickers are NTAG, both NFC-A. NFC-B costs
        // nothing. The platform NDEF check stays on (no FLAG_READER_SKIP_NDEF_CHECK) because
        // TagScanner reads stickers through the Ndef tech, which that check populates.
        // FLAG_READER_NO_PLATFORM_SOUNDS: we vibrate instead (Haptics).
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        const val PRESENCE_CHECK_DELAY_MS = 250
    }

    private var nfcAdapter: NfcAdapter? = null
    private val scanViewModel: ScanViewModel by viewModels()
    private lateinit var haptics: Haptics

    // Runs on a binder thread: vibrate right away, then hand the tag to the main thread.
    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        Log.d(TAG, "Reader mode delivered tag: techs=${tag.techList.joinToString { it.substringAfterLast('.') }}")
        haptics.tagDetected()
        runOnUiThread { scanViewModel.onTagDiscovered(tag) }
    }

    // NFC toggled from the quick-settings shade doesn't pause the activity, so listen as well.
    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF)
            if (state == NfcAdapter.STATE_ON || state == NfcAdapter.STATE_OFF) {
                refreshNfcStatus()
                if (state == NfcAdapter.STATE_ON) enableReaderMode()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        haptics = Haptics(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "This device does not support NFC", Toast.LENGTH_LONG).show()
        }

        setContent {
            BambuNfcTheme {
                AppNavGraph(scanViewModel = scanViewModel)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                scanViewModel.tagErrors.collect { haptics.error() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                scanViewModel.qrAccepted.collect { haptics.tagDetected() }
            }
        }

        // Cold start from a sticker tap (NDEF_DISCOVERED) or a label link (ACTION_VIEW App Link).
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshNfcStatus()
        ContextCompat.registerReceiver(
            this, adapterStateReceiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        enableReaderMode()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(adapterStateReceiver) }
        runCatching { nfcAdapter?.disableReaderMode(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun refreshNfcStatus() {
        val adapter = nfcAdapter
        val status = NfcStatus.of(hasAdapter = adapter != null, isEnabled = adapter?.isEnabled == true)
        Log.d(TAG, "NFC status: $status")
        scanViewModel.onNfcStatus(status)
    }

    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return // re-enabled from adapterStateReceiver when NFC comes on
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_DELAY_MS) }
        try {
            adapter.enableReaderMode(this, readerCallback, READER_FLAGS, extras)
            Log.d(TAG, "Reader mode enabled")
        } catch (e: Exception) {
            Log.w(TAG, "enableReaderMode failed: ${e.message}")
        }
    }

    /**
     * Tags normally arrive through reader mode; intents bring the rest: an App Link from the stock
     * camera / a browser (ACTION_VIEW) and a sticker tapped while the app was closed (NDEF_DISCOVERED).
     */
    private fun handleIntent(intent: Intent) {
        LinkIntents.linkFrom(intent.action, intent.dataString)?.let { link ->
            Log.d(TAG, "Link intent: $link")
            scanViewModel.onLink(link)
            return
        }
        if (intent.action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            intent.action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED
        ) return
        val tag: Tag? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        Log.d(TAG, "Tag delivered by intent ${intent.action}: ${tag != null}")
        tag?.let(scanViewModel::onTagDiscovered)
    }
}
