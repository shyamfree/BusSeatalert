package com.busseatalert.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.busseatalert.model.Operator

private data class Topic(val title: String, val body: String)

private val TOPICS = listOf(
    Topic(
        "What this app does",
        "Bus Seat Alert watches government bus seats on Kerala RTC (onlineksrtcswift.com), Karnataka RTC (ksrtc.in) " +
            "and TNSTC (tnstc.in) for your route and dates. It checks the sites in the background and tells you when " +
            "seats become available, when a full bus gets a cancellation, or when a new bus is added.\n\n" +
            "It only reads the public booking sites. It never books or holds seats; you book on the official site.",
    ),
    Topic(
        "1. Search",
        "• Tick the sites to search: Kerala RTC, Karnataka RTC, TNSTC. Only ticked sites are checked.\n" +
            "• Type From and To. After 3 letters, every matching place name from the ticked sites appears, grouped by " +
            "site. Tick the stops you want on each site; you can tick several (e.g. two Chennai bus stands) and the " +
            "app searches all of them and merges the buses.\n" +
            "• Below each box, every site shows what it will search, in its colour. A faded line means nothing was " +
            "ticked for that site, so it tries what you typed (the app knows common spellings like Palghat/Palakkad).\n" +
            "• ⇅ Swap reverses the journey.\n" +
            "• Add one or more journey dates. Only these dates are checked; past dates drop off by themselves.\n" +
            "• Press Search. The results appear below. For a different journey, change From/To and press Search again.",
    ),
    Topic(
        "2. More options",
        "• Price range and seats needed: buses outside these don't trigger alerts.\n" +
            "• Bus types per site: pick e.g. Swift Garuda or Airavat. Leave as Any type to accept all.\n" +
            "• Alert me when: a new bus appears, and/or seats open up on an existing bus.\n" +
            "• Send alerts by: push notification, SMS, email (SMS and email need setting up in Settings ⚙).",
    ),
    Topic(
        "3. Results and filters",
        "• Each date has a tab. The red bar shows the route and date; below it, when it was checked and the exact " +
            "names each site searched.\n" +
            "• Bus cards are coloured by site: Kerala red, Karnataka blue, TNSTC green. They show the bus type, " +
            "departure and arrival with dates, stops, duration, fare and free seats (green = plenty, amber = few, " +
            "grey FULL = none).\n" +
            "• Kerala RTC opens seats from a stop along a long route only 24 hours before the bus leaves. Such " +
            "buses show \"Booking opens …\" in amber; you can still view their seats, and alerts mention it.\n" +
            "• 'Leaves after midnight' marks buses that leave early in the morning of the journey date, i.e. the " +
            "night before.\n" +
            "• Filters & sort: by default you see all buses with free seats, cheapest first. Change site, seats, " +
            "departure time, bus type, max fare or sort order; Reset returns to the default.",
    ),
    Topic(
        "Best pick",
        "At the top of the results, the app suggests the best bus among those with seats.\n\n" +
            "• What matters most: Balanced, Cheapest, Most comfortable or Fastest.\n" +
            "• Seat I'd like: Any, Window, Aisle, Lower deck or Upper deck. Buses with that kind of seat free " +
            "rank higher.\n" +
            "• Comfort is judged from the bus type: AC sleepers (Ambaari, Gajaraj, Volvo SLX) highest, then AC " +
            "semi-sleepers and seaters (Airavat, Swift Garuda), non-AC sleepers, deluxe, express and ordinary " +
            "buses. A sleeper counts extra on long overnight trips.\n" +
            "• Seat positions come from each bus's seat map; window and aisle counts leave out seats reserved " +
            "for women.\n" +
            "• 'Why' lists the reasons, and Compare shows the top 5. Tap a row to see that bus above, with Seats " +
            "and Book.\n\n" +
            "The suggestion only looks at the buses on screen, so it follows your filters.",
    ),
    Topic(
        "4. Seat map",
        "Tap Seats ▾ on a bus to open its live seat map inside the card (view only); tap again to close.\n\n" +
            "• White with green border: available\n" +
            "• Pink ♀: available, ladies only\n" +
            "• Pale pink ♀: booked by a woman / booked ladies seat\n" +
            "• Light blue ♿: for differently-abled passengers\n" +
            "• Grey: booked · Dark grey: crew seat\n\n" +
            "Tap a seat to see what it is. If the live map differs from the last check, an orange note explains it: " +
            "seats change every minute as people book and cancel.",
    ),
    Topic(
        "5. Alerts and Notifications",
        "• Search only shows results. To be told about changes, press 🔔 Set alert for this search under the " +
            "results once the search is how you want it. A quiet \"Alert set\" notification shows the current seats.\n" +
            "• From then on the app checks that search every 15 minutes or more (Settings ⚙) and notifies you only " +
            "when something changes: seats free up (e.g. a cancellation), more seats appear, or a new bus with " +
            "seats is added. That alert pops up with sound, and also goes by SMS and email if you turned those on.\n" +
            "• When nothing changed, the phone notification is quietly updated to \"No change since last check\" " +
            "(no sound, no SMS or email). Turn this off in More options → \"Show No change after each check\". " +
            "An alert you haven't opened yet stays mentioned at the top of it.\n" +
            "• Changing the search (route, dates, sites, filters) turns its alert off and clears the old phone " +
            "notification; set the alert again for the new search.\n" +
            "• The green bar at the top is the main switch: OFF stops all background checks and notifications.\n" +
            "• Every change alert is also kept in the Notifications tab, in case you missed or cleared the phone " +
            "notification. Swipe one sideways to clear it, long-press or tap Select to clear several, or Clear all.",
    ),
    Topic(
        "6. Settings ⚙",
        "• App lock: asks for your fingerprint or phone PIN when opening the app, but not if you unlocked " +
            "your phone in the last 5 minutes (that already proves it's you). It uses your phone's own screen " +
            "lock; notifications still arrive while locked.\n" +
            "• Check interval (15 minutes minimum).\n" +
            "• Push notifications on/off, with a test button.\n" +
            "• SMS: sent from this phone's SIM to the number you enter; normal SMS charges apply. Android blocks the " +
            "SMS permission for apps installed outside the Play Store: if the status shows \"not allowed\", tap " +
            "Help for the steps. Some phones (e.g. Motorola on Android 17) still refuse direct sending; then each " +
            "alert comes with a \"Send SMS\" button that opens Messages with the text ready, so one tap sends it.\n" +
            "• WhatsApp: free alerts to your own WhatsApp through the CallMeBot service. Tap \"How to get the free " +
            "key\", message CallMeBot once from WhatsApp, then enter your number (with country code) and the key it " +
            "sends you, and tap Test WhatsApp.\n" +
            "• Email: uses your Gmail with an App Password (not your normal password). Turn on 2-Step Verification, " +
            "then create one at myaccount.google.com/apppasswords. Each channel has its own test button that shows " +
            "exactly what went wrong if it fails.",
    ),
    Topic(
        "How seats are counted",
        "For buses that match your search, the app opens each bus's seat map and counts seats you can actually " +
            "select, the same map you see on the site. This is more accurate than the sites' list numbers " +
            "(Karnataka's list is often a few seats off, and TNSTC's count includes seats reserved for " +
            "differently-abled passengers).",
    ),
    Topic(
        "Good to know",
        "• The sites can change their pages at any time; if a site stops working, its error shows in the results.\n" +
            "• Checks are kept gentle so the sites don't block you. Ticking many stops means more requests per check.\n" +
            "• A seat shown free can be booked by someone else before you reach the site. Book quickly.\n" +
            "• Everything stays on your phone. Nothing is sent anywhere except the bus sites, and your own SMS/email " +
            "alerts.",
    ),
)

/** User manual: what the app does and how to use each part. */
@Composable
internal fun GuideScreen(back: () -> Unit) {
    AppScaffold(title = "How it works", onBack = back) { modifier ->
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Operator.entries.forEach { Text(it.label, color = it.color, fontWeight = FontWeight.Bold) }
            }
            TOPICS.forEachIndexed { i, topic -> TopicCard(topic, startOpen = i == 0) }
            Spacer(Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun TopicCard(topic: Topic, startOpen: Boolean) {
    var open by remember { mutableStateOf(startOpen) }
    Card(Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(topic.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(if (open) "▲" else "▼", color = MaterialTheme.colorScheme.outline)
            }
            if (open) Text(topic.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
