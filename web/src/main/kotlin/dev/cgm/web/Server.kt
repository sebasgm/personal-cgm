package dev.cgm.web

import dev.cgm.core.WatchPayload
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/**
 * A relay: the phone pushes readings in, a browser reads them out.
 *
 * It holds one value in memory and forgets it on restart. There is no database,
 * no account, and no credential for the sensor vendor — this process cannot fetch
 * a reading even in principle, only carry one it was handed.
 *
 * That is deliberate. Anything it stored would be a second copy of history that
 * already lives on the phone, in a place with weaker custody than the phone has,
 * and losing it would matter. Holding only the current value means the worst a
 * compromise yields is one number, and the worst a restart costs is the seconds
 * until the next push.
 */
fun main() {
    val secret = System.getenv("CGM_RELAY_SECRET").orEmpty()
    val host = System.getenv("CGM_WEB_HOST") ?: "127.0.0.1"
    val port = System.getenv("CGM_WEB_PORT")?.toIntOrNull() ?: 8080

    if (secret.length < MIN_SECRET_LENGTH) {
        // Refusing to start beats starting open. A relay with a weak secret is a
        // public page showing someone's glucose, and it would not be obvious.
        System.err.println(
            "CGM_RELAY_SECRET must be set and at least $MIN_SECRET_LENGTH characters. " +
                "Generate it in the phone app under Settings."
        )
        return
    }

    println("Personal CGM relay on http://$host:$port")
    if (host != "127.0.0.1" && host != "localhost") {
        println(
            "Serve this behind TLS. Without it the secret and your readings cross " +
                "the network in clear."
        )
    }

    embeddedServer(Netty, port = port, host = host) { relayModule(secret) }.start(wait = true)
}

private const val MIN_SECRET_LENGTH = 24

fun Application.relayModule(secret: String, store: RelayStore = RelayStore()) {

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }

    routing {

        /** The phone, pushing a reading. Authenticated by the shared secret. */
        post("/api/push") {
            if (!RelaySecret.matches(RelaySecret.bearer(call.request.header("Authorization")), secret)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Bad secret"))
                return@post
            }

            val payload = runCatching { call.receive<WatchPayload>() }.getOrNull()
            if (payload == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unreadable payload"))
                return@post
            }

            val accepted = store.accept(payload)
            // A refused push is not an error worth retrying: it means something
            // newer already arrived.
            call.respond(mapOf("accepted" to accepted))
        }

        /**
         * The browser, proving it holds the secret once.
         *
         * Exchanged for a cookie so the secret is typed once rather than living
         * in a URL, where it would end up in history, logs and referrers.
         */
        post("/api/unlock") {
            val body = runCatching { call.receive<UnlockRequest>() }.getOrNull()
            if (!RelaySecret.matches(body?.secret, secret)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Bad secret"))
                return@post
            }
            call.response.headers.append(
                "Set-Cookie",
                "$VIEWER_COOKIE=${body!!.secret}; HttpOnly; SameSite=Strict; Path=/",
            )
            call.respond(mapOf("ok" to true))
        }

        get("/api/dashboard") {
            if (!RelaySecret.matches(call.request.cookies[VIEWER_COOKIE], secret)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Locked", needsUser = true))
                return@get
            }

            val payload = store.current()
            if (payload == null) {
                // Nothing to show is its own answer, not an error. The phone may
                // be off, offline, or simply not pushing.
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("No recent reading"))
                return@get
            }

            call.respond(buildDashboard(payload, System.currentTimeMillis()))
        }

        staticResources("/", "static") { default("index.html") }
    }
}

const val VIEWER_COOKIE = "cgm_viewer"

@kotlinx.serialization.Serializable
data class UnlockRequest(val secret: String)

