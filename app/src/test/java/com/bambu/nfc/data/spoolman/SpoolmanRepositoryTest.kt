package com.bambu.nfc.data.spoolman

import com.bambu.nfc.domain.NewSpoolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the repository against a fake Spoolman (never the real instance). */
class SpoolmanRepositoryTest {

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<Pair<String, String>>() // "METHOD path" to body
    private lateinit var repo: SpoolmanRepository

    // Mutable fake state
    private val spoolFields = mutableListOf("tag", "bambu_color_name")
    private val filamentFields = mutableListOf<String>()
    private var vendors = """[{"id":1,"name":"Bambu Lab","extra":{}}]"""
    private var filaments = "[]"
    private var spools = "[]"

    private fun fieldsJson(entity: String, keys: List<String>) =
        keys.joinToString(",", "[", "]") { """{"key":"$it","name":"$it","field_type":"text","entity_type":"$entity","order":0}""" }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?')
                val body = request.body.readUtf8()
                requests += "${request.method} $path" to body
                val json = when ("${request.method} $path") {
                    "GET /api/v1/field/spool" -> fieldsJson("spool", spoolFields)
                    "GET /api/v1/field/filament" -> fieldsJson("filament", filamentFields)
                    "POST /api/v1/field/spool/bambu_tray_uuid", "POST /api/v1/field/spool/nfc_uid" -> {
                        spoolFields += path.substringAfterLast('/'); fieldsJson("spool", spoolFields)
                    }
                    "POST /api/v1/field/filament/bambu_material_id" -> {
                        filamentFields += "bambu_material_id"; fieldsJson("filament", filamentFields)
                    }
                    "GET /api/v1/vendor" -> vendors
                    "POST /api/v1/vendor" -> """{"id":2,"name":"Overture"}"""
                    "GET /api/v1/filament" -> filaments
                    "POST /api/v1/filament" -> """{"id":11,"name":"PLA Matte","material":"PLA","density":1.24,"diameter":1.75,"vendor":{"id":1,"name":"Bambu Lab"}}"""
                    "PATCH /api/v1/filament/3" -> """{"id":3,"name":"PLA Matte","material":"PLA","density":1.31,"diameter":1.75}"""
                    "POST /api/v1/spool" -> """{"id":42,"filament":{"id":11,"density":1.24,"diameter":1.75},"initial_weight":1000.0,"remaining_weight":1000.0}"""
                    "GET /api/v1/spool" -> spools
                    "PATCH /api/v1/spool/7" -> """{"id":7,"filament":{"id":3,"density":1.31,"diameter":1.75}}"""
                    "PUT /api/v1/spool/42/measure", "PUT /api/v1/spool/42/use" ->
                        """{"id":42,"filament":{"id":11,"density":1.24,"diameter":1.75},"remaining_weight":800.0}"""
                    else -> return MockResponse().setResponseCode(404).setBody("""{"message":"not found"}""")
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(json)
            }
        }
        server.start()
        val url = server.url("/").toString()
        repo = SpoolmanRepository(SpoolmanApiProvider(OkHttpClient()) { url })
    }

    @After
    fun tearDown() = server.shutdown()

    private fun posts(prefix: String) = requests.filter { it.first.startsWith(prefix) }

    @Test
    fun `ensureExtraFields creates only the missing fields and is idempotent`() = runTest {
        filamentFields += "bambu_material_id" // already present
        val created = repo.ensureExtraFields()
        assertEquals(listOf("spool.bambu_tray_uuid", "spool.nfc_uid"), created)
        val fieldPosts = posts("POST /api/v1/field")
        assertEquals(2, fieldPosts.size)
        val body = Json.parseToJsonElement(fieldPosts.first().second).jsonObject
        assertEquals("text", body["field_type"]!!.jsonPrimitive.content)

        requests.clear()
        assertEquals(emptyList<String>(), repo.ensureExtraFields())
        assertTrue(posts("POST").isEmpty())
    }

    @Test
    fun `createSpool reuses the vendor, creates the filament and JSON-encodes extra values`() = runTest {
        val spec = NewSpoolSpec(
            vendorName = "bambu lab", filamentName = "PLA Matte", material = "PLA", colorHex = "f99963",
            colorName = "Mandarin Orange", netWeightG = 1000.0, extruderTempC = 210, bedTempC = 60,
            bambuMaterialId = "GFA01", bambuTrayUuid = "FE0F8D897AA94CB1B1E3DDC6862CFFE0", nfcUid = "75886b1d",
        )
        val spool = repo.createSpool(spec)
        assertEquals(42L, spool.id)
        assertTrue(posts("POST /api/v1/vendor").isEmpty())

        val filament = Json.parseToJsonElement(posts("POST /api/v1/filament").single().second).jsonObject
        assertEquals(1L, filament["vendor_id"]!!.jsonPrimitive.content.toLong())
        assertEquals("F99963", filament["color_hex"]!!.jsonPrimitive.content)
        assertEquals(210, filament["settings_extruder_temp"]!!.jsonPrimitive.content.toInt())
        assertEquals("\"GFA01\"", filament["extra"]!!.jsonObject["bambu_material_id"]!!.jsonPrimitive.content)

        val body = Json.parseToJsonElement(posts("POST /api/v1/spool").single().second).jsonObject
        assertEquals(11L, body["filament_id"]!!.jsonPrimitive.content.toLong())
        assertEquals(1000.0, body["initial_weight"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertTrue("null fields are omitted", "spool_weight" !in body && "location" !in body)
        val extra = body["extra"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(
            mapOf(
                "bambu_tray_uuid" to "\"FE0F8D897AA94CB1B1E3DDC6862CFFE0\"",
                "tag" to "\"FE0F8D897AA94CB1B1E3DDC6862CFFE0\"", // Bambuddy's field exists here, so it's set too
                "nfc_uid" to "\"75886B1D\"",
                "bambu_color_name" to "\"Mandarin Orange\"",
            ),
            extra
        )
    }

    @Test
    fun `createSpool matches an existing filament and backfills its material id`() = runTest {
        filaments = """[{"id":3,"name":"PLA Matte","material":"PLA","color_hex":"F99963","diameter":1.75,"density":1.31,"vendor":{"id":1,"name":"Bambu Lab"},"extra":{}}]"""
        repo.createSpool(NewSpoolSpec("Bambu Lab", "PLA Matte", "PLA", "F99963", bambuMaterialId = "GFA01"))
        assertTrue(posts("POST /api/v1/filament").isEmpty())
        assertEquals(1, posts("PATCH /api/v1/filament/3").size)
        val body = Json.parseToJsonElement(posts("POST /api/v1/spool").single().second).jsonObject
        assertEquals(3L, body["filament_id"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `createSpool creates a missing vendor`() = runTest {
        repo.createSpool(NewSpoolSpec("Overture", "PLA Pro", "PLA", "FFFFFF"))
        val vendor = Json.parseToJsonElement(posts("POST /api/v1/vendor").single().second).jsonObject
        assertEquals("Overture", vendor["name"]!!.jsonPrimitive.content)
        val filament = Json.parseToJsonElement(posts("POST /api/v1/filament").single().second).jsonObject
        assertEquals(2L, filament["vendor_id"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `measure and use hit the right endpoints`() = runTest {
        assertEquals(800.0, repo.measure(42, 1050.0).remainingWeight!!, 1e-9)
        repo.use(42, 12.5)
        assertEquals("""{"weight":1050.0}""", posts("PUT /api/v1/spool/42/measure").single().second)
        assertEquals("""{"use_weight":12.5}""", posts("PUT /api/v1/spool/42/use").single().second)
    }

    @Test
    fun `a spool linked by uid learns its tray uuid and second tag on the next Bambu scan`() = runTest {
        spools = """[{"id":7,"filament":{"id":3,"density":1.31,"diameter":1.75},"extra":{"nfc_uid":"\"AAAA0001\""}}]"""
        assertEquals(7L, repo.findBambuSpool("FE0F8D897AA94CB1B1E3DDC6862CFFE0", "AAAA0001")?.id)
        val patch = Json.parseToJsonElement(posts("PATCH /api/v1/spool/7").single().second).jsonObject
        assertEquals(
            mapOf("bambu_tray_uuid" to "\"FE0F8D897AA94CB1B1E3DDC6862CFFE0\""),
            patch["extra"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        )
        assertTrue("only extra is patched", patch.keys == setOf("extra"))

        requests.clear()
        spools = """[{"id":7,"filament":{"id":3,"density":1.31,"diameter":1.75},"extra":{"nfc_uid":"\"AAAA0001\"","bambu_tray_uuid":"\"FE0F8D897AA94CB1B1E3DDC6862CFFE0\""}}]"""
        repo.findBambuSpool("FE0F8D897AA94CB1B1E3DDC6862CFFE0", "BBBB0002")
        val second = Json.parseToJsonElement(posts("PATCH /api/v1/spool/7").single().second).jsonObject
        assertEquals("\"AAAA0001,BBBB0002\"", second["extra"]!!.jsonObject["nfc_uid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing spool returns null`() = runTest {
        assertEquals(null, repo.getSpool(999))
    }
}
