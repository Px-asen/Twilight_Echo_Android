package com.twilightecho.android.adapters

import com.twilightecho.android.coreclient.CoreErrorCode
import com.twilightecho.android.runtimehost.NetworkClientPort
import com.twilightecho.android.runtimehost.NetworkRequestSpec
import com.twilightecho.android.runtimehost.NetworkResponsePayload
import com.twilightecho.android.runtimehost.ResourceAccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Android network adapter backed by OkHttp (Section 14.5).
 * Supports coroutine cancellation, bounded timeouts, and structured error mapping.
 * Large audio streams are handled natively by Audio Core rather than buffered across the JS bridge.
 */
class AndroidNetworkAdapter internal constructor(
    private val httpClient: OkHttpClient
) : NetworkClientPort {
    constructor() : this(defaultOkHttpClient())

    override suspend fun execute(spec: NetworkRequestSpec): ResourceAccessResult<NetworkResponsePayload> =
        withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder()
                .url(spec.url)
                .headers(spec.headers.toHeaders())

            val method = spec.method.uppercase()
            val requestBody = spec.bodyUtf8?.toRequestBody(spec.contentType.toMediaTypeOrNull())
            when (method) {
                "GET" -> requestBuilder.get()
                "HEAD" -> requestBuilder.head()
                "DELETE" -> if (requestBody != null) requestBuilder.delete(requestBody) else requestBuilder.delete()
                "POST", "PUT", "PATCH" -> requestBuilder.method(
                    method,
                    requestBody ?: "".toRequestBody(spec.contentType.toMediaTypeOrNull())
                )
                else -> return@withContext ResourceAccessResult.Error(
                    code = CoreErrorCode.INVALID_REQUEST,
                    message = "Unsupported HTTP method: $method"
                )
            }

            val call = httpClient.newCall(requestBuilder.build())
            suspendCancellableCoroutine<ResourceAccessResult<NetworkResponsePayload>> { cont ->
                cont.invokeOnCancellation {
                    call.cancel()
                }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!cont.isActive) return
                        val code = if (call.isCanceled()) CoreErrorCode.CANCELLED else CoreErrorCode.FAILED
                        cont.resume(
                            ResourceAccessResult.Error(
                                code = code,
                                message = e.message ?: "Network IO failure",
                                retryable = !call.isCanceled()
                            )
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { resp ->
                            if (!cont.isActive) return
                            val bodyStr = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                            val headerMap = resp.headers.names().associateWith { name ->
                                resp.headers(name).joinToString(", ")
                            }
                            cont.resume(
                                ResourceAccessResult.Ok(
                                    NetworkResponsePayload(
                                        statusCode = resp.code,
                                        headers = headerMap,
                                        bodyUtf8 = bodyStr
                                    )
                                )
                            )
                        }
                    }
                })
            }
        }

    companion object {
        fun defaultOkHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
    }
}
