package com.cdspadvanced

import android.content.Context
import android.util.Log
import java.io.File

class DaemonProcessManager(private val context: Context) {

    companion object {
        private const val TAG = "DaemonProcessManager"
        private const val DAEMON_NAME = "minidspd"
        private const val DEFAULT_PORT = 5380
    }

    private var process: Process? = null

    fun start(usbFd: Int) {
        stop()

        val binary = findBinary()
        if (binary == null) {
            Log.e(TAG, "minidspd binary not found")
            return
        }

        Log.i(TAG, "Starting daemon: ${binary.absolutePath} --usb-fd $usbFd")

        try {
            val pb = ProcessBuilder(
                binary.absolutePath,
                "--usb-fd", usbFd.toString(),
                "--bind", "127.0.0.1:$DEFAULT_PORT"
            )
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            process = pb.start()

            // Log output in background
            Thread {
                try {
                    process?.inputStream?.bufferedReader()?.use { reader ->
                        reader.forEachLine { line ->
                            Log.d(TAG, "daemon: $line")
                        }
                    }
                } catch (_: Exception) {}
            }.start()

            Log.i(TAG, "Daemon started on port $DEFAULT_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start daemon: ${e.message}", e)
        }
    }

    fun stop() {
        process?.let {
            Log.i(TAG, "Stopping daemon")
            try {
                it.destroy()
            } catch (_: Exception) {}
            process = null
        }

        // Also kill any lingering instances
        try {
            Runtime.getRuntime().exec(arrayOf("killall", DAEMON_NAME))
        } catch (_: Exception) {}
    }

    private fun findBinary(): File? {
        val binDir = File(context.filesDir, "bin")
        val candidates = listOf(
            File(binDir, DAEMON_NAME),
            File(context.applicationInfo.nativeLibraryDir, "lib$DAEMON_NAME.so"),
            File("/data/local/tmp/$DAEMON_NAME"),
            File("/data/local/tmp/minidsp/$DAEMON_NAME")
        )
        return candidates.firstOrNull { it.exists() && it.canExecute() }
    }
}
