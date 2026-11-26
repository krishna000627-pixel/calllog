package com.calllogeditor.app

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Returns a function that opens the phone's contact picker. */
@Composable
fun rememberContactPicker(onPicked: (number: String, name: String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val l = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (r.resultCode == Activity.RESULT_OK && uri != null) Contacts.picked(ctx, uri)?.let { onPicked(it.first, it.second) }
    }
    return { try { l.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)) } catch (_: Exception) {} }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(cmap: Map<String, String>, onBack: () -> Unit, onApplied: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var rules by remember { mutableStateOf(Rules.load(ctx)) }
    var editing by remember { mutableStateOf<Rule?>(null) }
    BackHandler(onBack = onBack)

    fun persist(l: List<Rule>) { rules = l; Rules.save(ctx, l); Auto.refresh(ctx) }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text("Rules") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    TextButton({
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { Rules.apply(ctx, rules, CallRepo.load(ctx), onlyNew = false) }
                            snack.showSnackbar(if (n == 0) "Nothing to change" else "Changed $n calls")
                            onApplied()
                        }
                    }) { Text("Apply now") }
                })
        },
        floatingActionButton = { FloatingActionButton({ editing = Rule(0, "", 3) }) { Icon(Icons.Default.Add, "Add rule") } }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Text("New calls from a number change automatically. Apply now also changes the calls already in the log.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (rules.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No rules yet") }
            else LazyColumn {
                items(rules, key = { it.id }) { r ->
                    val who = cmap[Contacts.norm(r.number)] ?: r.number
                    Row(Modifier.fillMaxWidth().clickable { editing = r }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(who, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            val res = if (r.delete) "Delete entry" else typeName(r.type) + if (r.name.isNotBlank()) " · name ${r.name}" else ""
                            Text("→ $res", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Switch(r.enabled, { on -> persist(rules.map { if (it.id == r.id) it.copy(enabled = on) else it }) })
                    }
                }
            }
        }
    }

    editing?.let { r ->
        RuleEditor(r, onDismiss = { editing = null },
            onSave = { nr ->
                persist(if (nr.id == 0L) rules + nr.copy(id = System.currentTimeMillis()) else rules.map { if (it.id == nr.id) nr else it })
                editing = null
            },
            onDelete = { persist(rules.filter { it.id != r.id }); editing = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditor(r: Rule, onDismiss: () -> Unit, onSave: (Rule) -> Unit, onDelete: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var number by remember { mutableStateOf(r.number) }
    var type by remember { mutableStateOf(r.type) }
    var del by remember { mutableStateOf(r.delete) }
    var name by remember { mutableStateOf(r.name) }
    val pick = rememberContactPicker { n, _ -> number = n }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(if (r.id == 0L) "New rule" else "Edit rule", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(number, { number = it }, label = { Text("Calls from number") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                trailingIcon = { IconButton(pick) { Icon(Icons.Default.AccountCircle, "Pick contact") } },
                supportingText = { Text("Any format: +91…, 0…, or plain 10 digits") })
            Text("Change to", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TYPES.forEach { (t, n) -> FilterChip(!del && type == t, { type = t; del = false }, label = { Text(n) }) }
                FilterChip(del, { del = true }, label = { Text("Delete entry") })
            }
            if (!del) OutlinedTextField(name, { name = it }, label = { Text("Set name (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (r.id != 0L) TextButton(onDelete) { Text("Delete rule") }
                Spacer(Modifier.weight(1f))
                Button({ onSave(r.copy(number = number.trim(), type = type, delete = del, name = if (del) "" else name.trim())) },
                    enabled = Contacts.norm(number).isNotEmpty()) { Text("Save") }
            }
        }
    }
}
