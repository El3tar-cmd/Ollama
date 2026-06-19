package com.example

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class OllamaModel(
    val name: String,
    val size: Long
)

data class ChatMessage(
    val role: String,
    val content: String
)

class OllamaApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES) // Large timeout for pulling/inference streams
        .writeTimeout(15, TimeUnit.MINUTES)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    fun checkRunning(baseUrl: String, callback: (Boolean, String) -> Unit) {
        val request = Request.Builder()
            .url(baseUrl)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false, e.message ?: "Failed connection check")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    callback(true, response.body?.string() ?: "Online")
                } else {
                    callback(false, "Offline (HTTP ${response.code})")
                }
            }
        })
    }

    fun listModels(baseUrl: String, callback: (List<OllamaModel>?, String) -> Unit) {
        val request = Request.Builder()
            .url("$baseUrl/api/tags")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null, e.message ?: "Failed to list local models")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val respBody = response.body?.string()
                    if (response.isSuccessful && !respBody.isNullOrEmpty()) {
                        val json = JSONObject(respBody)
                        val array = json.optJSONArray("models")
                        val list = mutableListOf<OllamaModel>()
                        if (array != null) {
                            for (i in 0 until array.length()) {
                                val item = array.getJSONObject(i)
                                list.add(
                                    OllamaModel(
                                        name = item.getString("name"),
                                        size = item.optLong("size", 0)
                                    )
                                )
                            }
                        }
                        callback(list, "Success")
                    } else {
                        callback(null, "Server code ${response.code}")
                    }
                } catch (e: Exception) {
                    callback(null, "Decoding failure: ${e.message}")
                }
            }
        })
    }

    fun deleteModel(baseUrl: String, modelName: String, callback: (Boolean, String) -> Unit) {
        val jsonPayload = JSONObject().put("name", modelName).toString()
        val body = jsonPayload.toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url("$baseUrl/api/delete")
            .delete(body)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false, e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.code == 200) {
                    callback(true, "Model deleted successfully")
                } else {
                    callback(false, "Deletion failed: HTTP ${response.code}")
                }
            }
        })
    }

    fun pullModelStream(
        baseUrl: String,
        modelName: String,
        onProgress: (String, Int) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        val jsonPayload = JSONObject().put("name", modelName).toString()
        val body = jsonPayload.toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url("$baseUrl/api/pull")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onComplete(false, e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onComplete(false, "Server rejected pull request (HTTP ${response.code})")
                    return
                }

                val source = response.body?.source()
                if (source == null) {
                    onComplete(false, "Null response stream")
                    return
                }

                try {
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isNotEmpty()) {
                            try {
                                val json = JSONObject(line)
                                if (json.has("error")) {
                                    onComplete(false, json.getString("error"))
                                    return
                                }
                                val status = json.optString("status", "")
                                val total = json.optLong("total", 0L)
                                val completed = json.optLong("completed", 0L)

                                if (total > 0) {
                                    val pct = (completed * 100 / total).toInt()
                                    onProgress(status, pct)
                                } else {
                                    onProgress(status, -1)
                                }
                            } catch (e: Exception) {
                                // Ignore parsing errors on intermediate log streams
                            }
                        }
                    }
                    onComplete(true, "Model pulled successfully")
                } catch (e: Exception) {
                    onComplete(false, "Pull stream interrupted: ${e.message}")
                }
            }
        })
    }

    fun chatStream(
        baseUrl: String,
        modelName: String,
        messages: List<ChatMessage>,
        onTokenGenerated: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        val messagesArray = org.json.JSONArray()
        for (msg in messages) {
            messagesArray.put(
                JSONObject()
                    .put("role", msg.role)
                    .put("content", msg.content)
            )
        }

        val payload = JSONObject()
            .put("model", modelName)
            .put("messages", messagesArray)
            .put("stream", true)
            .toString()

        val body = payload.toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url("$baseUrl/api/chat")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onComplete(false, e.message ?: "Inference connection error")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onComplete(false, "Inference server error (HTTP ${response.code})")
                    return
                }

                val source = response.body?.source()
                if (source == null) {
                    onComplete(false, "Empty inference stream received")
                    return
                }

                try {
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isNotEmpty()) {
                            try {
                                val json = JSONObject(line)
                                if (json.has("error")) {
                                    onComplete(false, json.getString("error"))
                                    return
                                }
                                val done = json.optBoolean("done", false)
                                val msgObj = json.optJSONObject("message")
                                val token = msgObj?.optString("content", "") ?: ""

                                if (token.isNotEmpty()) {
                                    onTokenGenerated(token)
                                }
                                if (done) {
                                    break
                                }
                            } catch (e: Exception) {
                                // Silent skip malformed streams
                            }
                        }
                    }
                    onComplete(true, "Inference stream complete")
                } catch (e: Exception) {
                    onComplete(false, "Inference stream interrupted: ${e.message}")
                }
            }
        })
    }
}
