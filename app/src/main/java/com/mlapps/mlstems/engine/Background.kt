package com.mlapps.mlstems.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mlapps.mlstems.MainActivity

/** Tiene la CPU sveglia a schermo spento mentre un servizio lavora (rilasciare sempre con release()). */
fun acquireWakeLock(context: Context, tag: String): PowerManager.WakeLock =
    context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MLStems:$tag")
        .apply { acquire(3 * 60 * 60 * 1000L) }   // massimo 3 ore, per sicurezza

/** Notifica finale (completato / errore) che riapre l'app al tocco. */
fun notifyResult(context: Context, id: Int, text: String) {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("result", "Risultati", NotificationManager.IMPORTANCE_DEFAULT))
    val open = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    nm.notify(
        id,
        NotificationCompat.Builder(context, "result")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("MLStems")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build(),
    )
}
