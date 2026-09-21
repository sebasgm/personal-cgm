package dev.cgm.web

import dev.cgm.core.GlucoseSourceException
import dev.cgm.llu.LibreLinkUpCredentials
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/**
 * A local server for reading your own glucose in a browser.
 *
 * It exists for one reason: **Abbott's API cannot be called from a page.** Its
 * preflight responses carry no `Access-Control-Allow-Origin`, so any browser
 * request is blocked before it is sent. Something server-side has to make the
 * call, and this is the smallest thing that can.
 *
 * What it deliberately is not: a backend. It stores nothing, has no database, no
 * accounts of its own, and forgets everything when it stops. The project decided
 * against building an identity service (docs/02-roadmap.md §4) and this does not
 * quietly become one.
 *
 * It binds to loopback by default. This serves health data with no authentication
 * of its own beyond the Abbott login, so putting it on a network has to be a
 * decision someone makes on purpose rather than a default they inherit.
 */
fun main() {
    val host = System.getenv("CGM_WEB_HOST") ?: "127.0.0.1"
    val port = System.getenv("CGM_WEB_PORT")?.toIntOrNull() ?: 8080

    if (host != "127.0.0.1" && host != "localhost") {
        println(
            "WARNING: binding to $host exposes your glucose data to anything that " +
                "can reach this machine. There is no authentication here beyond the " +
                "LibreLinkUp login, and no TLS. Put it behind something that has both."
        )
    }

    println("Personal CGM web on http://$host:$port")
    embeddedServer(Netty, port = port, host = host, module = Application::cgmModule)
        .start(wait = true)
}

fun Application.cgmModule() {
    val sessions = SessionRegistry()

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }

    install(StatusPages) {
        exception<GlucoseSourceException> { call, cause ->
            // The same distinctions the phone makes: what the user must fix versus
            // what will fix itself.
            val (status, needsUser) = when (cause) {
                is GlucoseSourceException.AuthFailed,
                is GlucoseSourceException.AccountActionRequired ->
                    HttpStatusCode.Unauthorized to true
                is GlucoseSourceException.RateLimited -> HttpStatusCode.TooManyRequests to false
                is GlucoseSourceException.NoData -> HttpStatusCode.ServiceUnavailable to false
                else -> HttpStatusCode.BadGateway to false
            }
            call.respond(
                status,
                ErrorResponse(cause.message ?: "Could not reach LibreLinkUp", needsUser),
            )
        }
    }

    routing {
        post("/api/login") {
            val body = call.receive<LoginRequest>()
            if (body.email.isBlank() || body.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Email and password required"))
                return@post
            }

            val token = sessions.create(
                LibreLinkUpCredentials(body.email.trim(), body.password)
            )

            // HttpOnly so no script on the page can read it, SameSite=Strict so it
            // is never sent from another site. Not Secure, because this is served
            // over plain HTTP on loopback and a Secure cookie would simply be
            // dropped; that is also why it should stay on loopback.
            call.response.headers.append(
                "Set-Cookie",
                "${SessionRegistry.COOKIE}=$token; HttpOnly; SameSite=Strict; Path=/",
            )
            call.respond(mapOf("ok" to true))
        }

        post("/api/logout") {
            sessions.remove(call.sessionToken())
            call.response.headers.append(
                "Set-Cookie",
                "${SessionRegistry.COOKIE}=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0",
            )
            call.respond(mapOf("ok" to true))
        }

        get("/api/dashboard") {
            val session = sessions.get(call.sessionToken())
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not signed in", true))
                return@get
            }
            val result = session.source.fetch()
            call.respond(buildDashboard(result, System.currentTimeMillis()))
        }

        staticResources("/", "static") { default("index.html") }
    }
}

private fun io.ktor.server.application.ApplicationCall.sessionToken(): String? =
    request.cookies[SessionRegistry.COOKIE]
