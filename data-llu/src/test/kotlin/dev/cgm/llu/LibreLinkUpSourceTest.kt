package dev.cgm.llu

import dev.cgm.core.GlucoseSourceException
import dev.cgm.core.GlucoseUnit
import dev.cgm.core.TrendArrow
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibreLinkUpSourceTest {

    private lateinit var server: MockWebServer

    @BeforeEach fun setUp() { server = MockWebServer().also { it.start() } }
    @AfterEach fun tearDown() { server.close() }

    private fun source(store: SessionStore = InMemorySessionStore()) = LibreLinkUpSource(
        credentials = LibreLinkUpCredentials("follower@example.com", "hunter2"),
        sessionStore = store,
        baseUrlOverride = server.url("/"),
        clock = { FIXED_NOW_MILLIS },
    )

    private fun json(code: Int = 200, body: String) =
        MockResponse.Builder().code(code).body(body).build()

    // -- happy path -------------------------------------------------------

    @Test
    fun `reads current value trend range and delta`() = runTest {
        server.enqueue(json(body = LOGIN_OK))
        server.enqueue(json(body = CONNECTIONS_OK))
        server.enqueue(json(body = GRAPH_OK))

        val result = source().fetch()

        assertEquals(112.0, result.snapshot.reading.valueMgdl)
        assertEquals(TrendArrow.RISING, result.snapshot.reading.trend)
        assertEquals(GlucoseUnit.MGDL, result.snapshot.unit)
        assertEquals(70.0, result.snapshot.thresholds.lowMgdl)
        assertEquals(180.0, result.snapshot.thresholds.highMgdl)
        // Not supplied by the API, so the defaults must survive.
        assertEquals(55.0, result.snapshot.thresholds.urgentLowMgdl)
        assertEquals(240.0, result.snapshot.thresholds.veryHighMgdl)
        // 112 now, 105 in the graph point five minutes earlier.
        assertEquals(7.0, result.snapshot.delta!!.valueMgdl)
        assertEquals(5 * 60_000L, result.snapshot.delta!!.spanMillis)
        assertEquals(2, result.history.size)
    }

    @Test
    fun `parses FactoryTimestamp as UTC not local time`() = runTest {
        server.enqueue(json(body = LOGIN_OK))
        server.enqueue(json(body = CONNECTIONS_OK))
        server.enqueue(json(body = GRAPH_OK))

        val reading = source().fetch().snapshot.reading
        // "9/18/2026 9:23:45 PM" UTC
        assertEquals(1_789_766_625_000L, reading.timestampMillis)
    }

    @Test
    fun `sends Abbott's required headers including the Account-Id hash`() = runTest {
        server.enqueue(json(body = LOGIN_OK))
        server.enqueue(json(body = CONNECTIONS_OK))
        server.enqueue(json(body = GRAPH_OK))
        source().fetch()

        server.takeRequest() // login
        val authed = server.takeRequest()
        assertEquals("llu.android", authed.headers["product"])
        assertNotNull(authed.headers["version"])
        assertEquals("Bearer jwt-token", authed.headers["authorization"])
        // sha256("user-abc")
        assertEquals(
            "a4ff9a5e2f0a5b0b1a7bd3e78a9ee0b0a9b6b1f8d4f0c0b6a7de1c2c1e7f9a3d".length,
            authed.headers["account-id"]!!.length,
            "account-id must be a 64-char sha256 hex digest",
        )
    }

    // -- region redirect --------------------------------------------------

    @Test
    fun `follows the region redirect and remembers the new region`() = runTest {
        server.enqueue(json(body = REDIRECT_TO_EU2))
        server.enqueue(json(body = LOGIN_OK))
        server.enqueue(json(body = CONNECTIONS_OK))
        server.enqueue(json(body = GRAPH_OK))

        val store = InMemorySessionStore()
        source(store).fetch()

        assertEquals("eu2", store.load()!!.region)
    }

    // -- session reuse ----------------------------------------------------

    @Test
    fun `reuses a stored session instead of logging in again`() = runTest {
        val store = InMemorySessionStore(
            LibreLinkUpSession(
                region = "eu",
                token = "jwt-token",
                accountIdHash = "deadbeef",
                expiresAtSeconds = FIXED_NOW_MILLIS / 1000 + 5_000_000,
                patientId = "patient-1",
            )
        )
        server.enqueue(json(body = GRAPH_OK))

        source(store).fetch()

        // Exactly one call: straight to the graph, no login, no connections lookup.
        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().target.contains("/graph"))
    }

    @Test
    fun `re-logs in when the stored token is near expiry`() = runTest {
        val store = InMemorySessionStore(
            LibreLinkUpSession(
                region = "eu",
                token = "old",
                accountIdHash = "deadbeef",
                // Inside the safety margin, so it must not be used.
                expiresAtSeconds = FIXED_NOW_MILLIS / 1000 + 60,
                patientId = "patient-1",
            )
        )
        server.enqueue(json(body = LOGIN_OK))
        server.enqueue(json(body = GRAPH_OK))

        source(store).fetch()

        assertEquals("jwt-token", store.load()!!.token)
    }

    @Test
    fun `retries once after a 401 by logging in again`() = runTest {
        val store = InMemorySessionStore(
            LibreLinkUpSession(
                region = "eu",
                token = "revoked",
                accountIdHash = "deadbeef",
                expiresAtSeconds = FIXED_NOW_MILLIS / 1000 + 5_000_000,
                patientId = "patient-1",
            )
        )
        server.enqueue(json(code = 401, body = """{"status":0}"""))
        server.enqueue(json(body = LOGIN_OK))
        server.enqueue(json(body = GRAPH_OK))

        val result = source(store).fetch()

        assertEquals(112.0, result.snapshot.reading.valueMgdl)
    }

    // -- failure mapping --------------------------------------------------

    @Test
    fun `maps bad credentials to AuthFailed`() = runTest {
        server.enqueue(json(body = """{"status":2}"""))
        assertFailsWith<GlucoseSourceException.AuthFailed> { source().fetch() }
    }

    @Test
    fun `maps a pending terms step to AccountActionRequired`() = runTest {
        server.enqueue(json(body = """{"status":0,"data":{"step":{"type":"tou"}}}"""))
        val e = assertFailsWith<GlucoseSourceException.AccountActionRequired> { source().fetch() }
        assertTrue(e.message!!.contains("LibreLinkUp"))
    }

    @Test
    fun `maps 429 to RateLimited and honours Retry-After`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(429).setHeader("retry-after", "120").body("{}").build()
        )
        val e = assertFailsWith<GlucoseSourceException.RateLimited> { source().fetch() }
        assertEquals(120_000L, e.retryAfterMillis)
        assertTrue(e.isTransient)
    }

    @Test
    fun `maps an empty follower list to NoData with an actionable message`() = runTest {
        server.enqueue(json(body = LOGIN_OK))
        server.enqueue(json(body = """{"status":0,"data":[]}"""))
        val e = assertFailsWith<GlucoseSourceException.NoData> { source().fetch() }
        assertTrue(e.message!!.contains("follower"))
    }

    @Test
    fun `maps a missing measurement to NoData rather than crashing`() = runTest {
        server.enqueue(json(body = LOGIN_OK))
        server.enqueue(json(body = CONNECTIONS_OK))
        server.enqueue(json(body = """{"status":0,"data":{"connection":{"patientId":"p"},"graphData":[]}}"""))
        assertFailsWith<GlucoseSourceException.NoData> { source().fetch() }
    }

    @Test
    fun `tolerates unknown fields so an Abbott change does not take the app down`() = runTest {
        server.enqueue(json(body = LOGIN_OK))
        server.enqueue(json(body = CONNECTIONS_OK))
        server.enqueue(json(body = GRAPH_WITH_UNKNOWN_FIELDS))
        val result = source().fetch()
        assertEquals(112.0, result.snapshot.reading.valueMgdl)
    }

    @Test
    fun `reads mmol accounts in the display unit while storing mgdl`() = runTest {
        server.enqueue(json(body = LOGIN_OK))
        server.enqueue(json(body = CONNECTIONS_OK))
        server.enqueue(json(body = GRAPH_MMOL))
        val snapshot = source().fetch().snapshot
        assertEquals(GlucoseUnit.MMOLL, snapshot.unit)
        assertEquals(112.0, snapshot.reading.valueMgdl)
        assertEquals("6.2", snapshot.formattedValue())
    }

    private companion object {
        const val FIXED_NOW_MILLIS = 1_789_766_700_000L

        const val LOGIN_OK = """
            {"status":0,"data":{"user":{"id":"user-abc"},
             "authTicket":{"token":"jwt-token","expires":9999999999}}}
        """

        const val REDIRECT_TO_EU2 = """{"status":0,"data":{"redirect":true,"region":"eu2"}}"""

        const val CONNECTIONS_OK = """
            {"status":0,"data":[{"patientId":"patient-1","firstName":"A","lastName":"B",
             "targetLow":70,"targetHigh":180,"sensor":{"sn":"ABC","a":1789000000}}]}
        """

        const val MEASUREMENT = """
            {"FactoryTimestamp":"9/18/2026 9:23:45 PM","Timestamp":"9/18/2026 11:23:45 PM",
             "ValueInMgPerDl":112,"TrendArrow":4,"GlucoseUnits":1,"Value":112,
             "isHigh":false,"isLow":false}
        """

        const val GRAPH_OK = """
            {"status":0,"data":{"connection":{"patientId":"patient-1",
              "targetLow":70,"targetHigh":180,"glucoseMeasurement":$MEASUREMENT},
             "graphData":[
              {"FactoryTimestamp":"9/18/2026 9:13:45 PM","ValueInMgPerDl":98,"GlucoseUnits":1},
              {"FactoryTimestamp":"9/18/2026 9:18:45 PM","ValueInMgPerDl":105,"GlucoseUnits":1}
             ]}}
        """

        const val GRAPH_WITH_UNKNOWN_FIELDS = """
            {"status":0,"unexpectedTopLevel":true,"data":{"connection":{"patientId":"patient-1",
              "brandNewField":{"nested":1},
              "glucoseMeasurement":{"FactoryTimestamp":"9/18/2026 9:23:45 PM",
                "ValueInMgPerDl":112,"TrendArrow":4,"GlucoseUnits":1,"somethingNew":"x"}},
             "graphData":[]}}
        """

        const val GRAPH_MMOL = """
            {"status":0,"data":{"connection":{"patientId":"patient-1",
              "glucoseMeasurement":{"FactoryTimestamp":"9/18/2026 9:23:45 PM",
                "ValueInMgPerDl":112,"TrendArrow":3,"GlucoseUnits":0,"Value":6.2}},
             "graphData":[]}}
        """
    }
}

class TimestampsTest {
    @Test
    fun `parses the 12-hour US format Abbott sends`() {
        assertEquals(1_789_766_625_000L, Timestamps.parseUtcMillis("9/18/2026 9:23:45 PM"))
    }

    @Test
    fun `also parses the 24-hour variant some regions return`() {
        assertEquals(1_789_766_625_000L, Timestamps.parseUtcMillis("9/18/2026 21:23:45"))
    }

    @Test
    fun `returns null rather than throwing on an unknown shape`() {
        assertEquals(null, Timestamps.parseUtcMillis("2026-09-18T21:23:45Z"))
    }
}
