package com.vortexine.player.interfaz

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: VistaModelo by viewModels()

    private val permisos = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.recargar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Para ver tu música: Android 13+ pide «audio»; antes, «almacenamiento» (y hasta Android 9, escribir).
        permisos.launch(when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        })
        setContent { TemaVortex { App(vm) } }
        com.vortexine.player.segundo.Trabajo.programar(this)
        com.vortexine.player.segundo.Trabajo.ahora(this)
    }

    override fun onResume() {
        super.onResume()
        visible = true
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    companion object {
        /** Para saber si se puede abrir la ventana de «¿Actualizar?» o hay que avisar con una notificación. */
        @Volatile var visible = false
    }
}
