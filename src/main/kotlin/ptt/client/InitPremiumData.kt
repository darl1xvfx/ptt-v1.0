package ptt.client

import ptt.*
import java.time.Instant

class InitPremiumData {
    var leftTime: Int = 0
    var needShowNotificationCompletionPremium: Boolean = false
    var needShowWelcomeAlert: Boolean = false
    var reminderCompletionPremiumTime: Int = 0
    var wasShowAlertForFirstPurchasePremium: Boolean = false
    var wasShowReminderCompletionPremium: Boolean = true

    fun setPremiumDurationInMinutes(durationInMinutes: Int) {
        val currentTime = Instant.now()
        val expirationTime = currentTime.plusSeconds(durationInMinutes.toLong() * 60)
        val secondsLeft = (expirationTime.epochSecond - currentTime.epochSecond).toInt()
        this.leftTime = secondsLeft.coerceAtLeast(0)
    }

    fun grantPremiumStatusForDays(days: Int) {
        setPremiumDurationInMinutes(days * 24 * 60)
    }

    fun removePremiumStatus() {
        setPremiumDurationInMinutes(0)
    }

    fun hasPremiumStatus(): Boolean {
        return leftTime > 0
    }
}
