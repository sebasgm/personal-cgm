package dev.cgm.app.meter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import dev.cgm.app.data.GlucoseRepository
import dev.cgm.core.BloodTestOutcome
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
    suspend fun sync(address: String, zone: ZoneId): MeterSyncResult {
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: return MeterSyncResult.Failed("this phone has no Bluetooth service")
        val adapter = manager.adapter
            ?: return MeterSyncResult.Failed("this phone has no Bluetooth adapter")
        if (!adapter.isEnabled) return MeterSyncResult.Failed("Bluetooth is switched off")

        val device: BluetoothDevice = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return MeterSyncResult.Failed("that is not a device this phone knows")

        val reading = MeterGatt(context, device, zone).pull { meterName ->
            // One past the highest stored, so the meter sends only what is new. Clamped
            // to the top of a uint16 rather than overflowing it: asking again for the
            // last record costs one duplicate, where a wrapped request would quietly
            // re-download the whole history.
            repository.highestMeterSequence(meterName)?.let { (it + 1).coerceAtMost(0xFFFF) }
        }

        return when (reading) {
            is MeterReading.NotCompatible -> MeterSyncResult.NotCompatible(reading.services)
            is MeterReading.Failed -> MeterSyncResult.Failed(reading.reason)

            is MeterReading.Records -> {
                var stored = 0
                var duplicates = 0
                var refused = 0
                var controls = 0

                reading.records.forEach { record ->
                    if (record.isControlSolution) {
                        controls++
                        return@forEach
                    }
                    val test = record.bloodTest(reading.meterName, reading.clockSkewMillis)
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
