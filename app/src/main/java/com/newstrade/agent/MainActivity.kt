package com.newstrade.agent

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

data class Keys(
    val newsapi: String, val finnhub: String, val tavily: String, val gemini: String,
    val tgToken: String, val chatId: String, val sendTg: Boolean
)

val DIMENSIONS = listOf(
    Triple("Fed/ดอกเบี้ย/เงินเฟ้อ", "(\"Federal Reserve\" OR \"interest rate\" OR CPI OR FOMC)", listOf("DXY", "XAUUSD", "SPX")),
    Triple("จีน/เศรษฐกิจโลก", "(China economy OR PBOC OR \"manufacturing PMI\")", listOf("CNH", "copper", "A50")),
    Triple("ภูมิรัฐศาสตร์/พลังงาน", "(geopolitical risk OR oil OR OPEC OR sanctions)", listOf("WTI", "XAUUSD")),
    Triple("คริปโต/เงินไหล", "(Bitcoin OR \"crypto regulation\" OR ETF flows)", listOf("BTCUSD", "ETHUSD"))
)

fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = "GET"
    c.connectTimeout = 30000
    c.readTimeout = 60000
    val code = c.responseCode
    val s = if (code in 200..299) c.inputStream else c.errorStream
    return s?.bufferedReader()?.use { it.readText() } ?: ""
}

fun httpPost(url: String, body: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = "POST"
    c.connectTimeout = 30000
    c.readTimeout = 120000
    c.setRequestProperty("Content-Type", "application/json")
    c.doOutput = true
    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    val code = c.responseCode
    val s = if (code in 200..299) c.inputStream else c.errorStream
    return s?.bufferedReader()?.use { it.readText() } ?: ""
}

suspend fun runAgent(k: Keys, onLog: suspend (String) -> Unit): String = withContext(Dispatchers.IO) {
    val raw = JSONArray()
    val deep = JSONArray()

    onLog("📡 ดึงข่าวตลาดจาก Finnhub...")
    try {
        val r = JSONArray(httpGet("https://finnhub.io/api/v1/news?category=general&token=${k.finnhub}"))
        for (i in 0 until minOf(r.length(), 8)) {
            val a = r.getJSONObject(i)
            raw.put(JSONObject().put("title", a.optString("headline"))
                .put("desc", a.optString("summary")).put("src", a.optString("source")))
        }
    } catch (e: Exception) { onLog("⚠️ Finnhub พลาด: ${e.message} — ข้ามไปก่อน") }

    val from = java.time.OffsetDateTime.now().minusHours(24).toString()
    for ((name, q, assets) in DIMENSIONS) {
        onLog("📰 ค้นหามิติ: $name ...")
        try {
            val u = "https://newsapi.org/v2/everything?q=${URLEncoder.encode(q, "UTF-8")}" +
                    "&language=en&pageSize=6&sortBy=publishedAt&from=$from&apiKey=${k.newsapi}"
            val arts = JSONObject(httpGet(u)).optJSONArray("articles") ?: JSONArray()
            for (i in 0 until arts.length()) {
                val a = arts.getJSONObject(i)
                raw.put(JSONObject().put("title", a.optString("title"))
                    .put("desc", a.optString("description"))
                    .put("src", a.optJSONObject("source")?.optString("name") ?: ""))
            }
        } catch (e: Exception) { onLog("⚠️ NewsAPI ($name): ${e.message}") }
        try {
            val body = JSONObject().put("api_key", k.tavily)
                .put("query", "latest market analysis: $q").put("topic", "news")
                .put("search_depth", "advanced").put("max_results", 8)
                .put("days", 3).put("include_answer", true)
            val r = JSONObject(httpPost("https://api.tavily.com/search", body.toString()))
            val urls = JSONArray()
            val res = r.optJSONArray("results")
            if (res != null) for (i in 0 until res.length()) urls.put(res.getJSONObject(i).optString("url"))
            deep.put(JSONObject().put("topic", name).put("assets", JSONArray(assets))
                .put("answer", r.optString("answer")).put("sources", urls))
        } catch (e: Exception) { onLog("⚠️ Tavily ($name): ${e.message}") }
    }

    onLog("🧠 Gemini กำลังวิเคราะห์ ${raw.length()} ชิ้นข่าว...")
    val schema = """
        {"market_regime":"risk-on|risk-off|neutral",
         "executive_summary":"สรุปสถานการณ์ 5 บรรทัด ภาษาไทย",
         "signals":[{"asset":"","direction":"bullish|bearish|neutral","confidence":0,"catalyst":"","time_horizon":"intraday|swing","key_levels":""}],
         "risk_events_today":[""],
         "news_quality_note":"ประเมินความน่าเชื่อถือของแหล่งข่าว"}
    """.trimIndent()
    val prompt = "คุณคือนักวิเคราะห์ macro มืออาชีพ จงวิเคราะห์ข่าวเพื่อช่วยตัดสินใจเทรด ตอบเป็น JSON ตาม schema นี้เท่านั้น:\n$schema" +
            "\n\nDEEP_RESEARCH:\n$deep\n\nRAW_NEWS:\n${raw.toString().take(25000)}"
    val gBody = JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        .put("generationConfig", JSONObject().put("response_mime_type", "application/json").put("temperature", 0.3))
    val gResp = JSONObject(httpPost(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=${k.gemini}",
        gBody.toString()))
    val analysis = JSONObject(gResp.getJSONArray("candidates").getJSONObject(0)
        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text"))

    onLog("📨 จัดรูปแบบผลลัพธ์...")
    val now = java.time.LocalDateTime.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm"))
    val sb = StringBuilder()
    sb.append("🌍 News-to-Trade Briefing ($now น.)\n")
    sb.append("🎯 Regime: ").append(analysis.optString("market_regime", "").uppercase()).append("\n\n")
    sb.append("📝 ").append(analysis.optString("executive_summary")).append("\n\n")
    val signals = analysis.optJSONArray("signals") ?: JSONArray()
    for (i in 0 until signals.length()) {
        val s = signals.getJSONObject(i)
        val icon = when (s.optString("direction")) { "bullish" -> "🟢"; "bearish" -> "🔴"; else -> "⚪️" }
        sb.append(icon).append(" ").append(s.optString("asset")).append(" ").append(s.optString("direction"))
        sb.append(" | มั่นใจ ").append(s.optInt("confidence", 0)).append("% [").append(s.optString("time_horizon")).append("]\n")
        sb.append("   • ").append(s.optString("catalyst")).append("\n")
        sb.append("   • Levels: ").append(s.optString("key_levels", "-")).append("\n\n")
    }
    val risks = analysis.optJSONArray("risk_events_today") ?: JSONArray()
    if (risks.length() > 0) {
        sb.append("⚠️ ")
        for (i in 0 until minOf(3, risks.length())) sb.append(risks.getString(i)).append(" | ")
        sb.append("\n")
    }
    sb.append("\n📌 ข้อมูลเพื่อการศึกษา ไม่ใช่คำแนะนำการลงทุน")
    val text = sb.toString()

    if (k.sendTg && k.tgToken.isNotBlank() && k.chatId.isNotBlank()) {
        onLog("📲 ส่งเข้า Telegram...")
        try {
            val body = JSONObject().put("chat_id", k.chatId).put("text", text.take(4000))
            httpPost("https://api.telegram.org/bot${k.tgToken}/sendMessage", body.toString())
        } catch (e: Exception) { onLog("⚠️ Telegram: ${e.message}") }
    }
    text
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("agent_keys", Context.MODE_PRIVATE) }
    var newsapi by remember { mutableStateOf(prefs.getString("newsapi", "") ?: "") }
    var finnhub by remember { mutableStateOf(prefs.getString("finnhub", "") ?: "") }
    var tavily by remember { mutableStateOf(prefs.getString("tavily", "") ?: "") }
    var gemini by remember { mutableStateOf(prefs.getString("gemini", "") ?: "") }
    var tgToken by remember { mutableStateOf(prefs.getString("tgToken", "") ?: "") }
    var chatId by remember { mutableStateOf(prefs.getString("chatId", "") ?: "") }
    var sendTg by remember { mutableStateOf(prefs.getBoolean("sendTg", false)) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = { TopAppBar(title = { Text("🌍 News-to-Trade Agent") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("🔑 API Keys (กรอกครั้งเดียว แอปจำให้)", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(newsapi, onValueChange = { newsapi = it },
                label = { Text("NewsAPI Key") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(finnhub, onValueChange = { finnhub = it },
                label = { Text("Finnhub API Key") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(tavily, onValueChange = { tavily = it },
                label = { Text("Tavily API Key") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(gemini, onValueChange = { gemini = it },
                label = { Text("Gemini API Key") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(tgToken, onValueChange = { tgToken = it },
                label = { Text("Telegram Bot Token (ไม่ใส่ก็ได้)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(chatId, onValueChange = { chatId = it },
                label = { Text("Telegram Chat ID") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = sendTg, onCheckedChange = { sendTg = it })
                Spacer(Modifier.width(8.dp))
                Text("ส่ง Briefing เข้า Telegram ด้วย")
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    prefs.edit()
                        .putString("newsapi", newsapi).putString("finnhub", finnhub)
                        .putString("tavily", tavily).putString("gemini", gemini)
                        .putString("tgToken", tgToken).putString("chatId", chatId)
                        .putBoolean("sendTg", sendTg).apply()
                    if (newsapi.isBlank() || finnhub.isBlank() || tavily.isBlank() || gemini.isBlank()) {
                        result = "❌ กรุณากรอก NewsAPI / Finnhub / Tavily / Gemini key ให้ครบก่อน"
                        return@Button
                    }
                    busy = true; log = "เริ่มทำงาน..."; result = ""
                    scope.launch {
                        try {
                            val k = Keys(newsapi, finnhub, tavily, gemini, tgToken, chatId, sendTg)
                            val text = runAgent(k) { l -> withContext(Dispatchers.Main) { log = l } }
                            withContext(Dispatchers.Main) { result = text; busy = false }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { result = "❌ Error: ${e.message}"; busy = false }
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "⏳ กำลังทำงาน..." else "🔥 สร้าง Briefing ตอนนี้") }
            if (busy) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(log, fontSize = 12.sp)
            }
            if (result.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(result, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}
