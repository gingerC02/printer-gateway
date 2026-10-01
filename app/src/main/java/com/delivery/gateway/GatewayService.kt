package com.delivery.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * 网关前台服务：进程独立、后台常驻。
 * 负责 USB 打印机管理、WebSocket 服务（端口 8080）、打印记录落库、OTG 保活。
 * Activity 被关闭也不影响服务运行，从而不会漏接小程序的投送。
 *
 * 打印模式（持久化于 SharedPreferences）：
 *  - 自动打印（默认）：收到投送立即打印并落库。
 *  - 手动打印：收到投送只落库不打印，由用户在记录页逐条手动打印。
 * 前台服务类型为 dataSync，避免对“已连接 USB 打印机”的硬约束，未插打印机也能正常运行。
 */
class GatewayService : Service() {

    private lateinit var usbManager: UsbManager
    private var printerConn: android.hardware.usb.UsbDeviceConnection? = null
    private var printerEp: android.hardware.usb.UsbEndpoint? = null
    private var printerDevice: UsbDevice? = null
    private var printerIntf: android.hardware.usb.UsbInterface? = null
    private val keepAliveHandler = Handler(Looper.getMainLooper())
    private val KEEP_ALIVE_MS = 8 * 60 * 1000L
    private var server: PrintServer? = null

    private val ACTION_USB_PERMISSION = "com.delivery.gateway.USB_PERMISSION"
    private val CHANNEL_ID = "gateway_foreground"
    private val NOTIF_ID = 1

    companion object {
        var uiListener: ((status: String, info: String) -> Unit)? = null
        var lastStatus: String = "打印机：未连接"
        var lastInfo: String = ""

        const val PREF_NAME = "gateway_cfg"
        const val KEY_AUTO_PRINT = "auto_print"

        // 当前是否自动打印；服务未启动时取 SP 默认值
        var autoPrint: Boolean = true
        private var instance: GatewayService? = null

        fun setAutoPrint(ctx: Context, value: Boolean) {
            autoPrint = value
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_PRINT, value).apply()
        }

        /** 由记录页调用：把已存档记录的字节发送到打印机 */
        fun requestPrint(b64: String): Boolean {
            return instance?.doPrint(b64) ?: false
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { openPrinter(it) }
                    }
                    updateStatus()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    device?.let { ensurePrinter(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    printerConn?.close()
                    printerConn = null
                    printerEp = null
                    printerDevice = null
                    printerIntf = null
                    updateStatus()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(UsbManager::class.java)
        try { RecordStore.init(applicationContext) }
        catch (e: Exception) { appendInfo("存储初始化失败：${e.message}") }
        autoPrint = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_PRINT, true)
        instance = this
        createNotificationChannel()
        try {
            startForeground(NOTIF_ID, buildNotification(lastStatus))
        } catch (e: Exception) {
            // 兜底：即便前台服务因权限等原因起不来，也不要让整个 APP 黑屏闪退
            appendInfo("前台服务启动异常：${e.message}")
        }
        registerUsb()
        for (device in usbManager.deviceList.values) {
            if (isPrinter(device)) { ensurePrinter(device); break }
        }
        server = PrintServer(8080)
        try { server?.start() } catch (e: Exception) { appendInfo("WebSocket 启动失败：${e.message}") }
        keepAliveHandler.postDelayed(keepAliveRunnable, KEEP_ALIVE_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        server?.stop()
        printerConn?.close()
        server = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerUsb() {
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "网关常驻", NotificationManager.IMPORTANCE_LOW)
            chan.description = "送货单网关后台服务"
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(chan)
        }
    }

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("送货单打印网关运行中")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            keepAlive()
            keepAliveHandler.postDelayed(this, KEEP_ALIVE_MS)
        }
    }

    private fun keepAlive() {
        val conn = printerConn ?: return
        val intf = printerIntf ?: return
        try { conn.claimInterface(intf, true) } catch (e: Exception) {}
    }

    private fun isPrinter(d: UsbDevice): Boolean {
        if (d.deviceClass == UsbConstants.USB_CLASS_PRINTER) return true
        for (i in 0 until d.interfaceCount) {
            if (d.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_PRINTER) return true
        }
        return false
    }

    private fun ensurePrinter(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            openPrinter(device)
        } else {
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, pi)
        }
    }

    private fun openPrinter(device: UsbDevice) {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_PRINTER) continue
            val conn = usbManager.openDevice(device) ?: continue
            if (!conn.claimInterface(intf, true)) { conn.close(); continue }
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    printerDevice = device
                    printerIntf = intf
                    printerConn = conn
                    printerEp = ep
                    updateStatus()
                    return
                }
            }
        }
        updateStatus()
    }

    private fun printBytes(bytes: ByteArray): Boolean {
        val conn = printerConn ?: return false
        val ep = printerEp ?: return false
        var offset = 0
        while (offset < bytes.size) {
            val len = minOf(16384, bytes.size - offset)
            val sent = conn.bulkTransfer(ep, bytes, offset, len, 5000)
            if (sent <= 0) return false
            offset += sent
        }
        return true
    }

    // 供记录页手动打印调用
    private fun doPrint(b64: String): Boolean {
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            printBytes(bytes)
        } catch (e: Exception) { false }
    }

    private fun updateStatus() {
        lastStatus = if (printerConn != null) "打印机：已连接 ✓" else "打印机：未连接（插入打印机后授权即可）"
        pushUi()
    }

    private fun appendInfo(line: String) {
        lastInfo = if (lastInfo.isEmpty()) line else lastInfo + "\n" + line
        val lines = lastInfo.split("\n")
        if (lines.size > 200) lastInfo = lines.takeLast(200).joinToString("\n")
        pushUi()
    }

    private fun pushUi() {
        val s = lastStatus
        val i = lastInfo
        Handler(Looper.getMainLooper()).post { uiListener?.invoke(s, i) }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(s))
    }

    private fun fmtTime(ms: Long): String {
        val d = java.util.Date(ms)
        val f = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
        return f.format(d)
    }

    inner class PrintServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
        override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
            appendInfo("[${conn?.remoteSocketAddress?.address?.hostAddress}] 已连接")
        }
        override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {}

        // 文本帧：小程序发来的 JSON 信封 {code, ts, text, bytes(base64)}
        override fun onMessage(conn: WebSocket?, message: String?) {
            message ?: return
            try {
                val obj = JSONObject(message)
                val b64 = if (obj.has("bytes")) obj.getString("bytes") else null
                if (b64 == null) { conn?.send("ERR"); return }
                val code = obj.optString("code", "")
                val ts = obj.optLong("ts", System.currentTimeMillis())
                val text = obj.optString("text", "")
                val receivedAt = System.currentTimeMillis()
                // 自动打印：立即写入 USB；手动打印：仅存档，不打印
                val ok = if (autoPrint) printBytes(Base64.decode(b64, Base64.DEFAULT)) else true
                val printedAt = if (autoPrint) System.currentTimeMillis() else 0L
                if (ok) {
                    val id = "R" + receivedAt.toString(36) + "_" + ((Math.random() * 100000).toInt()).toString(36)
                    RecordStore.append(RecordStore.Record(id, code, receivedAt, printedAt, text, b64))
                    val tag = if (autoPrint) "" else "（待打印）"
                    appendInfo("[已存记录] ${if (code.isNotEmpty()) code else ""} @${fmtTime(receivedAt)}$tag")
                    conn?.send(if (autoPrint) "OK" else "STORED")
                } else {
                    conn?.send("ERR")
                }
            } catch (e: Exception) {
                appendInfo("[信封解析失败] ${e.message}")
                conn?.send("ERR")
            }
        }

        // 二进制帧：原始 ESC/POS 字节（兼容直接用工具投送的场景）
        // 自动打印模式：打印并落库；手动打印模式：仅落库不打印
        override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
            message ?: return
            val bytes = ByteArray(message.remaining())
            message.get(bytes)
            val ok = if (autoPrint) printBytes(bytes) else true
            val printedAt = if (autoPrint) System.currentTimeMillis() else 0L
            if (ok) {
                val receivedAt = System.currentTimeMillis()
                val id = "R" + receivedAt.toString(36) + "_" + ((Math.random() * 100000).toInt()).toString(36)
                val b64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                RecordStore.append(RecordStore.Record(id, "", receivedAt, printedAt, "", b64))
                conn?.send(if (autoPrint) "OK" else "STORED")
            } else {
                conn?.send("ERR")
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception?) {
            appendInfo("[错误] ${ex?.message}")
        }
        override fun onStart() {
            appendInfo("网关已启动，端口 8080，等待小程序连接…")
        }
    }
}
