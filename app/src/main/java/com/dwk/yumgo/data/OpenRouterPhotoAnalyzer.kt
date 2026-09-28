package com.dwk.yumgo.data

import android.content.Context
import com.dwk.yumgo.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cloud photo analysis over OpenRouter's OpenAI-compatible chat completions API.
 *
 * The staged photo is resized and stripped of metadata by [PhotoAnalysisImage], then uploaded
 * only when the user asked for analysis (callers gate on explicit action) and only when an API
 * key was baked into this build. A missing key returns [PhotoAnalysisResult.Unavailable] before
 * the photo is even read, so nothing is uploaded. Results are validated guesses for the review
 * screen: a clear printed expiry beats estimates, manufacturing / batch / ambiguous dates never
 * silently become an expiry, and an estimate without a readable date stays labelled estimated
 * with its assumptions in the note. Never writes to the fridge; never logs the key or photo bytes.
 */
class OpenRouterPhotoAnalyzer(
  context: Context,
  private val photos: PhotoStore,
  private val apiKey: String = BuildConfig.OPENROUTER_API_KEY,
  private val model: String = DEFAULT_MODEL,
) : PhotoAnalysisRepository {

  override suspend fun analyze(photoRef: String): PhotoAnalysisResult {
    if (apiKey.isBlank()) return PhotoAnalysisResult.Unavailable
    val dataUrl =
      try {
        PhotoAnalysisImage.prepare(photos, photoRef).getOrElse {
          return PhotoAnalysisResult.Failure("Couldn't read the photo")
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      }
    return try {
      withContext(Dispatchers.IO) {
        ensureActive()
        val content = postChatCompletions(dataUrl)
        ensureActive()
        parseModelContent(content)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: SocketTimeoutException) {
      PhotoAnalysisResult.Failure("Analysis timed out. Try again.")
    } catch (_: IOException) {
      PhotoAnalysisResult.Failure("Couldn't reach the analysis service. Check your connection and try again.")
    } catch (_: Exception) {
      PhotoAnalysisResult.Failure("Couldn't analyze the photo. Try again.")
    }
  }

  private fun postChatCompletions(dataUrl: String): String {
    val requestBody =
      JSONObject()
        .put("model", model)
        .put("temperature", TEMPERATURE)
        .put("max_tokens", MAX_TOKENS)
        .put(
          "messages",
          JSONArray().put(
            JSONObject()
              .put("role", "user")
              .put(
                "content",
                JSONArray()
                  .put(JSONObject().put("type", "text").put("text", promptFor(LocalDate.now())))
                  .put(
                    JSONObject()
                      .put("type", "image_url")
                      .put("image_url", JSONObject().put("url", dataUrl)),
                  ),
              ),
          ),
        )
        .toString()
    val connection =
      (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Authorization", "Bearer $apiKey")
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
      }
    try {
      connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
      val status = connection.responseCode
      val stream = if (status in 200..299) connection.inputStream else connection.errorStream
      val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
      if (status !in 200..299) throw IOException("OpenRouter HTTP $status")
      return messageContent(body)
    } finally {
      connection.disconnect()
    }
  }

  /** The assistant message text; some models return it as an array of parts instead of a string. */
  private fun messageContent(body: String): String {
    val message =
      try {
        JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
      } catch (_: Exception) {
        null
      } ?: throw IllegalStateException("Empty model response")
    val content =
      when (val raw = message.opt("content")) {
        is String -> raw
        is JSONArray -> buildString {
          for (index in 0 until raw.length()) {
            val part = raw.optJSONObject(index) ?: continue
            if (part.optString("type") == "text") append(part.optString("text"))
          }
        }
        else -> ""
      }.takeIf { it.isNotBlank() }
      ?: throw IllegalStateException("Empty model response")
    return content
  }

  private fun promptFor(today: LocalDate): String =
    """
    You are a food label reader for a fridge-tracking app. Look at the photo and reply with ONLY a JSON object and no other text:
    {"name": <short food name or null>, "expiry": <"YYYY-MM-DD" or null>, "expirySource": <"detected" | "estimated" | "none">, "note": <short note or null>}
    Today is $today.
    - "name": the food shown, e.g. "Whole milk". null when unrecognizable.
    - "detected": a use-by, best-before, or expires-on date clearly printed on the packaging and readable in the photo. Copy it as YYYY-MM-DD.
    - Never report a manufacturing, production, or packed-on date, a lot or batch code, or a partial or ambiguous date as the expiry. When only those are visible, use expiry null and expirySource "none", and explain in "note".
    - "estimated": only when no printed expiry is visible. Estimate a typical fridge shelf life from today and put the assumption in "note", e.g. "Unopened yogurt typically lasts ~2 weeks chilled".
    - "none": no date suggestion; say why in "note" when helpful.
    """.trimIndent()

  /**
   * Validates the model's JSON into editable guesses. Unparsable envelopes are failures; a bad
   * date inside valid JSON only drops the date, never the name.
   */
  private fun parseModelContent(content: String): PhotoAnalysisResult {
    val json = extractJsonObject(content)
      ?: return PhotoAnalysisResult.Failure("Couldn't analyze the photo. Try again.")
    val name =
      json
        .optFirstString("name", "food")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.take(MAX_NAME_CHARS)
    val rawExpiry =
      json.optFirstString("expiry", "expiryDate", "expiresOn")?.trim()?.takeIf { it.isNotEmpty() }
    val rawSource =
      json.optFirstString("expirySource", "expiry_source", "source")?.trim()?.lowercase()
    val note =
      json
        .optFirstString("note", "assumption", "assumptions")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.take(MAX_NOTE_CHARS)
    var expiry: LocalDate? = rawExpiry?.let(::parseExpiry)?.takeIf(::plausible)
    val source =
      when {
        expiry == null -> ExpirySource.None
        rawSource == "estimated" -> ExpirySource.Estimated
        rawSource == "none" -> ExpirySource.None
        else -> ExpirySource.Detected
      }
    // An explicit "none" label (or an unreadable date) means the date is not an expiry:
    // manufacturing and ambiguous dates never silently become one.
    if (source == ExpirySource.None) expiry = null
    return PhotoAnalysisResult.Success(
      name = name,
      expiry = expiry,
      expirySource = source,
      note = note,
    )
  }

  /** Tolerates markdown fences around the object; null when there is no JSON object at all. */
  private fun extractJsonObject(content: String): JSONObject? {
    val start = content.indexOf('{')
    val end = content.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return try {
      JSONObject(content.substring(start, end + 1))
    } catch (_: Exception) {
      null
    }
  }

  /** Strict ISO first, then unambiguous year-first numeric variants. Null when not a real date. */
  private fun parseExpiry(raw: String): LocalDate? {
    val normalized = raw.trim().take(MAX_DATE_CHARS)
    try {
      return LocalDate.parse(normalized)
    } catch (_: Exception) {
      // Fall through to the variants below.
    }
    for (separator in listOf('/', '.')) {
      val parts = normalized.split(separator)
      if (parts.size == 3 && parts[0].length == 4) {
        try {
          return LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        } catch (_: Exception) {
          // Try the next variant.
        }
      }
    }
    return null
  }

  /** Guards against batch codes or stray years parsed as dates. */
  private fun plausible(date: LocalDate): Boolean = date.year in MIN_PLAUSIBLE_YEAR..MAX_PLAUSIBLE_YEAR

  companion object {
    const val DEFAULT_MODEL = "deepseek/deepseek-v4.1-flash"
    const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 60_000
    private const val TEMPERATURE = 0.2
    private const val MAX_TOKENS = 300
    private const val MAX_NAME_CHARS = 80
    private const val MAX_NOTE_CHARS = 280
    private const val MAX_DATE_CHARS = 10
    private const val MIN_PLAUSIBLE_YEAR = 2000
    private const val MAX_PLAUSIBLE_YEAR = 2100
  }
}

/** First non-null string value under any of [keys]; non-string JSON values are skipped. */
private fun JSONObject.optFirstString(vararg keys: String): String? {
  for (key in keys) {
    val value = opt(key) ?: continue
    if (value === JSONObject.NULL) continue
    if (value is String) return value
  }
  return null
}
