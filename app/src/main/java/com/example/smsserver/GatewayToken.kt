package com.example.smsserver

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

class GatewayToken(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    val value: String by lazy {
        preferences.getString("token", null) ?: run {
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            check(preferences.edit().putString("token", token).commit())
            token
        }
    }
}
