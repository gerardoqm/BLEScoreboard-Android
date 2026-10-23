package com.gerardoqm.blescoreboard

import android.content.Context
import com.gerardoqm.blescoreboard.core.FirmwareVersion
import org.json.JSONObject

/**
 * The firmware image that ships inside the app, with its version.
 * It is produced locally by tools/bundle-firmware.sh into app/src/main/assets/firmware/ and is never committed: the image
 * contains the Wi-Fi and update passwords from secrets.h, and firmware.json carries the update key so the app can prove
 * itself to the board without asking anyone for a password.
 */
class BundledFirmware(val version: FirmwareVersion, val secret: String?, val image: ByteArray) {
    companion object {
        fun load(context: Context): BundledFirmware? = runCatching {
            val assets = context.assets
            val image = assets.open("firmware/scoreboard.bin").use { it.readBytes() }
            val info = JSONObject(assets.open("firmware/firmware.json").use { it.readBytes().toString(Charsets.UTF_8) })
            val version = FirmwareVersion.parse(info.getString("version")) ?: return null
            BundledFirmware(version, info.optString("secret").takeIf { it.isNotEmpty() }, image)
        }.getOrNull()
    }
}
