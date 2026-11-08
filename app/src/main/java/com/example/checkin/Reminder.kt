package com.example.checkin

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object Reminder {
    private const val CHANNEL = "daily_checkin"
    private const val NOTIF_ID = 1001

    private fun prefs(c: Context) = c.getSharedPreferences("reminder", Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = prefs(c).getBoolean("enabled", false)

    /** All reminder times as minutes-of-day. Default: one reminder at 21:00. */
    fun times(c: Context): List<Int> {
        val raw = prefs(c).getStringSet("times", null)
        val list = raw?.mapNotNull { it.toIntOrNull() }?.sorted() ?: listOf(21 * 60)
        return list.ifEmpty { listOf(21 * 60) }
    }

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("enabled", on).apply()
        if (on) scheduleAll(c) else cancelAll(c)
    }

    fun addTime(c: Context, minutes: Int) {
        val set = times(c).toMutableSet()
        set.add(minutes)
        prefs(c).edit().putStringSet("times", set.map { it.toString() }.toSet()).apply()
        if (isEnabled(c)) scheduleAll(c)
    }

    fun removeTime(c: Context, minutes: Int) {
        val set = times(c).toMutableSet()
        if (set.size <= 1) return // always keep at least one slot
        set.remove(minutes)
        prefs(c).edit().putStringSet("times", set.map { it.toString() }.toSet()).apply()
        cancel(c, minutes)
        if (isEnabled(c)) scheduleAll(c)
    }

    fun canNotify(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED

    private fun alarmIntent(c: Context, minutes: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c, minutes, Intent(c, ReminderReceiver::class.java).putExtra("minutes", minutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Schedules the next single alarm for one time slot; the receiver schedules the following day. */
    fun scheduleSlot(c: Context, minutes: Int) = schedule(c, minutes)

    private fun schedule(c: Context, minutes: Int) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val now = LocalDateTime.now()
        var t = now.toLocalDate().atTime(minutes / 60, minutes % 60)
        if (!t.isAfter(now.plusSeconds(5))) t = t.plusDays(1)
        val millis = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarmIntent(c, minutes))
    }

    fun scheduleAll(c: Context) {
        for (m in times(c)) schedule(c, m)
    }

    private fun cancel(c: Context, minutes: Int) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(alarmIntent(c, minutes))
    }

    fun cancelAll(c: Context) {
        for (m in times(c)) cancel(c, m)
    }

    fun dismiss(c: Context) {
        c.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    fun show(c: Context) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Ежедневное напоминание", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val open = PendingIntent.getActivity(
                c, 2,
                Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Quick reply right in the notification: one tap logs a mood-only entry for now.
            fun quickAction(mood: Int, emoji: String): NotificationCompat.Action {
                val pi = PendingIntent.getBroadcast(
                    c, 10 + mood,
                    Intent(c, QuickLogReceiver::class.java).putExtra("mood", mood),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                return NotificationCompat.Action.Builder(R.drawable.ic_stat_checkin, "$emoji $mood", pi).build()
            }
            val n = NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_checkin)
                .setContentTitle("Как прошёл день?")
                .setContentText("Отметь состояние — это займёт минуту.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(quickAction(3, "😕"))
                .addAction(quickAction(5, "😐"))
                .addAction(quickAction(7, "🙂"))
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (_: Exception) {
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Reminder.isEnabled(context)) return
        // Re-arm only the slot that fired: re-arming all of them could push a neighbouring slot that is
        // already due but not delivered yet (alarms are inexact) to tomorrow.
        val slot = intent.getIntExtra("minutes", -1)
        if (slot >= 0) Reminder.scheduleSlot(context, slot) else Reminder.scheduleAll(context)
        // goAsync + Thread: Store.shared() may read and parse the JSON file, which must not block the main thread (ANR).
        val pending = goAsync()
        val appCtx = context.applicationContext
        Thread {
            try {
                val now = java.time.LocalTime.now()
                val time = hhmm(now.hour, now.minute)
                // Remind only if this part of the day (morning / day / evening / night) has no record yet,
                // so a morning entry does not silence the evening reminder.
                if (!hasEntryInPart(Store.shared(appCtx).entries.toList(), LocalDate.now().toString(), time)) Reminder.show(appCtx)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Writes a mood-only quick entry from a notification action. */
class QuickLogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val mood = intent.getIntExtra("mood", 0)
        // Work off the main thread and finish only after the file is really written,
        // otherwise the process can be killed before the entry reaches the disk.
        Thread {
            try {
                if (mood in 1..10) {
                    val now = java.time.LocalTime.now()
                    val time = hhmm(now.hour, now.minute)
                    val today = LocalDate.now().toString()
                    val store = Store.shared(context)
                    if (!hasEntryInPart(store.entries.toList(), today, time)) {
                        store.save(DayEntry(newEntryId(), today, time, mapOf("mood" to mood.toDouble()), emptyMap(), emptyList(), "", System.currentTimeMillis()))
                        store.awaitWrites()
                    }
                }
            } finally {
                Reminder.dismiss(context)
                pending.finish()
            }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Reminder.isEnabled(context)) Reminder.scheduleAll(context)
    }
}

/** Re-plan alarms when the wall clock or the timezone changes (they shift the local schedule). */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Reminder.isEnabled(context)) Reminder.scheduleAll(context)
    }
}

@Composable
fun ReminderSection() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(Reminder.isEnabled(ctx)) }
    var times by remember { mutableStateOf(Reminder.times(ctx)) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Reminder.setEnabled(ctx, true); enabled = true
        } else {
            Toast.makeText(ctx, "Без разрешения на уведомления напоминание не работает", Toast.LENGTH_LONG).show()
        }
    }

    fun fmt(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Напоминание", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Напоминать", Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    if (on) {
                        if (Reminder.canNotify(ctx)) {
                            Reminder.setEnabled(ctx, true); enabled = true
                        } else {
                            permLauncher.launch("android.permission.POST_NOTIFICATIONS")
                        }
                    } else {
                        Reminder.setEnabled(ctx, false); enabled = false
                    }
                }
            )
        }
        times.forEach { m ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(m), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (times.size > 1) TextButton(onClick = {
                    Reminder.removeTime(ctx, m); times = Reminder.times(ctx)
                }) { Text("Убрать") }
            }
        }
        OutlinedButton(
            onClick = {
                val last = times.lastOrNull() ?: 21 * 60
                TimePickerDialog(ctx, { _, h, min ->
                    Reminder.addTime(ctx, h * 60 + min); times = Reminder.times(ctx)
                }, last / 60, last % 60, true).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("＋ Добавить время") }
        TextButton(onClick = {
            if (Reminder.canNotify(ctx)) {
                Reminder.show(ctx)
            } else {
                Toast.makeText(ctx, "Сначала включи напоминание и разреши уведомления", Toast.LENGTH_LONG).show()
            }
        }) { Text("Проверить уведомление сейчас") }
        Text(
            "Если в этой части дня (утро, день, вечер, ночь) запись уже есть, уведомление не придёт. В уведомлении можно " +
                "сразу отметить настроение в одно касание. Время срабатывания может плавать на несколько минут, а на некоторых телефонах (Xiaomi, Huawei, Samsung и др.) экономия батареи может глушить напоминания: если они не приходят, разреши приложению работу в фоне.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
