package com.calllogeditor.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

private val PERMS = arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)
private fun hasPerms(c: Context) = PERMS.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private val TYPES = listOf(1 to "Incoming", 2 to "Outgoing", 3 to "Missed", 5 to "Rejected", 6 to "Blocked", 4 to "Voicemail")
private fun typeName(t: Int) = TYPES.firstOrNull { it.first == t }?.second ?: "Other"
private fun typeColor(t: Int) = when (t) {
    1 -> Color(0xFF2E7D32); 2 -> Color(0xFF1565C0); 3 -> Color(0xFFC62828)
    5 -> Color(0xFFEF6C00); 6 -> Color(0xFF6D4C41); else -> Color(0xFF6A1B9A)
}
private fun fmtDate(ms: Long) = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))
private fun fmtDur(s: Long) = if (s < 60) "${s}s" else "${s / 60}m ${"%02d".format(s % 60)}s"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme(primary = Color(0xFF2DD4BF)) else lightColorScheme(primary = Color(0xFF0F766E))
            ) { Surface(Modifier.fillMaxSize()) { Root() } }
        }
    }
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPerms(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasPerms(ctx) }
    if (granted) CallLogScreen() else Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Call log access needed", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Allow reading and writing the call log to edit it.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = { launcher.launch(PERMS) }) { Text("Allow") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CallLogScreen() {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf(listOf<CallEntry>()) }
    var q by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf(setOf<Long>()) }
    var editing by remember { mutableStateOf<CallEntry?>(null) }
    var confirmDel by remember { mutableStateOf(false) }
    fun reload() { all = CallRepo.load(ctx) }
    LaunchedEffect(Unit) { reload() }
    val shown = remember(all, q) {
        if (q.isBlank()) all else all.filter { it.number.contains(q) || it.name.contains(q, true) }
    }

    Scaffold(
        topBar = {
            when {
                sel.isNotEmpty() -> TopAppBar(
                    title = { Text("${sel.size} selected") },
                    navigationIcon = { IconButton({ sel = emptySet() }) { Icon(Icons.Default.Close, "Cancel") } },
                    actions = {
                        IconButton({ sel = shown.map { it.id }.toSet() }) { Icon(Icons.Default.Check, "All") }
                        IconButton({ confirmDel = true }) { Icon(Icons.Default.Delete, "Delete") }
                    })
                searching -> TopAppBar(
                    title = {
                        TextField(q, { q = it }, singleLine = true, placeholder = { Text("Search") },
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                    },
                    navigationIcon = { IconButton({ searching = false; q = "" }) { Icon(Icons.Default.ArrowBack, "Back") } })
                else -> TopAppBar(
                    title = { Text("Call Log") },
                    actions = { IconButton({ searching = true }) { Icon(Icons.Default.Search, "Search") } })
            }
        },
        floatingActionButton = {
            if (sel.isEmpty()) FloatingActionButton({ editing = CallEntry() }) { Icon(Icons.Default.Add, "Add") }
        }
    ) { pad ->
        if (shown.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { Text("No calls") }
        else LazyColumn(Modifier.padding(pad)) {
            items(shown, key = { it.id }) { e ->
                val on = e.id in sel
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                        .combinedClickable(
                            onClick = { if (sel.isNotEmpty()) sel = if (on) sel - e.id else sel + e.id else editing = e },
                            onLongClick = { sel = sel + e.id })
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(typeColor(e.type)), Alignment.Center) {
                        Icon(if (on) Icons.Default.Check else Icons.Default.Call, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name.ifBlank { e.number.ifBlank { "Unknown" } }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge)
                        Text("${typeName(e.type)} · ${fmtDate(e.date)} · ${fmtDur(e.duration)}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (confirmDel) AlertDialog(
        onDismissRequest = { confirmDel = false },
        title = { Text("Delete ${sel.size}?") },
        text = { Text("This removes them from your phone's call log.") },
        confirmButton = {
            TextButton({
                CallRepo.delete(ctx, sel); sel = emptySet(); confirmDel = false; reload()
            }) { Text("Delete") }
        },
        dismissButton = { TextButton({ confirmDel = false }) { Text("Cancel") } })

    editing?.let { e ->
        Editor(e, onDismiss = { editing = null }, onDelete = {
            CallRepo.delete(ctx, setOf(e.id)); editing = null; reload()
        }, onSave = {
            if (CallRepo.save(ctx, it)) { editing = null; reload() }
            else Toast.makeText(ctx, "Could not save", Toast.LENGTH_SHORT).show()
        })
    }
}

@Composable
fun Editor(e: CallEntry, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (CallEntry) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(e.name) }
    var number by remember { mutableStateOf(e.number) }
    var type by remember { mutableStateOf(e.type) }
    var date by remember { mutableStateOf(e.date) }
    var dur by remember { mutableStateOf(e.duration.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (e.id == 0L) "New entry" else "Edit entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(number, { number = it }, label = { Text("Number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TYPES.forEach { (t, n) -> FilterChip(type == t, { type = t }, label = { Text(n) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        val c = Calendar.getInstance().apply { timeInMillis = date }
                        DatePickerDialog(ctx, { _, y, m, d ->
                            c.set(y, m, d); date = c.timeInMillis
                        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                    }) { Text(SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(date))) }
                    OutlinedButton({
                        val c = Calendar.getInstance().apply { timeInMillis = date }
                        TimePickerDialog(ctx, { _, h, mi ->
                            c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, mi); date = c.timeInMillis
                        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
                    }) { Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(date))) }
                }
                OutlinedTextField(dur, { dur = it.filter(Char::isDigit) }, label = { Text("Duration (sec)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = {
            TextButton({ onSave(e.copy(number = number.trim(), name = name.trim(), type = type, date = date, duration = dur.toLongOrNull() ?: 0)) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (e.id != 0L) TextButton(onDelete) { Text("Delete") }
                TextButton(onDismiss) { Text("Cancel") }
            }
        })
}
