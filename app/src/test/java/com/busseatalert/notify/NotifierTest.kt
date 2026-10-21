package com.busseatalert.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.busseatalert.data.Store
import com.busseatalert.model.BusService
import com.busseatalert.model.Operator
import com.busseatalert.model.Watch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotifierTest {
    @Test
    fun newAlertReplacesThePhoneNotificationButAppKeepsAll() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val store = Store(app)
        val watch = Watch("w1", "Palghat", "Bengaluru", dates = listOf("2026-10-25"))
        fun alert(op: Operator, trip: String, seats: Int) = Alert(watch, "2026-10-25",
            BusService(op, trip, "Route", "AC SEATER", "21:00", "05:00", 900, seats), Alert.Kind.SEATS_FREED, 0)

        Notifier(app, store.settings).send(listOf(alert(Operator.KERALA_RTC, "T1", 3), alert(Operator.TNSTC, "T2", 2)))
        Notifier(app, store.settings).send(listOf(alert(Operator.KARNATAKA_RTC, "T3", 5)))

        val shown = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        assertEquals("only one phone notification", 1, shown.size)
        val n = shown.single()
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() }
        println("title=$title | text=${n.extras.getCharSequence(Notification.EXTRA_TEXT)} | lines=$lines")
        assertEquals("🚌 SEATS OPENED UP!", title)
        assertTrue(lines.single().startsWith("Karnataka RTC"))   // the newest alert replaced the older one
        assertEquals("app keeps every site entry", 3, store.alerts.size)
    }
}
