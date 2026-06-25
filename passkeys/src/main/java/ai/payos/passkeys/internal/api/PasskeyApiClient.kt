package ai.payos.passkeys.internal.api

import ai.payos.PayOSConfiguration
import ai.payos.PayOSError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class PasskeyApiClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val sdkVersion: String = DEFAULT_SDK_VERSION
) {
    @Volatile
    private var configuration: PayOSConfiguration? = null

    fun configure(configuration: PayOSConfiguration) {
        this.configuration = configuration
    }

    suspend fun get(path: String): ApiEnvelope {
        return request("GET", path, null)
    }

    suspend fun post(path: String, body: JSONObject): ApiEnvelope {
        return request("POST", path, body)
    }

    internal fun buildRequest(
        method: String,
        path: String,
        body: JSONObject?
    ): Request {
        val configuration = currentConfiguration()
        val builder = Request.Builder()
            .url(buildUrl(path, configuration))
            .addHeader("Authorization", "Bearer ${configuration.linkToken}")
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-SDK-Version", sdkVersion)
            .addHeader("X-SDK-Platform", "android")

        return when (method.uppercase()) {
            "GET" -> builder.get().build()
            "POST" -> builder.post(
                (body ?: JSONObject()).toString().toRequestBody(JSON_MEDIA_TYPE)
            ).build()
            else -> throw PayOSError.InvalidState("Unsupported HTTP method: $method")
        }
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject?
    ): ApiEnvelope {
        val request = buildRequest(method, path, body)
        return withContext(Dispatchers.IO) {
            execute(request)
        }
    }

    private fun execute(request: Request): ApiEnvelope {
        try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val decoded = responseBody.toJsonObjectOrNull()

                if (!response.isSuccessful || decoded?.optBoolean("error", false) == true) {
                    val code = decoded?.optStringOrNull("code") ?: "http_${response.code}"
                    val message = decoded?.optStringOrNull("message")
                        ?: "Request failed with status ${response.code}"
                    throw PayOSError.BackendError(code, message)
                }

                return ApiEnvelope(
                    statusCode = response.code,
                    data = decoded?.optJSONObject("data"),
                    message = decoded?.optStringOrNull("message"),
                    code = decoded?.optStringOrNull("code"),
                    environment = decoded?.optStringOrNull("environment")
                )
            }
        } catch (error: PayOSError) {
            throw error
        } catch (error: IOException) {
            throw PayOSError.NetworkError(error.message ?: "Connection failed", error)
        } catch (error: Exception) {
            throw PayOSError.NetworkError(error.message ?: "Request failed", error)
        }
    }

    private fun currentConfiguration(): PayOSConfiguration {
        return configuration ?: throw PayOSError.NotConfigured
    }

    private fun buildUrl(path: String, configuration: PayOSConfiguration): String {
        val base = normalizedApiBaseUrl(configuration)
        val normalizedPath = path.trimStart('/')
        return "$base/$normalizedPath"
    }

    private fun normalizedApiBaseUrl(configuration: PayOSConfiguration): String {
        val base = (configuration.apiBaseUrl ?: if (configuration.sandbox) {
            "https://sandbox.api.payos.ai"
        } else {
            "https://api.payos.ai"
        }).trimEnd('/')

        return if (base.endsWith("/api")) base else "$base/api"
    }

    private fun String.toJsonObjectOrNull(): JSONObject? {
        if (isBlank()) return null
        return runCatching { JSONObject(this) }.getOrNull()
    }

    private companion object {
        const val DEFAULT_SDK_VERSION = "0.1.0"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

internal data class ApiEnvelope(
    val statusCode: Int,
    val data: JSONObject?,
    val message: String?,
    val code: String?,
    val environment: String?
)

internal fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}
