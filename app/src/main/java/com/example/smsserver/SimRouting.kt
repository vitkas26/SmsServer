package com.example.smsserver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

class DestinationNotConfiguredException : IllegalArgumentException()
class SimUnavailableException : IllegalStateException()

/** Maps the two allowed destination numbers to physical SIM slots. */
class SimRouting(private val context: Context) {
    private val preferences = context.getSharedPreferences("sim_routing", Context.MODE_PRIVATE)

    fun numbers(): Pair<String, String> =
        (preferences.getString("sim1_phone", DEFAULT_SIM1_PHONE) ?: DEFAULT_SIM1_PHONE) to
            (preferences.getString("sim2_phone", DEFAULT_SIM2_PHONE) ?: DEFAULT_SIM2_PHONE)

    fun save(sim1Phone: String, sim2Phone: String) {
        require(GatewayPolicy.validPhone(sim1Phone) && GatewayPolicy.validPhone(sim2Phone))
        require(sim1Phone != sim2Phone)
        preferences.edit()
            .putString("sim1_phone", sim1Phone)
            .putString("sim2_phone", sim2Phone)
            .apply()
    }

    fun prefixes(): Pair<List<String>, List<String>> =
        parsePrefixes(preferences.getString("sim1_prefixes", "").orEmpty()) to
            parsePrefixes(preferences.getString("sim2_prefixes", "").orEmpty())

    fun savePrefixes(sim1Prefixes: List<String>, sim2Prefixes: List<String>) {
        require(sim1Prefixes.all(::validPrefix) && sim2Prefixes.all(::validPrefix))
        require(sim1Prefixes.intersect(sim2Prefixes.toSet()).isEmpty())
        preferences.edit()
            .putString("sim1_prefixes", sim1Prefixes.distinct().joinToString(","))
            .putString("sim2_prefixes", sim2Prefixes.distinct().joinToString(","))
            .apply()
    }

    fun smsManagerFor(phone: String): SmsManager {
        val (sim1Phone, sim2Phone) = numbers()
        val (sim1Prefixes, sim2Prefixes) = prefixes()
        val slot = slotFor(phone, sim1Phone, sim2Phone, sim1Prefixes, sim2Prefixes)
            ?: throw DestinationNotConfiguredException()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            throw SimUnavailableException()
        }
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        val subscriptionId = try {
            subscriptions.getActiveSubscriptionInfoForSimSlotIndex(slot)?.subscriptionId
        } catch (_: SecurityException) {
            null
        } ?: throw SimUnavailableException()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
    }

    companion object {
        // Old code sent to these numbers with subscription IDs 0 and 1 respectively.
        // Slots are resolved at send time because subscription IDs are device-specific.
        const val DEFAULT_SIM1_PHONE = "0880173333"
        const val DEFAULT_SIM2_PHONE = "0550890380"

        private val prefixPattern = Regex("0[0-9]{2,5}")

        fun validPrefix(prefix: String): Boolean = prefixPattern.matches(prefix)

        fun parsePrefixes(input: String): List<String> {
            if (input.isBlank()) return emptyList()
            val prefixes = input.trim().split(Regex("[,;\\s]+"))
            require(prefixes.all(::validPrefix))
            return prefixes.distinct()
        }

        private fun localNumber(phone: String): String = when {
            phone.startsWith("+996") -> "0" + phone.drop(4)
            phone.startsWith("996") -> "0" + phone.drop(3)
            else -> phone
        }

        fun slotFor(
            phone: String,
            sim1Phone: String,
            sim2Phone: String,
            sim1Prefixes: List<String> = emptyList(),
            sim2Prefixes: List<String> = emptyList(),
        ): Int? {
            val destination = localNumber(phone)
            if (destination == localNumber(sim1Phone)) return 0
            if (destination == localNumber(sim2Phone)) return 1
            return (sim1Prefixes.map { it to 0 } + sim2Prefixes.map { it to 1 })
                .filter { (prefix, _) -> destination.startsWith(prefix) }
                .maxByOrNull { (prefix, _) -> prefix.length }
                ?.second
        }
    }
}
