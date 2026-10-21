package com.binancesignalscanner

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class Signal(
    val symbol: String,
    val side: String,
    val price: Double,
    val rsi: Double,
    val ema9: Double,
    val ema21: Double,
    val volumeRatio: Double,
    val score: Int
)

class MainActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var scanButton: Button
    private val worker = Executors.newSingleThreadExecutor()
    private val api = "https://fapi.binance.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 28, 20, 20)
            setBackgroundColor(Color.rgb(14, 20, 32))
        }

        val title = TextView(this).apply {
            text = "BINANCE SIGNAL SCANNER"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(245, 190, 60))
        }
        root.addView(title)

        val info = TextView(this).apply {
            text = "\nLIVE USDⓈ-M FUTURES • 5 MIN\nRSI(14) • EMA(9/21) • VOLUME\nSIGNALS ONLY — NO ORDERS\n"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        root.addView(info)

        scanButton = Button(this).apply {
            text = "SCAN ALL USDT PERPETUALS"
            setOnClickListener { startScan() }
        }
        root.addView(scanButton)

        output = TextView(this).apply {
            text = "\nReady. Tap Scan to begin.\n"
            textSize = 15f
            setTextColor(Color.rgb(220, 230, 245))
        }

        val scroll = ScrollView(this)
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun getJson(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "BinanceSignalScanner/1.0")
        try {
            if (connection.responseCode != 200) {
                throw Exception("HTTP ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun ema(values: List<Double>, period: Int): Double {
        val alpha = 2.0 / (period + 1)
        var result = values.take(period).average()
        for (i in period until values.size) {
            result = values[i] * alpha + result * (1.0 - alpha)
        }
        return result
    }

    private fun rsi(values: List<Double>, period: Int = 14): Double {
        var gains = 0.0
        var losses = 0.0
        for (i in 1..period) {
            val change = values[i] - values[i - 1]
            if (change > 0) gains += change else losses -= change
        }
        var avgGain = gains / period
        var avgLoss = losses / period
        for (i in period + 1 until values.size) {
            val change = values[i] - values[i - 1]
            avgGain = (avgGain * (period - 1) + maxOf(change, 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + maxOf(-change, 0.0)) / period
        }
        if (avgLoss == 0.0) return 100.0
        return 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
    }

    private fun analyze(symbol: String): Signal? {
        val candles = JSONArray(
            getJson("$api/fapi/v1/klines?symbol=$symbol&interval=5m&limit=60")
        )
        // Ignore the latest candle because it may not be closed yet.
        val closed = (0 until candles.length() - 1).map { candles.getJSONArray(it) }
        if (closed.size < 30) return null

        val closes = closed.map { it.getString(4).toDouble() }
        val volumes = closed.map { it.getString(5).toDouble() }
        val price = closes.last()
        val e9 = ema(closes, 9)
        val e21 = ema(closes, 21)
        val r = rsi(closes)
        val avgVolume = volumes.dropLast(1).takeLast(20).average()
        if (avgVolume <= 0.0) return null
        val ratio = volumes.last() / avgVolume

        val side = when {
            e9 > e21 && r in 50.0..70.0 && ratio > 1.0 -> "BUY"
            e9 < e21 && r in 30.0..50.0 && ratio > 1.0 -> "SELL"
            else -> return null
        }

        val trend = abs(e9 - e21) / price * 10000.0
        val rsiPart = if (side == "BUY") {
            (20.0 - abs(r - 60.0)).coerceAtLeast(0.0)
        } else {
            (20.0 - abs(r - 40.0)).coerceAtLeast(0.0)
        }
        val score = (60.0 + rsiPart + trend.coerceAtMost(10.0) +
            ((ratio - 1.0) * 5.0).coerceIn(0.0, 10.0))
            .toInt().coerceIn(60, 95)

        return Signal(symbol, side, price, r, e9, e21, ratio, score)
    }

    private fun startScan() {
        scanButton.isEnabled = false
        output.text = "Connecting to Binance...\nGetting USDT perpetual pairs...\n"

        worker.execute {
            try {
                val info = JSONObject(getJson("$api/fapi/v1/exchangeInfo"))
                val all = info.getJSONArray("symbols")
                val symbols = (0 until all.length()).mapNotNull { i ->
                    val s = all.getJSONObject(i)
                    if (s.optString("status") == "TRADING" &&
                        s.optString("contractType") == "PERPETUAL" &&
                        s.optString("quoteAsset") == "USDT" &&
                        s.optString("symbol").endsWith("USDT")) {
                        s.optString("symbol")
                    } else null
                }

                runOnUiThread {
                    output.text = "Live market data\nUSDT perpetual pairs: ${symbols.size}\nScanning 5m candles...\nPlease wait.\n"
                }

                val pool = Executors.newFixedThreadPool(6)
                val found = java.util.Collections.synchronizedList(mutableListOf<Signal>())
                var scanned = 0
                try {
                    for (batch in symbols.chunked(12)) {
                        val tasks = batch.map { symbol ->
                            pool.submit<Signal?> {
                                try { analyze(symbol) } catch (_: Exception) { null }
                            }
                        }
                        for (task in tasks) {
                            try { task.get(25, TimeUnit.SECONDS)?.let { found.add(it) } }
                            catch (_: Exception) { task.cancel(true) }
                            scanned++
                        }
                        runOnUiThread {
                            output.text = "Live market data\nPairs: ${symbols.size}\nChecked: $scanned / ${symbols.size}\nSignals found: ${found.size}\nScanning..."
                        }
                    }
                } finally {
                    pool.shutdownNow()
                }

                val top = found.filter { it.side == "SELL" }.sortedByDescending { it.score }.take(5)
                val result = StringBuilder()
                result.append("SCAN COMPLETE\n")
                result.append("USDT perpetual pairs: ${symbols.size}\n")
                result.append("Pairs checked: $scanned\n")
                result.append("Qualified signals: ${found.size}\n")
                result.append("Top SELL signals: ${top.size}\n")
                result.append("Score = indicator ranking, NOT win probability.\n\n")

                if (top.isEmpty()) {
                    result.append("No signals met all conditions.\n")
                } else {
                    top.forEachIndexed { index, s ->
                        val marginUsdt = 5.0
                        val leverage = 15.0
                        val positionValue = marginUsdt * leverage
                        val maxLossUsdt = 5.0
                        val priceMove = maxLossUsdt / positionValue
                        val sl = if (s.side == "BUY") {
                            s.price * (1.0 - priceMove)
                        } else {
                            s.price * (1.0 + priceMove)
                        }
                        val tp = if (s.side == "BUY") s.price * 1.02 else s.price * 0.98
                        result.append("${index + 1}. ${s.symbol}  ${s.side}\n")
                        result.append("Score: ${s.score}/100\n")
                        result.append("Entry: ${"%.8f".format(java.util.Locale.US, s.price)}\n")
                        result.append("Demo SL: ${"%.8f".format(java.util.Locale.US, sl)}\n")
                        result.append("Demo TP: ${"%.8f".format(java.util.Locale.US, tp)}\n")
                        result.append("RSI: ${"%.1f".format(java.util.Locale.US, s.rsi)}")
                        result.append(" | EMA9: ${"%.8f".format(java.util.Locale.US, s.ema9)}\n")
                        result.append("EMA21: ${"%.8f".format(java.util.Locale.US, s.ema21)}")
                        result.append(" | Vol: ${"%.2f".format(java.util.Locale.US, s.volumeRatio)}x avg\n\n")
                    }
                }
                result.append("5m candles • Public Binance data\n")
                result.append("Signals only. NO ORDERS PLACED.\n")
                runOnUiThread { output.text = result.toString() }
            } catch (e: Exception) {
                runOnUiThread {
                    output.text = "Scan failed:\n${e.message}\n\nCheck internet and try again."
                }
            } finally {
                runOnUiThread { scanButton.isEnabled = true }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
