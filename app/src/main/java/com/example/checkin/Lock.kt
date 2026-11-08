package com.example.checkin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Base64
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

object Lock {
    var locked by mutableStateOf(false)
    @Volatile var expectExternal = false
    /** True once the user has unlocked in the current process. Survives Activity re-creation
     *  (theme/font-size change), not a real process kill (cold start re-locks). */
    @Volatile var unlockedThisProcess = false

    /** When the activity last went to the background (elapsedRealtime) and how long it may stay there.
     *  Kept here, not in the Activity: a re-created Activity must not forget it (that was a PIN bypass). */
    @Volatile var backgroundedAt = 0L
    @Volatile var graceMs = 0L

    fun markUnlocked() { locked = false; unlockedThisProcess = true; backgroundedAt = 0L }

    fun noteStop(c: Context) {
        backgroundedAt = SystemClock.elapsedRealtime()
        // Leaving for a picker / share sheet gets a longer but bounded grace, so it can't mean "never lock".
        graceMs = if (expectExternal) maxOf(delayMs(c), 5 * 60_000L) else delayMs(c)
        expectExternal = false
    }

    /** Called from onStart: locks if the app stayed in the background longer than allowed. */
    fun checkTimeout(c: Context) {
        val t = backgroundedAt
        backgroundedAt = 0L
        if (t == 0L || !hasPin(c) || locked) return
        if (SystemClock.elapsedRealtime() - t >= graceMs) {
            unlockedThisProcess = false   // otherwise a re-created Activity would drop the lock again
            locked = true
        }
    }

    private fun p(c: Context) = c.getSharedPreferences("lock", Context.MODE_PRIVATE)
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 100_000, 256)).encoded

    fun hasPin(c: Context) = p(c).contains("hash")
    fun pinLength(c: Context) = p(c).getInt("len", 4)

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p(c).edit().putString("salt", b64(salt)).putString("hash", b64(hash(pin, salt)))
            .putInt("len", pin.length).putInt("fails", 0).putLong("until", 0L).putLong("untilWall", 0L).apply()
    }

    fun clearPin(c: Context) {
        // Only PIN-related keys: the delay and the screenshot-block choice belong to the user, not to the PIN.
        p(c).edit().remove("hash").remove("salt").remove("len").remove("fails")
            .remove("until").remove("untilWall").remove("bootId").remove("bio").apply()
    }

    private fun bootId(c: Context): Int = try {
        android.provider.Settings.Global.getInt(c.contentResolver, "boot_count", -1)
    } catch (_: Exception) { -1 }

    /** Lockout deadline. Within one boot it is elapsedRealtime (the wall clock can't shorten it); that clock
     *  restarts at reboot, so after a reboot the wall-clock deadline saved next to it takes over. */
    fun remainingLockSec(c: Context): Int {
        val pr = p(c)
        val until = pr.getLong("until", 0L)
        if (until <= 0L) return 0
        val byElapsed = until - SystemClock.elapsedRealtime()
        val byWall = pr.getLong("untilWall", 0L) - System.currentTimeMillis()
        val stored = pr.getInt("bootId", -1)
        val cur = bootId(c)
        val msLeft = when {
            stored == -1 || cur == -1 -> maxOf(byElapsed, byWall)   // boot counter unreadable: be strict
            stored == cur -> byElapsed
            else -> byWall
        }
        val r = minOf(900L, msLeft / 1000)
        return if (r > 0) (r + 1).toInt() else 0
    }

    fun verify(c: Context, pin: String): Boolean {
        if (remainingLockSec(c) > 0) return false
        val pr = p(c)
        val salt = Base64.decode(pr.getString("salt", "") ?: "", Base64.NO_WRAP)
        val expected = Base64.decode(pr.getString("hash", "") ?: "", Base64.NO_WRAP)
        if (salt.isEmpty() || expected.isEmpty()) return false
        val ok = MessageDigest.isEqual(hash(pin, salt), expected)
        if (ok) {
            pr.edit().putInt("fails", 0).putLong("until", 0L).putLong("untilWall", 0L).apply()
            return true
        }
        val fails = pr.getInt("fails", 0) + 1
        val e = pr.edit().putInt("fails", fails)
        if (fails % 5 == 0) {
            val sec = minOf(900L, 30L shl (fails / 5 - 1).coerceAtMost(5))
            e.putLong("until", SystemClock.elapsedRealtime() + sec * 1000)
                .putLong("untilWall", System.currentTimeMillis() + sec * 1000)
                .putInt("bootId", bootId(c))
        }
        e.apply()
        return false
    }

    fun bioEnabled(c: Context) = p(c).getBoolean("bio", false)
    fun setBio(c: Context, v: Boolean) { p(c).edit().putBoolean("bio", v).apply() }

    fun delaySec(c: Context) = p(c).getInt("delay", 60)
    fun setDelay(c: Context, s: Int) { p(c).edit().putInt("delay", s).apply() }
    fun delayMs(c: Context) = delaySec(c) * 1000L

    fun secure(c: Context) = p(c).getBoolean("secure", true)
    fun setSecure(c: Context, v: Boolean) { p(c).edit().putBoolean("secure", v).apply() }

    fun applySecure(a: Activity) {
        if (secure(a) && hasPin(a)) a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

object Bio {
    fun available(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val bm = c.getSystemService(BiometricManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 30)
            bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        else
            @Suppress("DEPRECATION") (bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)
    }

    fun prompt(activity: Activity, onSuccess: () -> Unit) {
        if (Build.VERSION.SDK_INT < 28) return
        try {
            val executor = activity.mainExecutor
            val builder = BiometricPrompt.Builder(activity)
                .setTitle("Вход в Checkin")
                .setNegativeButton("Ввести PIN", executor) { _, _ -> }
            if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            builder.build().authenticate(CancellationSignal(), executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onSuccess()
                }
            })
        } catch (_: Exception) {
        }
    }
}

/**
 * Fallback for a forgotten PIN: confirm the device credential (system PIN/pattern/password
 * or biometrics), then the app PIN is reset. Losing the diary because of a forgotten PIN
 * should never happen when the device itself is unlocked.
 */
object DeviceCred {
    fun available(c: Context): Boolean {
        val km = c.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager ?: return false
        return km.isDeviceSecure
    }

    /** API 28-29 path: returns an intent for startActivityForResult. Null if unavailable. */
    @Suppress("DEPRECATION")
    fun confirmIntent(c: Context): Intent? {
        if (Build.VERSION.SDK_INT < 28 || Build.VERSION.SDK_INT >= 30) return null
        val km = c.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager ?: return null
        return km.createConfirmDeviceCredentialIntent("Сброс PIN", "Подтверди, что это твоё устройство")
    }

    /** API 30+ path. */
    fun prompt(activity: Activity, onSuccess: () -> Unit) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val executor = activity.mainExecutor
            val builder = BiometricPrompt.Builder(activity)
                .setTitle("Сброс PIN")
                .setSubtitle("Подтверди, что это твоё устройство")
                // Device PIN / pattern / password only: a weak biometric (e.g. camera face unlock)
                // must not be enough to wipe the app's PIN.
                .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            builder.build().authenticate(CancellationSignal(), executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onSuccess()
                }
            })
        } catch (_: Exception) {
        }
    }
}

@Composable
private fun PinKey(label: String, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val desc = if (label == "⌫") "Стереть" else "Цифра $label"
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(72.dp).clip(CircleShape)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .semantics { contentDescription = desc }
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 26.sp) }
    }
}

@Composable
fun PinPad(
    length: Int,
    title: String,
    subtitle: String?,
    error: String?,
    enabled: Boolean = true,
    leftKey: @Composable () -> Unit = {},
    onComplete: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    fun press(d: String) {
        if (!enabled) return
        pin += d
        if (pin.length >= length) {
            val p = pin
            pin = ""
            onComplete(p)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle ?: " ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(length) { i ->
                Box(
                    Modifier.size(16.dp).clip(CircleShape).background(
                        if (i < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    )
                )
            }
        }
        Text(error ?: " ", color = MaterialTheme.colorScheme.error)
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { r.forEach { d -> PinKey(d) { press(d) } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { leftKey() }
            PinKey("0") { press("0") }
            PinKey("⌫") { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
        }
    }
}

@Composable
fun LockScreen() {
    val ctx = LocalContext.current
    val act = ctx.findActivity()
    val len = remember { Lock.pinLength(ctx) }
    val bio = Lock.bioEnabled(ctx) && Bio.available(ctx)
    val deviceCred = remember { DeviceCred.available(ctx) }
    var error by remember { mutableStateOf<String?>(null) }
    var remaining by remember { mutableStateOf(Lock.remainingLockSec(ctx)) }

    fun resetPin() {
        Lock.clearPin(ctx)
        act?.let { Lock.applySecure(it) }
        Lock.markUnlocked()
        Toast.makeText(ctx, "PIN сброшен. Установи новый в настройках ⚙️", Toast.LENGTH_LONG).show()
    }

    // API 28-29: device-credential confirmation goes through an activity result.
    val credLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res -> if (res.resultCode == android.app.Activity.RESULT_OK) resetPin() }

    LaunchedEffect(remaining) {
        if (remaining > 0) {
            delay(1000)
            remaining = Lock.remainingLockSec(ctx)
        }
    }
    LaunchedEffect(Unit) {
        if (bio && remaining == 0) act?.let { a -> Bio.prompt(a) { Lock.markUnlocked() } }
    }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.systemBars),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PinPad(
                length = len,
                title = "🔒 Checkin",
                subtitle = if (remaining > 0) "Слишком много попыток. Подожди $remaining с" else "Введи PIN",
                error = error,
                enabled = remaining == 0,
                leftKey = {
                    if (bio) TextButton(onClick = { act?.let { a -> Bio.prompt(a) { Lock.markUnlocked() } } }) {
                        Text("👆", fontSize = 28.sp)
                    }
                }
            ) { pin ->
                if (Lock.verify(ctx, pin)) {
                    Lock.markUnlocked()
                } else {
                    error = "Неверный PIN"
                    remaining = Lock.remainingLockSec(ctx)
                }
            }
            if (deviceCred) {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 30) {
                        act?.let { a -> DeviceCred.prompt(a) { resetPin() } }
                    } else {
                        DeviceCred.confirmIntent(ctx)?.let { i ->
                            Lock.expectExternal = true
                            credLauncher.launch(i)
                        }
                    }
                }) { Text("Забыл PIN?", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun PinSetupDialog(onDone: (String) -> Unit, onCancel: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    var len by remember { mutableStateOf(4) }
    var first by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    0 -> {
                        Text("Длина PIN-кода", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("4 цифры", len == 4) { len = 4 }
                            Chip("6 цифр", len == 6) { len = 6 }
                        }
                        Button(onClick = { step = 1 }) { Text("Дальше") }
                    }
                    1 -> PinPad(len, "Новый PIN", null, error) { p -> first = p; error = null; step = 2 }
                    else -> PinPad(len, "Повтори PIN", null, error) { p ->
                        if (p == first) onDone(p) else { error = "Не совпало, попробуй ещё раз"; step = 1 }
                    }
                }
                TextButton(onClick = onCancel) { Text("Отмена") }
            }
        }
    }
}

@Composable
fun PinVerifyDialog(title: String, onOk: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var remaining by remember { mutableStateOf(Lock.remainingLockSec(ctx)) }
    LaunchedEffect(remaining) {
        if (remaining > 0) {
            delay(1000)
            remaining = Lock.remainingLockSec(ctx)
        }
    }
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PinPad(
                    Lock.pinLength(ctx), title,
                    if (remaining > 0) "Подожди $remaining с" else null,
                    error, enabled = remaining == 0
                ) { pin ->
                    if (Lock.verify(ctx, pin)) onOk()
                    else { error = "Неверный PIN"; remaining = Lock.remainingLockSec(ctx) }
                }
                TextButton(onClick = onCancel) { Text("Отмена") }
            }
        }
    }
}