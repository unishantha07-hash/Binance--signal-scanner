package com.binancesignalscanner

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SignalWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        const val CHANNEL_ID = "binance_signal_channel"
    }

    override fun doWork(): Result {
        return try {
            createNotificationChannel()
            checkTrades()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun checkTrades() {
        val history = TradeStore.getHistory(applicationContext)
        val now = System.currentTimeMillis()
        var changed = false

        for (i in 0 until history.length()) {
            val trade = history.optJSONObject(i) ?: continue
            val status = trade.optString("status")
            if (status != "OPEN" && status != "PENDING") continue

            val symbol = trade.optString("symbol")
            val entry = trade.optDouble("entry")
            val sl = trade.optDouble("sl")
            val tp = trade.optDouble("tp")
            val side = trade.optString("side")
            val createdAt = trade.optLong("createdAt", now)
            val lastChecked = trade.optLong("lastCheckedAt", createdAt)

            val url = "https://fapi.binance.com/fapi/v1/klines" +
                "?symbol=$symbol&interval=5m&startTime=$lastChecked&limit=100"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 15000

            val candles = try {
                JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }

            for (j in 0 until candles.length()) {
                val candle = candles.optJSONArray(j) ?: continue
                val openTime = candle.getLong(0)
                val high = candle.getString(2).toDouble()
                val low = candle.getString(3).toDouble()
                val closeTime = candle.getLong(6)

                if (closeTime > now) continue
                if (closeTime <= lastChecked) continue

                if (trade.optString("status") == "PENDING") {
                    val activated = if (side == "BUY") low <= entry else high >= entry
                    if (activated) {
                        trade.put("status", "OPEN")
                        trade.put("activatedAt", closeTime)
                        trade.put("lastCheckedAt", closeTime)
                        notifyUser(
                            trade.optString("id").hashCode(),
                            "Binance Entry Activated",
                            "$symbol $side entry reached: $entry"
                        )
                        changed = true
                    } else {
                        trade.put("lastCheckedAt", closeTime)
                        changed = true
                    }
                    continue
                }

                if (trade.optString("status") != "OPEN") continue

                val hitSL = if (side == "BUY") low <= sl else high >= sl
                val hitTP = if (side == "BUY") high >= tp else low <= tp

                if (hitSL || hitTP) {
                    val finalStatus = when {
                        hitSL && hitTP -> "AMBIGUOUS"
                        hitTP -> "WIN"
                        else -> "LOSS"
                    }

                    val exit = when (finalStatus) {
                        "WIN" -> tp
                        "LOSS" -> sl
                        else -> 0.0
                    }

                    val pnl = if (finalStatus == "AMBIGUOUS") {
                        0.0
                    } else {
                        val direction = if (side == "BUY") 1.0 else -1.0
                        TradeStore.POSITION_USDT * direction * (exit - entry) / entry
                    }

                    trade.put("status", finalStatus)
                    trade.put("exitPrice", if (exit > 0.0) exit else JSONObject.NULL)
                    trade.put("pnlUsd", pnl)
                    trade.put("pnlPct", if (finalStatus == "AMBIGUOUS") 0.0 else pnl / TradeStore.POSITION_USDT * 100.0)
                    trade.put("closedAt", closeTime)

                    val title = when (finalStatus) {
                        "WIN" -> "BINANCE TP HIT"
                        "LOSS" -> "BINANCE SL HIT"
                        else -> "BINANCE RESULT AMBIGUOUS"
                    }
                    notifyUser(
                        trade.optString("id").hashCode(),
                        title,
                        "$symbol $side | P/L: ${"%.4f".format(java.util.Locale.US, pnl)} USDT"
                    )
                    changed = true
                    break
                } else {
                    trade.put("lastCheckedAt", closeTime)
                    changed = true
                }
            }

        }

        if (changed) TradeStore.saveHistory(applicationContext, history)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Binance Signal Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = applicationContext.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun notifyUser(id: Int, title: String, message: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT < 33 ||
            applicationContext.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(applicationContext).notify(id, notification)
        }
    }
}
