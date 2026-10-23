package com.gerardoqm.blescoreboard

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.gerardoqm.blescoreboard.ui.AppRoot

/** The only screen host. AppCompat lets the language picked in the app apply to every text (Android 12 and older included). */
class MainActivity : AppCompatActivity() {
    private val manager get() = (application as ScoreboardApp).manager

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        manager.onPermissionsResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        manager.applyStoredLanguage()
        setContent { AppRoot(manager) }
        requestPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        manager.refreshClock()
        manager.onPermissionsResult()
    }

    private fun requestPermissionsIfNeeded() {
        val wanted = manager.requiredPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (!manager.hasBluetoothPermissions() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionRequest.launch(wanted.toTypedArray())
        }
    }

    /** Called from the scanner screen when the user taps "Allow Bluetooth". */
    fun askForBluetooth() = permissionRequest.launch(manager.requiredPermissions())
}
