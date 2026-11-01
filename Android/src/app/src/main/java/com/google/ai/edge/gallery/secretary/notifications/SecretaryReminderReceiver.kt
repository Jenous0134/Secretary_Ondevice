/* Copyright 2026 Jenous. Licensed under the Apache License, Version 2.0. */
package com.google.ai.edge.gallery.secretary.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.secretary.data.ScheduleItem

class SecretaryReminderReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sec_Ond 일정 알림", NotificationManager.IMPORTANCE_HIGH))
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.icon).setContentTitle(intent.getStringExtra("title") ?: "일정 알림")
      .setContentText(intent.getStringExtra("message") ?: "마감 일정이 다가옵니다.")
      .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
    NotificationManagerCompat.from(context).notify(intent.getIntExtra("notification_id", 0), notification)
  }

  companion object {
    const val CHANNEL_ID = "secretary_deadlines"
    fun schedule(context: Context, item: ScheduleItem) {
      val alarm = context.getSystemService(AlarmManager::class.java)
      item.reminderDays.split(',').mapNotNull { it.trim().toIntOrNull() }.forEach { days ->
        val triggerAt = item.eventAt - days * 86_400_000L
        if (triggerAt <= System.currentTimeMillis()) return@forEach
        val requestCode = (item.id * 31 + days).toInt()
        val intent = Intent(context, SecretaryReminderReceiver::class.java).apply {
          putExtra("title", item.title); putExtra("message", "D-$days · ${item.description.ifBlank { "예정된 일정이 있습니다." }}")
          putExtra("notification_id", requestCode)
        }
        val pending = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
      }
    }
  }
}
