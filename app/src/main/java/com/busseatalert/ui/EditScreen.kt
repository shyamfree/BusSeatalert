package com.busseatalert.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.busseatalert.check.CheckWorker
import com.busseatalert.data.Store
import com.busseatalert.model.Operator
import com.busseatalert.model.Watch
import com.busseatalert.notify.Notifier
import com.busseatalert.providers.Providers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

@Composable
internal fun EditScreen(existing: Watch?, store: Store, onDeleted: () -> Unit, done: () -> Unit) {
    val context = LocalContext.current
    val today = LocalDate.now().toString()
    val scope = rememberCoroutineScope()
    val watchId = remember { existing?.id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var notifyPush by remember { mutableStateOf(existing?.notifyPush ?: true) }
    var notifySms by remember { mutableStateOf(existing?.notifySms ?: true) }
    var notifyEmail by remember { mutableStateOf(existing?.notifyEmail ?: true) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf(existing?.from ?: "") }
    var to by remember { mutableStateOf(existing?.to ?: "") }
    var dates by remember {
        mutableStateOf(existing?.journeyDates()?.filter { it >= today } ?: listOf(LocalDate.now().plusDays(1).toString()))
    }
    var operators by remember { mutableStateOf(existing?.operators ?: Operator.entries.toSet()) }
    var fromBySite by remember { mutableStateOf(existing?.fromBySite ?: emptyMap()) }
    var toBySite by remember { mutableStateOf(existing?.toBySite ?: emptyMap()) }
    var typesBySite by remember { mutableStateOf(existing?.busTypesBySite ?: emptyMap()) }
    var minPrice by remember { mutableStateOf(existing?.minPrice?.toString() ?: "") }
    var maxPrice by remember { mutableStateOf(existing?.maxPrice?.toString() ?: "") }
    var busType by remember { mutableStateOf(existing?.busTypeKeywords ?: "") }
    var minSeats by remember { mutableStateOf((existing?.minSeats ?: 1).toString()) }
    var alertNew by remember { mutableStateOf(existing?.alertNewBus ?: true) }
    var alertFreed by remember { mutableStateOf(existing?.alertSeatsFreed ?: true) }

    fun current() = Watch(
        id = watchId,
        from = from.trim(), to = to.trim(), name = name.trim(),
        notifyPush = notifyPush, notifySms = notifySms, notifyEmail = notifyEmail,
        dates = dates, operators = operators,
        fromBySite = fromBySite.mapValues { it.value.trim() }.filterValues { it.isNotBlank() },
        toBySite = toBySite.mapValues { it.value.trim() }.filterValues { it.isNotBlank() },
        busTypesBySite = typesBySite.filterKeys { it in operators }.filterValues { it.isNotEmpty() },
        minPrice = minPrice.toIntOrNull(), maxPrice = maxPrice.toIntOrNull(),
        busTypeKeywords = busType.trim(), minSeats = minSeats.toIntOrNull()?.coerceAtLeast(1) ?: 1,
        alertNewBus = alertNew, alertSeatsFreed = alertFreed, enabled = existing?.enabled ?: true,
    )

    fun pickDate() {
        val d = LocalDate.now().plusDays(1)
        DatePickerDialog(context, { _, y, m, day ->
            val picked = LocalDate.of(y, m + 1, day).toString()
            if (picked < today) Toast.makeText(context, "That date has passed", Toast.LENGTH_SHORT).show()
            else dates = (dates + picked).distinct().sorted()
        }, d.year, d.monthValue - 1, d.dayOfMonth).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    AppScaffold(title = if (existing == null) "New watch" else "Edit watch", onBack = done) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") },
                placeholder = { Text("e.g. Diwali trip home") }, singleLine = true,
                supportingText = { Text("Shown on this search's notifications") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(from, { from = it }, label = { Text("From") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(to, { to = it }, label = { Text("To") }, modifier = Modifier.fillMaxWidth())

            Text("Journey dates", style = MaterialTheme.typography.titleSmall)
            Text("Only these dates are checked. Add one or more.", style = MaterialTheme.typography.bodySmall)
            DateChips(dates, onRemove = { d -> dates = dates - d })
            OutlinedButton(onClick = ::pickDate, modifier = Modifier.fillMaxWidth()) { Text("+ Add date") }

            Text("Operators", style = MaterialTheme.typography.titleSmall)
            Operator.entries.forEach { op ->
                CheckRow(op.label, op in operators) { operators = if (it) operators + op else operators - op }
            }

            Text("Each site: place names and bus types", style = MaterialTheme.typography.titleSmall)
            Text(
                "Sites spell places differently (TNSTC: PALGHAT, Kerala RTC: Palakkad). Leave blank to use From/To, " +
                    "or tap Find to pick the exact name each site uses. Pick bus types per site, or leave as Any type.",
                style = MaterialTheme.typography.bodySmall,
            )
            Operator.entries.filter { it in operators }.forEach { op ->
                SitePlaces(
                    op, from, to,
                    siteFrom = fromBySite[op].orEmpty(), siteTo = toBySite[op].orEmpty(),
                    onFrom = { fromBySite = fromBySite + (op to it) },
                    onTo = { toBySite = toBySite + (op to it) },
                    dates = dates,
                    selectedTypes = typesBySite[op].orEmpty(),
                    onTypes = { typesBySite = typesBySite + (op to it) },
                )
            }

            Text("Filters", style = MaterialTheme.typography.titleSmall)
            Row {
                OutlinedTextField(minPrice, { minPrice = it.filter(Char::isDigit) }, label = { Text("Min ₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(maxPrice, { maxPrice = it.filter(Char::isDigit) }, label = { Text("Max ₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
            OutlinedTextField(busType, { busType = it }, label = { Text("Extra bus type keywords, all sites (optional)") },
                supportingText = { Text("Comma separated, e.g. Sleeper, AC. Usually the per-site bus types above are enough.") },
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(minSeats, { minSeats = it.filter(Char::isDigit) }, label = { Text("Seats needed") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())

            Text("Alert me when", style = MaterialTheme.typography.titleSmall)
            CheckRow("A newly added bus appears on this route", alertNew) { alertNew = it }
            CheckRow("Seats open up on an existing bus (cancellations)", alertFreed) { alertFreed = it }

            Text("Notifications for this search", style = MaterialTheme.typography.titleSmall)
            val settings = remember { store.settings }
            CheckRow("Push notification" + if (!settings.pushEnabled) " (off in Settings)" else "", notifyPush) { notifyPush = it }
            CheckRow("SMS" + if (!settings.smsEnabled) " (not set up in Settings)" else "", notifySms) { notifySms = it }
            CheckRow("Email" + if (!settings.emailEnabled) " (not set up in Settings)" else "", notifyEmail) { notifyEmail = it }
            Text("Every alert also appears in the Alerts tab, whatever is ticked here.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = {
                    // Android lets each channel have its own sound, vibration and importance.
                    val channel = Notifier.ensureWatchChannel(context, current())
                    context.startActivity(
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, channel),
                    )
                },
                enabled = from.isNotBlank() && to.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sound & vibration for this search") }
            OutlinedButton(
                onClick = {
                    testing = true
                    scope.launch {
                        testResult = Notifier(context, store.settings).testSearch(current())
                        testing = false
                    }
                },
                enabled = !testing && from.isNotBlank() && to.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (testing) "Sending…" else "Send a test for this search") }
            testResult?.let {
                AlertDialog(
                    onDismissRequest = { testResult = null },
                    confirmButton = { TextButton(onClick = { testResult = null }) { Text("OK") } },
                    title = { Text("Test for ${current().displayName}") },
                    text = { Text(it) },
                )
            }

            Button(
                onClick = {
                    when {
                        from.isBlank() || to.isBlank() -> "Fill in From and To"
                        operators.isEmpty() -> "Pick at least one operator"
                        dates.isEmpty() -> "Add at least one journey date"
                        else -> null
                    }?.let {
                        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val saved = current()
                    store.upsertWatch(saved)
                    // Check straight away so the Searches tab shows results for the new settings.
                    if (saved.enabled) CheckWorker.runNow(context, saved.id)
                    done()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            if (existing != null) {
                TextButton(onClick = {
                    store.deleteWatch(existing.id)
                    Notifier.deleteWatchChannel(context, existing.id)
                    onDeleted()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete watch", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.padding(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DateChips(dates: List<String>, onRemove: (String) -> Unit) {
    if (dates.isEmpty()) {
        Text("No dates yet", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        dates.forEach { d ->
            InputChip(
                selected = true,
                onClick = { onRemove(d) },
                label = { Text(shortDate(d)) },
                trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) },
            )
        }
    }
}

/** From/To overrides for one site, with a lookup of the names that site actually uses. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SitePlaces(
    op: Operator, from: String, to: String,
    siteFrom: String, siteTo: String,
    onFrom: (String) -> Unit, onTo: (String) -> Unit,
    dates: List<String>,
    selectedTypes: Set<String>,
    onTypes: (Set<String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var fromHits by remember { mutableStateOf<List<String>?>(null) }
    var toHits by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(op.label, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(siteFrom, onFrom, label = { Text("From on ${op.label}") },
                placeholder = { Text(from.ifBlank { "Same as From" }) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(siteTo, onTo, label = { Text("To on ${op.label}") },
                placeholder = { Text(to.ifBlank { "Same as To" }) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(
                onClick = {
                    loading = true; error = null
                    scope.launch {
                        val provider = Providers.get(op)
                        runCatching {
                            fromHits = provider.suggestPlaces(siteFrom.ifBlank { from }, isFrom = true)
                            toHits = provider.suggestPlaces(siteTo.ifBlank { to }, isFrom = false)
                        }.onFailure { error = "Couldn't reach ${op.label}: ${it.message}" }
                        loading = false
                    }
                },
                enabled = !loading && (siteFrom.ifBlank { from }.length >= 3 || siteTo.ifBlank { to }.length >= 3),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Find names on ${op.label}")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            listOf(Triple("From", fromHits, onFrom), Triple("To", toHits, onTo)).forEach { (label, hits, pick) ->
                if (hits == null) return@forEach
                Text(if (hits.isEmpty()) "$label: no matching names on ${op.label}" else "$label: tap the right one",
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    hits.forEach { name -> AssistChip(onClick = { pick(name) }, label = { Text(name) }) }
                }
            }
            BusTypePicker(op, siteFrom.ifBlank { from }, siteTo.ifBlank { to }, dates, selectedTypes, onTypes)
        }
    }
}

/** Multi-select dropdown of the site's bus types, with an option to load the types on this route. */
@Composable
internal fun BusTypePicker(
    op: Operator, from: String, to: String, dates: List<String>,
    selected: Set<String>, onChange: (Set<String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var routeTypes by remember { mutableStateOf<List<String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var noneOnRoute by remember { mutableStateOf(false) }
    // Route-specific types first when loaded; keep anything already picked visible.
    val options = ((routeTypes ?: op.knownBusTypes) + selected).distinct()

    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                when (selected.size) {
                    0 -> "Bus types: Any type ▾"
                    1 -> "Bus type: ${selected.first()} ▾"
                    else -> "Bus types: ${selected.size} selected ▾"
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Any type", fontWeight = FontWeight.SemiBold) },
                leadingIcon = { Checkbox(selected.isEmpty(), null) },
                onClick = { onChange(emptySet()) },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        when {
                            loading -> "Loading types on this route…"
                            routeTypes != null -> "Showing types on this route (${routeTypes!!.size})"
                            else -> "Show only types on this route"
                        },
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                enabled = !loading && routeTypes == null && from.isNotBlank() && to.isNotBlank(),
                onClick = {
                    loading = true; error = null
                    scope.launch {
                        runCatching { Providers.get(op).busTypes(from, to, dates) }
                            .onSuccess { if (it.isEmpty()) noneOnRoute = true else routeTypes = it }
                            .onFailure { error = "Couldn't load types from ${op.label}: ${it.message}" }
                        loading = false
                    }
                },
            )
            HorizontalDivider()
            options.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type) },
                    leadingIcon = { Checkbox(type in selected, null) },
                    onClick = { onChange(if (type in selected) selected - type else selected + type) },
                )
            }
        }
    }
    if (noneOnRoute) {
        Text("No buses found on this route for the chosen dates, so the full list is shown.",
            style = MaterialTheme.typography.bodySmall)
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (selected.isNotEmpty()) {
        Text(selected.joinToString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}
