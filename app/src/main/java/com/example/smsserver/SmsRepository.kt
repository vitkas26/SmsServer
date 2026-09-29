package com.example.smsserver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import java.util.Date

data class SmsMessage(val phone: String, val body: String, val timestamp: Long)

class SmsRepository(private val context: Context) {
    private val simRouting = SimRouting(context)
    private val dao = AppDatabase.getInstance(context).daoData()

    fun inbox(start: Date, end: Date): List<SmsMessage> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val result = mutableListOf<SmsMessage>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} < ?",
            arrayOf(start.time.toString(), end.time.toString()),
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val phoneIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext() && result.size < 200) {
                result += SmsMessage(
                    cursor.getString(phoneIndex).orEmpty(),
                    cursor.getString(bodyIndex).orEmpty(),
                    cursor.getLong(dateIndex),
                )
            }
        }
        return result
    }

    suspend fun sent(start: Date, end: Date): List<SmsMessage> =
        dao.getFromTable(start, end).map { SmsMessage(it.tel, it.sms, it.nowData.time) }

    @Suppress("DEPRECATION")
    suspend fun send(phone: String, message: String) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            "SMS permission is missing"
        }
        val manager = simRouting.smsManagerFor(phone)
        val parts = manager.divideMessage(message)
        if (parts.size == 1) {
            manager.sendTextMessage(phone, null, message, null, null)
        } else {
            manager.sendMultipartTextMessage(phone, null, parts, null, null)
        }
        dao.insertData(LogData(tel = phone, sms = message, nowData = Date()))
    }
}
