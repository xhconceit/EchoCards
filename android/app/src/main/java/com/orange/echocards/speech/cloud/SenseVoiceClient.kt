package com.orange.echocards.speech.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * SenseVoice 云端转写端口。上传 WAV，返回转写文字；失败抛异常，由 [SenseVoiceErrorMapper] 统一映射。
 */
interface SenseVoiceClient {
    suspend fun transcribe(wav: ByteArray): String
}

/** 服务返回非 2xx 时抛出，携带状态码供错误映射区分可重试性。 */
class SenseVoiceApiException(val statusCode: Int, override val message: String) : IOException(message)

/**
 * SiliconFlow SenseVoice 的 OpenAI 风格批量转写实现：
 * `POST {baseUrl}/audio/transcriptions`，multipart 上传 `file` + `model`，响应 `{"text":"..."}`。
 */
class OkHttpSenseVoiceClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.siliconflow.cn/v1",
    private val model: String = "FunAudioLLM/SenseVoiceSmall",
    private val client: OkHttpClient = OkHttpClient(),
) : SenseVoiceClient {

    override suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$baseUrl/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw SenseVoiceApiException(response.code, "识别服务返回 ${response.code}：${preview(responseText)}")
            }
            JSONObject(responseText).getString("text")
        }
    }

    private fun preview(text: String): String = if (text.length <= 120) text else text.take(120) + "…"
}
