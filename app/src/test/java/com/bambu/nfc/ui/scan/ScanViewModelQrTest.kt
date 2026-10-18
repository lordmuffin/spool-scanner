package com.bambu.nfc.ui.scan

import android.content.Intent
import com.bambu.nfc.data.nfc.BambuTagParser
import com.bambu.nfc.data.nfc.BambuTagReader
import com.bambu.nfc.data.nfc.TagScanner
import com.bambu.nfc.data.qr.LinkIntents
import com.bambu.nfc.data.qr.QrScanner
import com.bambu.nfc.data.settings.SpoolLinks
import com.bambu.nfc.data.spoolman.ExtraField
import com.bambu.nfc.data.spoolman.ExtraFieldParams
import com.bambu.nfc.data.spoolman.ExtraValue
import com.bambu.nfc.data.spoolman.Filament
import com.bambu.nfc.data.spoolman.FilamentCreate
import com.bambu.nfc.data.spoolman.FilamentPatch
import com.bambu.nfc.data.spoolman.Spool
import com.bambu.nfc.data.spoolman.SpoolCreate
import com.bambu.nfc.data.spoolman.SpoolMeasure
import com.bambu.nfc.data.spoolman.SpoolPatch
import com.bambu.nfc.data.spoolman.SpoolUse
import com.bambu.nfc.data.spoolman.SpoolmanApi
import com.bambu.nfc.data.spoolman.SpoolmanApiSource
import com.bambu.nfc.data.spoolman.SpoolmanRepository
import com.bambu.nfc.data.spoolman.Vendor
import com.bambu.nfc.data.spoolman.VendorCreate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.ConnectException

/** In-memory Spoolman: just enough of the API for the scan flows. */
private class FakeSpoolmanApi : SpoolmanApi {
    val vendor = Vendor(1, "Bambu Lab")
    val spoolsById = mutableMapOf<Long, Spool>()
    val filamentsById = mutableMapOf<Long, Filament>()
    var unreachable = false
    var spoolGets = 0
    private val fields = mutableMapOf("spool" to mutableListOf<String>(), "filament" to mutableListOf())

    private fun check() { if (unreachable) throw ConnectException("connection refused") }
    private fun notFound(): Nothing = throw HttpException(Response.error<Any>(404, "{}".toResponseBody()))

    override suspend fun info() = JsonObject(emptyMap())
    override suspend fun fields(entity: String) = fields.getValue(entity).map { ExtraField(it, it, "text", entity) }
    override suspend fun addField(entity: String, key: String, params: ExtraFieldParams): List<ExtraField> {
        fields.getValue(entity) += key
        return fields(entity)
    }
    override suspend fun vendors() = listOf(vendor)
    override suspend fun createVendor(body: VendorCreate) = Vendor(2, body.name)
    override suspend fun filaments() = filamentsById.values.toList()
    override suspend fun createFilament(body: FilamentCreate) = Filament(id = 100, name = body.name).also { filamentsById[it.id] = it }
    override suspend fun filament(id: Long): Filament { check(); return filamentsById[id] ?: notFound() }
    override suspend fun patchFilament(id: Long, body: FilamentPatch) = filamentsById.getValue(id)
    override suspend fun spools(allowArchived: Boolean): List<Spool> { check(); return spoolsById.values.toList() }
    override suspend fun spool(id: Long): Spool { check(); spoolGets++; return spoolsById[id] ?: notFound() }
    override suspend fun createSpool(body: SpoolCreate): Spool {
        val spool = Spool(id = 500, filament = filamentsById.getValue(body.filamentId), initialWeight = body.initialWeight)
        spoolsById[spool.id] = spool
        return spool
    }
    override suspend fun patchSpool(id: Long, body: SpoolPatch): Spool {
        val updated = spoolsById.getValue(id).let { it.copy(extra = it.extra + body.extra.orEmpty()) }
        spoolsById[id] = updated
        return updated
    }
    override suspend fun use(id: Long, body: SpoolUse) = spoolsById.getValue(id)
    override suspend fun measure(id: Long, body: SpoolMeasure) = spoolsById.getValue(id)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelQrTest {

    private val api = FakeSpoolmanApi()
    private val qr = MutableSharedFlow<String>()
    private lateinit var vm: ScanViewModel

    private val pla = Filament(id = 3, name = "PLA Matte", vendor = api.vendor, material = "PLA", colorHex = "000000", density = 1.31)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        api.filamentsById[3] = pla
        api.spoolsById[5] = Spool(id = 5, filament = pla, remainingWeight = 640.0)
        api.spoolsById[6] = Spool(id = 6, filament = pla, extra = mapOf("nfc_uid" to ExtraValue.encode("04AABBCCDDEEFF")))
        val repo = SpoolmanRepository(object : SpoolmanApiSource {
            override fun api() = api
            override fun currentBaseUrl() = "https://spoolman.lab.apj.dev/"
        })
        vm = ScanViewModel(
            scanner = TagScanner(BambuTagReader(), BambuTagParser()),
            spoolman = repo,
            links = SpoolLinks { "https://spoolman.lab.apj.dev" },
            qrScanner = object : QrScanner { override val codes = qr },
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun known() = vm.uiState.value as ScanViewModel.UiState.Known

    @Test
    fun `scanned label for a known spool opens it and closes the camera`() = runTest {
        val accepted = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.qrAccepted.collect { accepted += it } }
        vm.openQrScanner()
        qr.emit("https://spoolman.lab.apj.dev/spool/show/5")

        assertNull("camera closes after the first valid code", vm.qrCamera.value)
        assertEquals(5L, known().spool.id)
        assertTrue("no NFC tag recorded yet: offer a sticker", known().offerSticker)
        assertEquals(1, accepted.size)
    }

    @Test
    fun `old WEB+SPOOLMAN labels still open in the in-app scanner`() = runTest {
        vm.openQrScanner()
        qr.emit("WEB+SPOOLMAN:S-6")
        assertEquals(6L, known().spool.id)
        assertFalse("spool already has a sticker", known().offerSticker)
    }

    @Test
    fun `the same label decoded on many frames is looked up once`() = runTest {
        vm.openQrScanner()
        repeat(5) { qr.emit("https://spoolman.lab.apj.dev/spool/show/5") }
        assertEquals(1, api.spoolGets)
    }

    @Test
    fun `frames are ignored while the camera is closed`() = runTest {
        qr.emit("https://spoolman.lab.apj.dev/spool/show/5")
        assertEquals(ScanViewModel.UiState.Ready, vm.uiState.value)
        assertEquals(0, api.spoolGets)
    }

    @Test
    fun `unknown spool id shows not found, and Retry looks again`() = runTest {
        vm.openQrScanner()
        qr.emit("https://spoolman.lab.apj.dev/spool/show/99")
        assertEquals(ScanViewModel.UiState.NotFound(99), vm.uiState.value)

        api.spoolsById[99] = Spool(id = 99, filament = pla)
        vm.openSpool(99)
        assertEquals(99L, known().spool.id)
    }

    @Test
    fun `filament code opens the create form prefilled from that filament`() = runTest {
        vm.openQrScanner()
        qr.emit("WEB+SPOOLMAN:F-3")
        val create = vm.uiState.value as ScanViewModel.UiState.Create
        assertEquals(3L, create.spec.filamentId)
        assertEquals("PLA Matte", create.spec.filamentName)
        assertEquals("Spoolman filament #3", create.prefilledFrom)
    }

    @Test
    fun `a QR that isn't a Spoolman code keeps the camera open with a notice`() = runTest {
        vm.openQrScanner()
        qr.emit("WIFI:S:home;T:WPA;P:hunter2;;")
        assertEquals(ScanViewModel.QrCamera(notice = "WIFI:S:home;T:WPA;P:hunter2;;"), vm.qrCamera.value)
        assertEquals(ScanViewModel.UiState.Ready, vm.uiState.value)
        assertEquals(0, api.spoolGets)

        qr.emit("https://spoolman.lab.apj.dev/spool/show/5") // a real label after that still works
        assertEquals(5L, known().spool.id)
    }

    @Test
    fun `Spoolman unreachable shows its error`() = runTest {
        api.unreachable = true
        vm.openQrScanner()
        qr.emit("https://spoolman.lab.apj.dev/spool/show/5")
        val error = vm.uiState.value as ScanViewModel.UiState.Error
        assertTrue(error.message, error.message.contains("connect to Spoolman"))
    }

    @Test
    fun `ACTION_VIEW intents are routed to the same lookup`() = runTest {
        val link = LinkIntents.linkFrom(Intent.ACTION_VIEW, "https://spoolman.apj.dev/spool/show/5")
        assertNotNull(link)
        assertNull(LinkIntents.linkFrom(Intent.ACTION_MAIN, "https://spoolman.apj.dev/spool/show/5"))
        assertNull(LinkIntents.linkFrom(Intent.ACTION_VIEW, null))

        vm.onLink(link!!)
        assertEquals(5L, known().spool.id)
    }

    @Test
    fun `a link that isn't a spool page shows the raw text`() = runTest {
        vm.onLink("https://spoolman.lab.apj.dev/spool/show/abc")
        assertEquals(ScanViewModel.UiState.NotSpoolmanCode("https://spoolman.lab.apj.dev/spool/show/abc"), vm.uiState.value)
        assertEquals(0, api.spoolGets)
    }

    @Test
    fun `closing the scanner by hand leaves the scan screen alone`() = runTest {
        vm.openQrScanner()
        vm.closeQrScanner()
        assertNull(vm.qrCamera.value)
        assertEquals(ScanViewModel.UiState.Ready, vm.uiState.value)
    }
}
