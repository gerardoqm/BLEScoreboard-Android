package com.gerardoqm.blescoreboard.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gerardoqm.blescoreboard.core.BoardProtocol
import java.util.UUID

/**
 * One GATT connection to a scoreboard.
 *
 * Android runs one GATT operation at a time and drops any started while another is in flight, so every write goes through
 * a queue that starts the next operation when the previous one's callback arrives. Callbacks come on a Binder thread and are
 * moved to the main thread, where the queue and the listener live.
 *
 * Setup order: connect, request the 185-byte MTU the board asks for (Android starts at 23, which cuts writes at 20 bytes),
 * discover services, turn on notifications for OTA, CONFIG and STATE, then [Listener.onReady].
 */
@SuppressLint("MissingPermission")   // the caller checks BLUETOOTH_CONNECT before creating a connection
class BoardConnection(
    private val context: Context,
    val device: BluetoothDevice,
    private val listener: Listener,
) : BluetoothGattCallback() {

    interface Listener {
        fun onReady(connection: BoardConnection)
        fun onDisconnected(connection: BoardConnection)
        fun onNotification(connection: BoardConnection, characteristic: UUID, value: ByteArray)
        /** A queued write finished (also for writes without response); used to pace the firmware transfer. */
        fun onWriteComplete(connection: BoardConnection, characteristic: UUID)
    }

    private sealed class Op {
        class Write(val uuid: UUID, var data: ByteArray, val noResponse: Boolean, val coalesceKey: String?) : Op()
        class EnableNotifications(val uuid: UUID) : Op()
        data object RequestMtu : Op()
        data object DiscoverServices : Op()
        data object Ready : Op()
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private val queue = ArrayDeque<Op>()
    private var inFlight: Op? = null
    private var closed = false
    private val characteristics = HashMap<UUID, BluetoothGattCharacteristic>()

    /** Negotiated MTU; the largest write is MTU - 3. */
    var mtu = 23
        private set

    val hasOta get() = characteristics.containsKey(BoardProtocol.OTA)
    val hasConfig get() = characteristics.containsKey(BoardProtocol.CONFIG)
    val hasState get() = characteristics.containsKey(BoardProtocol.STATE)

    /** Writes without response still waiting, so the firmware transfer does not flood the queue. */
    val pendingUnacknowledgedWrites: Int
        get() = queue.count { it is Op.Write && it.noResponse } + if ((inFlight as? Op.Write)?.noResponse == true) 1 else 0

    private val timeout = Runnable {
        Log.w(TAG, "GATT operation timed out: $inFlight")
        completeCurrent()
    }

    fun connect() {
        gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
    }

    /** Ends the link. [Listener.onDisconnected] follows. */
    fun disconnect() {
        val g = gatt ?: return finishClose()
        g.disconnect()
        // Some phones never call back after disconnect(); close anyway.
        main.postDelayed({ if (!closed) finishClose() }, 1500)
    }

    /**
     * Queues a write. With a `coalesceKey`, a write still waiting with the same key is replaced instead of adding another,
     * so quick changes (a stepper held down) never build a backlog of stale match states.
     */
    fun write(uuid: UUID, data: ByteArray, noResponse: Boolean = false, coalesceKey: String? = null) {
        if (closed || !characteristics.containsKey(uuid)) return
        if (coalesceKey != null) {
            val waiting = queue.firstOrNull { it is Op.Write && it.coalesceKey == coalesceKey } as Op.Write?
            if (waiting != null) { waiting.data = data; return }
        }
        enqueue(Op.Write(uuid, data, noResponse, coalesceKey))
    }

    private fun enqueue(op: Op) {
        queue.addLast(op)
        if (inFlight == null) next()
    }

    private fun next() {
        if (closed) return
        val op = queue.removeFirstOrNull() ?: return
        inFlight = op
        val g = gatt ?: return
        val started = when (op) {
            is Op.RequestMtu -> g.requestMtu(BoardProtocol.MTU)
            is Op.DiscoverServices -> g.discoverServices()
            is Op.EnableNotifications -> enableNotifications(g, op.uuid)
            is Op.Write -> startWrite(g, op)
            is Op.Ready -> { listener.onReady(this); main.post { completeCurrent() }; true }
        }
        if (!started) {
            Log.w(TAG, "Could not start $op")
            main.post { completeCurrent() }
        } else {
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, if (op is Op.DiscoverServices) 10_000 else 3_000)
        }
    }

    private fun completeCurrent() {
        main.removeCallbacks(timeout)
        val finished = inFlight
        inFlight = null
        if (finished is Op.Write) listener.onWriteComplete(this, finished.uuid)
        // the listener may already have started the next operation (it queued a write while nothing was in flight)
        if (inFlight == null) next()
    }

    @Suppress("DEPRECATION")
    private fun startWrite(g: BluetoothGatt, op: Op.Write): Boolean {
        val c = characteristics[op.uuid] ?: return false
        val type = if (op.noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, op.data, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            c.writeType = type
            c.value = op.data
            g.writeCharacteristic(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(g: BluetoothGatt, uuid: UUID): Boolean {
        val c = characteristics[uuid] ?: return false
        g.setCharacteristicNotification(c, true)
        val descriptor = c.getDescriptor(BoardProtocol.CCCD) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            descriptor.value = value
            g.writeDescriptor(descriptor)
        }
    }

    private fun finishClose() {
        if (closed) return
        closed = true
        main.removeCallbacks(timeout)
        queue.clear(); inFlight = null
        gatt?.close(); gatt = null
        listener.onDisconnected(this)
    }

    // MARK: - BluetoothGattCallback (Binder thread -> main thread)

    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        main.post {
            if (closed) return@post
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                enqueue(Op.RequestMtu)
                enqueue(Op.DiscoverServices)
            } else {
                finishClose()
            }
        }
    }

    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
        main.post {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            if (inFlight is Op.RequestMtu) completeCurrent()
        }
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
        main.post {
            val service = g.getService(BoardProtocol.SERVICE)
            if (service != null) {
                for (uuid in listOf(BoardProtocol.STATE, BoardProtocol.CONFIG, BoardProtocol.OTA)) {
                    service.getCharacteristic(uuid)?.let { characteristics[uuid] = it }
                }
            }
            // notifications first, then the board is ready for the colors, visibility and match state
            for (uuid in listOf(BoardProtocol.OTA, BoardProtocol.CONFIG, BoardProtocol.STATE)) {
                if (characteristics.containsKey(uuid)) queue.addLast(Op.EnableNotifications(uuid))
            }
            queue.addLast(Op.Ready)
            if (inFlight is Op.DiscoverServices) completeCurrent() else if (inFlight == null) next()
        }
    }

    override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        main.post { if (inFlight is Op.EnableNotifications) completeCurrent() }
    }

    override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        main.post {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "Write to ${characteristic.uuid} failed: $status")
            if (inFlight is Op.Write) completeCurrent()
        }
    }

    override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val copy = value.copyOf()
        main.post { if (!closed) listener.onNotification(this, characteristic.uuid, copy) }
    }

    @Deprecated("Android 12 and older")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return   // the overload above handles it
        val copy = characteristic.value?.copyOf() ?: return
        main.post { if (!closed) listener.onNotification(this, characteristic.uuid, copy) }
    }

    companion object {
        private const val TAG = "BoardConnection"
    }
}
