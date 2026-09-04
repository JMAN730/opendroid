package com.opendroid.ai.actions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.StatFs
import android.provider.MediaStore
import android.media.AudioManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.opendroid.ai.accessibility.GenericAppAutomator
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.storage.StorageWorkspaceProvider
import com.opendroid.ai.core.util.DeviceCapabilities
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.delay

@Singleton
class AdvancedControlActions @Inject constructor() {

    companion object {
        /** Hard ceiling for the WAIT action so a plan can never stall execution indefinitely. */
        private const val MAX_WAIT_MS = 10_000L

        private fun hasStoragePermission(context: Context): Boolean {
            return StorageWorkspaceProvider.hasStorageAccess(context)
        }

        private fun checkStoragePermission(context: Context): ActionResult? {
            if (!hasStoragePermission(context)) {
                return ActionResult(false, null, "Storage / Files access is not available.")
            }
            return null
        }

        private fun resolvePath(pathStr: String, context: Context): File {
            return StorageWorkspaceProvider.resolveFile(context, pathStr)
        }
    }

    fun getActions(): List<Action> = listOf(
        GetSystemInfoAction(),
        SetRingerModeAction(),
        ListFilesAction(),
        ReadFileAction(),
        WriteFileAction(),
        DeleteFileAction(),
        CreateDirectoryAction(),
        CopyFileAction(),
        MoveFileAction(),
        ZipFilesAction(),
        UnzipFileAction(),
        TakePhotoBackgroundAction(),
        ListInstalledAppsAction(),
        CloseAppAction(),
        ClickTextAction(),
        ClickIdAction(),
        TypeTextAction(),
        TypeIdAction(),
        ScrollAction(),
        GetScreenTextAction(),
        ClickCoordinatesAction(),
        PressEnterAction(),
        WaitAction()
    )

    private class GetSystemInfoAction : Action {
        override val name: String = "GET_SYSTEM_INFO"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val isCharging = batteryManager.isCharging

                val stat = StatFs(Environment.getDataDirectory().path)
                val totalStorage = (stat.blockCountLong * stat.blockSizeLong) / (1024 * 1024 * 1024)
                val freeStorage = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024 * 1024)

                val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val memInfo = android.app.ActivityManager.MemoryInfo()
                actManager.getMemoryInfo(memInfo)
                val totalMem = memInfo.totalMem / (1024 * 1024)
                val availMem = memInfo.availMem / (1024 * 1024)

                val connManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val isConnected: Boolean
                val networkType: String
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val network = connManager.activeNetwork
                    val capabilities = connManager.getNetworkCapabilities(network)
                    isConnected = capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    networkType = when {
                        capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                        capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "MOBILE"
                        capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
                        capabilities != null -> "OTHER"
                        else -> "NONE"
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val activeNetwork = connManager.activeNetworkInfo
                    isConnected = activeNetwork?.isConnectedOrConnecting == true
                    @Suppress("DEPRECATION")
                    networkType = activeNetwork?.typeName ?: "UNKNOWN"
                }

                val info = """
                    Battery: $batteryPct% (Charging: $isCharging)
                    Storage: Free $freeStorage GB / Total $totalStorage GB
                    Memory: Free $availMem MB / Total $totalMem MB
                    Network: Connected=$isConnected (Type=$networkType)
                    OS Version: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
                    Device: ${Build.MANUFACTURER} ${Build.MODEL}
                """.trimIndent()

                ActionResult(true, info, null)
            } catch (e: Exception) {
                ActionResult(false, null, "Couldn't get system info right now.")
            }
        }
    }

    private class SetRingerModeAction : Action {
        override val name: String = "SET_RINGER_MODE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val modeStr = params["mode"] ?: "normal"
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val targetMode = when (modeStr.lowercase()) {
                "silent" -> AudioManager.RINGER_MODE_SILENT
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            return try {
                audioManager.ringerMode = targetMode
                ActionResult(true, "Ringer is on $modeStr now!", null)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(false, "Couldn't change the ringer directly. I opened the settings for you.", e.localizedMessage, true)
            }
        }
    }

    private class ListFilesAction : Action {
        override val name: String = "LIST_FILES"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            checkStoragePermission(context)?.let { return it }
            val pathStr = params["path"]
            return StorageWorkspaceProvider.listFiles(context, pathStr)
        }
    }

    private class ReadFileAction : Action {
        override val name: String = "READ_FILE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            checkStoragePermission(context)?.let { return it }
            val filePath = params["filePath"] ?: return ActionResult(false, null, "filePath parameter is missing")
            return StorageWorkspaceProvider.readFile(context, filePath)
        }
    }

    private class WriteFileAction : Action {
        override val name: String = "WRITE_FILE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            checkStoragePermission(context)?.let { return it }
            val filePath = params["filePath"] ?: return ActionResult(false, null, "filePath parameter is missing")
            val content = params["content"] ?: ""
            return StorageWorkspaceProvider.writeFile(context, filePath, content)
        }
    }

    private class DeleteFileAction : Action {
        override val name: String = "DELETE_FILE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            checkStoragePermission(context)?.let { return it }
            val filePath = params["filePath"] ?: return ActionResult(false, null, "filePath parameter is missing")
            return StorageWorkspaceProvider.deleteFile(context, filePath)
        }
    }

    private class TakePhotoBackgroundAction : Action {
        override val name: String = "TAKE_PHOTO_BACKGROUND"

        @SuppressLint("MissingPermission")
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            // Camera hardware is optional (see AndroidManifest uses-feature) — no
            // point opening a camera app on a device that has no camera at all.
            if (!DeviceCapabilities.hasCamera(context)) {
                return ActionResult(false, null, "This device doesn't have a camera.")
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return launchCameraIntentFallback(context, "Camera permission missing. Launched camera app instead.")
            }
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            return try {
                val cameraIdList = cameraManager.cameraIdList
                if (cameraIdList.isEmpty()) {
                    return ActionResult(false, null, "No cameras available on this device")
                }
                val cameraId = cameraIdList.firstOrNull { id ->
                    val chars = cameraManager.getCameraCharacteristics(id)
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: cameraIdList[0]

                val photoFile = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "background_photo_${System.currentTimeMillis()}.jpg")
                val success = captureStillImage(context, cameraManager, cameraId, photoFile)
                if (success) {
                    ActionResult(true, "Photo saved!", null)
                } else {
                    launchCameraIntentFallback(context, "Background capture failed. Launched camera app instead.")
                }
            } catch (e: Exception) {
                launchCameraIntentFallback(context, "Couldn't take a background photo, so I opened the camera app.")
            }
        }

        private fun launchCameraIntentFallback(context: Context, msg: String): ActionResult {
            return try {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "$msg Camera app opened.", null, true)
            } catch (e: Exception) {
                ActionResult(false, null, "Couldn't open the camera app.")
            }
        }

        private suspend fun captureStillImage(
            context: Context,
            cameraManager: CameraManager,
            cameraId: String,
            outputFile: File
        ): Boolean = suspendCoroutine { continuation ->
            // Guard against SecurityException from openCamera - the permission can
            // be revoked between the caller's check and this capture starting.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                continuation.resume(false)
                return@suspendCoroutine
            }
            val handlerThread = HandlerThread("CameraBackgroundThread")
            handlerThread.start()
            val backgroundHandler = Handler(handlerThread.looper)

            var cameraDevice: CameraDevice? = null
            var captureSession: CameraCaptureSession? = null
            var imageReader: ImageReader? = null
            var isResumed = false

            fun cleanUp() {
                try {
                    captureSession?.close()
                    cameraDevice?.close()
                    imageReader?.close()
                    handlerThread.quitSafely()
                } catch (e: Exception) {
                    // Ignore cleanup exceptions
                }
            }

            fun resumeOnce(result: Boolean) {
                if (!isResumed) {
                    isResumed = true
                    cleanUp()
                    continuation.resume(result)
                }
            }

            try {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(ImageFormat.JPEG)
                val size = sizes?.firstOrNull { it.width <= 1920 && it.height <= 1080 } ?: sizes?.firstOrNull() ?: android.util.Size(640, 480)

                imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
                imageReader.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val buffer: ByteBuffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()
                        try {
                            FileOutputStream(outputFile).use { it.write(bytes) }
                            resumeOnce(true)
                        } catch (e: Exception) {
                            resumeOnce(false)
                        }
                    } else {
                        resumeOnce(false)
                    }
                }, backgroundHandler)

                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        val targets = listOf(imageReader.surface)
                        camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                try {
                                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    builder.addTarget(imageReader.surface)
                                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    
                                    session.capture(builder.build(), null, backgroundHandler)
                                } catch (e: Exception) {
                                    resumeOnce(false)
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                resumeOnce(false)
                            }
                        }, backgroundHandler)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        resumeOnce(false)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        resumeOnce(false)
                    }
                }, backgroundHandler)

                // Safety timeout
                backgroundHandler.postDelayed({
                    resumeOnce(false)
                }, 8000)

            } catch (e: Exception) {
                resumeOnce(false)
            }
        }
    }

    private class ListInstalledAppsAction : Action {
        override val name: String = "LIST_INSTALLED_APPS"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val pm = context.packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val appList = apps.map { app ->
                    val label = pm.getApplicationLabel(app).toString()
                    val packageName = app.packageName
                    "$label ($packageName)"
                }.sorted().joinToString("\n")
                ActionResult(true, appList, null)
            } catch (e: Exception) {
                ActionResult(false, null, "Couldn't list the apps right now.")
            }
        }
    }

    private class CloseAppAction : Action {
        override val name: String = "CLOSE_APP"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val success = GenericAppAutomator.pressHome()
            return ActionResult(success, if (success) "Done, went to the home screen." else "Couldn't close the app.", null)
        }
    }

    private class ClickTextAction : Action {
        override val name: String = "CLICK_TEXT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val text = params["text"] ?: return ActionResult(false, null, "text parameter is missing")
            val success = GenericAppAutomator.clickText(text)
            return ActionResult(success, if (success) "Tapped on '$text'!" else "Couldn't find '$text' to tap on.", null)
        }
    }

    private class ClickIdAction : Action {
        override val name: String = "CLICK_ID"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val viewId = params["viewId"] ?: return ActionResult(false, null, "viewId parameter is missing")
            val success = GenericAppAutomator.clickId(viewId)
            return ActionResult(success, if (success) "Tapped the element!" else "Couldn't find that element.", null)
        }
    }

    private class TypeTextAction : Action {
        override val name: String = "TYPE_TEXT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val searchText = params["searchText"] ?: return ActionResult(false, null, "searchText parameter is missing")
            val content = params["content"] ?: ""
            val success = GenericAppAutomator.typeText(searchText, content)
            return ActionResult(success, if (success) "Typed it in!" else "Couldn't find that text field.", null)
        }
    }

    private class TypeIdAction : Action {
        override val name: String = "TYPE_ID"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val viewId = params["viewId"] ?: return ActionResult(false, null, "viewId parameter is missing")
            val content = params["content"] ?: ""
            val success = GenericAppAutomator.typeId(viewId, content)
            return ActionResult(success, if (success) "Typed it in!" else "Couldn't find that field.", null)
        }
    }

    private class ScrollAction : Action {
        override val name: String = "SCROLL"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val direction = params["direction"] ?: "forward"
            val forward = direction.lowercase() == "forward"
            val success = GenericAppAutomator.scroll(forward)
            return ActionResult(success, if (success) "Scrolled $direction!" else "Can't scroll here.", null)
        }
    }

    private class GetScreenTextAction : Action {
        override val name: String = "GET_SCREEN_TEXT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val text = GenericAppAutomator.scrapeScreen()
            return ActionResult(true, text.ifEmpty { "No text visible on screen" }, null)
        }
    }

    private class ClickCoordinatesAction : Action {
        override val name: String = "CLICK_COORDINATES"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val x = params["x"]?.toFloatOrNull() ?: return ActionResult(false, null, "x coordinate is missing or invalid")
            val y = params["y"]?.toFloatOrNull() ?: return ActionResult(false, null, "y coordinate is missing or invalid")
            val success = GenericAppAutomator.clickCoordinates(x, y)
            return ActionResult(success, if (success) "Tapped there!" else "Couldn't tap at that spot.", null)
        }
    }

    private class PressEnterAction : Action {
        override val name: String = "PRESS_ENTER"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val success = GenericAppAutomator.pressEnter()
            return ActionResult(success, if (success) "Submitted!" else "Couldn't find a focused field to submit.", null)
        }
    }

    private class WaitAction : Action {
        override val name: String = "WAIT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val requestedMs = params["durationMs"]?.toLongOrNull() ?: 2000L
            val clampedMs = requestedMs.coerceIn(0L, MAX_WAIT_MS)
            delay(clampedMs)
            return ActionResult(true, "Waited ${clampedMs}ms.", null)
        }
    }

    private class CreateDirectoryAction : Action {
        override val name: String = "CREATE_DIRECTORY"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            checkStoragePermission(context)?.let { return it }
            val pathStr = params["path"] ?: return ActionResult(false, null, "path parameter is missing")
            return StorageWorkspaceProvider.createDirectory(context, pathStr)
        }
    }

    private class CopyFileAction : Action {
        override val name: String = "COPY_FILE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            checkStoragePermission(context)?.let { return it }
            val srcPath = params["sourcePath"] ?: return ActionResult(false, null, "sourcePath parameter is missing")
            val destPath = params["destPath"] ?: return ActionResult(false, null, "destPath parameter is missing")
            return StorageWorkspaceProvider.copyFile(context, srcPath, destPath)
        }
    }

    private class MoveFileAction : Action {
        override val name: String = "MOVE_FILE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            checkStoragePermission(context)?.let { return it }
            val srcPath = params["sourcePath"] ?: return ActionResult(false, null, "sourcePath parameter is missing")
            val destPath = params["destPath"] ?: return ActionResult(false, null, "destPath parameter is missing")
            return StorageWorkspaceProvider.moveFile(context, srcPath, destPath)
        }
    }

    private class ZipFilesAction : Action {
        override val name: String = "ZIP_FILES"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            checkStoragePermission(context)?.let { return it }
            val srcPath = params["sourcePath"] ?: return ActionResult(false, null, "sourcePath parameter is missing")
            val zipFilePath = params["zipFilePath"] ?: return ActionResult(false, null, "zipFilePath parameter is missing")
            return StorageWorkspaceProvider.zipFiles(context, srcPath, zipFilePath)
        }
    }

    private class UnzipFileAction : Action {
        override val name: String = "UNZIP_FILE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            checkStoragePermission(context)?.let { return it }
            val zipFilePath = params["zipFilePath"] ?: return ActionResult(false, null, "zipFilePath parameter is missing")
            val destDirPath = params["destDirPath"] ?: return ActionResult(false, null, "destDirPath parameter is missing")
            return StorageWorkspaceProvider.unzipFile(context, zipFilePath, destDirPath)
        }
    }
}

