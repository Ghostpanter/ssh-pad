package com.sshtab.pad

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import com.sshtab.pad.crypto.CryptoBootstrap
import com.sshtab.pad.log.SessionLog
import com.sshtab.pad.service.SshSessionService
import com.sshtab.pad.ui.SshPadAppUi
import com.sshtab.pad.ui.theme.SshPadTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CryptoBootstrap.install()
        enableEdgeToEdge()
        SessionLog.event("activity onCreate")
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

    override fun onPause() {
        SessionLog.event("activity onPause")
        SshSessionService.ensureRunning(this)
        super.onPause()
    }

    override fun onStop() {
        SessionLog.event("activity onStop")
        SshSessionService.ensureRunning(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        SessionLog.event("activity onResume")
        SshSessionService.ensureRunning(this)
        com.sshtab.pad.service.SessionClient.bind(this)
    }
}
