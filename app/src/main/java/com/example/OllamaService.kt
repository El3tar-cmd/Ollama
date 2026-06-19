package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.Collections

class OllamaService : Service() {
    companion object {
        var isRunning = false
        var activeProcess: Process? = null
        val logBuffer = Collections.synchronizedList(mutableListOf<String>())
        var onLogReceived: ((String) -> Unit)? = null
        var serviceInstance: OllamaService? = null
    }

    private lateinit var ollamaExecutor: OllamaExecutor

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        ollamaExecutor = OllamaExecutor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getStringExtra("host") ?: "127.0.0.1:11434"
        val origins = intent?.getStringExtra("origins") ?: "*"

        isRunning = true
        logBuffer.clear()
        addLog("Ollama background service initialized.")

        startForegroundServiceNotification()

        val oldProcess = activeProcess
        if (oldProcess != null) {
            ollamaExecutor.stopOllamaService(oldProcess)
            activeProcess = null
        }

        activeProcess = ollamaExecutor.startOllamaService(host, origins) { line ->
            addLog(line)
        }

        return START_NOT_STICKY
    }

    private fun addLog(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            synchronized(logBuffer) {
                logBuffer.add(trimmed)
                if (logBuffer.size > 1500) {
                    logBuffer.removeAt(0)
                }
            }
            onLogReceived?.invoke(trimmed)
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "OllamaChannel"
        val manager = getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            channelId,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager?.createNotificationChannel(channel)

        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_msg))
            .setSmallIcon(android.R.drawable.ic_media_play) // Standard play icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(11434, notification)
    }

    override fun onDestroy() {
        isRunning = false
        val proc = activeProcess
        if (proc != null) {
            ollamaExecutor.stopOllamaService(proc)
            activeProcess = null
        }
        serviceInstance = null
        addLog("Ollama service stopped.")
        super.onDestroy()
    }
}
