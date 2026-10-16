package com.vbridge.mapper.utils

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object InjectorManager {
    private const val TAG = "InjectorManager"
    const val TARGET_BIN_PATH = "/data/local/tmp/touch_injector"
    const val DEFAULT_TOUCH_DEV = "/dev/input/event3"

    data class InjectorStatus(
        val isRunning: Boolean,
        val pid: String? = null,
        val devicePath: String = DEFAULT_TOUCH_DEV
    )

    data class CommandResult(
        val isSuccess: Boolean,
        val exitCode: Int,
        val output: String
    )

    /**
     * Checks if touch_injector daemon is running via root shell.
     */
    suspend fun checkStatus(): InjectorStatus = withContext(Dispatchers.IO) {
        val res = runRootCommand("pidof touch_injector", timeoutMs = 1500)
        val pid = res.output.trim().split("\\s+".toRegex()).firstOrNull { it.isNotEmpty() }
        val isRunning = res.isSuccess && !pid.isNullOrEmpty()
        InjectorStatus(isRunning = isRunning, pid = pid)
    }

    /**
     * Extracts bundled touch_injector binary from assets to internal storage,
     * copies it to /data/local/tmp/, chmods 755, and launches the daemon in background.
     */
    suspend fun restartInjector(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 1. Kill any existing instance
            runRootCommand("pkill -9 -f touch_injector", timeoutMs = 1500)

            // 2. Ensure binary exists in /data/local/tmp/
            val localAssetBin = File(context.filesDir, "touch_injector")
            try {
                context.assets.open("touch_injector").use { input ->
                    FileOutputStream(localAssetBin).use { output ->
                        input.copyTo(output)
                    }
                }
                localAssetBin.setExecutable(true, false)
                runRootCommand("cp ${localAssetBin.absolutePath} $TARGET_BIN_PATH", timeoutMs = 1500)
            } catch (e: Exception) {
                Log.w(TAG, "Asset copy skipped or failed: ${e.message}")
            }

            // 3. Set permissions & launch daemon with nohup and detached I/O
            val launchCmd = "chmod 755 $TARGET_BIN_PATH && nohup $TARGET_BIN_PATH -d $DEFAULT_TOUCH_DEV </dev/null >/dev/null 2>&1 &"
            runRootCommand(launchCmd, timeoutMs = 2000)

            // 4. Give the kernel daemon 350ms to bind socket
            delay(350)

            // 5. Verify PID
            val check = checkStatus()
            if (check.isRunning && check.pid != null) {
                Result.success("Touch injector running (PID: ${check.pid})")
            } else {
                Result.failure(Exception("Daemon launched but PID not detected."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart injector", e)
            Result.failure(e)
        }
    }

    /**
     * Executes a command string using ProcessBuilder with su -c and a strict timeout.
     */
    fun runRootCommand(command: String, timeoutMs: Long = 2500): CommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            val output = StringBuilder()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))

            val tOut = Thread {
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (output.isNotEmpty()) output.append("\n")
                        output.append(line)
                    }
                } catch (_: Exception) {}
            }
            val tErr = Thread {
                try {
                    var line: String?
                    while (errReader.readLine().also { line = it } != null) {
                        if (output.isNotEmpty()) output.append("\n")
                        output.append(line)
                    }
                } catch (_: Exception) {}
            }
            tOut.start()
            tErr.start()

            val finished = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                process.waitFor()
                true
            }

            if (!finished) {
                process.destroyForcibly()
                tOut.interrupt()
                tErr.interrupt()
            }

            val exitCode = if (finished) process.exitValue() else -1
            CommandResult(exitCode == 0, exitCode, output.toString().trim())
        } catch (e: Exception) {
            Log.e(TAG, "runRootCommand error: ${e.message}")
            CommandResult(false, -1, e.message ?: "Unknown error")
        }
    }
}
