package dev.cgm.app.meter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import dev.cgm.core.GlucoseMeasurement
import dev.cgm.core.GlucoseServiceUuids
import dev.cgm.core.MeterRecord
import dev.cgm.core.MeterTime
import dev.cgm.core.RacpResponse
import dev.cgm.core.RecordAccess
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * One conversation with a finger-prick meter.
 *
 * A session rather than a service: connect, take what the meter has, disconnect. A
 * meter is awake for a few seconds after a test and asleep the rest of the day, so
 * there is nothing to hold a connection open for — and holding one would be the app
 * keeping the radio busy for a device that has nothing to say.
 *
 * **Serialised, one operation at a time.** Android's GATT stack silently drops a second
 * write issued before the first has completed, which is the single most common way this
 * kind of code fails: it works in testing, then fails on a phone that is a little busier.
 * Every step here suspends until its own callback arrives, so there is only ever one in
 * flight — and because each step suspends rather than blocks, nothing sits on the binder
 * thread the callbacks are delivered on.
 *
 * **Every step has a deadline.** A meter that stops answering halfway through leaves a
 * coroutine waiting forever otherwise, and the symptom of that is a sync button that
 * never comes back rather than one that reports a failure.
 *
 * Needs `BLUETOOTH_CONNECT` on API 31+; the caller holds it, which is why the
 * permission checks are suppressed rather than repeated at every call.
 */
@SuppressLint("MissingPermission")
class MeterGatt(
    private val context: Context,
    private val device: BluetoothDevice,
    private val zone: ZoneId,
    /**
     * Whether to wait for the meter instead of demanding it be there now.
     *
     * `false` is a person pressing a button: connect, or report that it did not work.
     * `true` hands the wait to the Bluetooth controller, which completes the connection
     * whenever the device next advertises — which is how a result can arrive on its own
     * without this app scanning for anything. It needs no timeout because there is
     * nothing to time out: the wait ends when the meter appears or when the caller is
     * cancelled.
     */
    private val waitForDevice: Boolean = false,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private var gatt: BluetoothGatt? = null

    /** The operation currently in flight, completed by whichever callback belongs to it. */
    private var pending: CompletableDeferred<ByteArray?>? = null

    private var connected: CompletableDeferred<Boolean>? = null

    /** Records as they arrive, which is between the request and its response. */
    private val records = mutableListOf<MeterRecord>()

    /** Set when the control point says the burst is over, one way or another. */
    private var finished: CompletableDeferred<RacpResponse>? = null

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connected?.complete(status == BluetoothGatt.GATT_SUCCESS)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected?.complete(false)
                    // A meter that drops mid-pull must not leave the pull waiting for a
                    // response that can no longer come.
                    finished?.complete(RacpResponse.Unrecognised(0))
                    pending?.complete(null)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Kept for API 31 and 32, where the value is read off the characteristic")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            pending?.complete(
                if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = received(characteristic.uuid, value)

        @Deprecated("Kept for API 31 and 32, where the value is read off the characteristic")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            received(characteristic.uuid, characteristic.value ?: return)
        }
    }

    private fun received(uuid: UUID, value: ByteArray) {
        when (uuid) {
            uuid(GlucoseServiceUuids.MEASUREMENT) ->
                GlucoseMeasurement.parse(value, zone)?.let { records += it }

            uuid(GlucoseServiceUuids.RECORD_ACCESS) ->
                finished?.complete(RecordAccess.parse(value))
        }
    }

    /**
     * Connects, asks for what [request] says to ask for, and disconnects.
     *
     * [request] is a function rather than bytes because the decision needs the meter's
     * name, and only the meter can supply it: a request for "everything after 41" means
     * nothing without knowing whose 41 it is. So the name is read first and the request
     * is settled mid-conversation.
     */
    suspend fun pull(request: suspend (meterName: String) -> ByteArray): MeterReading = try {
        session(request)
    } catch (timeout: TimeoutCancellationException) {
        MeterReading.Failed("timed out waiting for the meter")
    } finally {
        close()
    }

    private suspend fun session(request: suspend (meterName: String) -> ByteArray): MeterReading {
        connected = CompletableDeferred()
        val connection =
            device.connectGatt(context, waitForDevice, callback, BluetoothDevice.TRANSPORT_LE)
            ?: return MeterReading.Failed("could not open a connection")
        gatt = connection

        val accepted = if (waitForDevice) {
            connected!!.await()
        } else {
            withTimeout(CONNECT_TIMEOUT_MILLIS) { connected!!.await() }
        }
        if (!accepted) {
            return MeterReading.Failed("the meter did not accept the connection")
        }

        if (!step { connection.discoverServices() }) {
            return MeterReading.Failed("could not read the meter's services")
        }

        val glucose = connection.getService(uuid(GlucoseServiceUuids.SERVICE))
            ?: return MeterReading.NotCompatible(
                connection.services.map { it.uuid.toString() }
            )

        // The manufacturer picks the quirks, so it is read before anything else is asked
        // for. Absent, the standard path is the right guess.
        val manufacturer = read(
            connection,
            GlucoseServiceUuids.DEVICE_INFORMATION,
            GlucoseServiceUuids.MANUFACTURER_NAME,
        )?.decodeToString()?.trim()?.takeIf { it.isNotEmpty() }

        // Roche answers the clock inside the glucose service rather than on the Current
        // Time Service. Tried in that order rather than branched on the name, because a
        // meter that has the characteristic is answering with it whatever it is called.
        val clock = read(
            connection,
            GlucoseServiceUuids.SERVICE,
            GlucoseServiceUuids.DATE_TIME,
        ) ?: read(
            connection,
            GlucoseServiceUuids.CURRENT_TIME_SERVICE,
            GlucoseServiceUuids.CURRENT_TIME,
        )

        val meterNow = clock?.let { MeterTime.parse(it, zone) }
            ?: return MeterReading.Failed("the meter would not say what time it thinks it is")
        val skew = MeterTime.skewMillis(nowMillis(), meterNow)

        val measurement = glucose.getCharacteristic(uuid(GlucoseServiceUuids.MEASUREMENT))
            ?: return MeterReading.Failed("the meter has no measurement characteristic")
        val control = glucose.getCharacteristic(uuid(GlucoseServiceUuids.RECORD_ACCESS))
            ?: return MeterReading.Failed("the meter has no record access control point")

        if (!subscribe(connection, measurement, indicate = false)) {
            return MeterReading.Failed("the meter refused to send measurements")
        }
        // Indications rather than notifications on the control point: they are
        // acknowledged, which is what makes it a request and a response.
        if (!subscribe(connection, control, indicate = true)) {
            return MeterReading.Failed("the meter refused to answer on its control point")
        }

        records.clear()
        finished = CompletableDeferred()

        val name = manufacturer ?: device.name ?: "meter"
        if (!write(connection, control, request(name))) {
            return MeterReading.Failed("the meter would not take the request")
        }

        val outcome = withTimeout(PULL_TIMEOUT_MILLIS) { finished!!.await() }
        val taken = records.toList()

        return when (outcome) {
            is RacpResponse.Completed, is RacpResponse.NoRecords ->
                MeterReading.Records(taken, name, skew)

            is RacpResponse.Failed ->
                MeterReading.Failed("the meter refused the request (code ${outcome.code})")

            // Records already in hand are kept: a burst that was cut short is still a
            // burst, and throwing away what arrived would mean asking for it again.
            else -> if (taken.isEmpty()) {
                MeterReading.Failed("the meter stopped answering")
            } else {
                MeterReading.Records(taken, name, skew)
            }
        }
    }

    private suspend fun read(
        connection: BluetoothGatt,
        service: String,
        characteristic: String,
    ): ByteArray? {
        val target = connection.getService(uuid(service))?.getCharacteristic(uuid(characteristic))
            ?: return null
        pending = CompletableDeferred()
        if (!connection.readCharacteristic(target)) return null
        return withTimeout(STEP_TIMEOUT_MILLIS) { pending!!.await() }
    }

    @Suppress("DEPRECATION")
    private suspend fun write(
        connection: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        pending = CompletableDeferred()
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            connection.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = value
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            connection.writeCharacteristic(characteristic)
        }
        if (!accepted) return false
        return withTimeout(STEP_TIMEOUT_MILLIS) { pending!!.await() } != null
    }

    /**
     * Turns a characteristic on, locally and on the meter.
     *
     * Both halves are needed and they are different things: the local call tells
     * Android to deliver what arrives, and the descriptor write tells the meter to send
     * it. Doing only the first is the classic silent failure — everything succeeds and
     * nothing ever arrives.
     */
    @Suppress("DEPRECATION")
    private suspend fun subscribe(
        connection: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        indicate: Boolean,
    ): Boolean {
        if (!connection.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(uuid(GlucoseServiceUuids.CLIENT_CONFIG))
            ?: return false

        val value = if (indicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        pending = CompletableDeferred()
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            connection.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            connection.writeDescriptor(descriptor)
        }
        if (!accepted) return false
        return withTimeout(STEP_TIMEOUT_MILLIS) { pending!!.await() } != null
    }

    private suspend fun step(action: () -> Boolean): Boolean {
        pending = CompletableDeferred()
        if (!action()) return false
        return withTimeout(STEP_TIMEOUT_MILLIS) { pending!!.await() } != null
    }

    private fun close() {
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
    }

    private fun uuid(value: String): UUID = UUID.fromString(value)

    private companion object {
        /** Long enough for a meter that has just woken, short enough to report a failure. */
        const val CONNECT_TIMEOUT_MILLIS = 20_000L
        const val STEP_TIMEOUT_MILLIS = 10_000L

        /** A first sync can be hundreds of records, each its own notification. */
        const val PULL_TIMEOUT_MILLIS = 60_000L
    }
}

/** What one conversation with a meter produced. */
sealed interface MeterReading {

    /** Records, with the meter's name and how far its clock is from the phone's. */
    data class Records(
        val records: List<MeterRecord>,
        val meterName: String,
        val clockSkewMillis: Long,
    ) : MeterReading

    /**
     * The device answered, and does not speak the standard glucose service.
     *
     * Carries what it does expose, because that list is the whole diagnosis: this is
     * the GATT dump the plan calls for, taken on the owner's own meter, and it is the
     * difference between "this meter cannot work" and "this meter needs a quirk".
     */
    data class NotCompatible(val services: List<String>) : MeterReading

    data class Failed(val reason: String) : MeterReading
}
