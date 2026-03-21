package com.cdspadvanced

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CDSPAdvanced"
        private const val ACTION_USB_PERMISSION = "com.cdspadvanced.USB_PERMISSION"
        private const val MINIDSP_VENDOR_ID = 0x2752
        private const val PERMISSION_REQUEST_AUDIO = 1001
    }

    private lateinit var usbManager: UsbManager
    private lateinit var webView: WebView
    private lateinit var daemonManager: DaemonProcessManager
    private var usbConnection: UsbDeviceConnection? = null
    private var pendingPermRequest: PermissionRequest? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        connectToDevice(device)
                    } else {
                        Toast.makeText(context, "USB permission required for C-DSP", Toast.LENGTH_LONG).show()
                        loadWebUI()
                    }
                }
            }
        }
    }

    private val usbEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> findAndConnectDevice()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handleDeviceDetached()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        daemonManager = DaemonProcessManager(this)

        registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), RECEIVER_NOT_EXPORTED)
        registerReceiver(usbEventReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, RECEIVER_NOT_EXPORTED)

        webView = findViewById(R.id.webView)
        setupWebView()
        findAndConnectDevice()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(usbEventReceiver) } catch (_: Exception) {}
        daemonManager.stop()
        usbConnection?.close()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) findAndConnectDevice()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    // ── Audio permission for UMIK-1 ──

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingPermRequest?.let { it.grant(it.resources) }
            } else {
                pendingPermRequest?.deny()
                Toast.makeText(this, "Mic permission needed for RTA", Toast.LENGTH_LONG).show()
            }
            pendingPermRequest = null
        }
    }

    // ── USB ──

    private fun findAndConnectDevice() {
        for ((_, device) in usbManager.deviceList) {
            if (device.vendorId == MINIDSP_VENDOR_ID) {
                if (usbManager.hasPermission(device)) {
                    connectToDevice(device)
                } else {
                    usbManager.requestPermission(device,
                        PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE))
                }
                return
            }
        }
        loadWebUI()
    }

    private fun connectToDevice(device: UsbDevice) {
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Toast.makeText(this, "Failed to open C-DSP", Toast.LENGTH_LONG).show()
            loadWebUI()
            return
        }
        usbConnection = connection
        startDaemon(connection.fileDescriptor)
    }

    private fun handleDeviceDetached() {
        daemonManager.stop()
        usbConnection?.close()
        usbConnection = null
        runOnUiThread {
            webView.evaluateJavascript("if(typeof tryConnect==='function'){connected=false;tryConnect();}", null)
        }
    }

    // ── Daemon ──

    private fun startDaemon(usbFd: Int) {
        Thread {
            try {
                daemonManager.start(usbFd)
                Thread.sleep(1500)
            } catch (e: Exception) {
                Log.e(TAG, "Daemon error: ${e.message}", e)
            }
            runOnUiThread { loadWebUI() }
        }.start()
    }

    // ── WebView ──
    // CRITICAL: No WebViewAssetLoader. It returns 500 on Atoto S8 Ultra custom ROM.
    // CRITICAL: No onReceivedError/onReceivedHttpError reload handlers. They cause infinite loops.
    // Just load file:///android_asset/ and let the HTML handle API errors itself.

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
        }

        // Plain WebViewClient — NO shouldInterceptRequest, NO error reload handlers
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i(TAG, "Page loaded: $url")
            }
        }

        // WebChromeClient — grants mic permission for UMIK-1 RTA
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    if (hasAudioPermission()) {
                        runOnUiThread { request.grant(request.resources) }
                    } else {
                        pendingPermRequest = request
                        runOnUiThread {
                            ActivityCompat.requestPermissions(this@MainActivity,
                                arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_AUDIO)
                        }
                    }
                } else {
                    runOnUiThread { request.grant(request.resources) }
                }
            }

            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                msg?.let { Log.d("WebView", "${it.messageLevel()}: ${it.message()}") }
                return true
            }
        }

        WebView.setWebContentsDebuggingEnabled(true)
    }

    private fun loadWebUI() {
        runOnUiThread {
            webView.loadUrl("file:///android_asset/htmlapp/index.html")
        }
    }

    // ── Fullscreen ──

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }
}
