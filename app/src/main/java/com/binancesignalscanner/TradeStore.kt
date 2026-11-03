package com.binancesignalscanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object TradeStore {
    const val PREFS = "binance_trade_history"
    const val HISTORY = "history"
    const val POSITION_USDT = 10.0

    fun recordSignals(context: Context, signals: List<Signal>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val history = JSONArray(prefs.getString(HISTORY, "[]") ?: "[]")
        val now = System.currentTimeMillis()
        val candleBucket = now / 300000L

        for (signal in signals) {
            if (signal.setup == "WAIT") continue

            val key = "${signal.symbol}_${signal.side}_$candleBucket"
            var exists = false

            for (i in 0 until history.length()) {
                if (history.optJSONObject(i)?.optString("id") == key) {
                    exists = true
                    break
                }
            }
            if (exists) continue

            val trade = JSONObject().apply {
                put("id", key)
                put("symbol", signal.symbol)
                put("side", signal.side)
                put("setup", signal.setup)
                put("status", if (signal.setup == "MARKET") "OPEN" else "PENDING")
                put("entry", signal.entry)
                put("sl", signal.stopLoss)
                put("tp", signal.takeProfit)
                put("signalPrice", signal.price)
                put("positionUsdt", POSITION_USDT)
                put("leverage", 1.0)
                put("createdAt", now)
                put("lastCheckedAt", ((now / 300000L) + 1L) * 300000L)
                put("exitPrice", JSONObject.NULL)
                put("pnlUsd", 0.0)
                put("pnlPct", 0.0)
            }
            history.put(trade)
        }

        prefs.edit().putString(HISTORY, history.toString()).apply()
    }

    fun getHistory(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return JSONArray(prefs.getString(HISTORY, "[]") ?: "[]")
    }

    fun saveHistory(context: Context, history: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(HISTORY, history.toString())
            .apply()
    }
}
