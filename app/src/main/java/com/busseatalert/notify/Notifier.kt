package com.busseatalert.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.busseatalert.R
import com.busseatalert.data.Store
import com.busseatalert.check.DateStatus
import com.busseatalert.model.AlertBus
import com.busseatalert.model.AlertRecord
import com.busseatalert.model.AppSettings
import com.busseatalert.model.BusService
import com.busseatalert.model.LogKind
import com.busseatalert.model.Operator
import com.busseatalert.model.Watch
import com.busseatalert.ui.MainActivity
import com.busseatalert.ui.shortDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

data class Alert(
    val watch: Watch,
    val date: String,
    val bus: BusService,
    val kind: Kind,
    val previousSeats: Int? = null,
) {
    enum class Kind { AVAILABLE, NEW_BUS, SEATS_FREED }

    /** One short line per bus, e.g. "Thu 22 Oct 23:25 AC SEATER: 25 seats (was full)". */
    val line: String get() = buildString {
        append("${shortDate(date)} ${bus.departure} ${bus.busType}: ${bus.seatsAvailable} seats")
        when {
            kind == Kind.NEW_BUS -> append(" (new bus)")
            previousSeats != null -> append(" (was ${if (previousSeats <= 0) "full" else previousSeats})")
        }
    }

    companion object {
        /** Summary for a group of alerts from one site, e.g. "2 buses with seats opened up, 1 new bus". */
        fun headline(alerts: List<Alert>): String {
            val counts = alerts.groupingBy { it.kind }.eachCount()
            return listOfNotNull(
                counts[Kind.SEATS_FREED]?.let { "$it bus${if (it > 1) "es" else ""} with seats opened up" },
                counts[Kind.NEW_BUS]?.let { "$it new bus${if (it > 1) "es" else ""}" },
                counts[Kind.AVAILABLE]?.let { "$it bus${if (it > 1) "es" else ""} with seats" },
            ).joinToString(", ")
        }
    }
}

/**
 * Sends one push / SMS / email per site per watch, summarising that site's alerts.
 * Full bus details stay in the app.
 */
class Notifier(private val context: Context, private val settings: AppSettings) {
    private val store = Store(context)

    private data class Group(val watch: Watch, val operator: Operator, val alerts: List<Alert>) {
        val title get() = "${watch.displayName} · ${operator.label}"
        val headline get() = Alert.headline(alerts)
        val lines get() = alerts.sortedWith(compareBy({ it.date }, { it.bus.departure })).map { it.line }
    }

    suspend fun send(alerts: List<Alert>) {
        if (!store.appEnabled) return
        val groups = alerts.groupBy { it.watch.id to it.bus.operator }
            .map { (key, list) -> Group(list.first().watch, key.second, list) }
            .sortedBy { it.operator.ordinal }
        // The in-app Notifications list keeps one entry per site, so older alerts stay there.
        val recordIds = groups.associateWith { g ->
            val id = java.util.UUID.randomUUID().toString()
            store.addAlert(
                AlertRecord(id, System.currentTimeMillis(), g.watch.id, g.operator, g.title, g.headline, g.lines,
                    buses = g.alerts.map { AlertBus(it.date, it.bus.key) }),
            )
            id
        }
        // Phone, SMS, WhatsApp and email get a single combined message per check.
        // SMS goes first: if the phone refuses it, the push gets a "Send SMS" button instead.
        val smsGroups = groups.filter { it.watch.notifySms }
        var smsForButton: String? = null
        if (settings.smsEnabled && smsGroups.isNotEmpty()) {
            val text = shortText(smsGroups)
            runCatching { sms(text); store.smsDirectBlocked = false }.onFailure {
                logFailure("SMS", it)
                if (generateSequence(it) { e -> e.cause }.any { e -> e is SecurityException }) {
                    store.smsDirectBlocked = true
                    smsForButton = text
                }
            }
        }
        val pushGroups = groups.filter { it.watch.notifyPush }
        if (settings.pushEnabled && pushGroups.isNotEmpty()) {
            runCatching { push(pushGroups, pushGroups.mapNotNull { recordIds[it] }, smsForButton) }
                .onFailure { logFailure("Push notification", it) }
        }
        val waGroups = groups.filter { it.watch.notifyWhatsapp }
        if (settings.whatsappEnabled && waGroups.isNotEmpty()) {
            runCatching { whatsapp(shortText(waGroups, maxLines = 8)) }.onFailure { logFailure("WhatsApp", it) }
        }
        val emailGroups = groups.filter { it.watch.notifyEmail }
        if (settings.emailEnabled && emailGroups.isNotEmpty()) {
            val body = emailGroups.joinToString("\n\n") { g -> "${g.operator.label}: ${g.headline}\n${g.lines.joinToString("\n")}" } +
                "\n\nOpen Bus Seat Alert for full details and seat maps."
            runCatching { email("Bus Seat Alert: ${summary(emailGroups)}", body) }.onFailure { logFailure("Email", it) }
        }
    }

    /** Plain-text alert for SMS and WhatsApp. */
    private fun shortText(groups: List<Group>, maxLines: Int = 4): String =
        "Bus Seat Alert: ${summary(groups)}\n" + groups.flatMap { g -> g.lines.map { "${g.operator.label} $it" } }
            .let { lines -> lines.take(maxLines).joinToString("\n") + if (lines.size > maxLines) "\n+${lines.size - maxLines} more in the app" else "" }

    /**
     * Sends a WhatsApp message to the user's own number through CallMeBot's free API
     * (https://api.callmebot.com/whatsapp.php?phone=&text=&apikey=). The user gets the key once by
     * messaging CallMeBot from WhatsApp.
     */
    private suspend fun whatsapp(text: String) = withContext(Dispatchers.IO) {
        val phone = settings.whatsappPhone.filter { it.isDigit() || it == '+' }
        check(phone.length >= 10) { "Enter your WhatsApp number with country code, e.g. +919876543210" }
        check(settings.whatsappApiKey.isNotBlank()) { "Enter the CallMeBot API key (see \"How to get the free key\")" }
        val url = "https://api.callmebot.com/whatsapp.php".toHttpUrl().newBuilder()
            .addQueryParameter("phone", if (phone.startsWith("+")) phone else "+$phone")
            .addQueryParameter("text", text)
            .addQueryParameter("apikey", settings.whatsappApiKey.trim())
            .build()
        com.busseatalert.providers.Providers.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val plain = body.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
            // CallMeBot answers 200 with a page; success says the message was queued or sent.
            check(r.isSuccessful && Regex("queued|sent", RegexOption.IGNORE_CASE).containsMatchIn(plain)) {
                "CallMeBot: " + plain.take(200).ifBlank { "HTTP ${r.code}" }
            }
        }
    }

    suspend fun testWhatsapp(): String {
        runCatching { whatsapp("Bus Seat Alert test: WhatsApp alerts are working.") }.getOrElse { throw IllegalStateException(explain(it)) }
        return "Test sent to WhatsApp ${settings.whatsappPhone}. It can take up to a minute to arrive."
    }

    /** "3 buses with seats · Palghat → Bengaluru" for a set of site groups. */
    private fun summary(groups: List<Group>): String {
        val buses = groups.sumOf { it.alerts.size }
        return "$buses bus${if (buses > 1) "es" else ""} with seats · ${groups.first().watch.displayName}"
    }

    private fun logFailure(channel: String, error: Throwable) =
        store.addLog("$channel failed", LogKind.ERROR, explain(error))

    /**
     * Posts the single phone notification. It always uses the same id, so a new alert replaces
     * the previous one (the history stays in the app's Notifications tab). It still pops up and
     * sounds each time, and is styled to stand out: red bus icon, bold title, each bus line in its
     * site's colour.
     */
    private fun push(groups: List<Group>, recordIds: List<String> = emptyList(), smsText: String? = null) {
        val watch = groups.first().watch
        val channel = ensureWatchChannel(context, watch)
        val manager = NotificationManagerCompat.from(context)
        check(manager.areNotificationsEnabled()) {
            "Notifications are turned off for this app. Enable them in Android Settings → Apps → Bus Seat Alert → Notifications."
        }
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_WATCH_ID, watch.id)
                .putExtra(MainActivity.EXTRA_FROM_ALERT, true)
                .putExtra(MainActivity.EXTRA_ALERT_IDS, recordIds.toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val kinds = groups.flatMap { it.alerts }.map { it.kind }.toSet()
        val title = SpannableString(
            when {
                Alert.Kind.SEATS_FREED in kinds -> "🚌 SEATS OPENED UP!"
                Alert.Kind.NEW_BUS in kinds && kinds.size == 1 -> "🚌 NEW BUS ADDED!"
                else -> "🚌 SEATS AVAILABLE!"
            },
        ).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, 0) }

        // Expanded view: wrapped text (an inbox list cuts every line off at the screen edge).
        // One coloured, bold site label per bus: Kerala red, Karnataka blue, TNSTC green.
        val lines = groups.flatMap { g -> g.lines.map { g.operator to it } }
        val body = SpannableStringBuilder().apply {
            append(summary(groups))
            lines.take(MAX_LINES).forEach { (op, line) -> append("\n").append(colored(op, line)) }
            if (lines.size > MAX_LINES) append("\n+${lines.size - MAX_LINES} more in the app")
        }
        val style = NotificationCompat.BigTextStyle()
            .setBigContentTitle(title)
            .bigText(body)
            .setSummaryText("tap to see seats")

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_bus)
            .setLargeIcon(busBadge())
            .setContentTitle(title)
            .setContentText(summary(groups))
            .setSubText(groups.joinToString(" · ") { it.operator.label })
            .setStyle(style)
            .setColor(KSRTC_RED)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOnlyAlertOnce(false) // replacing still pops up and sounds
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .apply {
                // The phone blocks direct SMS: one tap opens Messages with the alert ready to send.
                if (smsText != null) addAction(0, "Send SMS", PendingIntent.getActivity(
                    context, NOTIFICATION_ID + 1,
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${settings.smsNumber}")).putExtra("sms_body", smsText)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ))
            }
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            throw IllegalStateException("Notification permission not granted", e)
        }
    }

    /**
     * After a check that found nothing new: quietly updates the single phone notification (no sound,
     * no pop-up) to say so, with the current status. Never sent by SMS or email, and not added to
     * the Notifications tab. If a change alert is still unread, it stays mentioned at the top and
     * tapping opens it, so a quiet update never hides a change the user hasn't seen.
     */
    fun sendNoChange(watch: Watch, statuses: List<DateStatus>) {
        if (!store.appEnabled || !settings.pushEnabled || !watch.notifyPush || !watch.notifyNoChange || statuses.isEmpty()) return
        val title = if (statuses.sumOf { it.withSeats.size } == 0) "No seats yet · no change" else "✓ No change since last check"
        runCatching { pushStatus(watch, statuses, title) }.onFailure { logFailure("Push notification", it) }
    }

    /** Right after the user taps "Set alert": a quiet notification with what the search shows now. */
    fun sendAlertSet(watch: Watch, statuses: List<DateStatus>) {
        if (!store.appEnabled || !settings.pushEnabled || !watch.notifyPush || statuses.isEmpty()) return
        runCatching { pushStatus(watch, statuses, "🔔 Alert set · watching for changes") }.onFailure { logFailure("Push notification", it) }
    }

    private fun pushStatus(watch: Watch, statuses: List<DateStatus>, title: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val channel = ensureWatchChannel(context, watch)
        val unread = store.alerts.filter { it.watchId == watch.id && !it.read }
        // Alerts from the same check are saved together; open all of them.
        val unreadBatch = unread.firstOrNull()?.let { latest -> unread.filter { latest.time - it.time < 60_000 } }.orEmpty()
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_WATCH_ID, watch.id)
                .apply {
                    if (unreadBatch.isNotEmpty()) {
                        putExtra(MainActivity.EXTRA_FROM_ALERT, true)
                        putExtra(MainActivity.EXTRA_ALERT_IDS, unreadBatch.map { it.id }.toTypedArray())
                    }
                }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val withSeats = statuses.sumOf { it.withSeats.size }
        val at = java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH).format(java.util.Date())
        val body = SpannableStringBuilder().append("${watch.displayName} · checked $at")
        unreadBatch.firstOrNull()?.let { a ->
            val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(a.time))
            body.append("\n").append(SpannableString("🔔 Unread alert from $time: ${a.headline}").apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, length, 0)
                setSpan(ForegroundColorSpan(KSRTC_RED), 0, length, 0)
            })
        }
        statuses.forEach { st ->
            body.append("\n").append(SpannableString(
                "${com.busseatalert.ui.shortDate(st.date)}: ${st.withSeats.size} of ${st.matching} with seats",
            ).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, 0) })
            // The cheapest few buses with seats, in full.
            st.withSeats.sortedBy { it.fare }.take(3).forEach { b ->
                body.append("\n").append(colored(b.operator, "${b.departure} ${b.busType} · ₹${b.fare} · ${b.seatsAvailable} seats"))
            }
            if (st.withSeats.size > 3) body.append("\n+${st.withSeats.size - 3} more in the app")
            if (st.failedSites.isNotEmpty()) body.append("\n⚠ ${st.failedSites.joinToString()} didn't load this time")
        }
        val style = NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(body).setSummaryText("tap to open")
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle(title)
            .setContentText("${watch.displayName} · $withSeats bus${if (withSeats == 1) "" else "es"} with seats · checked $at")
            .setStyle(style)
            .setColor(KSRTC_RED)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true) // no sound, vibration or pop-up for "nothing changed"
            .setShowWhen(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            throw IllegalStateException("Notification permission not granted", e)
        }
    }

    private fun colored(op: Operator, line: String): CharSequence = SpannableStringBuilder().apply {
        val label = "${op.label}  "
        append(label)
        setSpan(ForegroundColorSpan(SITE_COLORS.getValue(op)), 0, label.length, 0)
        setSpan(StyleSpan(Typeface.BOLD), 0, label.length, 0)
        append(line)
    }

    /** The bus icon, white on a red circle, for the notification's large icon. */
    private fun busBadge(): Bitmap {
        val size = (64 * context.resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KSRTC_RED })
        ContextCompat.getDrawable(context, R.drawable.ic_bus)?.mutate()?.let { icon ->
            icon.setTint(android.graphics.Color.WHITE)
            val pad = size / 5
            icon.setBounds(pad, pad, size - pad, size - pad)
            icon.draw(canvas)
        }
        return bitmap
    }

    /**
     * Sends [text] by SMS through the phone's default SMS SIM. With [waitForResult], waits up to 30 s
     * for the phone's own sent report and returns null when sent, or why it wasn't.
     */
    private suspend fun sms(text: String, waitForResult: Boolean = false): String? {
        check(settings.smsNumber.isNotBlank()) { "No phone number set for SMS" }
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            "SMS permission not allowed. Android blocks it for apps installed outside the Play Store: open App info → ⋮ → " +
                "Allow restricted settings, then Permissions → SMS → Allow. (Settings → SMS → Help shows the steps.)"
        }
        // Dual-SIM phones: send through the SIM chosen for SMS, not whatever the system picks.
        val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
        val base = context.getSystemService(SmsManager::class.java)
        val manager = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(subId) else base
        val parts = manager.divideMessage(text)
        if (!waitForResult) {
            manager.sendMultipartTextMessage(settings.smsNumber, null, parts, null, null)
            return null
        }
        val action = "${context.packageName}.SMS_SENT.${System.nanoTime()}"
        val result = kotlinx.coroutines.CompletableDeferred<Int>()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { if (!result.isCompleted) result.complete(resultCode) }
        }
        ContextCompat.registerReceiver(context, receiver, android.content.IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sent = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
            manager.sendMultipartTextMessage(settings.smsNumber, null, parts, ArrayList(parts.map { sent }), null)
            val code = kotlinx.coroutines.withTimeoutOrNull(30_000) { result.await() }
                ?: return "No report from the phone after 30 seconds. It may still arrive; check your Messages app's Sent folder."
            return when (code) {
                android.app.Activity.RESULT_OK -> null
                SmsManager.RESULT_ERROR_NO_SERVICE -> "No mobile signal."
                SmsManager.RESULT_ERROR_RADIO_OFF -> "Mobile network is off (airplane mode?)."
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "The phone or SIM refused to send it (check SMS balance/plan and the SIM chosen for SMS)."
                else -> "The phone reported error code $code."
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private suspend fun email(subject: String, body: String) = withContext(Dispatchers.IO) {
        check(settings.emailFrom.isNotBlank()) { "Your Gmail address (sender) is empty" }
        check(settings.emailAppPassword.isNotBlank()) { "The Gmail app password is empty" }
        check(settings.emailTo.isNotBlank()) { "\"Send email to\" is empty" }
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
            put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "20000")
            put("mail.smtp.writetimeout", "20000")
        }
        val session = Session.getInstance(props, object : javax.mail.Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(settings.emailFrom, settings.emailAppPassword)
        })
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(settings.emailFrom))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(settings.emailTo))
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
        }
        Transport.send(message)
    }

    private fun sample(): Group {
        val date = java.time.LocalDate.now().plusDays(1).toString()
        val watch = Watch("test", "Palghat", "Bengaluru", name = "Test alert", dates = listOf(date))
        val bus = BusService(Operator.TNSTC, "TEST123", "SETC 729NS", "NON AC SLEEPER SEATER", "22:00", "07:00", 555, 7)
        return Group(watch, Operator.TNSTC, listOf(Alert(watch, date, bus, Alert.Kind.SEATS_FREED, previousSeats = 0)))
    }

    /** Sends a sample alert through this search's own channels, as set in the search and in Settings. */
    suspend fun testSearch(watch: Watch): String {
        val g = sample().let { it.copy(watch = watch, alerts = it.alerts.map { a -> a.copy(watch = watch) }) }
        val results = mutableListOf<String>()
        fun run(label: String, on: Boolean, configured: Boolean, block: () -> Unit) {
            results += when {
                !on -> "$label: off for this search"
                !configured -> "$label: not set up in Settings"
                else -> runCatching(block).fold({ "$label: sent" }, { "$label: FAILED — ${explain(it)}" })
            }
        }
        run("Push", watch.notifyPush, settings.pushEnabled) { push(listOf(g)) }
        results += when {
            !watch.notifySms -> "SMS: off for this search"
            !settings.smsEnabled -> "SMS: not set up in Settings"
            else -> runCatching { sms("Test for ${watch.displayName}: SMS alerts are working.", waitForResult = true) }
                .fold({ it?.let { r -> "SMS: FAILED — $r" } ?: "SMS: sent" }, { "SMS: FAILED — ${explain(it)}" })
        }
        results += when {
            !watch.notifyWhatsapp -> "WhatsApp: off for this search"
            !settings.whatsappEnabled -> "WhatsApp: not set up in Settings"
            else -> runCatching { whatsapp("Test for ${watch.displayName}: WhatsApp alerts are working.") }
                .fold({ "WhatsApp: sent" }, { "WhatsApp: FAILED — ${explain(it)}" })
        }
        results += when {
            !watch.notifyEmail -> "Email: off for this search"
            !settings.emailEnabled -> "Email: not set up in Settings"
            else -> runCatching { email("Test: ${watch.displayName}", "Email alerts for this search are working.") }
                .fold({ "Email: sent" }, { "Email: FAILED — ${explain(it)}" })
        }
        return results.joinToString("\n")
    }

    /** Test helpers for the Settings screen: each returns a message, or throws with an explanation. */
    suspend fun testPush(): String {
        runCatching { push(listOf(sample())) }.getOrElse { throw IllegalStateException(explain(it)) }
        return "Test notification sent. Check your notification shade."
    }

    suspend fun testSms(): String {
        val problem = runCatching { sms("Bus Seat Alert test: SMS alerts are working.", waitForResult = true) }
            .getOrElse { throw IllegalStateException(smsHelp(explain(it))) }
        if (problem != null) throw IllegalStateException(smsHelp(problem))
        return "Test SMS sent to ${settings.smsNumber}. Carrier charges may apply."
    }

    /** What to check when the phone itself refuses to send an SMS the app is allowed to send. */
    private fun smsHelp(reason: String) = if (reason.startsWith("SecurityException")) "$reason\n\n" +
        "The SMS permission is allowed, but this phone's system still blocks apps other than its Messages app " +
        "from sending SMS. That's a phone setting the app can't change. Alerts still come as notifications and " +
        "can go by email." else "$reason\n\n" +
        "This comes from the phone, not the bus sites. Please check:\n" +
        "• Two SIMs? Set a default SIM for SMS (Settings → SIM / Mobile network → SMS), not \"Ask every time\".\n" +
        "• The SIM can send SMS (active plan, SMS balance).\n" +
        "• Your phone's security or battery app (on some brands) allows Bus Seat Alert to send SMS.\n" +
        "Push notifications and email work without SMS."

    suspend fun testEmail(): String {
        val g = sample()
        runCatching { email("Bus Seat Alert test email", "Email alerts are working. A real alert looks like this:\n\n${g.title}\n${g.headline}\n${g.lines.joinToString("\n")}") }
            .getOrElse { throw IllegalStateException(explain(it)) }
        return "Test email sent to ${settings.emailTo}. Check Inbox and Spam."
    }

    /** Turns low-level errors into something the user can act on. */
    private fun explain(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.toList()
        return when {
            chain.any { it is javax.mail.AuthenticationFailedException } ->
                "Gmail rejected the login. Use a 16-letter App Password (not your normal Gmail password): turn on " +
                    "2-Step Verification, then create one at myaccount.google.com/apppasswords. " +
                    "The sender address must be the same Gmail account.\n(${e.message})"
            chain.any { it is java.net.UnknownHostException } -> "No internet connection (couldn't reach the server)."
            chain.any { it is java.net.SocketTimeoutException } -> "Timed out reaching the server. Check your internet connection."
            chain.any { it is javax.mail.internet.AddressException } -> "An email address is not valid: ${e.message}"
            else -> chain.firstOrNull { it.message != null }?.let { t ->
                // Bare system messages (e.g. "getSmsMmsDisabled") are clearer with their error type.
                if (t.message!!.contains(' ')) t.message!! else "${t.javaClass.simpleName}: ${t.message}"
            } ?: e.javaClass.simpleName
        }
    }

    companion object {
        private const val GROUP = "searches"
        /** One notification on the phone at a time; each new alert replaces it. */
        private const val NOTIFICATION_ID = 7001
        /** Bus lines in the expanded notification; the rest are counted and shown in the app. */
        private const val MAX_LINES = 10
        private val KSRTC_RED = 0xFFD71920.toInt()
        private val SITE_COLORS = mapOf(
            Operator.KERALA_RTC to 0xFFE53935.toInt(),
            Operator.KARNATAKA_RTC to 0xFF1E88E5.toInt(),
            Operator.TNSTC to 0xFF2E9E4F.toInt(),
        )
        private val COLORS = intArrayOf(0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), 0xFFC62828.toInt(), 0xFF6A1B9A.toInt(),
            0xFFEF6C00.toInt(), 0xFF00838F.toInt(), 0xFFAD1457.toInt(), 0xFF4E342E.toInt())

        fun colorFor(watch: Watch): Int = COLORS[Math.floorMod(watch.id.hashCode(), COLORS.size)]

        fun channelId(watch: Watch) = "search_${watch.id}"

        /** Removes the phone notification, e.g. when the user starts a different search. */
        fun cancelPhoneNotification(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannelGroup(android.app.NotificationChannelGroup(GROUP, "Searches"))
            // Channel from older versions, replaced by one channel per search.
            manager.deleteNotificationChannel("seat_alerts")
        }

        /** One channel per search, so each can have its own sound and vibration in Android settings. */
        fun ensureWatchChannel(context: Context, watch: Watch): String {
            ensureChannel(context)
            val channel = NotificationChannel(channelId(watch), watch.displayName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Seat alerts for ${watch.from} → ${watch.to}"
                group = GROUP
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            return channel.id
        }

        fun deleteWatchChannel(context: Context, watchId: String) =
            context.getSystemService(NotificationManager::class.java).deleteNotificationChannel("search_$watchId")
    }
}
