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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), ConnectChecker, SurfaceHolder.Callback {

    private lateinit var binding: ActivityMainBinding
    private var rtspServerCamera2: RtspServerCamera2? = null
    
    // Default streaming parameters
    private var videoWidth = 1280
    private var videoHeight = 720
    private var videoFps = 30
    private var videoBitrate = 2500 * 1024  // 2.5 Mbps
    private var audioBitrate = 128 * 1024   // 128 Kbps
    private var audioSampleRate = 44100
    private var rtspPort = 8554
    
    private var isStreaming = false
    private var connectedClients = 0
    private var isFrontCamera = false

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 1
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on while streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        
        if (checkPermissions()) {
            initializeCamera()
        } else {
            requestPermissions()
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
                    updateCameraInfo()
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
            // Use OpenGlView constructor for proper video preview
            rtspServerCamera2 = RtspServerCamera2(
                binding.openGlView,
                this,
                rtspPort
            )
            updateCameraInfo()
            updateRtspUrl()
            addLog("RTSP 服务器初始化完成")
        } catch (e: Exception) {
            addLog("初始化失败: ${e.message}")
            showToast("初始化失败: ${e.message}")
        }
    }
    
    private fun startStreaming() {
        val camera = rtspServerCamera2
        if (camera == null) {
            showToast("Camera not initialized")
            return
        }
        
        try {
            // Prepare video: width, height, fps, bitrate, rotation
            val prepareVideo = camera.prepareVideo(
                videoWidth,
                videoHeight,
                videoFps,
                videoBitrate,
                0  // rotation
            )
            addLog("视频准备: ${if (prepareVideo) "成功" else "失败"}")
            
            // Prepare audio: bitrate, sampleRate, isStereo
            val prepareAudio = camera.prepareAudio(
                audioBitrate,
                audioSampleRate,
                true  // isStereo
            )
            addLog("音频准备: ${if (prepareAudio) "成功" else "失败"}")
            
            if (prepareVideo && prepareAudio) {
                // Start preview first
                camera.startPreview()
                addLog("摄像头预览已启动")
                
                // Then start the RTSP server stream
                camera.startStream()
                isStreaming = true
                updateUI()
                addLog("RTSP 服务器已启动，端口: $rtspPort")
            } else {
                showToast("编码器准备失败")
                addLog("错误: 编码器准备失败")
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
                if (camera.isOnPreview) {
                    camera.stopPreview()
                    addLog("停止预览")
                }
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
        val cameraFacing = if (isFrontCamera) "前置" else "后置"
        binding.tvCameraInfo.text = "摄像头: $cameraFacing | ${videoWidth}x${videoHeight} @ ${videoFps}fps"
    }
    
    private fun copyUrlToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("RTSP URL", binding.tvRtspUrl.text)
        clipboard.setPrimaryClip(clip)
        showToast("URL已复制到剪贴板")
    }
    
    private fun showSettingsDialog() {
        val options = arrayOf(
            "分辨率: 1920x1080",
            "分辨率: 1280x720",
            "分辨率: 640x480",
            "比特率: 5 Mbps",
            "比特率: 2.5 Mbps",
            "比特率: 1 Mbps"
        )
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { videoWidth = 1920; videoHeight = 1080 }
                    1 -> { videoWidth = 1280; videoHeight = 720 }
                    2 -> { videoWidth = 640; videoHeight = 480 }
                    3 -> videoBitrate = 5000 * 1024
                    4 -> videoBitrate = 2500 * 1024
                    5 -> videoBitrate = 1000 * 1024
                }
                updateCameraInfo()
                if (isStreaming) {
                    showToast("请先停止推流再更改设置")
                } else {
                    showToast("设置已更新")
                }
            }
            .show()
    }
    
    private fun addLog(message: String) {
        runOnUiThread {
            try {
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val logText = "[$timestamp] $message\n${binding.tvLog.text}"
                binding.tvLog.text = logText.take(2000)  // Limit log size
            } catch (e: Exception) {
                // Ignore logging errors
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
    
    // ConnectChecker callbacks - these are called from background threads!
    override fun onConnectionStarted(url: String) {
        runOnUiThread {
            addLog("客户端正在连接: $url")
        }
    }
    
    override fun onConnectionSuccess() {
        runOnUiThread {
            connectedClients++
            updateUI()
            addLog("客户端连接成功 (共 $connectedClients 个)")
        }
    }
    
    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            addLog("连接失败: $reason")
        }
    }
    
    override fun onNewBitrate(bitrate: Long) {
        // Optional: Update bitrate display - called frequently, don't log
    }
    
    override fun onDisconnect() {
        runOnUiThread {
            if (connectedClients > 0) connectedClients--
            updateUI()
            addLog("客户端断开连接 (剩余 $connectedClients 个)")
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
}
