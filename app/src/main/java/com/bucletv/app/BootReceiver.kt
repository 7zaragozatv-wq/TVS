package com.bucletv.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Abre Bucle TV automáticamente cuando la TV termina de arrancar. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autoarranque", true)) return   // desactivado en el menú
        val pending = goAsync()
        // Pequeña espera para que el launcher de la TV termine de cargar y no nos tape
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val launch = Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                app.startActivity(launch)
            } catch (e: Exception) {
                // Si el sistema lo bloquea, no hay nada más que hacer aquí
            } finally {
                pending.finish()
            }
        }, 4000)
    }
}
