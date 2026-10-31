package com.vortexine.player

import android.app.Application
import com.vortexine.player.motor.Biblioteca
import com.vortexine.player.motor.DescargadorNewPipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class VortexApp : Application() {
    /** Para lo que debe seguir aunque cierres la pantalla (las descargas). */
    val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var biblioteca: Biblioteca

    override fun onCreate() {
        super.onCreate()
        NewPipe.init(DescargadorNewPipe(), Localization("es", "MX"), ContentCountry("MX"))
        biblioteca = Biblioteca(this)
        com.vortexine.player.reproductor.Ecualizador.iniciar(this)
        com.vortexine.player.motor.Cuenta.cargar(this)
        com.vortexine.player.motor.Sincro.cargar(this)
    }
}
