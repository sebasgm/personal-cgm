package dev.cgm.llu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for Abbott's LibreLinkUp API.
 *
 * Unofficial and reverse-engineered: field names use Abbott's own casing, which
 * is inconsistent (`patientId` but `ValueInMgPerDl`). Everything is optional
 * where we can tolerate absence, because the API adds and removes fields without
 * notice and a strict parse would take the app down.
 */

@Serializable
internal data class Envelope<T>(
    val status: Int = 0,
    val data: T? = null,
    val error: ErrorBody? = null,
)

@Serializable
internal data class ErrorBody(val message: String? = null)

@Serializable
internal data class LoginData(
    val user: User? = null,
    val authTicket: AuthTicket? = null,
    // Present instead of the above when we hit the wrong regional endpoint.
    val redirect: Boolean = false,
    val region: String? = null,
    // Present when the account must do something in the app first.
    val step: Step? = null,
)

@Serializable
internal data class Step(val type: String? = null)

@Serializable
internal data class User(val id: String)

@Serializable
internal data class AuthTicket(
    val token: String,
    /** Epoch seconds. Abbott issues roughly six months. */
    val expires: Long = 0,
)

@Serializable
internal data class Connection(
    val patientId: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val targetLow: Double? = null,
    val targetHigh: Double? = null,
    val glucoseMeasurement: Measurement? = null,
    val sensor: Sensor? = null,
)

@Serializable
internal data class Sensor(
    val sn: String? = null,
    /** Activation time, epoch seconds. */
    val a: Long? = null,
)

@Serializable
internal data class GraphData(
    val connection: Connection? = null,
    val graphData: List<Measurement> = emptyList(),
)

@Serializable
internal data class Measurement(
    /** UTC, US format: "9/18/2026 9:23:45 PM". The one to trust. */
    @SerialName("FactoryTimestamp") val factoryTimestamp: String,
    /** Same instant in the sensor's local time. Deliberately unused. */
    @SerialName("Timestamp") val timestamp: String? = null,
    @SerialName("ValueInMgPerDl") val valueInMgPerDl: Double,
    /** 1..5. Absent on historical graph points. */
    @SerialName("TrendArrow") val trendArrow: Int? = null,
    /** 1 = mg/dL, 0 = mmol/L. The follower account's display preference. */
    @SerialName("GlucoseUnits") val glucoseUnits: Int = 1,
    @SerialName("isHigh") val isHigh: Boolean = false,
    @SerialName("isLow") val isLow: Boolean = false,
)
