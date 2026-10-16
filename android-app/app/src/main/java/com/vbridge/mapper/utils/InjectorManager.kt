package com.vbridge.mapper.utils

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
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
    const val PC_BIN_PATH = "/data/local/tmp/touch_injector"
    const val STANDALONE_BIN_PATH = "/data/local/tmp/vbridge_engine"
    const val DEFAULT_TOUCH_DEV = "/dev/input/event3"
    const val IPC_SOCKET_NAME = "vbridge_ctrl"

    enum class EngineMode(val displayName: String, val binaryName: String, val processName: String) {
        STANDALONE_OTG("Standalone OTG (Zero-PC)", "vbridge_engine", "vbridge_engine"),
        PC_BRIDGE("PC Bridge Mode", "touch_injector", "touch_injector")
    }

    data class InjectorStatus(
        val isRunning: Boolean,
        val mode: EngineMode? = null,
        val pid: String? = null,
        val devicePath: String = DEFAULT_TOUCH_DEV
    )

    data class CommandResult(
        val isSuccess: Boolean,
        val exitCode: Int,
        val output: String
    )

    /**
     * Checks if either the standalone OTG engine or PC bridge daemon is running.
     */
    suspend fun checkStatus(): InjectorStatus = withContext(Dispatchers.IO) {
        // 1. Check Standalone OTG Engine
        val resStandalone = runRootCommand("pidof vbridge_engine", timeoutMs = 1200)
        val pidStandalone = resStandalone.output.trim().split("\\s+".toRegex()).firstOrNull { it.isNotEmpty() }
        if (resStandalone.isSuccess && !pidStandalone.isNullOrEmpty()) {
            return@withContext InjectorStatus(
                isRunning = true,
                mode = EngineMode.STANDALONE_OTG,
                pid = pidStandalone
            )
        }

        // 2. Check PC Bridge Daemon
        val resPc = runRootCommand("pidof touch_injector", timeoutMs = 1200)
        val pidPc = resPc.output.trim().split("\\s+".toRegex()).firstOrNull { it.isNotEmpty() }
        if (resPc.isSuccess && !pidPc.isNullOrEmpty()) {
            return@withContext InjectorStatus(
                isRunning = true,
                mode = EngineMode.PC_BRIDGE,
                pid = pidPc
            )
        }

        InjectorStatus(isRunning = false)
    }

    /**
     * Starts the Standalone On-Device Input Engine (Zero-PC Mode).
     * Intercepts OTG/Bluetooth keyboard & mouse, hides Android cursor, and maps to event3.
     */
    suspend fun startStandaloneEngine(
        context: Context,
        profilePath: String = "/sdcard/VbridgeBind/preset1/keybinding1/profile.json"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Kill existing daemons
            runRootCommand("pkill -9 -f vbridge_engine; pkill -9 -f touch_injector", timeoutMs = 1500)

            // Extract binary from assets if needed
            deployBinaryFromAssets(context, "vbridge_engine", STANDALONE_BIN_PATH)

            // Launch detached
            val cmd = "chmod 755 $STANDALONE_BIN_PATH && nohup $STANDALONE_BIN_PATH -d $DEFAULT_TOUCH_DEV -p \"$profilePath\" </dev/null >/dev/null 2>&1 &"
            runRootCommand(cmd, timeoutMs = 2000)

            delay(350)

            val check = checkStatus()
            if (check.isRunning && check.mode == EngineMode.STANDALONE_OTG) {
                Result.success("Standalone OTG Engine Active (PID: ${check.pid})")
            } else {
                Result.failure(Exception("Failed to launch standalone OTG engine."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "startStandaloneEngine failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Starts the PC Bridge Daemon (listening on TCP port 8888 for Windows client).
     */
    suspend fun startPcBridgeDaemon(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Kill existing daemons
            runRootCommand("pkill -9 -f vbridge_engine; pkill -9 -f touch_injector", timeoutMs = 1500)

            // Extract binary from assets if needed
            deployBinaryFromAssets(context, "touch_injector", PC_BIN_PATH)

            // Launch detached
            val cmd = "chmod 755 $PC_BIN_PATH && nohup $PC_BIN_PATH -d $DEFAULT_TOUCH_DEV </dev/null >/dev/null 2>&1 &"
            runRootCommand(cmd, timeoutMs = 2000)

            delay(350)

            val check = checkStatus()
            if (check.isRunning && check.mode == EngineMode.PC_BRIDGE) {
                Result.success("PC Bridge Daemon Active (PID: ${check.pid})")
            } else {
                Result.failure(Exception("Failed to launch PC bridge daemon."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "startPcBridgeDaemon failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Restarts whichever daemon is active or restarts in specified mode.
     */
    suspend fun restartInjector(context: Context, preferredMode: EngineMode = EngineMode.STANDALONE_OTG): Result<String> {
        return if (preferredMode == EngineMode.STANDALONE_OTG) {
            startStandaloneEngine(context)
        } else {
            startPcBridgeDaemon(context)
        }
    }

    /**
     * Sends a real-time IPC message to vbridge_engine via abstract Unix domain socket (@vbridge_ctrl).
     * Zero-overhead, sub-millisecond execution!
     */
    suspend fun sendIpcCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(IPC_SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                socket.outputStream.write(command.toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
            }
            Log.i(TAG, "IPC command sent successfully: $command")
            true
        } catch (e: Exception) {
            Log.w(TAG, "IPC command failed (engine might be in PC mode or stopped): ${e.message}")
            false
        }
    }

    private fun deployBinaryFromAssets(context: Context, assetName: String, targetPath: String) {
        val localFile = File(context.filesDir, assetName)
        try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(localFile).use { output ->
                    input.copyTo(output)
                }
            }
            localFile.setExecutable(true, false)
            runRootCommand("cp ${localFile.absolutePath} $targetPath && chmod 755 $targetPath", timeoutMs = 1500)
        } catch (e: Exception) {
            Log.w(TAG, "deployBinaryFromAssets warning for $assetName: ${e.message}")
        }
    }

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
