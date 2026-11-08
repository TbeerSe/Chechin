package com.example.checkin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = Store.shared(applicationContext)
        if (Reminder.isEnabled(this)) Reminder.scheduleAll(this)
        // A lock set by the background timeout stays set (unlockedThisProcess is cleared when it fires).
        Lock.locked = Lock.hasPin(this) && !Lock.unlockedThisProcess
        Lock.applySecure(this)
        setContent {
            CheckinAppTheme {
                if (Lock.locked) LockScreen() else AppRoot(store)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Rotation / theme / font changes re-create the activity but are not "leaving the app".
        if (!isChangingConfigurations) Lock.noteStop(this)
    }

    override fun onStart() {
        super.onStart()
        Nav.resumeTick++
        Lock.checkTimeout(this)
    }

    override fun onResume() {
        super.onResume()
        Lock.expectExternal = false
    }
}
