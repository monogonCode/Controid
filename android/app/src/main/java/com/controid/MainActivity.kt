package com.controid

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.controid.service.EcosystemForegroundService
import com.controid.service.EcosystemStateHub
import com.controid.ui.ControidApp

class MainActivity : ComponentActivity() {

    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasConnectedDevicePermissions()) {
            EcosystemStateHub.setError(null)
            startAndBindEcosystemService(refreshPermissions = true)
        } else {
            EcosystemStateHub.setError(
                "Bluetooth permissions are required for BLE GATT, HID, and foreground service"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val missing = missingRuntimePermissions()
        if (hasConnectedDevicePermissions()) {
            startAndBindEcosystemService(refreshPermissions = false)
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }

        setContent {
            ControidApp()
        }
    }

    override fun onDestroy() {
        if (serviceBound) {
            runCatching { unbindService(serviceConnection) }
            serviceBound = false
        }
        super.onDestroy()
    }

    private fun startAndBindEcosystemService(refreshPermissions: Boolean) {
        val intent = Intent(this, EcosystemForegroundService::class.java).apply {
            if (refreshPermissions) {
                action = EcosystemForegroundService.ACTION_REFRESH_PERMISSIONS
            }
        }
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure { err ->
            EcosystemStateHub.setError("Failed starting foreground service: ${err.message}")
            return
        }
        if (!serviceBound) {
            runCatching {
                bindService(
                    Intent(this, EcosystemForegroundService::class.java),
                    serviceConnection,
                    Context.BIND_AUTO_CREATE
                )
            }
        }
    }

    private fun missingRuntimePermissions(): List<String> {
        val required = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        return required.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasConnectedDevicePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val connectGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        val advertiseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED
        return connectGranted && advertiseGranted
    }
}
