package com.sshtab.pad

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import com.sshtab.pad.crypto.CryptoBootstrap
import com.sshtab.pad.service.SshSessionService
import com.sshtab.pad.ui.SshPadAppUi
import com.sshtab.pad.ui.theme.SshPadTheme

class MainActivity : ComponentActivity() {
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CryptoBootstrap.install()
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    17,
                )
            }
        }
        setContent {
            SshPadTheme {
                SshPadAppUi()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            bound = bindService(
                Intent(this, SshSessionService::class.java),
                connection,
                BIND_AUTO_CREATE,
            )
        } catch (_: Exception) {
            bound = false
        }
    }

    override fun onStop() {
        if (bound) {
            try {
                unbindService(connection)
            } catch (_: Exception) {
            }
            bound = false
        }
        super.onStop()
    }
}
