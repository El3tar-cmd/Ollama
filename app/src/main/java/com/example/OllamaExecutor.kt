package com.example

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class OllamaExecutor(private val context: Context) {
    private val TAG = "OllamaExecutor"
    val binDir = File(context.filesDir, "bin")
    val ollamaFile = File(binDir, "ollama")
    val modelsDir = File(context.filesDir, "ollama_models")

    fun setupEnvironment() {
        if (!binDir.exists()) binDir.mkdirs()
        if (!modelsDir.exists()) modelsDir.mkdirs()
        if (ollamaFile.exists()) {
            ollamaFile.setExecutable(true, false)
        }
    }

    fun startOllamaService(
        host: String = "127.0.0.1:11434",
        origins: String = "*",
        onLog: (String) -> Unit
    ): Process? {
        setupEnvironment()
        if (!ollamaFile.exists()) {
            onLog("Error: Ollama executable not found. Please click 'Setup/Download' first.")
            return null
        }

        try {
            val pb = ProcessBuilder(ollamaFile.absolutePath, "serve")
            val env = pb.environment()
            env["OLLAMA_HOST"] = host
            env["OLLAMA_MODELS"] = modelsDir.absolutePath
            env["OLLAMA_ORIGINS"] = origins
            env["HOME"] = context.filesDir.absolutePath
            env["TMPDIR"] = context.cacheDir.absolutePath
            env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir

            pb.redirectErrorStream(true)
            val proc = pb.start()

            // Spawn separate thread to parse streaming logs of the daemon
            Thread {
                try {
                    val reader = proc.inputStream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        onLog(line ?: "")
                    }
                } catch (e: Exception) {
                    onLog("Log pipe closed: ${e.message}")
                }
            }.start()

            return proc
        } catch (e: Exception) {
            onLog("Failed to start Ollama executable: ${e.message}")
            Log.e(TAG, "Process init failed", e)
            return null
        }
    }

    fun stopOllamaService(process: Process?) {
        try {
            process?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping process", e)
        }
    }

    fun downloadBinary(
        downloadUrlStr: String,
        onProgress: (Int, String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        setupEnvironment()
        Thread {
            try {
                onProgress(0, "Connecting to binary server...")
                var currentUrlStr = downloadUrlStr
                var conn: HttpURLConnection
                var responseCode: Int
                var redirectCount = 0
                val maxRedirects = 5

                // Manual redirect handling loops to support raw/releases downloads safely
                while (true) {
                    val url = URL(currentUrlStr)
                    conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.instanceFollowRedirects = false
                    conn.connect()
                    responseCode = conn.responseCode

                    if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || 
                        responseCode == HttpURLConnection.HTTP_MOVED_PERM || 
                        responseCode == 307 || responseCode == 308) {
                        val newUrl = conn.getHeaderField("Location")
                        if (newUrl.isNullOrEmpty()) {
                            throw IOException("Redirect received with empty Location header.")
                        }
                        currentUrlStr = newUrl
                        redirectCount++
                        if (redirectCount > maxRedirects) {
                            throw IOException("Too many redirects")
                        }
                        onProgress(5, "Loading redirect endpoint...")
                        conn.disconnect()
                        continue
                    }
                    break
                }

                val contentLength = conn.contentLength
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    onComplete(false, "Server active check failed: HTTP $responseCode")
                    return@Thread
                }

                onProgress(10, "Downloading executable...")
                val tmpFile = File(context.cacheDir, "ollama_bin.tmp")
                if (tmpFile.exists()) tmpFile.delete()

                conn.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        var totalBytes: Long = 0
                        val bis = BufferedInputStream(input)

                        while (bis.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                            if (contentLength > 0) {
                                val percentage = (totalBytes * 100 / contentLength).toInt()
                                onProgress(percentage, "Downloading: $percentage% (${totalBytes / 1024 / 1024}MB)")
                            } else {
                                onProgress(-1, "Downloading: ${totalBytes / 1024 / 1024}MB (Indefinite)")
                            }
                        }
                    }
                }

                onProgress(90, "Applying operational files...")
                val isTgz = downloadUrlStr.endsWith(".tgz") || downloadUrlStr.endsWith(".tar.gz")
                if (isTgz) {
                    onProgress(92, "Decompressing archive package...")
                    try {
                        extractTarGzipStream(tmpFile, binDir)
                    } catch (e: Exception) {
                        throw IOException("Decompression failed: ${e.message}", e)
                    }
                    var foundBin: File? = null
                    binDir.walkTopDown().forEach { file ->
                        if (file.isFile && file.name == "ollama" && file.absolutePath != ollamaFile.absolutePath) {
                            foundBin = file
                        }
                    }
                    if (foundBin != null) {
                        if (ollamaFile.exists()) ollamaFile.delete()
                        foundBin!!.copyTo(ollamaFile, overwrite = true)
                        binDir.listFiles()?.forEach { child ->
                            if (child.absolutePath != ollamaFile.absolutePath) {
                                child.deleteRecursively()
                            }
                        }
                        tmpFile.delete()
                    } else {
                        throw IOException("Ollama binary not found in extracted archive mapping.")
                    }
                } else {
                    if (ollamaFile.exists()) ollamaFile.delete()
                    tmpFile.copyTo(ollamaFile, overwrite = true)
                    tmpFile.delete()
                }

                ollamaFile.setExecutable(true, false)
                onComplete(true, "Standalone binary installed successfully under files/bin/ollama")
            } catch (e: Exception) {
                onComplete(false, "Setup interrupted: ${e.message}")
            }
        }.start()
    }

    private fun extractTarGzipStream(tarGzFile: File, destDir: File) {
        GZIPInputStream(FileInputStream(tarGzFile)).use { gzis ->
            val progressInputStream = BufferedInputStream(gzis)
            val header = ByteArray(512)
            while (true) {
                var bytesRead = 0
                while (bytesRead < 512) {
                    val n = progressInputStream.read(header, bytesRead, 512 - bytesRead)
                    if (n == -1) break
                    bytesRead += n
                }
                if (bytesRead < 512) {
                    break
                }
                
                var allNulls = true
                for (b in header) {
                    if (b != 0.toByte()) {
                        allNulls = false
                        break
                    }
                }
                if (allNulls) {
                    break
                }
                
                var nameEnd = 0
                while (nameEnd < 100 && header[nameEnd] != 0.toByte()) {
                    nameEnd++
                }
                val name = String(header, 0, nameEnd, Charsets.UTF_8).trim()
                if (name.isEmpty()) continue
                
                var sizeStart = 124
                val sizeEnd = 124 + 12
                while (sizeStart < sizeEnd && (header[sizeStart] == ' '.toByte() || header[sizeStart] == 0.toByte())) {
                    sizeStart++
                }
                var realSizeEnd = sizeEnd
                while (realSizeEnd > sizeStart && (header[realSizeEnd - 1] == ' '.toByte() || header[realSizeEnd - 1] == 0.toByte())) {
                    realSizeEnd--
                }
                
                val sizeStr = if (sizeStart < realSizeEnd) {
                    String(header, sizeStart, realSizeEnd - sizeStart, Charsets.UTF_8)
                } else {
                    "0"
                }
                
                val fileSize = try {
                    sizeStr.toLong(8)
                } catch (e: Exception) {
                    0L
                }
                
                val typeFlag = header[156]
                val isDirectory = typeFlag == '5'.toByte() || name.endsWith("/")
                
                val targetFile = File(destDir, name)
                if (isDirectory) {
                    targetFile.mkdirs()
                } else {
                    targetFile.parentFile?.mkdirs()
                    FileOutputStream(targetFile).use { fos ->
                        var remaining = fileSize
                        val buf = ByteArray(16384)
                        while (remaining > 0) {
                            val toRead = minOf(remaining, buf.size.toLong()).toInt()
                            val r = progressInputStream.read(buf, 0, toRead)
                            if (r == -1) {
                                throw IOException("Unexpected End of Stream in Tar extraction for $name")
                            }
                            fos.write(buf, 0, r)
                            remaining -= r
                        }
                    }
                }
                
                val padding = (512 - (fileSize % 512)) % 512
                var skipped = 0L
                while (skipped < padding) {
                    val skipBuf = ByteArray(minOf(padding - skipped, 4096).toInt())
                    val r = progressInputStream.read(skipBuf)
                    if (r == -1) break
                    skipped += r
                }
            }
        }
    }
}
