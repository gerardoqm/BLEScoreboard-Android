package com.gerardoqm.blescoreboard.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A semantic version, `major.minor.patch`. */
data class FirmwareVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<FirmwareVersion> {
    override fun compareTo(other: FirmwareVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"

    companion object {
        fun parse(text: String): FirmwareVersion? {
            val parts = text.split('.').mapNotNull { it.trim().toIntOrNull() }
            return if (parts.size == 3) FirmwareVersion(parts[0], parts[1], parts[2]) else null
        }
    }
}

/** Standard CRC-32 (the one zlib uses). The board computes the same value while it receives the image. */
object Crc32 {
    private val table = IntArray(256) { index ->
        var c = index
        repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
        c
    }

    fun checksum(data: ByteArray): Int {
        var crc = -1
        for (b in data) crc = table[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        return crc.inv()
    }
}

/** Why a firmware update failed, in words the app translates. */
enum class OtaFailure { ConnectionLost, Cancelled, Stalled, KeyRejected, Locked, TooBig, FlashError, CheckFailed, Busy, Other }

/** Progress of a firmware update. */
sealed class OtaState {
    data object Idle : OtaState()
    data object Authenticating : OtaState()
    data object Preparing : OtaState()
    data class Sending(val fraction: Double) : OtaState()
    data object Finishing : OtaState()
    data object Done : OtaState()
    /** `detail` says where the transfer stopped, e.g. "[48000/1234000 sent, 45056 confirmed]"; `code` is the board's status. */
    data class Failed(val reason: OtaFailure, val code: Int = 0, val detail: String = "") : OtaState()

    val isRunning: Boolean get() = this !is Idle && this !is Done && this !is Failed
}

/** How the transfer reaches the OTA characteristic. */
interface OtaLink {
    /** Write with response (control messages). */
    fun sendControl(data: ByteArray)
    /** Write without response (image chunks). */
    fun sendChunk(data: ByteArray)
    /** Whether the Bluetooth stack can take another chunk now. */
    fun canSendChunk(): Boolean
    /** Largest chunk payload, opcode excluded. */
    val maxChunkPayload: Int
}

/**
 * Sends a firmware image to the board over the OTA characteristic.
 *
 * BEGIN returns a random nonce, AUTH answers it with HMAC-SHA256(update key, nonce), START announces the size and CRC-32,
 * DATA chunks go out without response and the board acknowledges progress every 4 KB (we never run more than `window`
 * bytes ahead), END makes the board verify the CRC, switch to the new image and reboot.
 */
class OtaTransfer(
    private val image: ByteArray,
    private val secret: String,
    private val link: OtaLink,
    private val onState: (OtaState) -> Unit,
) {
    var state: OtaState = OtaState.Idle
        private set(value) { field = value; onState(value) }

    private var offset = 0
    private var acked = 0
    private var endSent = false
    private var lastProgressMs = 0L
    private val window = 8192

    fun start(nowMs: Long) {
        if (state.isRunning) return
        offset = 0; acked = 0; endSent = false
        lastProgressMs = nowMs
        state = OtaState.Authenticating
        link.sendControl(byteArrayOf(OP_BEGIN))
    }

    fun cancel() {
        if (!state.isRunning) return
        link.sendControl(byteArrayOf(OP_ABORT))
        state = OtaState.Failed(OtaFailure.Cancelled)
    }

    /** The Bluetooth link dropped. Losing it while the board installs and restarts is expected. */
    fun linkLost() {
        if (state.isRunning && state != OtaState.Finishing) fail(OtaFailure.ConnectionLost)
    }

    /** Call every few seconds: gives up when the board has been silent for 20 seconds. */
    fun checkStall(nowMs: Long) {
        if (state.isRunning && nowMs - lastProgressMs > 20_000) {
            link.sendControl(byteArrayOf(OP_ABORT))
            fail(OtaFailure.Stalled)
        }
    }

    /** A notification from the OTA characteristic. */
    fun handle(data: ByteArray, nowMs: Long) {
        if (data.isEmpty() || !state.isRunning) return
        lastProgressMs = nowMs
        val op = data[0]
        if (op == OP_ACK && data.size >= 5) {
            acked = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8) or
                ((data[3].toInt() and 0xFF) shl 16) or ((data[4].toInt() and 0xFF) shl 24)
            if (state is OtaState.Sending) state = OtaState.Sending(minOf(1.0, acked.toDouble() / maxOf(image.size, 1)))
            pump()
            return
        }
        if (data.size < 2) return
        val status = data[1].toInt() and 0xFF
        when (op) {
            OP_BEGIN -> {
                if (status != 0 || data.size < 18) return fail(status)
                val nonce = data.copyOfRange(2, 18)
                link.sendControl(byteArrayOf(OP_AUTH) + hmacSha256(secret.toByteArray(Charsets.UTF_8), nonce))
            }
            OP_AUTH -> {
                if (status != 0) return fail(status)
                state = OtaState.Preparing
                link.sendControl(byteArrayOf(OP_START) + le32(image.size) + le32(Crc32.checksum(image)))
            }
            OP_START -> {
                if (status != 0) return fail(status)
                state = OtaState.Sending(0.0)
                pump()
            }
            OP_END -> if (status == 0) state = OtaState.Done else fail(status)
        }
    }

    /** Sends as much of the image as the Bluetooth stack and the board's window allow. Call again whenever a write completes. */
    fun pump() {
        if (state !is OtaState.Sending) return
        val payload = minOf(link.maxChunkPayload, 180)
        if (payload <= 0) return
        while (offset < image.size && link.canSendChunk() && offset - acked < window) {
            val end = minOf(offset + payload, image.size)
            link.sendChunk(byteArrayOf(OP_DATA) + image.copyOfRange(offset, end))
            offset = end
        }
        if (offset >= image.size && acked >= image.size && !endSent) {
            endSent = true
            state = OtaState.Finishing
            link.sendControl(byteArrayOf(OP_END))
        }
    }

    private fun fail(status: Int) {
        val reason = when (status) {
            2 -> OtaFailure.Busy
            3 -> OtaFailure.KeyRejected
            4 -> OtaFailure.TooBig
            5 -> OtaFailure.FlashError
            6 -> OtaFailure.CheckFailed
            7 -> OtaFailure.Locked
            else -> OtaFailure.Other
        }
        fail(reason, status)
    }

    private fun fail(reason: OtaFailure, code: Int = 0) {
        state = OtaState.Failed(reason, code, "[$offset/${image.size} sent, $acked confirmed]")
    }

    companion object {
        const val OP_BEGIN: Byte = 0x01
        const val OP_AUTH: Byte = 0x02
        const val OP_START: Byte = 0x03
        const val OP_DATA: Byte = 0x04
        const val OP_ACK: Byte = 0x05
        const val OP_END: Byte = 0x06
        const val OP_ABORT: Byte = 0x07

        fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(message)
        }

        fun le32(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(), (v shr 16 and 0xFF).toByte(), (v ushr 24).toByte())
    }
}
