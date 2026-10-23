package com.gerardoqm.blescoreboard.core

import java.util.UUID

/**
 * The Bluetooth LE protocol of the scoreboard (firmware repository `scoreboard_BT_ESP32S3`, README "Bluetooth LE Protocol").
 * Keep it in sync with the firmware and with AdvancedBLEManager.swift in the iOS app.
 */
object BoardProtocol {
    val SERVICE: UUID = UUID.fromString("12345678-1234-1234-1234-123456789012")
    /** STATE: phone -> board match state (write), board -> phone telemetry (notify every 2 s). */
    val STATE: UUID = UUID.fromString("87654321-4321-4321-4321-210987654321")
    /** CONFIG: `[cmd, args...]` writes, `[cmd, status]` notifications (status 0 = OK). */
    val CONFIG: UUID = UUID.fromString("87654321-4321-4321-4321-210987654322")
    /** OTA: firmware update over Bluetooth. */
    val OTA: UUID = UUID.fromString("87654321-4321-4321-4321-210987654323")
    /** Client Characteristic Configuration descriptor, written to turn notifications on. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** The board asks for this MTU; Android must request it itself or every write is cut at 20 bytes. */
    const val MTU = 185

    /** Telemetry layout this app expects. Keep in sync with PROTOCOL_VERSION in the sketch. */
    const val EXPECTED_PROTOCOL_VERSION = 5

    const val DEVICE_NAME_MAX_LENGTH = 20

    // CONFIG commands
    const val CMD_SET_NAME: Byte = 0x01      // [0x01] + 1-20 printable ASCII: stages the name
    const val CMD_APPLY: Byte = 0x02         // [0x02]: saves the staged name and reboots
    const val CMD_COLORS: Byte = 0x03        // [0x03] + 9 x RGB565 big-endian, in PanelElement order
    const val CMD_ALERT: Byte = 0x04         // [0x04, type] + 1-32 ASCII (16 before protocol 8)
    const val CMD_VISIBILITY: Byte = 0x05    // [0x05, mask low, mask high]
    const val CMD_ALERT_ICON: Byte = 0x06    // [0x06, first row] + 1-4 rows of 16 RGB565 pixels; [0x06] alone removes it
    const val CMD_NAME_ICON: Byte = 0x07     // [0x07, slot, first row] + rows of 10 RGB565 pixels; [0x07, slot] clears it
    const val CMD_END_TEXT: Byte = 0x08      // [0x08] + 1-32 ASCII: what the board scrolls when the last period runs out

    /** Alert types of CMD_ALERT. */
    const val ALERT_GAME = 1
    const val ALERT_PERIOD = 2
    const val ALERT_GOAL = 3

    /**
     * The STATE write: 8 header bytes, then `home|away|ratio|bottomLabel` as text.
     * The timer and period are drawn from the header; the ratio and label are still sent for older firmware.
     */
    fun statePacket(
        homeScore: Int, awayScore: Int, period: Int, brightness: Int,
        minutes: Int, seconds: Int, running: Boolean, totalPeriods: Int, text: String,
    ): ByteArray {
        val header = byteArrayOf(
            homeScore.u8(), awayScore.u8(), period.u8(), brightness.u8(),
            minutes.u8(), seconds.u8(), (if (running) 1 else 0).toByte(), totalPeriods.u8(),
        )
        return header + text.toByteArray(Charsets.ISO_8859_1)
    }

    fun colorsPacket(colors: List<Rgb>): ByteArray {
        val out = ArrayList<Byte>()
        out += CMD_COLORS
        for (c in colors) { val v = c.rgb565; out += (v shr 8).toByte(); out += (v and 0xFF).toByte() }
        return out.toByteArray()
    }

    fun visibilityPacket(mask: Int) = byteArrayOf(CMD_VISIBILITY, (mask and 0xFF).toByte(), ((mask shr 8) and 0xFF).toByte())

    fun alertPacket(type: Int, text: String) = byteArrayOf(CMD_ALERT, type.toByte()) + text.toByteArray(Charsets.ISO_8859_1)

    fun endTextPacket(text: String) = byteArrayOf(CMD_END_TEXT) + text.toByteArray(Charsets.ISO_8859_1)

    fun renamePacket(name: String) = byteArrayOf(CMD_SET_NAME) + name.toByteArray(Charsets.US_ASCII)

    fun applyPacket() = byteArrayOf(CMD_APPLY)

    /** The 16 x 16 goal icon, four rows per write; `null` removes it. */
    fun alertIconPackets(pixels: IntArray?): List<ByteArray> {
        if (pixels == null || pixels.size != GOAL_ICON_SIZE * GOAL_ICON_SIZE) return listOf(byteArrayOf(CMD_ALERT_ICON))
        return (0 until GOAL_ICON_SIZE step 4).map { firstRow ->
            rowsPacket(byteArrayOf(CMD_ALERT_ICON, firstRow.toByte()), pixels, firstRow, 4, GOAL_ICON_SIZE)
        }
    }

    /** A 10 x 10 team-name picture for `slot` (0-1 home, 2-3 away), five rows per write; `null` clears the slot. */
    fun nameIconPackets(slot: Int, pixels: IntArray?): List<ByteArray> {
        if (pixels == null || pixels.size != NAME_ICON_SIZE * NAME_ICON_SIZE) return listOf(byteArrayOf(CMD_NAME_ICON, slot.toByte()))
        return listOf(0, 5).map { firstRow ->
            rowsPacket(byteArrayOf(CMD_NAME_ICON, slot.toByte(), firstRow.toByte()), pixels, firstRow, 5, NAME_ICON_SIZE)
        }
    }

    private fun rowsPacket(prefix: ByteArray, pixels: IntArray, firstRow: Int, rows: Int, size: Int): ByteArray {
        val out = ByteArray(prefix.size + rows * size * 2)
        prefix.copyInto(out)
        var i = prefix.size
        for (p in pixels.copyOfRange(firstRow * size, (firstRow + rows) * size)) {
            out[i++] = (p shr 8).toByte(); out[i++] = (p and 0xFF).toByte()
        }
        return out
    }

    private fun Int.u8(): Byte = coerceIn(0, 255).toByte()

    const val GOAL_ICON_SIZE = 16
    const val NAME_ICON_SIZE = 10
}

/** What the board supports, from the protocol version in its telemetry. */
data class BoardCapabilities(val protocolVersion: Int) {
    val colors get() = protocolVersion >= 3
    val hide get() = protocolVersion >= 4
    val goalIcon get() = protocolVersion >= 6
    val nameIcons get() = protocolVersion >= 7
    val longAlerts get() = protocolVersion >= 8
    val endText get() = protocolVersion >= 9
    val outdated get() = protocolVersion < BoardProtocol.EXPECTED_PROTOCOL_VERSION
}

/**
 * Telemetry notification on STATE: `[min, sec, running, period, battery%]`, + `[flags, protocolVersion]` from protocol 2,
 * + `[major, minor, patch]` firmware version from protocol 5.
 */
data class Telemetry(
    val minutes: Int, val seconds: Int, val running: Boolean, val period: Int,
    val batteryPercent: Int, val protocolVersion: Int, val firmwareVersion: FirmwareVersion?,
) {
    companion object {
        fun parse(data: ByteArray): Telemetry? {
            if (data.size < 5) return null
            fun u(i: Int) = data[i].toInt() and 0xFF
            return Telemetry(
                minutes = u(0), seconds = u(1), running = u(2) == 1, period = u(3),
                batteryPercent = minOf(u(4), 100),
                protocolVersion = if (data.size >= 7) u(6) else 1,
                firmwareVersion = if (data.size >= 10) FirmwareVersion(u(7), u(8), u(9)) else null,
            )
        }
    }
}

/** The clock the board reports, with when it was received (milliseconds, monotonic). */
data class BoardClock(val minutes: Int, val seconds: Int, val running: Boolean, val period: Int, val receivedAtMs: Long) {
    /** What the board's clock reads now, assuming it kept counting since the report. */
    fun secondsAt(nowMs: Long): Int {
        val reported = minutes * 60 + seconds
        return if (running) maxOf(0, reported - ((nowMs - receivedAtMs) / 1000).toInt()) else reported
    }
}
