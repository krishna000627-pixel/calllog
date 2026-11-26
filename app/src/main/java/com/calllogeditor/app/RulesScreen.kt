package com.calllogeditor.app

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
fun RulesTab(cmap: Map<String, String>, onApplied: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var rules by remember { mutableStateOf(Rules.load(ctx)) }
    var editing by remember { mutableStateOf<Rule?>(null) }

    fun persist(l: List<Rule>) { rules = l; Rules.save(ctx, l); Auto.refresh(ctx) }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Rules")
                        val active = rules.count { it.enabled }
                        Text(
                            if (rules.isEmpty()) "Automate call log edits" else "$active active · ${rules.size} total",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (rules.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    val n = withContext(Dispatchers.IO) {
                                        Rules.apply(ctx, rules, CallRepo.load(ctx), onlyNew = false, cmap = cmap)
                                    }
                                    snack.showSnackbar(if (n == 0) "Nothing to change" else "Updated $n calls")
                                    onApplied()
                                }
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("Apply now") }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Rule(0, "", type = 0) }) {
                Icon(Icons.Default.Add, "Add rule")
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    "New calls matching a rule change automatically. Tap Apply now to run on existing calls.",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (rules.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No rules yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tap + to change numbers, call types, or auto-delete",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(rules, key = { it.id }) { r ->
                        RuleCard(
                            r = r,
                            cmap = cmap,
                            onClick = { editing = r },
                            onToggle = { on -> persist(rules.map { if (it.id == r.id) it.copy(enabled = on) else it }) }
                        )
                    }
                }
            }
        }
    }

    editing?.let { r ->
        RuleEditor(
            r = r,
            onDismiss = { editing = null },
            onSave = { nr ->
                persist(
                    if (nr.id == 0L) rules + nr.copy(id = System.currentTimeMillis())
                    else rules.map { if (it.id == nr.id) nr else it }
                )
                editing = null
            },
            onDelete = {
                persist(rules.filter { it.id != r.id })
                editing = null
            }
        )
    }
}

@Composable
private fun RuleCard(
    r: Rule,
    cmap: Map<String, String>,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val srcName = cmap[Contacts.norm(r.number)]
    val cardColors = if (r.enabled) {
        CardDefaults.elevatedCardColors()
    } else {
        CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    }
    ElevatedCard(onClick = onClick, colors = cardColors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = srcName ?: r.number,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (srcName != null) {
                        Text(
                            text = "From ${r.number}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(checked = r.enabled, onCheckedChange = onToggle)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (r.delete) {
                    RuleBadge("Delete entry", MaterialTheme.colorScheme.error)
                } else {
                    if (r.targetNumber.isNotBlank()) {
                        val dstName = cmap[Contacts.norm(r.targetNumber)]
                        val label = if (dstName != null) "Number → $dstName (${r.targetNumber})" else "Number → ${r.targetNumber}"
                        RuleBadge(label, MaterialTheme.colorScheme.primary)
                    }
                    if (r.type > 0) {
                        RuleBadge("Type → ${typeName(r.type)}", typeColor(r.type))
                    }
                    if (r.name.isNotBlank()) {
                        RuleBadge("Name → ${r.name}", MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleBadge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditor(r: Rule, onDismiss: () -> Unit, onSave: (Rule) -> Unit, onDelete: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var number by remember { mutableStateOf(r.number) }
    var targetNumber by remember { mutableStateOf(r.targetNumber) }
    var type by remember { mutableStateOf(r.type) }
    var del by remember { mutableStateOf(r.delete) }
    var name by remember { mutableStateOf(r.name) }
    val pickSrc = rememberContactPicker { n, _ -> number = n }
    val pickDst = rememberContactPicker { n, _ -> targetNumber = n }

    val hasAction = del || targetNumber.isNotBlank() || type > 0 || name.isNotBlank()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(if (r.id == 0L) "New rule" else "Edit rule", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text("Calls from number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                trailingIcon = { IconButton(pickSrc) { Icon(Icons.Default.AccountCircle, "Pick contact") } },
                supportingText = { Text("Matches any format: +91…, 0…, or 10 digits") }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!del, { del = false }, label = { Text("Modify call") })
                FilterChip(del, { del = true }, label = { Text("Delete entry") })
            }

            if (del) {
                Text(
                    "Matching calls will be deleted from the call log automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                OutlinedTextField(
                    value = targetNumber,
                    onValueChange = { targetNumber = it },
                    label = { Text("Change number to (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    trailingIcon = { IconButton(pickDst) { Icon(Icons.Default.AccountCircle, "Pick contact") } },
                    supportingText = { Text("Leave blank to keep original number") }
                )

                Text("Change type (optional)", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(type == 0, { type = 0 }, label = { Text("Keep original") })
                    TYPES.forEach { (t, n) ->
                        FilterChip(type == t, { type = t }, label = { Text(n) })
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Set name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(
                            if (targetNumber.isNotBlank()) "Leave blank to use the new number's contact name"
                            else "Leave blank to keep current name"
                        )
                    }
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (r.id != 0L) {
                    TextButton(onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("Delete rule")
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        onSave(
                            r.copy(
                                number = number.trim(),
                                targetNumber = if (del) "" else targetNumber.trim(),
                                type = if (del) 0 else type,
                                delete = del,
                                name = if (del) "" else name.trim()
                            )
                        )
                    },
                    enabled = Contacts.norm(number).isNotEmpty() && hasAction
                ) { Text("Save") }
            }
        }
    }
}
