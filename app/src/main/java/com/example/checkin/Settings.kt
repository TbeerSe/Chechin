@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.example.checkin

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun PasswordDialog(title: String, askConfirm: Boolean, hint: String?, onOk: (String) -> Unit, onCancel: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, label = { Text("Пароль") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation()
                )
                if (askConfirm) OutlinedTextField(
                    value = pw2, onValueChange = { pw2 = it }, label = { Text("Повтори пароль") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation()
                )
                if (err != null) Text(err ?: "", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 10+ characters, not 6: this password protects the whole diary.
                val e = if (askConfirm && pw.length < 10) "Минимум 10 символов"
                else if (askConfirm && pw != pw2) "Пароли не совпадают"
                else if (pw.isEmpty()) "Введи пароль"
                else null
                if (e != null) err = e else onOk(pw)
            }) { Text("ОК") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } }
    )
}

private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024

/** Reads an import file, refusing absurdly large picks (a wrong file would otherwise run the app out of memory). */
private fun readCapped(ctx: Context, uri: android.net.Uri): String {
    ctx.contentResolver.openInputStream(uri).use { ins ->
        if (ins == null) return ""
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_IMPORT_BYTES) throw java.io.IOException("файл слишком большой (больше 20 МБ)")
        }
        return out.toString("UTF-8")
    }
}

@Composable
fun SettingsScreen(store: Store, onOpenConfig: () -> Unit) {
    val ctx = LocalContext.current
    val act = ctx.findActivity()
    val scope = rememberCoroutineScope()

    var replaceMode by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var encryptedImport by remember { mutableStateOf<String?>(null) }
    var exportPassDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var aiDays by rememberSaveable { mutableStateOf(30) }
    var aiNotes by rememberSaveable { mutableStateOf(false) }
    var aiTest by rememberSaveable { mutableStateOf(false) }

    var pinSet by remember { mutableStateOf(Lock.hasPin(ctx)) }
    var pinDialog by remember { mutableStateOf(0) }
    var bioOn by remember { mutableStateOf(Lock.bioEnabled(ctx)) }
    var delaySec by remember { mutableStateOf(Lock.delaySec(ctx)) }
    var secureOn by remember { mutableStateOf(Lock.secure(ctx)) }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    // Reminder when the last manual export gets old.
    fun prefs() = ctx.getSharedPreferences("exports", Context.MODE_PRIVATE)
    fun lastExportAge(): Int? {
        val t = prefs().getLong("lastExportAt", 0L)
        if (t <= 0L) return null
        return ((System.currentTimeMillis() - t) / 86_400_000L).toInt()
    }
    fun markExported() = prefs().edit().putLong("lastExportAt", System.currentTimeMillis()).apply()

    fun writeOut(uri: android.net.Uri) {
        val data = pending
        // `pending` does not survive a re-created screen: never write an empty file and call it a backup.
        if (data == null || data.isEmpty()) {
            toast("Не удалось сохранить: данные потеряны (экран был пересоздан). Повтори экспорт.")
            return
        }
        try {
            val out = ctx.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("нет доступа к файлу")
            out.use { it.write(data) }
            toast("Файл сохранён")
            markExported()
        } catch (e: Exception) {
            toast("Ошибка сохранения: ${e.message}")
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeOut(uri)
        pending = null
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) writeOut(uri)
        pending = null
    }
    fun saveFile(name: String, text: String) {
        pending = text.toByteArray(Charsets.UTF_8)
        Lock.expectExternal = true
        saveLauncher.launch(name)
    }
    fun savePdf(name: String, bytes: ByteArray) {
        pending = bytes
        Lock.expectExternal = true
        pdfLauncher.launch(name)
    }

    fun doImport(text: String) {
        try {
            val n = store.import(text, replaceMode)
            toast("Импортировано записей: $n")
        } catch (e: Exception) {
            toast("Не удалось импортировать: ${e.message}")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = readCapped(ctx, uri)
                if (Crypto.isEncrypted(text)) encryptedImport = text else doImport(text)
            } catch (e: Exception) {
                toast("Не удалось прочитать файл: ${e.message}")
            }
        }
    }
    fun pickImport(replace: Boolean) {
        replaceMode = replace
        Lock.expectExternal = true
        importLauncher.launch(arrayOf("*/*"))
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenTitle("Ещё")

        SoftCard { ReminderSection() }
        SoftCard { TestEntryCard(store) }

        SoftCard {
            SectionTitle("Мои вопросы")
            Text("Добавляй свои показатели, привычки и причины, переименовывай и скрывай ненужные.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onOpenConfig, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Text("Настроить вопросы")
            }
        }

        // ---------------- Protection
        SoftCard {
            SectionTitle("🔒 Защита")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("PIN-код при входе", Modifier.weight(1f))
                Switch(checked = pinSet, onCheckedChange = { on -> pinDialog = if (on) 1 else 2 })
            }
            if (pinSet) {
                OutlinedButton(onClick = { pinDialog = 3 }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("Сменить PIN")
                }
                if (Bio.available(ctx)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Вход по отпечатку / лицу", Modifier.weight(1f))
                        Switch(checked = bioOn, onCheckedChange = { on ->
                            if (on) {
                                act?.let { a -> Bio.prompt(a) { Lock.setBio(ctx, true); bioOn = true } }
                            } else {
                                Lock.setBio(ctx, false); bioOn = false
                            }
                        })
                    }
                }
                Text("Блокировать через:", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Сразу", 30 to "30 с", 60 to "1 мин", 300 to "5 мин").forEach { (s, label) ->
                        Chip(label, delaySec == s) { Lock.setDelay(ctx, s); delaySec = s }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Скрывать в списке приложений и блокировать скриншоты", Modifier.weight(1f))
                    Switch(checked = secureOn, onCheckedChange = { on ->
                        Lock.setSecure(ctx, on); secureOn = on
                        act?.let { a -> Lock.applySecure(a) }
                    })
                }
            }
            Text(
                "PIN закрывает вход в приложение (при 5 ошибках подряд включается пауза, не обнуляемая переводом часов). " +
                    "Файл с данными лежит во внутренней памяти приложения, недоступной другим приложениям, но не зашифрован: PIN защищает вход, а не сам файл. " +
                    "Забытый PIN можно сбросить через пароль устройства — кнопка «Забыл PIN?» на экране блокировки.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // ---------------- AI export
        SoftCard {
            SectionTitle("🤖 Для нейросети")
            Text(
                "Собирает компактный JSON с готовым промптом внутри. Приложение ничего не отправляет само: " +
                    "ты сам решаешь, куда передать файл или текст (ChatGPT, Claude и др.).",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Период:", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7 to "7 дней", 30 to "30 дней", 90 to "90 дней", 0 to "Всё").forEach { (d, label) ->
                    Chip(label, aiDays == d) { aiDays = d }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Включить текст заметок", Modifier.weight(1f))
                Switch(checked = aiNotes, onCheckedChange = { aiNotes = it })
            }
            if (aiNotes) Text(
                "⚠️ Заметки — самые личные данные. Убедись, что в них нет имён, адресов и другой чувствительной информации.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
            )
            AiTestToggleRow(store, aiTest) { aiTest = it }
            // The analysis runs permutation tests: keep it off the UI thread (a long period used to freeze the app).
            fun withAiText(action: (String) -> Unit) {
                if (busy) return
                scope.launch {
                    busy = true
                    try {
                        val text = withContext(Dispatchers.Default) { AiExport.build(store, aiDays, aiNotes, aiTest) }
                        action(text)
                    } catch (e: Exception) {
                        toast("Не удалось собрать данные: ${e.message}")
                    } finally {
                        busy = false
                    }
                }
            }
            Button(
                onClick = {
                    withAiText { text ->
                        // EXTRA_TEXT above ~100k can throw TransactionTooLargeException in some targets;
                        // above that, a file is the reliable route.
                        if (text.length > 50_000) {
                            toast("Данных слишком много для отправки текстом — сохрани файлом или выбери период короче")
                        } else {
                            Lock.expectExternal = true
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Checkin: данные для анализа")
                                putExtra(Intent.EXTRA_TEXT, text)
                                // Intent.EXTRA_IS_SENSITIVE (API 33) by its string value, so this compiles with any compileSdk.
                                if (Build.VERSION.SDK_INT >= 33) putExtra("android.intent.extra.IS_SENSITIVE", true)
                            }
                            ctx.startActivity(Intent.createChooser(send, "Отправить"))
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
            ) { Text("Поделиться текстом") }
            OutlinedButton(
                onClick = {
                    withAiText { text ->
                        try {
                            val cm = ctx.getSystemService(ClipboardManager::class.java)
                            val clip = ClipData.newPlainText("checkin", text)
                            // ClipDescription.setIsSensitive (API 33) just sets this extra; written directly so it compiles with any compileSdk.
                            if (Build.VERSION.SDK_INT >= 33) {
                                clip.description.extras = android.os.PersistableBundle().apply {
                                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                                }
                            }
                            cm?.setPrimaryClip(clip)
                            toast("Скопировано. Вставь в чат с нейросетью.")
                        } catch (e: Exception) {
                            toast("Не удалось скопировать: ${e.message}")
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
            ) { Text("Скопировать в буфер") }
            OutlinedButton(
                onClick = { withAiText { text -> saveFile("checkin_for_ai_${LocalDate.now()}.json", text) } },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
            ) { Text("Сохранить файлом") }
        }

        // ---------------- Data
        SoftCard {
            SectionTitle("💾 Данные и бэкап")
            Text(
                "Всё хранится только на этом устройстве; облачный бэкап отключён, но перенос на новый телефон " +
                    "при переносе данных устройства работает. Рядом с основным файлом автоматически хранятся " +
                    "три последние копии (checkin_backup_0..2.json). Обычный экспорт не зашифрован — для " +
                    "облака используй экспорт с паролем.",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Записей: ${store.entries.size}")
            val age = lastExportAge()
            Text(
                if (age == null) "Ручного экспорта ещё не было."
                else if (age == 0) "Последний экспорт: сегодня ✓"
                else "Последний экспорт был $age " + (if (age % 10 == 1 && age % 100 != 11) "день" else if (age % 10 in 2..4 && age % 100 !in 12..14) "дня" else "дней") + " назад.",
                style = MaterialTheme.typography.bodySmall,
                color = if (age != null && age > 7) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { saveFile("checkin_export_${LocalDate.now()}.json", store.toJsonString()) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
            ) { Text("Экспорт в JSON") }
            OutlinedButton(
                onClick = { exportPassDialog = true },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                enabled = !busy
            ) { Text(if (busy) "Шифрую… (может занять полминуты)" else "Экспорт с паролем (зашифрованный)") }
            OutlinedButton(
                onClick = { saveFile("checkin_${LocalDate.now()}.csv", CsvExport.build(store)) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
            ) { Text("Экспорт в CSV (для таблиц)") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val bytes = withContext(Dispatchers.Default) { PdfReport.build(store) }
                            savePdf("checkin_report_${LocalDate.now()}.pdf", bytes)
                        } catch (e: Exception) {
                            toast("Не удалось собрать PDF: ${e.message}")
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                enabled = !busy
            ) { Text("PDF-отчёт для специалиста (30 дней)") }
            OutlinedButton(
                onClick = { pickImport(false) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
            ) { Text("Импорт (объединить)") }
            OutlinedButton(
                onClick = { confirmReplace = true },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
            ) { Text("Импорт (заменить всё)") }
        }


        TextButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Удалить ВСЕ записи", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
    }

    // ---------------- dialogs
    when (pinDialog) {
        1 -> PinSetupDialog(
            onDone = { pin ->
                Lock.setPin(ctx, pin)
                pinSet = true; pinDialog = 0
                delaySec = Lock.delaySec(ctx); secureOn = Lock.secure(ctx); bioOn = Lock.bioEnabled(ctx)
                act?.let { a -> Lock.applySecure(a) }
                toast("PIN установлен")
            },
            onCancel = { pinDialog = 0 }
        )
        2 -> PinVerifyDialog(
            title = "PIN для отключения",
            onOk = {
                Lock.clearPin(ctx)
                pinSet = false; bioOn = false; pinDialog = 0
                delaySec = Lock.delaySec(ctx); secureOn = Lock.secure(ctx)
                act?.let { a -> Lock.applySecure(a) }
                toast("PIN отключён")
            },
            onCancel = { pinDialog = 0 }
        )
        3 -> PinVerifyDialog(title = "Текущий PIN", onOk = { pinDialog = 1 }, onCancel = { pinDialog = 0 })
        else -> {}
    }

    if (exportPassDialog) {
        PasswordDialog(
            title = "Пароль для бэкапа",
            askConfirm = true,
            hint = "Минимум 10 символов. Без этого пароля файл не открыть. Восстановить его нельзя.",
            onOk = { pw ->
                exportPassDialog = false
                // PBKDF2 at 600k iterations is deliberately slow - keep it off the UI thread.
                scope.launch {
                    busy = true
                    try {
                        val enc = withContext(Dispatchers.Default) { Crypto.encrypt(store.toJsonString(), pw) }
                        saveFile("checkin_encrypted_${LocalDate.now()}.json", enc)
                    } catch (e: Exception) {
                        toast("Ошибка шифрования: ${e.message}")
                    } finally {
                        busy = false
                    }
                }
            },
            onCancel = { exportPassDialog = false }
        )
    }
    if (encryptedImport != null) {
        PasswordDialog(
            title = "Файл зашифрован",
            askConfirm = false,
            hint = "Введи пароль, указанный при экспорте.",
            onOk = { pw ->
                val raw = encryptedImport ?: ""
                scope.launch {
                    busy = true
                    try {
                        val plain = withContext(Dispatchers.Default) { Crypto.decrypt(raw, pw) }
                        encryptedImport = null
                        doImport(plain)
                    } catch (e: Exception) {
                        toast(e.message ?: "Ошибка")
                    } finally {
                        busy = false
                    }
                }
            },
            onCancel = { encryptedImport = null }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Удалить все данные дневника?") },
            text = { Text("Будут удалены записи, результаты теста, эксперименты и все автоматические копии на устройстве. Это нельзя отменить. Сначала сделай экспорт, если нужна копия. Настройки вопросов останутся.") },
            confirmButton = { TextButton(onClick = { store.clearAll(); confirmClear = false }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } }
        )
    }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Заменить все данные?") },
            text = { Text("Текущие записи будут удалены и заменены содержимым файла.") },
            confirmButton = { TextButton(onClick = { confirmReplace = false; pickImport(true) }) { Text("Выбрать файл") } },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Отмена") } }
        )
    }
}
