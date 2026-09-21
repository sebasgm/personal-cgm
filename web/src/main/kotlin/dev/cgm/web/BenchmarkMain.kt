package dev.cgm.web

import dev.cgm.core.ForecastBenchmark
import dev.cgm.core.GlucoseReading
import dev.cgm.core.ZeroOrderHold
import java.io.File
import java.time.Instant

/**
 * Runs the forecast benchmark over a recorded fixture.
 *
 * Reads the JSONL the Python probe writes (`spike/llu_probe.py record`), so a
 * model can be scored against real history without a device in the loop:
 *
 *     ./gradlew :web:benchmark --args="spike/fixtures/readings-....jsonl"
 *
 * The point is not the numbers from any one file. It is that the question "does
 * this model beat assuming the value stays put" has an answer, on this person's
 * data, that can be checked rather than asserted.
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: error("usage: benchmark <readings.jsonl>")
    val readings = readJsonl(File(path))

    if (readings.isEmpty()) {
        println("No readings in $path")
        return
    }

    val spanHours = (readings.last().timestampMillis - readings.first().timestampMillis) / 3_600_000.0
    println("$path")
    println("${readings.size} readings over ${"%.1f".format(spanHours)} hours\n")

    val result = ForecastBenchmark.run(readings)

    println("%-10s %8s %7s %7s %8s %8s".format("model", "horizon", "n", "RMSE", "zone A", "unsafe"))
    println("-".repeat(54))

    result.horizonsMillis.forEach { horizon ->
        result.models.forEach { model ->
            val score = model.at(horizon) ?: return@forEach
            println(
                "%-10s %6dm %7d %7.1f %7.0f%% %7.1f%%".format(
                    model.modelId,
                    horizon / 60_000,
                    score.predictionCount,
                    score.rmse,
                    score.accurateFraction * 100,
                    score.unsafeFraction * 100,
                )
            )
        }
        println()
    }

    println("Improvement over '${ZeroOrderHold.id}' (positive means better):")
    result.horizonsMillis.forEach { horizon ->
        result.models.filter { it.modelId != ZeroOrderHold.id }.forEach { model ->
            val delta = result.improvementOverBaseline(model.modelId, horizon)
            val n = model.at(horizon)?.predictionCount ?: 0
            if (delta != null && n > 0) {
                println("  %6dm  %-10s %+6.2f mg/dL  (n=%d)".format(horizon / 60_000, model.modelId, delta, n))
            }
        }
    }
}

/** The probe's line format: one JSON object per reading. */
private fun readJsonl(file: File): List<GlucoseReading> =
    file.readLines().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val value = field(line, "value_mgdl")?.toDoubleOrNull() ?: return@mapNotNull null
        val timestamp = field(line, "timestamp_utc") ?: return@mapNotNull null
        runCatching {
            GlucoseReading(
                valueMgdl = value,
                timestampMillis = Instant.parse(timestamp.replace("+00:00", "Z")).toEpochMilli(),
            )
        }.getOrNull()
    }.sortedBy { it.timestampMillis }

/** Small enough that a JSON parser would be more ceremony than the job needs. */
private fun field(line: String, name: String): String? {
    val key = "\"$name\":"
    val start = line.indexOf(key).takeIf { it >= 0 }?.plus(key.length) ?: return null
    val rest = line.substring(start).trimStart()
    return if (rest.startsWith('"')) rest.drop(1).substringBefore('"')
    else rest.takeWhile { it.isDigit() || it == '.' || it == '-' || it == '+' || it == 'e' || it == 'E' }
}
