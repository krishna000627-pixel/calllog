package com.calllogeditor.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(callOk(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = callOk(ctx) }

    if (!granted) {
        Column(
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
        return
    }

    var tab by remember { mutableStateOf(0) }
    var all by remember { mutableStateOf(listOf<CallEntry>()) }
    var cmap by remember { mutableStateOf(mapOf<String, String>()) }
    var cOk by remember { mutableStateOf(Contacts.has(ctx)) }
    var autoNames by remember { mutableStateOf(Auto.namesOn(ctx)) }
    var batteryOk by remember { mutableStateOf(Battery.exempt(ctx)) }

    fun reload() {
        scope.launch {
            val (l, m) = withContext(Dispatchers.IO) { CallRepo.load(ctx) to Contacts.loadMap(ctx) }
            all = l; cmap = m
        }
    }

    val batteryL = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { batteryOk = Battery.exempt(ctx) }
    fun askBattery(onError: () -> Unit = {}) {
        try { batteryL.launch(Battery.intent(ctx)) } catch (_: Exception) { onError() }
    }

    LaunchedEffect(Unit) {
        reload(); Auto.refresh(ctx)
        if (!Battery.exempt(ctx) && !Battery.askedBefore(ctx)) { Battery.markAsked(ctx); askBattery() }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Call, "Calls") },
                    label = { Text("Calls") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.List, "Rules") },
                    label = { Text("Rules") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, "Tools") },
                    label = { Text("Tools") }
                )
            }
        }
    ) { outerPad ->
        Box(Modifier.padding(bottom = outerPad.calculateBottomPadding()).fillMaxSize()) {
            when (tab) {
                0 -> CallsTab(
                    all = all,
                    cmap = cmap,
                    cOk = cOk,
                    onContactsGranted = { cOk = it; reload() },
                    onReload = ::reload
                )
                1 -> RulesTab(cmap = cmap, onApplied = ::reload)
                2 -> ToolsTab(
                    all = all,
                    cmap = cmap,
                    cOk = cOk,
                    autoNames = autoNames,
                    batteryOk = batteryOk,
                    onContactsGranted = { cOk = it; reload() },
                    onToggleAutoNames = { on -> autoNames = on; Auto.setNames(ctx, on) },
                    onAskBattery = ::askBattery,
                    onReload = ::reload
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CallsTab(
    all: List<CallEntry>,
    cmap: Map<String, String>,
    cOk: Boolean,
    onContactsGranted: (Boolean) -> Unit,
    onReload: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var q by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<Int?>(null) }
    var newest by remember { mutableStateOf(true) }
    var sel by remember { mutableStateOf(setOf<Long>()) }
    var editing by remember { mutableStateOf<CallEntry?>(null) }
    var bulk by remember { mutableStateOf(false) }

    fun msg(t: String) { scope.launch { snack.showSnackbar(t) } }
    fun nameOf(e: CallEntry) = cmap[Contacts.norm(e.number)] ?: e.name
    val cLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onContactsGranted(it) }

    fun deleteWithUndo(list: List<CallEntry>) {
        if (list.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) { CallRepo.delete(ctx, list.map { it.id }.toSet()) }
            sel = emptySet(); onReload()
            val r = snack.showSnackbar("Deleted ${list.size}", "Undo", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) { list.forEach { CallRepo.save(ctx, it.copy(id = 0)) } }
                onReload()
            }
        }
    }

    val counts = remember(all) { all.groupingBy { it.type }.eachCount() }
    val shown = remember(all, cmap, q, filter, newest) {
        all.filter {
            (filter == null || it.type == filter) &&
                (q.isBlank() || it.number.contains(q) || Contacts.norm(it.number).let { n -> n.isNotEmpty() && n == Contacts.norm(q) } ||
                    (cmap[Contacts.norm(it.number)] ?: it.name).contains(q, true))
        }.let { if (newest) it else it.reversed() }
    }
    val groups = remember(shown) { shown.groupBy { dayLabel(it.date) } }
    val talk = remember(shown) { shown.filter { it.type !in NO_DURATION_TYPES }.sumOf { it.duration } }

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
                    }
                )
                searching -> TopAppBar(
                    title = {
                        TextField(
                            value = q,
                            onValueChange = { q = it },
                            singleLine = true,
                            placeholder = { Text("Search name or number") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            trailingIcon = {
                                if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(Icons.Default.Close, "Clear") }
                            }
                        )
                    },
                    navigationIcon = { IconButton({ searching = false; q = "" }) { Icon(Icons.Default.ArrowBack, "Back") } }
                )
                else -> TopAppBar(
                    title = {
                        Column {
                            Text("Call Log")
                            val sub = if (talk > 0) "${shown.size} calls · ${fmtDur(talk)}" else "${shown.size} calls"
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        TextButton({ newest = !newest }) { Text(if (newest) "Newest" else "Oldest") }
                        IconButton({ searching = true }) { Icon(Icons.Default.Search, "Search") }
                    }
                )
            }
        },
        floatingActionButton = {
            if (sel.isEmpty()) FloatingActionButton({ editing = CallEntry() }) { Icon(Icons.Default.Add, "Add") }
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (!cOk) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow contacts to show saved names", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton({ cLauncher.launch(Manifest.permission.READ_CONTACTS) }) { Text("Allow") }
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(filter == null, { filter = null }, label = { Text("All (${all.size})") })
                TYPES.forEach { (t, n) ->
                    val c = counts[t] ?: 0
                    if (c > 0 || filter == t || t in listOf(1, 2, 3, 5)) {
                        FilterChip(filter == t, { filter = if (filter == t) null else t }, label = { Text("$n ($c)") })
                    }
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("No calls", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    groups.forEach { (label, list) ->
                        stickyHeader(key = "h$label") {
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text("${list.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(list, key = { it.id }) { e ->
                            val on = e.id in sel
                            CallRow(
                                e = e,
                                name = nameOf(e),
                                selected = on,
                                onClick = { if (sel.isNotEmpty()) sel = if (on) sel - e.id else sel + e.id else editing = e },
                                onLong = { sel = sel + e.id }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(84.dp)) }
                }
            }
        }
    }

    editing?.let { e ->
        Editor(
            e = e,
            nameOf = { cmap[Contacts.norm(it)] },
            onDismiss = { editing = null },
            onDelete = { editing = null; deleteWithUndo(listOf(e)) },
            onDuplicate = {
                scope.launch {
                    withContext(Dispatchers.IO) { CallRepo.save(ctx, e.copy(id = 0)) }
                    editing = null; onReload(); msg("Duplicated")
                }
            },
            onHistory = { editing = null; q = e.number; searching = true },
            onDeleteAll = {
                val n = Contacts.norm(e.number)
                editing = null
                deleteWithUndo(all.filter { Contacts.norm(it.number) == n })
            },
            onSave = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { CallRepo.save(ctx, it) }
                    if (ok) { editing = null; onReload() } else msg("Could not save")
                }
            }
        )
    }

    if (bulk) BulkDialog(sel.size, onDismiss = { bulk = false }) { name, type ->
        bulk = false
        val targets = all.filter { it.id in sel }
        scope.launch {
            withContext(Dispatchers.IO) {
                targets.forEach {
                    val nt = type ?: it.type
                    val nd = if (nt in NO_DURATION_TYPES) 0L else it.duration
                    CallRepo.save(ctx, it.copy(name = name ?: it.name, type = nt, duration = nd))
                }
            }
            sel = emptySet(); onReload(); msg("Updated ${targets.size} calls")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallRow(e: CallEntry, name: String, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val title = name.ifBlank { e.number.ifBlank { "Unknown" } }
    val tc = typeColor(e.type)
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else tc.copy(alpha = 0.15f)),
            Alignment.Center
        ) {
            when {
                selected -> Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                name.firstOrNull()?.isLetter() == true -> Text(name.first().uppercase(), color = tc, fontWeight = FontWeight.Bold)
                else -> Icon(Icons.Default.Call, null, tint = tc, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(color = tc.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
                    Text(
                        typeName(e.type),
                        Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = tc
                    )
                }
                val details = listOfNotNull(
                    if (name.isNotBlank() && e.number.isNotBlank()) e.number else null,
                    if (e.duration > 0 && e.type !in NO_DURATION_TYPES) fmtDur(e.duration) else null
                ).joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(
                        details,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(fmtTime(e.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsTab(
    all: List<CallEntry>,
    cmap: Map<String, String>,
    cOk: Boolean,
    autoNames: Boolean,
    batteryOk: Boolean,
    onContactsGranted: (Boolean) -> Unit,
    onToggleAutoNames: (Boolean) -> Unit,
    onAskBattery: (() -> Unit) -> Unit,
    onReload: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var showAllTop by remember { mutableStateOf(false) }

    fun msg(t: String) { scope.launch { snack.showSnackbar(t) } }
    fun nameOf(e: CallEntry) = cmap[Contacts.norm(e.number)] ?: e.name

    val cLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onContactsGranted(it) }
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
            onReload()
        }
    }

    val topCallers = remember(all, cmap) {
        all.groupBy { Contacts.norm(it.number).ifEmpty { "unknown" } }.map { (_, l) ->
            val f = l.first()
            Triple(nameOf(f).ifBlank { f.number.ifBlank { "Unknown" } }, l.size, l.filter { it.type !in NO_DURATION_TYPES }.sumOf { it.duration })
        }.sortedByDescending { it.second }.take(10)
    }
    val answered = remember(all) { all.count { it.type == 1 || it.type == 2 } }
    val missed = remember(all) { all.count { it.type in NO_DURATION_TYPES } }
    val talk = remember(all) { all.filter { it.type !in NO_DURATION_TYPES }.sumOf { it.duration } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { TopAppBar(title = { Text("Tools & Insights") }) }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Insights & Top Callers
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatBox("Total", "${all.size}", Modifier.weight(1f))
                        StatBox("Answered", "$answered", Modifier.weight(1f))
                        StatBox("Missed", "$missed", Modifier.weight(1f))
                        StatBox("Talk", fmtDur(talk), Modifier.weight(1f))
                    }
                    if (topCallers.isNotEmpty()) {
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Top callers", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            if (topCallers.size > 5) {
                                TextButton({ showAllTop = !showAllTop }) { Text(if (showAllTop) "Top 5" else "Top 10") }
                            }
                        }
                        val visible = if (showAllTop) topCallers else topCallers.take(5)
                        visible.forEachIndexed { i, r ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}. ${r.first}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                val dur = if (r.third > 0) " · ${fmtDur(r.third)}" else ""
                                Text("${r.second} calls$dur", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // 2. Contacts & Automation
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Contacts & Background", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto-fill names", style = MaterialTheme.typography.bodyLarge)
                            Text("Fill contact names automatically for new calls", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(autoNames, {
                            if (!cOk) cLauncher.launch(Manifest.permission.READ_CONTACTS)
                            else onToggleAutoNames(it)
                        })
                    }
                    HorizontalDivider()
                    ToolRow("Sync names from contacts", "Update saved call names across your log") {
                        if (!cOk) cLauncher.launch(Manifest.permission.READ_CONTACTS)
                        else scope.launch {
                            val n = withContext(Dispatchers.IO) { CallRepo.syncNames(ctx, all, cmap) }
                            msg(if (n == 0) "Names already match" else "Updated $n names")
                            onReload()
                        }
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !batteryOk) {
                            onAskBattery { msg("Not supported on this phone") }
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Background execution", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (batteryOk) "Unrestricted — rules run reliably" else "Restricted — tap to allow background use",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (batteryOk) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                            )
                        }
                        if (!batteryOk) Icon(Icons.Default.Warning, "Restricted", tint = MaterialTheme.colorScheme.error)
                        else Icon(Icons.Default.Check, "OK", tint = Color(0xFF2E7D32))
                    }
                }
            }

            // 3. Backup & Cleanup
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Backup & Cleanup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    ToolRow("Export CSV", "Save all ${all.size} calls to a CSV file") {
                        exportL.launch("call_log_${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.csv")
                    }
                    HorizontalDivider()
                    ToolRow("Import CSV", "Restore calls from a CSV backup") {
                        importL.launch(arrayOf("*/*"))
                    }
                    HorizontalDivider()
                    ToolRow("Remove duplicates", "Delete identical entries with same number, time, and type") {
                        val seen = HashSet<String>()
                        val extra = all.filter { !seen.add("${it.number}|${it.date}|${it.type}") }
                        if (extra.isEmpty()) msg("No duplicates found")
                        else scope.launch {
                            withContext(Dispatchers.IO) { CallRepo.delete(ctx, extra.map { it.id }.toSet()) }
                            onReload()
                            msg("Removed ${extra.size} duplicates")
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(vertical = 8.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun ToolRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Editor(
    e: CallEntry, nameOf: (String) -> String?, onDismiss: () -> Unit,
    onDelete: () -> Unit, onDuplicate: () -> Unit, onHistory: () -> Unit, onDeleteAll: () -> Unit,
    onSave: (CallEntry) -> Unit
) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val contactName = nameOf(e.number)
    var number by remember { mutableStateOf(e.number) }
    // The edit screen always starts from the contact's current name, not the old name saved in the log.
    var name by remember { mutableStateOf(contactName ?: e.name) }
    var touched by remember { mutableStateOf(contactName == null && e.name.isNotBlank()) }
    var fromContact by remember { mutableStateOf(contactName != null) }
    var type by remember { mutableStateOf(e.type) }
    var date by remember { mutableStateOf(e.date) }
    var min by remember { mutableStateOf((e.duration / 60).toString()) }
    var sec by remember { mutableStateOf((e.duration % 60).toString()) }
    val pick = rememberContactPicker { n, nm -> number = n; name = nm; touched = true; fromContact = true }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (e.id == 0L) "New call" else "Edit call", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (e.id != 0L && number.isNotBlank()) {
                    IconButton({
                        try { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))) } catch (_: Exception) {}
                    }) { Icon(Icons.Default.Phone, "Call") }
                    TextButton({
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("number", number))
                    }) { Text("Copy") }
                }
            }
            OutlinedTextField(
                value = number,
                onValueChange = {
                    number = it
                    if (!touched) { val n = nameOf(it); name = n ?: ""; fromContact = n != null }
                },
                label = { Text("Number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                trailingIcon = { IconButton(pick) { Icon(Icons.Default.AccountCircle, "Pick contact") } }
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; touched = true; fromContact = false },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { if (fromContact) Text("From contacts") }
            )
            Text("Type", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TYPES.forEach { (t, n) -> FilterChip(type == t, { type = t }, label = { Text(n) }) }
            }
            Text("Date & Time", style = MaterialTheme.typography.labelLarge)
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
            if (type !in NO_DURATION_TYPES) {
                Text("Duration", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(min, { min = it.filter(Char::isDigit).take(5) }, label = { Text("Minutes") }, singleLine = true,
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(sec, { sec = it.filter(Char::isDigit).take(2) }, label = { Text("Seconds") }, singleLine = true,
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
            if (e.id != 0L && number.isNotBlank()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = onHistory, label = { Text("All from number") })
                    AssistChip(onClick = onDuplicate, label = { Text("Duplicate") })
                    AssistChip(onClick = onDeleteAll, label = { Text("Delete all from number") })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (e.id != 0L) {
                    TextButton(onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("Delete")
                    }
                }
                Spacer(Modifier.weight(1f))
                Button({
                    val d = if (type in NO_DURATION_TYPES) 0L
                    else (min.toLongOrNull() ?: 0) * 60 + (sec.toLongOrNull() ?: 0).coerceAtMost(59)
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
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
