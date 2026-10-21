package com.busseatalert.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.busseatalert.data.Store
import com.busseatalert.model.AlertRecord
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val WHEN = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)

/** Re-reads the store every few seconds so background checks show up without leaving the screen. */
@Composable
internal fun rememberTicker(periodMs: Long = 5000): Int {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(periodMs); tick++ } }
    return tick
}

@Composable
internal fun BottomTabs(current: Tab, store: Store, select: (Tab) -> Unit) {
    val tick = rememberTicker()
    val unread = remember(tick, current) { store.unreadAlerts }
    NavigationBar {
        NavigationBarItem(current == Tab.SEARCH, { select(Tab.SEARCH) },
            icon = { Icon(Icons.Default.Search, null) }, label = { Text(Tab.SEARCH.label) })
        NavigationBarItem(current == Tab.NOTIFICATIONS, { select(Tab.NOTIFICATIONS) },
            icon = {
                BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else "$unread") } }) {
                    Icon(Icons.Default.Notifications, null)
                }
            },
            label = { Text(Tab.NOTIFICATIONS.label) })
    }
}

/**
 * Every alert the app has sent, kept here in case the phone notification was missed or cleared.
 * Clear one by swiping it away, several by selecting them, or all at once.
 */
@Composable
internal fun NotificationsScreen(store: Store, navigate: (Screen) -> Unit, selectTab: (Tab) -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val tick = rememberTicker()
    val all = remember(tick, version) { store.alerts }
    val watchIds = remember(tick) { store.watches.map { it.id }.toSet() }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmClearAll by remember { mutableStateOf(false) }
    // Last cleared notifications, for Undo.
    var undo by remember { mutableStateOf<List<AlertRecord>>(emptyList()) }
    LaunchedEffect(undo) { if (undo.isNotEmpty()) { delay(6000); undo = emptyList() } }

    fun clear(ids: Set<String>) {
        undo = all.filter { it.id in ids }
        store.deleteAlerts(ids)
        selected = selected - ids
        version++
    }

    fun open(a: AlertRecord) {
        store.markRead(a.id); version++
        navigate(Screen.AlertDetail(listOf(a.id)))
    }

    AppScaffold(
        title = if (selecting) "${selected.size} selected" else "Notifications",
        onBack = if (selecting) ({ selecting = false; selected = emptySet() }) else null,
        actions = {
            if (selecting) {
                TextButton(onClick = { selected = if (selected.size == all.size) emptySet() else all.map { it.id }.toSet() }) {
                    Text(if (selected.size == all.size) "None" else "All", color = Color.White)
                }
                TextButton(onClick = { clear(selected); selecting = false }, enabled = selected.isNotEmpty()) {
                    Text("Clear", color = Color.White)
                }
            } else if (all.isNotEmpty()) {
                TextButton(onClick = { selecting = true }) { Text("Select", color = Color.White) }
                TextButton(onClick = { confirmClearAll = true }) { Text("Clear all", color = Color.White) }
            }
        },
        bottomBar = { BottomTabs(Tab.NOTIFICATIONS, store, selectTab) },
    ) { modifier ->
        LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column {
                    Text("Every alert is kept here, even if you cleared it from the phone. Swipe one sideways to clear it; " +
                        "long-press or tap Select to clear several.", style = MaterialTheme.typography.bodySmall)
                    if (all.any { !it.read } && !selecting) {
                        TextButton(onClick = { store.markRead(); version++ }) { Text("Mark all as read") }
                    }
                }
            }
            if (undo.isNotEmpty()) item {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF424242), RoundedCornerShape(8.dp)).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Cleared ${undo.size} notification${if (undo.size > 1) "s" else ""}", color = Color.White,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { undo.forEach(store::addAlert); undo = emptyList(); version++ }) {
                        Text("UNDO", color = Color(0xFFFFCDD2), fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (all.isEmpty()) item { Text("No notifications.", Modifier.padding(top = 16.dp)) }
            items(all, key = { it.id }) { a ->
                if (selecting) {
                    NotificationCard(a, selected = a.id in selected, selecting = true,
                        onClick = { selected = if (a.id in selected) selected - a.id else selected + a.id }, onLongClick = {})
                } else {
                    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                        if (value != SwipeToDismissBoxValue.Settled) { clear(setOf(a.id)); true } else false
                    })
                    SwipeToDismissBox(
                        state = swipe,
                        backgroundContent = {
                            Box(
                                Modifier.fillMaxSize().background(Color(0xFFE57373), RoundedCornerShape(12.dp)).padding(horizontal = 20.dp),
                                contentAlignment = if (swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd
                                else Alignment.CenterStart,
                            ) { Text("Clear", color = Color.White, fontWeight = FontWeight.Bold) }
                        },
                    ) {
                        NotificationCard(a, selected = false, selecting = false, onClick = { open(a) },
                            onLongClick = { selecting = true; selected = setOf(a.id) })
                    }
                }
            }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Clear all notifications?") },
            text = { Text("This removes all ${all.size} from this list. Your search and alerts keep working.") },
            confirmButton = { TextButton(onClick = { clear(all.map { it.id }.toSet()); confirmClearAll = false }) { Text("Clear all") } },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotificationCard(a: AlertRecord, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val unread = !a.read
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> MaterialTheme.colorScheme.primaryContainer
                unread -> a.operator.tint
                else -> MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            if (selecting) Checkbox(selected, { onClick() })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.title, fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                        color = a.operator.color, modifier = Modifier.weight(1f))
                    if (unread) Badge { Text("NEW") }
                }
                Text(a.headline, style = MaterialTheme.typography.bodyMedium)
                a.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(WHEN.format(Date(a.time)) + if (!selecting) " · tap for details" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
