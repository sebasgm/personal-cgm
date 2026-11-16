package dev.cgm.app.meter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import dev.cgm.app.data.GlucoseRepository
import dev.cgm.core.BloodTestOutcome
import dev.cgm.core.RecordAccess
import java.time.ZoneId

/**
 * Reading a meter and storing what it said.
 *
 * The half of the job that is not Bluetooth: deciding what to ask for, throwing away
 * what must not be stored, and counting what happened so the screen can report it.
 * Separate from [MeterGatt] because this half can be reasoned about without a radio,
 * and because the radio half should not know what a database is.
 */
class MeterSync(
    private val context: Context,
    private val repository: GlucoseRepository,
) {

    /** The meters Android has already bonded, which is the only list this app offers. */
    @SuppressLint("MissingPermission")
    fun bonded(): List<BondedDevice> {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return emptyList()
        val adapter = manager.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()

        return runCatching {
            adapter.bondedDevices.orEmpty().map { BondedDevice(it.address, it.name ?: it.address) }
        }.getOrDefault(emptyList())
    }

    /**
     * Reads the meter at [address] and stores whatever is new.
     *
     * Control-solution tests are dropped before anything else sees them: they are
     * deliberately off-scale, and one stored among real results would not merely be
     * noise — it would be the worst point in every comparison the sensor is scored by.
     * They are counted rather than silently discarded, because a sync that found four
     * records and stored none should say which four.
     */
    @SuppressLint("MissingPermission")
    suspend fun sync(
        address: String,
        zone: ZoneId,
        scope: MeterScope = MeterScope.NEW,
        waitForDevice: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ): MeterSyncResult {
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: return MeterSyncResult.Failed("this phone has no Bluetooth service")
        val adapter = manager.adapter
            ?: return MeterSyncResult.Failed("this phone has no Bluetooth adapter")
        if (!adapter.isEnabled) return MeterSyncResult.Failed("Bluetooth is switched off")

        val device: BluetoothDevice = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return MeterSyncResult.Failed("that is not a device this phone knows")

        val reading = MeterGatt(context, device, zone, waitForDevice).pull { meterName ->
            when (scope) {
                // One past the highest stored, so the meter sends only what is new.
                // Clamped to the top of a uint16 rather than overflowing it: asking
                // again for the last record costs one duplicate, where a wrapped
                // request would quietly re-download the whole history.
                MeterScope.NEW -> repository.highestMeterSequence(meterName)
                    ?.let { RecordAccess.recordsFrom((it + 1).coerceAtMost(0xFFFF)) }
                    ?: RecordAccess.allRecords

                MeterScope.LAST -> RecordAccess.lastRecord

                // A period asks for everything and keeps what falls inside it. The
                // standard does have a time-range filter, and it is optional — meters
                // commonly answer "operator not supported" — so the fallback would be
                // this anyway, and one path that always works beats two where the
                // second is only reached when the first has already failed.
                MeterScope.DAYS_7, MeterScope.DAYS_30, MeterScope.ALL ->
                    RecordAccess.allRecords
            }
        }

        return when (reading) {
            is MeterReading.NotCompatible -> MeterSyncResult.NotCompatible(reading.services)
            is MeterReading.Failed -> MeterSyncResult.Failed(reading.reason)

            is MeterReading.Records -> {
                var stored = 0
                var duplicates = 0
                var refused = 0
                var controls = 0
                var outside = 0
                val since = scope.sinceMillis(nowMillis)

                reading.records.forEach { record ->
                    if (record.isControlSolution) {
                        controls++
                        return@forEach
                    }
                    val test = record.bloodTest(reading.meterName, reading.clockSkewMillis)
                    // Filtered on the corrected time rather than the meter's own, so a
                    // meter whose clock is two hours out does not lose two hours of the
                    // period that was asked for.
                    if (since != null && test.takenAtMillis < since) {
                        outside++
                        return@forEach
                    }
                    when (repository.saveBloodTest(test)) {
                        BloodTestOutcome.STORED -> stored++
                        BloodTestOutcome.DUPLICATE -> duplicates++
                        BloodTestOutcome.REFUSED -> refused++
                    }
                }

                MeterSyncResult.Synced(
                    meterName = reading.meterName,
                    stored = stored,
                    duplicates = duplicates,
                    controlSolutions = controls,
                    refused = refused,
                    outsidePeriod = outside,
                    clockSkewMillis = reading.clockSkewMillis,
                )
            }
        }
    }
}

/**
 * What the meter screen is showing.
 *
 * [Done] keeps the whole result rather than a rendered sentence, so the screen decides
 * how much of it to say and this layer stays free of resource ids.
 */
sealed interface MeterState {
    data object Idle : MeterState
    data object Reading : MeterState
    data class Done(val result: MeterSyncResult) : MeterState
}

/**
 * How much of a meter's memory to ask for.
 *
 * [NEW] is what the automatic read uses and what anyone pressing a button wants nine
 * times out of ten. The rest exist because the automatic one cannot be checked: when a
 * result is missing, the question is whether the meter has it at all, and the only way
 * to find out is to be able to ask for something other than "what is new".
 */
enum class MeterScope(val labelRes: Int) {
    /** Everything above the highest record already stored. */
    NEW(dev.cgm.app.R.string.meter_scope_new),

    /** The newest record only, whatever is already stored. */
    LAST(dev.cgm.app.R.string.meter_scope_last),

    DAYS_7(dev.cgm.app.R.string.meter_scope_7),
    DAYS_30(dev.cgm.app.R.string.meter_scope_30),

    /** The meter's whole memory. Hundreds of records, and the dedupe earns its keep. */
    ALL(dev.cgm.app.R.string.meter_scope_all);

    /** The oldest instant this scope keeps, or null when it keeps everything. */
    fun sinceMillis(nowMillis: Long): Long? = when (this) {
        DAYS_7 -> nowMillis - 7 * DAY_MILLIS
        DAYS_30 -> nowMillis - 30 * DAY_MILLIS
        NEW, LAST, ALL -> null
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

/** A meter Android has bonded, as the screen lists it. */
data class BondedDevice(val address: String, val name: String)

/** What a sync did, in enough detail to be reported rather than summarised. */
sealed interface MeterSyncResult {

    data class Synced(
        val meterName: String,
        val stored: Int,
        val duplicates: Int,
        val controlSolutions: Int,
        val refused: Int,
        /** Records the meter sent that fell outside the period asked for. */
        val outsidePeriod: Int,
        val clockSkewMillis: Long,
    ) : MeterSyncResult

    /**
     * The device is not a standard glucose meter, and here is what it is instead.
     *
     * The service list is the diagnosis rather than decoration: it is the GATT dump the
     * plan calls for, taken on the owner's own meter, and it separates "this meter
     * cannot work" from "this meter needs a quirk writing".
     */
    data class NotCompatible(val services: List<String>) : MeterSyncResult

    data class Failed(val reason: String) : MeterSyncResult
}
