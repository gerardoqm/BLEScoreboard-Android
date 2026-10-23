package com.gerardoqm.blescoreboard.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun statePacketHeaderAndText() {
        val p = BoardProtocol.statePacket(12, 9, 2, 40, 7, 45, true, 4, "LION|BEAR|2/4|P2 07:45")
        assertArrayEquals(byteArrayOf(12, 9, 2, 40, 7, 45, 1, 4), p.copyOfRange(0, 8))
        assertEquals("LION|BEAR|2/4|P2 07:45", String(p.copyOfRange(8, p.size), Charsets.ISO_8859_1))
    }

    @Test fun colorsAreBigEndianRgb565InElementOrder() {
        val p = BoardProtocol.colorsPacket(PanelElement.entries.map { it.defaultColor })
        assertEquals(1 + 9 * 2, p.size)
        assertEquals(BoardProtocol.CMD_COLORS, p[0])
        // timer running = pure green = 0x07E0
        assertEquals(0x07.toByte(), p[1]); assertEquals(0xE0.toByte(), p[2])
    }

    @Test fun visibilityMaskFollowsTimerRunningForStopped() {
        val visible = PanelElement.entries.associateWith { true } + (PanelElement.TimerRunning to false)
        val mask = PanelElement.visibilityMask(visible)
        assertEquals(0, mask and 0b11)
        assertEquals(0x1FC, mask)
        assertArrayEquals(byteArrayOf(0x05, 0xFC.toByte(), 0x01), BoardProtocol.visibilityPacket(mask))
    }

    @Test fun telemetryWithFirmwareVersion() {
        val t = Telemetry.parse(byteArrayOf(7, 45, 1, 2, 120, 0, 9, 1, 3, 0))!!
        assertEquals(7, t.minutes); assertEquals(45, t.seconds); assertTrue(t.running); assertEquals(2, t.period)
        assertEquals(100, t.batteryPercent)
        assertEquals(9, t.protocolVersion)
        assertEquals(FirmwareVersion(1, 3, 0), t.firmwareVersion)
    }

    @Test fun shortTelemetryIsProtocolOne() {
        val t = Telemetry.parse(byteArrayOf(10, 0, 0, 1, 50))!!
        assertEquals(1, t.protocolVersion); assertNull(t.firmwareVersion)
        assertNull(Telemetry.parse(byteArrayOf(1, 2)))
    }

    @Test fun iconPackets() {
        val goal = BoardProtocol.alertIconPackets(IntArray(256) { 0xFFFF })
        assertEquals(4, goal.size)
        assertEquals(2 + 4 * 16 * 2, goal[0].size)
        assertEquals(12.toByte(), goal[3][1])
        assertArrayEquals(byteArrayOf(0x06), BoardProtocol.alertIconPackets(null)[0])
        val name = BoardProtocol.nameIconPackets(3, IntArray(100))
        assertEquals(2, name.size); assertEquals(3 + 5 * 10 * 2, name[0].size); assertEquals(5.toByte(), name[1][2])
        assertArrayEquals(byteArrayOf(0x07, 2), BoardProtocol.nameIconPackets(2, null)[0])
    }

    @Test fun crc32MatchesZlib() {
        assertEquals(0xCBF43926.toInt(), Crc32.checksum("123456789".toByteArray()))
    }

    @Test fun firmwareVersionsCompare() {
        assertTrue(FirmwareVersion(1, 2, 3) < FirmwareVersion(1, 3, 0))
        assertEquals(FirmwareVersion(1, 4, 0), FirmwareVersion.parse("1.4.0"))
        assertNull(FirmwareVersion.parse("1.4"))
    }
}

class TextsTest {
    @Test fun ordinals() {
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "21st"), listOf(1, 2, 3, 4, 11, 21).map { Texts.ordinal(it, "en") })
        assertEquals(listOf("1er", "2do", "3er", "4to", "7mo"), listOf(1, 2, 3, 4, 7).map { Texts.ordinal(it, "es") })
    }

    @Test fun plurals() {
        assertEquals("quarters", Texts.plural("quarter", "en"))
        assertEquals("halves", Texts.plural("half", "en"))
        assertEquals("tiempos", Texts.plural("tiempo", "es"))
        assertEquals("mitades", Texts.plural("mitad", "es"))
    }

    @Test fun panelSafeStripsAccentsAndPipes() {
        assertEquals("Aguilas", Texts.panelSafe("Águilas"))
        assertEquals("AB", Texts.panelSafe("A|B"))
    }

    @Test fun emojiBecomeSlots() {
        assertTrue(Texts.isEmoji("⚽"))
        assertTrue(Texts.isEmoji("🦁"))
        assertTrue(Texts.isEmoji("❤️"))
        assertFalse(Texts.isEmoji("©"))
        assertFalse(Texts.isEmoji("A"))
        assertEquals("\u0001 LIONS", Texts.panelText("🦁 Lions", 0))
        assertEquals("\u0003\u0004BEARS", Texts.panelText("🐻🐾🐻Bears", 2))
    }

    @Test fun alertTextIsCappedAndCollapsed() {
        assertEquals("1ST QUARTER", Texts.alertText("1ST  QUARTER", 32))
        assertEquals(16, Texts.alertText("GOAL BY THE MIGHTY LIONS OF THE NORTH", 16).length)
    }

    @Test fun boardNames() {
        assertTrue(Texts.isValidBoardName("Court 1"))
        assertFalse(Texts.isValidBoardName("Cancha Ñ"))
        assertFalse(Texts.isValidBoardName("x".repeat(21)))
    }
}

class MatchRulesTest {
    @Test fun halfTime() {
        assertTrue(MatchRules.halfTimeEndsPeriod(2, 4))
        assertEquals(300, MatchRules.secondsUntilHalfTime(300, 2, 4, 600))
        assertEquals(100, MatchRules.secondsUntilHalfTime(400, 2, 3, 600))
        assertNull(MatchRules.secondsUntilHalfTime(200, 2, 3, 600))
        assertNull(MatchRules.secondsUntilHalfTime(200, 1, 4, 600))
    }

    @Test fun timeLeft() {
        val chosen = setOf(5, 3, 1)
        assertEquals(5, MatchRules.crossedTimeLeft(chosen, 301, 300, 600))
        assertNull(MatchRules.crossedTimeLeft(chosen, 300, 299, 600))
        assertEquals(1, MatchRules.crossedTimeLeft(chosen, 400, 30, 600))   // a long gap: the shortest one
        assertNull(MatchRules.crossedTimeLeft(setOf(10), 700, 599, 600))     // not shorter than the period
    }
}

class OtaTransferTest {
    private class FakeLink : OtaLink {
        val control = ArrayList<ByteArray>()
        val chunks = ArrayList<ByteArray>()
        override fun sendControl(data: ByteArray) { control += data }
        override fun sendChunk(data: ByteArray) { chunks += data }
        override fun canSendChunk() = true
        override val maxChunkPayload = 180
    }

    @Test fun fullHandshakeAndTransfer() {
        val image = ByteArray(10_000) { it.toByte() }
        val link = FakeLink()
        val ota = OtaTransfer(image, "secret", link) {}
        ota.start(0)
        assertArrayEquals(byteArrayOf(OtaTransfer.OP_BEGIN), link.control.last())

        val nonce = ByteArray(16) { 7 }
        ota.handle(byteArrayOf(OtaTransfer.OP_BEGIN, 0) + nonce, 1)
        val auth = link.control.last()
        assertEquals(OtaTransfer.OP_AUTH, auth[0]); assertEquals(33, auth.size)
        assertArrayEquals(OtaTransfer.hmacSha256("secret".toByteArray(), nonce), auth.copyOfRange(1, 33))

        ota.handle(byteArrayOf(OtaTransfer.OP_AUTH, 0), 2)
        val start = link.control.last()
        assertEquals(OtaTransfer.OP_START, start[0])
        assertArrayEquals(OtaTransfer.le32(image.size), start.copyOfRange(1, 5))

        ota.handle(byteArrayOf(OtaTransfer.OP_START, 0), 3)
        // the window holds the transfer at 8 KB until the board acknowledges
        assertEquals(8192 / 180 + 1, link.chunks.size)
        ota.handle(byteArrayOf(OtaTransfer.OP_ACK) + OtaTransfer.le32(8192), 4)
        assertEquals(image.size, link.chunks.sumOf { it.size - 1 })
        ota.handle(byteArrayOf(OtaTransfer.OP_ACK) + OtaTransfer.le32(image.size), 5)
        assertEquals(OtaState.Finishing, ota.state)
        assertArrayEquals(byteArrayOf(OtaTransfer.OP_END), link.control.last())
        ota.handle(byteArrayOf(OtaTransfer.OP_END, 0), 6)
        assertEquals(OtaState.Done, ota.state)
    }

    @Test fun wrongKeyFails() {
        val ota = OtaTransfer(ByteArray(10), "k", FakeLink()) {}
        ota.start(0)
        ota.handle(byteArrayOf(OtaTransfer.OP_BEGIN, 3), 1)
        assertEquals(OtaFailure.KeyRejected, (ota.state as OtaState.Failed).reason)
    }
}
