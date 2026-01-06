package com.rtsp.camera

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker

import com.pedro.library.view.OpenGlView
import com.pedro.rtspserver.RtspServerCamera2
import com.rtsp.camera.databinding.ActivityMainBinding
import com.pedro.rtspserver.server.RtspServer
import com.pedro.rtspserver.server.ServerClient
import com.pedro.rtspserver.server.ClientListener
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), ConnectChecker, SurfaceHolder.Callback {

    private lateinit var binding: ActivityMainBinding
    private var rtspServerCamera2: RtspServerCamera2? = null
    
    // Default streaming parameters
    private var audioBitrate = 128 * 1024   // 128 Kbps
    private var audioSampleRate = 44100
    private var rtspPort = 8554
    
    private var isStreaming = false
    private var connectedClients = 0
    private var isFrontCamera = false

    companion object {
        private const val TAG = "RTSPCamera"
        private const val PERMISSIONS_REQUEST_CODE = 1
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private var prefResolutionIndex = 0
    private var prefFpsIndex = 0
    private var prefBitrate = 6000 * 1024 // Default to 6 Mbps
    private var prefIFrameInterval = 2 // Default 2 seconds
    private var prefLowLatency = false
    private var prefIntraRefreshPeriod = 0
    private var nativeRatio: Float = 0f

    private val supportedResolutions = mutableListOf<android.util.Size>()
    private val supportedFps = listOf(30, 60, 24, 15)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on while streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        if (checkPermissions()) {
            loadCameraCapabilities() // Load capabilities early
            initializeCamera()
        } else {
            requestPermissions()
        }

        setupUI()
    }
    
    // Load available resolutions from Camera Characteristic
    private fun loadCameraCapabilities() {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val targetFacing = if (isFrontCamera) 
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT 
            else 
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK

            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                facing == targetFacing
            } ?: cameraManager.cameraIdList.firstOrNull()

            cameraId?.let { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                
                // Get Physical Sensor constraints to determine "Native" aspect ratio (No Crop)
                val activeArray = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (activeArray != null) {
                    nativeRatio = activeArray.width().toFloat() / activeArray.height()
                }
                
                val map = characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                // We use MediaRecorder class to get video sizes
                val sizes = map?.getOutputSizes(android.media.MediaRecorder::class.java)
                
                supportedResolutions.clear()
                sizes?.let { sizeArray ->
                    // Sort priorities:
                    // 1. Native Aspect Ratio (First)
                    // 2. Pixel Count (High to Low)
                    supportedResolutions.addAll(sizeArray.sortedWith(Comparator { s1, s2 ->
                        val r1 = s1.width.toFloat() / s1.height
                        val r2 = s2.width.toFloat() / s2.height
                        
                        // Check if ratios match native (tolerance 0.02)
                        val isNative1 = kotlin.math.abs(r1 - nativeRatio) < 0.02f
                        val isNative2 = kotlin.math.abs(r2 - nativeRatio) < 0.02f
                        
                        when {
                            isNative1 && !isNative2 -> -1 // s1 is native, s1 first
                            !isNative1 && isNative2 -> 1  // s2 is native, s2 first
                            else -> (s2.width * s2.height) - (s1.width * s1.height) // Descending size
                        }
                    }))
                }
            }
            
            // Default to the first option (Max Native Resolution)
            prefResolutionIndex = 0

        } catch (e: Exception) {
            addLog("Error loading camera capabilities: ${e.message}")
            android.util.Log.e(TAG, "Error loading capabilities", e)
            // Fallback defaults
            supportedResolutions.clear()
            supportedResolutions.add(android.util.Size(1920, 1080))
            supportedResolutions.add(android.util.Size(1280, 720))
        }
    }

    private fun setupUI() {
        // Start/Stop button
        binding.btnStartStop.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }
        
        // Switch camera button
        binding.btnSwitchCamera.setOnClickListener {
            rtspServerCamera2?.let { camera ->
                try {
                    camera.switchCamera()
                    isFrontCamera = !isFrontCamera
                    
                    // Reload capabilities for the new camera (Front/Back)
                    loadCameraCapabilities()
                    updateCameraInfo()
                    
                    if (isStreaming) {
                        showToast("已切换摄像头。当前分辨率仍为推流开始时的设置。")
                    } else {
                        // Restart preview with new camera
                        rtspServerCamera2?.stopPreview()
                        startCameraPreview()
                    }
                } catch (e: Exception) {
                    showToast("切换摄像头失败: ${e.message}")
                    addLog("切换摄像头失败: ${e.message}")
                }
            }
        }
        
        // Copy URL button
        binding.btnCopyUrl.setOnClickListener {
            copyUrlToClipboard()
        }
        
        // Settings button
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }
        
        updateUI()
    }
    
    private fun initializeCamera() {
        // Use OpenGlView's SurfaceHolder callback
        binding.openGlView.holder.addCallback(this)
    }
    
    private fun setupRtspServer() {
        try {
            rtspServerCamera2 = RtspServerCamera2(
                binding.openGlView,
                this,
                rtspPort
            )
            
            rtspServerCamera2?.rtspServer?.setClientListener(object : ClientListener {
                override fun onClientConnected(client: ServerClient) {
                    runOnUiThread {
                        connectedClients++
                        updateUI()
                        addLog("客户端已连接: ${client.getAddress()}")
                        requestKeyFrame()
                    }
                }

                override fun onClientDisconnected(client: ServerClient) {
                    runOnUiThread {
                        if (connectedClients > 0) connectedClients--
                        updateUI()
                        addLog("客户端断开: ${client.getAddress()}")
                    }
                }

                override fun onClientNewBitrate(bitrate: Long, client: ServerClient) {
                    // Optional: track client bitrate
                }
            })
            
            // CustomVideoEncoder injection removed
            
            updateCameraInfo()
            updateRtspUrl()
            addLog("RTSP 服务器初始化完成")
            
            // Auto start preview
            startCameraPreview()
            
        } catch (e: Exception) {
            addLog("初始化失败: ${e.message}")
            showToast("初始化失败: ${e.message}")
        }
    }
    
    private fun getCameraRotation(): Int {
        var rotation = 90 // Default fallback
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val targetFacing = if (isFrontCamera) 
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT 
            else 
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == targetFacing
            }
            
            cameraId?.let { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                rotation = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error getting sensor orientation", e)
        }
        return rotation
    }

    private fun startCameraPreview() {
        val camera = rtspServerCamera2 ?: return
        if (camera.isOnPreview) return // Already previewing

        try {
            val resolution = supportedResolutions.getOrElse(prefResolutionIndex) { android.util.Size(1280, 720) }
            val fps = supportedFps.getOrElse(prefFpsIndex) { 30 }
            val width = resolution.width
            val height = resolution.height
            val rotation = getCameraRotation()

            // Just start preview, don't prepare stream constructs yet if not needed, 
            // but library usually requires prepareVideo for preview to have correct dimensions/orientation?
            // RtspServerCamera2.startPreview() checks if video is prepared?
            // Actually, RtspServerCamera2 usually needs prepareVideo called first to set dimensions.
            // Let's call prepareVideo here.
            
            val bitrate = prefBitrate // needed for prepareVideo
            
            val prepareVideo = camera.prepareVideo(
                width,
                height,
                fps,
                bitrate,
                prefIFrameInterval,
                rotation
            )
            
            if (prepareVideo) {
                camera.startPreview()
                addLog("摄像头预览已启动 (${width}x${height} @ ${fps}fps $rotation°)")
            } else {
                addLog("预览启动失败: 编码器准备失败")
            }
            
            // Audio prepare isn't strictly needed for VALID preview alone usually, 
            // but good to have ready? Let's skip audio for pure preview to save resources if possible,
            // or just prepare it so startStream is instant. Let's prepare it.
            camera.prepareAudio(audioBitrate, audioSampleRate, true)

        } catch (e: Exception) {
            addLog("预览启动出错: ${e.message}")
        }
    }

    private fun startStreaming() {
        val camera = rtspServerCamera2
        if (camera == null) {
            showToast("Camera not initialized")
            return
        }
        
        if (camera.isStreaming) {
            showToast("Already streaming")
            return
        }
        
        // If not previewing, try to start it (which prepares video)
        if (!camera.isOnPreview) {
            startCameraPreview()
        }
        
        // If still not prepared (e.g. failed), we can't stream
        // But startCameraPreview calls prepareVideo.
        // We can double check if we need to re-prepare or if existing preparation is sufficient.
        // RtspServerCamera2 internal state management usually allows startStream after startPreview.
        
        try {
            val resolution = supportedResolutions.getOrElse(prefResolutionIndex) { android.util.Size(1280, 720) }
            val fps = supportedFps.getOrElse(prefFpsIndex) { 30 }
            val width = resolution.width
            val height = resolution.height
            val rotation = getCameraRotation()
            val bitrate = prefBitrate

            val encoder = camera.getVideoEncoder()
            encoder.latency = if (prefLowLatency) 1 else 0
            encoder.intraRefreshPeriod = prefIntraRefreshPeriod

            val prepareVideo = camera.prepareVideo(
                width,
                height,
                fps,
                bitrate,
                prefIFrameInterval,
                rotation
            )
            val prepareAudio = camera.prepareAudio(audioBitrate, audioSampleRate, true)

             if (prepareVideo && prepareAudio) {
                camera.startPreview()
                addLog("摄像头预览已启动")
                
                camera.startStream()
                isStreaming = true
                

                
                updateUI()
                addLog("RTSP 服务器已启动，端口: $rtspPort")
            } else {
                 showToast("音频准备失败")
             }
        } catch (e: Exception) {
            addLog("启动失败: ${e.message}")
            showToast("启动失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun stopStreaming() {
        try {
            rtspServerCamera2?.let { camera ->
                if (camera.isStreaming) {
                    camera.stopStream()
                    addLog("停止推流")
                }
                // Don't stop preview when stopping stream, so image remains visible
                // if (camera.isOnPreview) {
                //    camera.stopPreview()
                //    addLog("停止预览")
                // }
            }
        } catch (e: Exception) {
            addLog("停止失败: ${e.message}")
        }
        isStreaming = false
        connectedClients = 0
        updateUI()
        addLog("RTSP 服务器已停止")
    }
    
    private fun updateUI() {
        runOnUiThread {
            if (isStreaming) {
                binding.btnStartStop.text = "停止推流"
                binding.btnStartStop.setBackgroundColor(
                    ContextCompat.getColor(this, android.R.color.holo_red_dark)
                )
                binding.statusIndicator.setBackgroundColor(
                    ContextCompat.getColor(this, android.R.color.holo_green_light)
                )
                binding.tvStatus.text = "推流中"
                binding.cardUrl.visibility = View.VISIBLE
            } else {
                binding.btnStartStop.text = "开始推流"
                binding.btnStartStop.setBackgroundColor(
                    ContextCompat.getColor(this, android.R.color.holo_green_dark)
                )
                binding.statusIndicator.setBackgroundColor(
                    ContextCompat.getColor(this, android.R.color.holo_red_light)
                )
                binding.tvStatus.text = "未推流"
                binding.cardUrl.visibility = View.GONE
            }
            
            binding.tvClients.text = "连接数: $connectedClients"
        }
    }
    
    private fun updateRtspUrl() {
        val ipAddress = getLocalIpAddress()
        val url = if (ipAddress != null) {
            "rtsp://$ipAddress:$rtspPort/"
        } else {
            "rtsp://127.0.0.1:$rtspPort/"
        }
        binding.tvRtspUrl.text = url
    }
    
    private fun updateCameraInfo() {
        val resolution = supportedResolutions.getOrElse(prefResolutionIndex) { android.util.Size(1280, 720) }
        val fps = supportedFps.getOrElse(prefFpsIndex) { 30 }
        
        val cameraFacing = if (isFrontCamera) "前置" else "后置"
        val bitrateMbps = String.format("%.1f", prefBitrate / 1024f / 1024f)
        binding.tvCameraInfo.text = "摄像头: $cameraFacing | ${resolution.width}x${resolution.height} @ ${fps}fps | ${bitrateMbps} Mbps"
    }
    
    private fun copyUrlToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("RTSP URL", binding.tvRtspUrl.text)
        clipboard.setPrimaryClip(clip)
        showToast("URL已复制到剪贴板")
    }
    
    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(com.rtsp.camera.R.layout.dialog_settings, null)
        val spinnerResolution = dialogView.findViewById<android.widget.Spinner>(com.rtsp.camera.R.id.spinnerResolution)
        val spinnerFps = dialogView.findViewById<android.widget.Spinner>(com.rtsp.camera.R.id.spinnerFps)
        val seekBarBitrate = dialogView.findViewById<android.widget.SeekBar>(com.rtsp.camera.R.id.seekBarBitrate)
        val tvBitrateValue = dialogView.findViewById<android.widget.TextView>(com.rtsp.camera.R.id.tvBitrateValue)
        val etIFrameInterval = dialogView.findViewById<android.widget.EditText>(com.rtsp.camera.R.id.etIFrameInterval)
        val switchLowLatency = dialogView.findViewById<android.widget.Switch>(com.rtsp.camera.R.id.switchLowLatency)
        val etIntraRefreshPeriod = dialogView.findViewById<android.widget.EditText>(com.rtsp.camera.R.id.etIntraRefreshPeriod)
        val btnSave = dialogView.findViewById<android.widget.Button>(com.rtsp.camera.R.id.btnSaveSettings)

        // Setup Resolution Spinner with Aspect Ratio Info
        val resItems = supportedResolutions.map { size ->
            val ratio = size.width.toFloat() / size.height
            val gcd = gcd(size.width, size.height)
            val w = size.width / gcd
            val h = size.height / gcd
            
            // Allow small tolerance for aspect ratio matching
            val isNative = kotlin.math.abs(ratio - nativeRatio) < 0.02f
            val nativeTag = if (isNative) " (Native)" else ""
            
            "${size.width}x${size.height} ($w:$h)$nativeTag"
        }
        
        val resAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, resItems)
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerResolution.adapter = resAdapter
        spinnerResolution.setSelection(prefResolutionIndex)

        // Setup FPS Spinner
        val fpsItems = supportedFps.map { "$it fps" }
        val fpsAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, fpsItems)
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFps.adapter = fpsAdapter
        spinnerFps.setSelection(prefFpsIndex)

        // Setup Bitrate SeekBar (Log scale)
        // Range: 128 Kbps to ~60000 Kbps
        // 0..100 -> Logarithmic mapping
        val minKbps = 128
        val maxKbps = 60000
        val maxProgress = 100
        
        // Helper to convert progress to bitrate
        fun progressToBitrate(progress: Int): Int {
            if (progress == 0) return minKbps * 1024
            val t = progress.toFloat() / maxProgress
            // Log scale formula: value = min * (max/min)^t
            val kbps = (minKbps * Math.pow((maxKbps.toDouble() / minKbps), t.toDouble())).toInt()
            return kbps * 1024
        }
        
        // Helper to convert bitrate to progress
        fun bitrateToProgress(bitrateBytes: Int): Int {
            val kbps = bitrateBytes / 1024
            if (kbps <= minKbps) return 0
            val ratio = Math.log(kbps.toDouble() / minKbps) / Math.log(maxKbps.toDouble() / minKbps)
            return (ratio * maxProgress).toInt().coerceIn(0, maxProgress)
        }
        
        // Helper to update text
        fun updateBitrateText(bitrate: Int) {
            val kbps = bitrate / 1024
            if (kbps >= 1000) {
                 tvBitrateValue.text = String.format("%.1f Mbps", kbps / 1024f)
            } else {
                 tvBitrateValue.text = "$kbps Kbps"
            }
        }

        seekBarBitrate.max = maxProgress
        seekBarBitrate.progress = bitrateToProgress(prefBitrate)
        updateBitrateText(prefBitrate)

        seekBarBitrate.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                updateBitrateText(progressToBitrate(progress))
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        // Setup I-Frame Interval
        etIFrameInterval.setText(prefIFrameInterval.toString())

        // Setup Low Latency and Intra Refresh
        switchLowLatency.isChecked = prefLowLatency
        if (prefIntraRefreshPeriod > 0) {
            etIntraRefreshPeriod.setText(prefIntraRefreshPeriod.toString())
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnSave.setOnClickListener {
            prefResolutionIndex = spinnerResolution.selectedItemPosition
            prefFpsIndex = spinnerFps.selectedItemPosition
            
            // Save Bitrate
            prefBitrate = progressToBitrate(seekBarBitrate.progress)
            
            // Save I-Frame Interval
            val intervalStr = etIFrameInterval.text.toString()
            prefIFrameInterval = intervalStr.toIntOrNull() ?: 2
            
            // Save Low Latency and Intra Refresh
            prefLowLatency = switchLowLatency.isChecked
            val intraStr = etIntraRefreshPeriod.text.toString()
            prefIntraRefreshPeriod = intraStr.toIntOrNull() ?: 0
            

            
            android.util.Log.i(TAG, "Settings saved: lowLatency=$prefLowLatency, intraRefresh=$prefIntraRefreshPeriod")
            
            updateCameraInfo()
            if (isStreaming) {
                showToast("设置已保存 (部分即时生效)")
            } else {
                showToast("设置已保存，预览已更新")
                rtspServerCamera2?.stopPreview()
                startCameraPreview()
            }
            dialog.dismiss()
        }
        
        dialog.show()
    }

    private fun gcd(a: Int, b: Int): Int {
        return if (b == 0) a else gcd(b, a % b)
    }
    
    private fun addLog(message: String) {
        // Also log to Logcat for debugging
        android.util.Log.d(TAG, message)
        
        runOnUiThread {
            try {
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val logText = "[$timestamp] $message\n${binding.tvLog.text}"
                binding.tvLog.text = logText.take(2000)  // Limit log size
            } catch (e: Exception) {
                // Ignore logging errors
                android.util.Log.e(TAG, "Error updating log UI", e)
            }
        }
    }
    
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    private fun checkPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            REQUIRED_PERMISSIONS,
            PERMISSIONS_REQUEST_CODE
        )
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                loadCameraCapabilities() // Load capabilities after permission
                initializeCamera()
            } else {
                showToast("需要摄像头和麦克风权限")
                finish()
            }
        }
    }
    
    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    // SurfaceHolder.Callback for OpenGlView
    override fun surfaceCreated(holder: SurfaceHolder) {
        setupRtspServer()
    }
    
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Handle surface changes if needed
    }
    
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (isStreaming) {
            stopStreaming()
        }
    }
    
    // ConnectChecker callbacks
    override fun onConnectionStarted(url: String) {
        runOnUiThread {
            addLog("客户端正在连接: $url")
        }
    }
    
    override fun onConnectionSuccess() {
        runOnUiThread {
            updateUI()
            addLog("RTSP服务启动成功")
        }
    }
    

    
    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            addLog("连接失败: $reason")
        }
    }
    
    override fun onNewBitrate(bitrate: Long) {
        // Optional
    }
    
    override fun onDisconnect() {
        runOnUiThread {
            updateUI()
            addLog("RTSP服务停止")
        }
    }
    
    override fun onAuthError() {
        runOnUiThread {
            addLog("认证错误")
        }
    }
    
    override fun onAuthSuccess() {
        runOnUiThread {
            addLog("认证成功")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            if (isStreaming) {
                stopStreaming()
            }
        } catch (e: Exception) {
            // Ignore errors on destroy
        }
    }

    
    /**
     * Request an immediate keyframe (I-frame/IDR) from the video encoder.
     * This is called when a new client connects so they don't have to wait
     * for the next scheduled I-frame.
     */
    private fun requestKeyFrame() {
        try {
            val camera = rtspServerCamera2 ?: return
            val encoder = camera.getVideoEncoder()
            if (encoder.isRunning) {
                encoder.requestKeyframe()
                runOnUiThread {
                    addLog("已请求关键帧 (Direct)")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to request keyframe: ${e.message}", e)
        }
    }
}
