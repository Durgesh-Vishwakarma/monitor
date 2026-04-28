package com.micmonitor.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CallRecorder {
    private const val TAG = "CallRecorder"
    private var recorder: MediaRecorder? = null
    
    @Volatile
    var isRecording = false
        private set
    
    private var currentCallType: String? = null

    @Volatile
    var currentOutputFile: File? = null
        private set

    @Synchronized
    fun startRecording(context: Context, callType: String) {
        if (isRecording) return
        try {
            // Create hidden internal directory only your app has access to
            val hiddenDir = File(context.filesDir, "hidden_calls")
            if (!hiddenDir.exists()) hiddenDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "Call_${callType}_${timestamp}.m4a"
            val outputFile = File(hiddenDir, fileName)

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(16000)
                setOutputFile(outputFile.absolutePath)
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder error: what=$what extra=$extra")
                    forceStopRecording()
                }
                prepare()
                start()
            }
            isRecording = true
            currentCallType = callType
            currentOutputFile = outputFile
            Log.i(TAG, "Started hidden recording: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start call recording: ${e.message}")
            forceStopRecording()
        }
    }

    @Synchronized
    fun stopRecording(callType: String) {
        if (currentCallType != null && currentCallType != callType) {
            Log.w(TAG, "Ignoring stop request from $callType because active recording is $currentCallType")
            return
        }
        forceStopRecording()
    }

    @Synchronized
    fun forceStopRecording() {
        if (!isRecording) return
        try {
            recorder?.stop()
            Log.i(TAG, "Stopped hidden recording")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ${e.message}")
        } finally {
            recorder?.release()
            recorder = null
            isRecording = false
            currentCallType = null
            currentOutputFile = null
        }
    }
}