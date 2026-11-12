package com.sreeram.jarvis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Live information: web answers (Gemini with Google Search), weather (Open-Meteo), and screenshot questions.
object Web {
    private val models = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash")

    private fun request(url: String, body: JSONObject?, key: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 40000
        if (body != null) {
            conn.requestMethod = "POST"
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return code to stream.bufferedReader().readText()
    }

    private fun gemini(parts: JSONArray, grounded: Boolean, system: String): String {
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 500))
        if (grounded) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        var last = "No answer"
        for (m in models) {
            val (code, text) = request("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent", body, BuildConfig.API_KEY)
            if (code in 200..299) {
                val ps = JSONObject(text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                return (0 until ps.length()).joinToString("") { ps.getJSONObject(it).optString("text") }.trim().ifBlank { "No answer" }
            }
            last = if (code == 429) "The web lookup is rate limited right now." else "The web lookup failed."
            if (code !in setOf(404, 429, 500, 503)) break
        }
        return last
    }

    fun answer(question: String): String = gemini(JSONArray().put(JSONObject().put("text", question)), true,
        "Answer the question using current web information in one to three short spoken sentences. No markdown.")

    fun vision(question: String, screenshot: Bitmap): String {
        val scale = 1024f / maxOf(screenshot.width, screenshot.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(screenshot, (screenshot.width * scale).toInt(), (screenshot.height * scale).toInt(), true) else screenshot
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
        val parts = JSONArray().put(JSONObject().put("text", question))
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg")
                .put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))))
        return gemini(parts, false, "You are looking at a phone screenshot. Answer in one to three short spoken sentences. No markdown.")
    }

    fun weather(context: Context, city: String): String {
        var lat: Double
        var lon: Double
        var place: String
        if (city.isNotBlank()) {
            val (code, text) = request("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(city, "UTF-8"), null)
            val hit = if (code in 200..299) JSONObject(text).optJSONArray("results")?.optJSONObject(0) else null
            hit ?: return "Couldn't find a place called $city"
            lat = hit.getDouble("latitude"); lon = hit.getDouble("longitude"); place = hit.getString("name")
        } else {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                return "Location permission is not granted, so tell me a city."
            val lm = context.getSystemService(LocationManager::class.java)
            val loc = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
                ?: return "I don't have a recent location, so tell me a city."
            lat = loc.latitude; lon = loc.longitude; place = "your area"
        }
        val (code, text) = request("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=1", null)
        if (code !in 200..299) return "Weather service error $code"
        val j = JSONObject(text)
        val cur = j.getJSONObject("current")
        val day = j.getJSONObject("daily")
        return "$place: ${cur.getDouble("temperature_2m").toInt()} degrees, feels like ${cur.getDouble("apparent_temperature").toInt()}, " +
            "${describe(cur.getInt("weather_code"))}. Today high ${day.getJSONArray("temperature_2m_max").getDouble(0).toInt()}, " +
            "low ${day.getJSONArray("temperature_2m_min").getDouble(0).toInt()}, rain chance ${day.getJSONArray("precipitation_probability_max").optInt(0)} percent"
    }

    private fun describe(c: Int) = when (c) {
        0 -> "clear"; 1, 2 -> "partly cloudy"; 3 -> "overcast"; 45, 48 -> "foggy"
        in 51..57 -> "drizzle"; in 61..67 -> "rain"; in 71..77 -> "snow"; in 80..82 -> "rain showers"
        in 95..99 -> "thunderstorms"; else -> "mixed conditions"
    }
}
