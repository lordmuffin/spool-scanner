package com.bambu.nfc.data.labelito

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class LabelitoClientTest {

    private val server = MockWebServer()
    private lateinit var client: LabelitoClient
    private var token = "secret"
    private val fields = mapOf("material" to "PLA Matte", "color" to "Charcoal", "vendor" to "Bambu Lab",
        "spool_id" to "#5", "qr" to "WEB+SPOOLMAN:S-5")

    @Before
    fun setUp() {
        server.start()
        val http = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build()
        client = LabelitoClient(http) { LabelitoClient.Config(server.url("/").toString(), token) }
    }

    @After
    fun tearDown() = server.shutdown()

    private suspend fun failure(block: suspend () -> Unit): Throwable =
        runCatching { block() }.exceptionOrNull() ?: error("expected a failure")

    @Test
    fun `preview posts the spool-label template with bearer token and returns the png`() = runTest {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
        assertArrayEquals(png, client.preview(fields))

        val req = server.takeRequest()
        assertEquals("/preview", req.path)
        assertEquals("Bearer secret", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("spool-label", body["template"]!!.jsonPrimitive.content)
        assertEquals(fields, body["fields"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })
    }

    @Test
    fun `print posts to print`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"abc"}"""))
        client.print(fields)
        assertEquals("/print", server.takeRequest().path)
    }

    @Test
    fun `409 is a wrong-roll error carrying labelito's detail`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"Loaded media is 29mm, template needs 62mm continuous"}"""))
        val e = failure { client.print(fields) }
        assertTrue(e is LabelitoException.WrongRoll)
        assertEquals("Loaded media is 29mm, template needs 62mm continuous", (e as LabelitoException.WrongRoll).detail)
        assertTrue(e.message!!.contains("DK-2205"))
    }

    @Test
    fun `401 is a bad token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Invalid or missing credentials"))
        assertTrue(failure { client.preview(fields) } is LabelitoException.Unauthorized)
    }

    @Test
    fun `503 means the printer host is unavailable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("no available server"))
        val e = failure { client.print(fields) }
        assertTrue(e is LabelitoException.HostUnavailable)
        assertTrue(e.message!!.contains("asleep"))
    }

    @Test
    fun `a timeout means the printer host is asleep`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertTrue(failure { client.print(fields) } is LabelitoException.HostUnavailable)
    }

    @Test
    fun `missing token is reported before any request`() = runTest {
        token = ""
        assertTrue(failure { client.print(fields) } is LabelitoException.NotConfigured)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `other errors keep the code and detail`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":{"field":"qr"}}"""))
        val e = failure { client.print(fields) } as LabelitoException.Http
        assertEquals(422, e.code)
        assertEquals("""{"field":"qr"}""", e.detail)
    }
}
