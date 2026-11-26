package com.calllogeditor.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private val CALL_PERMS = arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)
private val ALL_PERMS = CALL_PERMS + Manifest.permission.READ_CONTACTS
private fun callOk(c: Context) = CALL_PERMS.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

val TYPES = listOf(1 to "Incoming", 2 to "Outgoing", 3 to "Missed", 5 to "Rejected", 6 to "Blocked", 4 to "Voicemail")
fun typeName(t: Int) = TYPES.firstOrNull { it.first == t }?.second ?: "Other"
fun typeColor(t: Int) = when (t) {
    1 -> Color(0xFF2E7D32); 2 -> Color(0xFF1565C0); 3 -> Color(0xFFC62828)
    5 -> Color(0xFFEF6C00); 6 -> Color(0xFF6D4C41); else -> Color(0xFF6A1B9A)
}
fun fmtTime(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
fun fmtDur(s: Long) = when {
    s < 60 -> "${s}s"
    s < 3600 -> "${s / 60}m ${"%02d".format(s % 60)}s"
    else -> "${s / 3600}h ${"%02d".format(s % 3600 / 60)}m"
}
fun dayLabel(ms: Long): String {
    fun start(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val d = (start(System.currentTimeMillis()) - start(ms)) / 86_400_000L
    return when (d) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(ms))
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme(
                primary = Color(0xFF2DD4BF), onPrimary = Color(0xFF00201C),
                background = Color(0xFF0E1116), surface = Color(0xFF0E1116),
                surfaceVariant = Color(0xFF1A2029), secondaryContainer = Color(0xFF16342F)
            ) else lightColorScheme(primary = Color(0xFF0F766E))
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
    }
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(callOk(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = callOk(ctx) }
    if (granted) CallLogScreen() else Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Allow access", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("Call log: read and edit your calls.", style = MaterialTheme.typography.bodyMedium)
        Text("Contacts: show the exact name for each number.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        Button(onClick = { launcher.launch(ALL_PERMS) }) { Text("Allow") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CallLogScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var all by remember { mutableStateOf(listOf<CallEntry>()) }
    var names by remember { mutableStateOf(mapOf<String, String>()) }
    var cOk by remember { mutableStateOf(Contacts.has(ctx)) }
    var q by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<Int?>(null) }
    var newest by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf(setOf<Long>()) }
    var editing by remember { mutableStateOf<CallEntry?>(null) }
    var bulk by remember { mutableStateOf(false) }

    fun msg(t: String) { scope.launch { snack.showSnackbar(t) } }
    fun reload() {
        scope.launch {
            val (l, n) = withContext(Dispatchers.IO) {
                val l = CallRepo.load(ctx)
                val n = HashMap<String, String>()
                l.map { it.number }.distinct().forEach { num -> Contacts.lookup(ctx, num)?.let { n[num] = it } }
                l to n.toMap()
            }
            all = l; names = n
        }
    }
    fun nameOf(e: CallEntry) = names[e.number] ?: e.name

    val cLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cOk = it; reload() }
    val exportL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.openOutputStream(uri)?.use { it.write(Csv.export(all).toByteArray()) }; true } catch (_: Exception) { false }
            }
            msg(if (ok) "Exported ${all.size} calls" else "Export failed")
        }
    }
    val importL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                    CallRepo.importAll(ctx, Csv.parse(text), all)
                } catch (_: Exception) { -1 }
            }
            msg(if (n < 0) "Import failed" else "Imported $n calls")
            reload()
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun deleteWithUndo(list: List<CallEntry>) {
        scope.launch {
            withContext(Dispatchers.IO) { CallRepo.delete(ctx, list.map { it.id }.toSet()) }
            sel = emptySet(); reload()
            val r = snack.showSnackbar("Deleted ${list.size}", "Undo", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) { list.forEach { CallRepo.save(ctx, it.copy(id = 0)) } }
                reload()
            }
        }
    }

    val shown = remember(all, names, q, filter, newest) {
        all.filter {
            (filter == null || it.type == filter) &&
                (q.isBlank() || it.number.contains(q) || (names[it.number] ?: it.name).contains(q, true))
        }.let { if (newest) it else it.reversed() }
    }
    val groups = remember(shown) { shown.groupBy { dayLabel(it.date) } }
    val talk = remember(shown) { shown.sumOf { it.duration } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            when {
                sel.isNotEmpty() -> TopAppBar(
                    title = { Text("${sel.size} selected") },
                    navigationIcon = { IconButton({ sel = emptySet() }) { Icon(Icons.Default.Close, "Cancel") } },
                    actions = {
                        IconButton({ sel = shown.map { it.id }.toSet() }) { Icon(Icons.Default.Check, "Select all") }
                        IconButton({ bulk = true }) { Icon(Icons.Default.Edit, "Edit") }
                        IconButton({ deleteWithUndo(all.filter { it.id in sel }) }) { Icon(Icons.Default.Delete, "Delete") }
                    })
                searching -> TopAppBar(
                    title = {
                        TextField(q, { q = it }, singleLine = true, placeholder = { Text("Name or number") },
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                    },
                    navigationIcon = { IconButton({ searching = false; q = "" }) { Icon(Icons.Default.ArrowBack, "Back") } })
                else -> TopAppBar(
                    title = {
                        Column {
                            Text("Call Log")
                            Text("${shown.size} calls · ${fmtDur(talk)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        IconButton({ searching = true }) { Icon(Icons.Default.Search, "Search") }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(if (newest) "Oldest first" else "Newest first") },
                                    onClick = { newest = !newest; menu = false })
                                DropdownMenuItem(text = { Text("Sync names from contacts") }, onClick = {
                                    menu = false
                                    if (!cOk) cLauncher.launch(Manifest.permission.READ_CONTACTS)
                                    else scope.launch {
                                        val n = withContext(Dispatchers.IO) { CallRepo.syncNames(ctx, all) }
                                        msg(if (n == 0) "Names already match" else "Updated $n names"); reload()
                                    }
                                })
                                DropdownMenuItem(text = { Text("Export CSV") }, onClick = {
                                    menu = false
                                    exportL.launch("call_log_${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.csv")
                                })
                                DropdownMenuItem(text = { Text("Import CSV") }, onClick = {
                                    menu = false; importL.launch(arrayOf("*/*"))
                                })
                            }
                        }
                    })
            }
        },
        floatingActionButton = {
            if (sel.isEmpty()) FloatingActionButton({ editing = CallEntry() }) { Icon(Icons.Default.Add, "Add") }
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (!cOk) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow contacts to show exact names", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton({ cLauncher.launch(Manifest.permission.READ_CONTACTS) }) { Text("Allow") }
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(filter == null, { filter = null }, label = { Text("All") })
                TYPES.forEach { (t, n) -> FilterChip(filter == t, { filter = if (filter == t) null else t }, label = { Text(n) }) }
            }
            if (shown.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No calls") }
            else LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { (label, list) ->
                    stickyHeader(key = "h$label") {
                        Text(label, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 14.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    items(list, key = { it.id }) { e ->
                        val on = e.id in sel
                        CallRow(e, nameOf(e), on,
                            onClick = { if (sel.isNotEmpty()) sel = if (on) sel - e.id else sel + e.id else editing = e },
                            onLong = { sel = sel + e.id })
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    editing?.let { e ->
        Editor(e, nameOf = { Contacts.lookup(ctx, it) }, onDismiss = { editing = null },
            onDelete = { editing = null; deleteWithUndo(listOf(e)) },
            onDuplicate = {
                scope.launch {
                    withContext(Dispatchers.IO) { CallRepo.save(ctx, e.copy(id = 0)) }
                    editing = null; reload(); msg("Duplicated")
                }
            },
            onSave = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { CallRepo.save(ctx, it) }
                    if (ok) { editing = null; reload() } else msg("Could not save")
                }
            })
    }

    if (bulk) BulkDialog(sel.size, onDismiss = { bulk = false }) { name, type ->
        bulk = false
        val targets = all.filter { it.id in sel }
        scope.launch {
            withContext(Dispatchers.IO) {
                targets.forEach { CallRepo.save(ctx, it.copy(name = name ?: it.name, type = type ?: it.type)) }
            }
            sel = emptySet(); reload(); msg("Updated ${targets.size} calls")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallRow(e: CallEntry, name: String, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val title = name.ifBlank { e.number.ifBlank { "Unknown" } }
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(typeColor(e.type)), Alignment.Center) {
            when {
                selected -> Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                name.firstOrNull()?.isLetter() == true -> Text(name.first().uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                else -> Icon(Icons.Default.Call, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            val sub = listOfNotNull(
                if (name.isNotBlank() && e.number.isNotBlank()) e.number else null,
                typeName(e.type),
                if (e.duration > 0) fmtDur(e.duration) else null
            ).joinToString(" · ")
            Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(fmtTime(e.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Editor(
    e: CallEntry, nameOf: (String) -> String?, onDismiss: () -> Unit,
    onDelete: () -> Unit, onDuplicate: () -> Unit, onSave: (CallEntry) -> Unit
) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var number by remember { mutableStateOf(e.number) }
    var name by remember { mutableStateOf(e.name.ifBlank { nameOf(e.number) ?: "" }) }
    var touched by remember { mutableStateOf(e.name.isNotBlank()) }
    var fromContact by remember { mutableStateOf(e.name.isBlank() && name.isNotBlank()) }
    var type by remember { mutableStateOf(e.type) }
    var date by remember { mutableStateOf(e.date) }
    var min by remember { mutableStateOf((e.duration / 60).toString()) }
    var sec by remember { mutableStateOf((e.duration % 60).toString()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(if (e.id == 0L) "New entry" else "Edit entry", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(number, {
                number = it
                if (!touched) { val n = nameOf(it); name = n ?: ""; fromContact = n != null }
            }, label = { Text("Number") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            OutlinedTextField(name, { name = it; touched = true; fromContact = false },
                label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { if (fromContact) Text("From contacts") })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TYPES.forEach { (t, n) -> FilterChip(type == t, { type = t }, label = { Text(n) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    val c = Calendar.getInstance().apply { timeInMillis = date }
                    DatePickerDialog(ctx, { _, y, m, d -> c.set(y, m, d); date = c.timeInMillis },
                        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                }, Modifier.weight(1f)) { Text(SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(date))) }
                OutlinedButton({
                    val c = Calendar.getInstance().apply { timeInMillis = date }
                    TimePickerDialog(ctx, { _, h, mi ->
                        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, mi); date = c.timeInMillis
                    }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
                }, Modifier.weight(1f)) { Text(fmtTime(date)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(min, { min = it.filter(Char::isDigit).take(5) }, label = { Text("Minutes") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(sec, { sec = it.filter(Char::isDigit).take(2) }, label = { Text("Seconds") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (e.id != 0L) {
                    TextButton(onDelete) { Text("Delete") }
                    TextButton(onDuplicate) { Text("Duplicate") }
                }
                Spacer(Modifier.weight(1f))
                Button({
                    val d = (min.toLongOrNull() ?: 0) * 60 + (sec.toLongOrNull() ?: 0).coerceAtMost(59)
                    onSave(e.copy(number = number.trim(), name = name.trim(), type = type, date = date, duration = d))
                }) { Text("Save") }
            }
        }
    }
}

@Composable
fun BulkDialog(count: Int, onDismiss: () -> Unit, onApply: (String?, Int?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit $count calls") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Set name (optional)") }, singleLine = true)
                Text("Set type (optional)", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TYPES.forEach { (t, n) -> FilterChip(type == t, { type = if (type == t) null else t }, label = { Text(n) }) }
                }
            }
        },
        confirmButton = {
            TextButton({ onApply(name.trim().ifBlank { null }, type) }, enabled = name.isNotBlank() || type != null) { Text("Apply") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
