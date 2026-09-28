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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
        val requestBody = buildRequest(dataUrl)
        val connection = openConnection()
        // HttpURLConnection's blocking write/read ignores thread interrupts. A watcher child is
        // cancelled as soon as this scope starts cancelling (while execute() is still blocked on
        // this thread) and its finally runs on another IO thread, so disconnect() closes the
        // socket immediately and the upload stops instead of running to the server reply/timeout.
        val watcher =
          launch(start = CoroutineStart.UNDISPATCHED) {
            try {
              awaitCancellation()
            } finally {
              connection.disconnect()
            }
          }
        try {
          // The watcher above finishes without suspending when the job is already cancelling,
          // so re-check before touching the socket: a dead job must never start the upload.
          ensureActive()
          val content = runInterruptible { execute(connection, requestBody) }
          ensureActive()
          parseModelContent(content)
        } catch (error: IOException) {
          // A handler-driven disconnect surfaces here as an IOException; report the
          // cancellation instead of a network failure.
          ensureActive()
          throw error
        } finally {
          watcher.cancel()
          connection.disconnect()
        }
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

  private fun buildRequest(dataUrl: String): ByteArray =
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
      .toByteArray(Charsets.UTF_8)

  private fun openConnection(): HttpURLConnection =
    (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
      connectTimeout = CONNECT_TIMEOUT_MS
      readTimeout = READ_TIMEOUT_MS
      requestMethod = "POST"
      doOutput = true
      setRequestProperty("Authorization", "Bearer $apiKey")
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "application/json")
    }

  /** Runs on an interruptible thread; callers disconnect the connection afterwards. */
  private fun execute(connection: HttpURLConnection, requestBody: ByteArray): String {
    connection.outputStream.use { it.write(requestBody) }
    val status = connection.responseCode
    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
    val body = stream?.bufferedReader(Charsets.UTF_8)?.use { readBounded(it) }.orEmpty()
    if (status !in 200..299) throw IOException("OpenRouter HTTP $status")
    return messageContent(body)
  }

  /** Reads at most MAX_RESPONSE_CHARS so a misbehaving server cannot grow the heap. */
  private fun readBounded(reader: java.io.BufferedReader): String {
    val out = StringBuilder()
    val buffer = CharArray(4096)
    while (out.length < MAX_RESPONSE_CHARS) {
      val read = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - out.length))
      if (read < 0) break
      out.append(buffer, 0, read)
    }
    return out.toString()
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
    var note =
      json
        .optFirstString("note", "assumption", "assumptions")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.take(MAX_NOTE_CHARS)
    var expiry: LocalDate? = rawExpiry?.let(::parseExpiry)?.takeIf(::plausible)
    // Strict provenance: only the exact labels count. Anything else — missing, "manufactured",
    // "packed", "unknown" — drops the date, so model noise never silently becomes an expiry.
    val source =
      when {
        expiry == null -> ExpirySource.None
        rawSource == "detected" -> ExpirySource.Detected
        rawSource == "estimated" -> ExpirySource.Estimated
        else -> ExpirySource.None
      }
    if (source == ExpirySource.None) expiry = null
    // An estimate without assumptions is not presentable; label its basis instead.
    if (source == ExpirySource.Estimated && note == null) note = DEFAULT_ESTIMATE_NOTE
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
    /** Upper bound on a chat-completions body; max_tokens keeps real answers far below it. */
    const val MAX_RESPONSE_CHARS = 32_768
    const val DEFAULT_ESTIMATE_NOTE = "Estimated typical shelf life; no date was readable on the packaging."
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
