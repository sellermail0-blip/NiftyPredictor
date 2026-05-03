package com.nifty.predictor

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update 
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.tanh

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = PredictionViewModel(application)
        setContent { PredictorScreen(vm) }
    }
}

// ==========================================
// 1. MATH ENGINE (LOCKED LOGIC)
// ==========================================
object PredictorEngine {
    enum class Direction(val symbol: String) { UP("↑"), DOWN("↓"), SIDEWAYS("→") }

    data class Result(
        val direction: Direction,
        val expectedNifty: Double,
        val expectedSensex: Double,
        val probability: Double
    )

    fun calculate(gift: Double, prevNifty: Double, vix: Double, newsItemScores: List<Pair<Int, Long>>): Result {
        if (prevNifty == 0.0) return Result(Direction.SIDEWAYS, 0.0, 0.0, 0.0)
        val g = ((gift - prevNifty) / prevNifty) * 100.0
        var sumWiSi = 0.0
        var sumWi = 0.0
        for ((score, ageMinutes) in newsItemScores) {
            val wi = exp(-0.03 * ageMinutes)
            sumWiSi += wi * score
            sumWi += wi
        }
        val n = if (sumWi > 0.0) sumWiSi / sumWi else 0.0
        val gn = tanh(g / 0.6)
        val vn = (16.0 - vix) / 8.0
        val s = 0.55 * gn + 0.25 * vn + 0.20 * n
        val direction = when {
            s > 0.18 -> Direction.UP
            s < -0.18 -> Direction.DOWN
            else -> Direction.SIDEWAYS
        }
        val eNifty = s * (vix / 16.0) * 0.75
        val eSensex = eNifty * 0.93
        val pUp = (1.0 / (1.0 + exp(-4.5 * s))) * 100.0
        val probability = if (direction == Direction.DOWN) 100.0 - pUp else pUp
        return Result(direction, eNifty, eSensex, probability)
    }

    fun analyzeSentiment(headline: String): Int {
        if (headline.isBlank()) return 0
        val lower = headline.lowercase(Locale.getDefault()) 
        val bullish = listOf("rbi pause", "rate cut", "fii buy", "dii buy", "crude down", "oil falls", "gdp beat", "inflation cools", "ceasefire", "dovish")
        val bearish = listOf("rbi hike", "rate hike", "fii sell", "dii sell", "crude up", "oil rises", "brent surge", "war", "attack", "inflation hot", "sebi ban", "circuit", "hawkish", "fed hike")
        if (bullish.any { lower.contains(it) }) return 1
        if (bearish.any { lower.contains(it) }) return -1
        return 0
    }

    fun isNewsTrigger(headline: String): Boolean {
        if (headline.isBlank()) return false
        val triggers = listOf("rbi", "mpc", "repo", "sebi", "ban", "fii", "crude", "oil", "brent", "opec", "war", "fed", "powell", "inflation", "gdp", "budget", "tax")
        return triggers.any { headline.lowercase(Locale.getDefault()).contains(it) } 
    }
}

// ==========================================
// 2. NETWORK LAYER
// ==========================================
class DataFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val nseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Connection" to "keep-alive"
    )

    suspend fun fetchVix(): Double? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.nseindia.com/api/allIndices")
                .apply { nseHeaders.forEach { addHeader(it.key, it.value) } }
                .build()
            client.newCall(request).execute().use { response -> 
                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (!bodyString.isNullOrBlank()) {
                        val json = JSONObject(bodyString)
                        val data = json.optJSONArray("data") 
                        if (data != null) {
                            for (i in 0 until data.length()) {
                                val index = data.optJSONObject(i)
                                if (index?.optString("indexSymbol") == "INDIA VIX") {
                                    return@withContext index.optDouble("last", Double.NaN).takeIf { !it.isNaN() }
                                }
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) { 
            if (e is CancellationException) throw e // BUGFIX
            null 
        }
    }
    
    suspend fun fetchGiftNifty(): Double? = withContext(Dispatchers.IO) { null }
    suspend fun fetchPrevNifty(): Double? = withContext(Dispatchers.IO) { null }
    
    data class NewsData(val scores: List<Pair<Int, Long>>, val hasNewTrigger: Boolean)
    suspend fun fetchNews(): NewsData = withContext(Dispatchers.IO) { NewsData(emptyList(), false) }
}

// ==========================================
// 3. STATE & TIMING
// ==========================================
data class UiState(
    val result: PredictorEngine.Result? = null,
    val isFetching: Boolean = false,
    val lastUpdatedMs: Long = 0L,
    val isNewsTriggered: Boolean = false,
    val isOffline: Boolean = false
)

class PredictionViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()
    private val istZone = TimeZone.getTimeZone("Asia/Kolkata")
    private var isAutoActive = true
    private var triggersInLastTwoMins = 0
    private var triggerTimestamps = mutableListOf<Long>() 
    private val fetcher = DataFetcher()
    private var cachedVix = 15.0 
    private var cachedPrevNifty = 22000.0 
    private val prefs = application.getSharedPreferences("NiftyPredictorCache", Context.MODE_PRIVATE)

    init {
        loadCache() 
        startAdaptivePolling()
    }

    private fun loadCache() {
        val dirString = prefs.getString("dir", null)
        if (dirString != null) {
            val dir = try { PredictorEngine.Direction.valueOf(dirString) } catch (e: Exception) { PredictorEngine.Direction.SIDEWAYS }
            val eNifty = prefs.getFloat("eNifty", 0f).toDouble()
            val eSensex = prefs.getFloat("eSensex", 0f).toDouble()
            val prob = prefs.getFloat("prob", 0f).toDouble()
            val lastMs = prefs.getLong("lastMs", 0L)
            _uiState.update { it.copy(result = PredictorEngine.Result(dir, eNifty, eSensex, prob), lastUpdatedMs = lastMs, isOffline = true) }
        }
    }

    private fun startAdaptivePolling() {
        viewModelScope.launch {
            while (isActive && isAutoActive) { 
                val fetchStartTime = System.currentTimeMillis()
                val hasTrigger = refreshData()
                if (hasTrigger) {
                    triggerTimestamps.add(fetchStartTime) 
                    viewModelScope.launch {
                        _uiState.update { it.copy(isNewsTriggered = true) } 
                        delay(2000L) 
                        _uiState.update { it.copy(isNewsTriggered = false) } 
                    }
                }
                val now = System.currentTimeMillis()
                triggerTimestamps.removeAll { now - it > 120_000L } 
                triggersInLastTwoMins = triggerTimestamps.size
                var delayMs: Long
                if (triggersInLastTwoMins >= 3) {
                    delayMs = 180_000L
                    triggerTimestamps.clear()
                } else if (hasTrigger) {
                    delayMs = 5_000L
                } else {
                    val calendar = Calendar.getInstance(istZone)
                    val hour = calendar.get(Calendar.HOUR_OF_DAY)
                    val minute = calendar.get(Calendar.MINUTE)
                    val currentMinutes = hour * 60 + minute
                    delayMs = getBaseInterval(currentMinutes)
                    val isCharging = true 
                    val isScreenOn = true 
                    if (!isCharging && !isScreenOn) delayMs *= 2
                }
                val execTime = System.currentTimeMillis() - fetchStartTime
                delay(maxOf(0L, delayMs - execTime))
            }
        }
    }

    private fun getBaseInterval(currentMinutes: Int): Long {
        return when {
            currentMinutes in (5 * 60 + 30)..(8 * 60 + 29) -> 120_000L
            currentMinutes in (8 * 60 + 30)..(9 * 60 + 14) -> 45_000L
            currentMinutes in (9 * 60 + 15)..(10 * 60 + 29) -> 30_000L
            currentMinutes in (10 * 60 + 30)..(13 * 60 + 59) -> 180_000L
            currentMinutes in (14 * 60)..(15 * 60 + 44) -> 60_000L
            currentMinutes in (15 * 60 + 45)..(22 * 60 + 59) -> 240_000L
            else -> 900_000L
        }
    }

    fun manualRefresh() {
        if (_uiState.value.isFetching) return // BUGFIX
        val now = System.currentTimeMillis()
        if (now - _uiState.value.lastUpdatedMs > 15_000L) {
            viewModelScope.launch { refreshData() }
        }
    }

    private suspend fun refreshData(): Boolean { 
        _uiState.update { it.copy(isFetching = true) } 
        val vixDeferred = viewModelScope.async { fetcher.fetchVix() }
        val giftDeferred = viewModelScope.async { fetcher.fetchGiftNifty() }
        val prevNiftyDeferred = viewModelScope.async { fetcher.fetchPrevNifty() }
        val newsDeferred = viewModelScope.async { fetcher.fetchNews() }
        val fetchedVix = vixDeferred.await()
        val gift = giftDeferred.await()
        val prevNifty = prevNiftyDeferred.await()
        val newsData = newsDeferred.await() 
        val isOffline = (gift == null || fetchedVix == null)
        if (fetchedVix != null) cachedVix = fetchedVix
        if (prevNifty != null) cachedPrevNifty = prevNifty
        val now = System.currentTimeMillis()
        val result = if (gift != null) {
             PredictorEngine.calculate(gift, cachedPrevNifty, cachedVix, newsData.scores).also { res ->
                 prefs.edit().apply {
                     putString("dir", res.direction.name)
                     putFloat("eNifty", res.expectedNifty.toFloat())
                     putFloat("eSensex", res.expectedSensex.toFloat())
                     putFloat("prob", res.probability.toFloat())
                     putLong("lastMs", now)
                     apply()
                 }
             }
        } else { _uiState.value.result }
        val updatedTime = if (gift != null) now else _uiState.value.lastUpdatedMs // BUGFIX
        _uiState.update { it.copy(result = result, isFetching = false, lastUpdatedMs = updatedTime, isOffline = isOffline) }
        return newsData.hasNewTrigger
    }
}

// ==========================================
// 4. UI
// ==========================================
@Composable
fun PredictorScreen(viewModel: PredictionViewModel) {
    val state by viewModel.uiState.collectAsState()
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { viewModel.manualRefresh() }) {
                    if (state.isFetching) { CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp)) }
                    else { Text("↻", color = Color.White, fontSize = 24.sp) }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            state.result?.let { res ->
                PredictionCard("NIFTY 50", res.direction, res.expectedNifty, res.probability)
                Spacer(modifier = Modifier.height(16.dp))
                PredictionCard("SENSEX", res.direction, res.expectedSensex, res.probability)
            }
            Spacer(modifier = Modifier.height(48.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val timeStr = if (state.lastUpdatedMs > 0L) { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(state.lastUpdatedMs)) } else { "--:--:--" }
                val color = if (state.isOffline) Color.Gray else Color.White
                Text("Updated $timeStr", color = color, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.size(10.dp).background(color = when { state.isOffline -> Color.Gray; state.isNewsTriggered -> Color(0xFFFFA500); else -> Color.Green }, shape = MaterialTheme.shapes.small))
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("Educational probability only, not financial advice", color = Color.DarkGray, fontSize = 10.sp)
        }
    }
}

@Composable
fun PredictionCard(title: String, dir: PredictorEngine.Direction, move: Double, prob: Double) {
    Card(modifier = Modifier.fillMaxWidth().height(140.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color.LightGray, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(dir.symbol, color = if (dir == PredictorEngine.Direction.DOWN) Color.Red else Color.Green, fontSize = 48.sp)
                Text(String.format(Locale.getDefault(), "%+.2f%%", move), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold) 
                Text(String.format(Locale.getDefault(), "%.0f%%", prob), color = Color.Cyan, fontSize = 28.sp)
            }
        }
    }
}
