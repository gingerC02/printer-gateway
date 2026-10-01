package com.delivery.gateway

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * UI 壳：仅负责显示状态、启动/查看网关、切换自动打印开关。
 * 真正的网关逻辑在 GatewayService（前台服务）中，本 Activity 关闭不影响网关运行。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var infoText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        ipText = findViewById(R.id.ipText)
        infoText = findViewById(R.id.infoText)

        // 启动网关前台服务（常驻后台，独立于本界面运行）
        val svc = Intent(this, GatewayService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }

        val btnRecords = findViewById<Button>(R.id.btnRecords)
        btnRecords.setOnClickListener {
            startActivity(Intent(this, StorageActivity::class.java))
        }

        // 自动打印开关：初值从 SP 读取，变更即持久化并通知服务
        val swAutoPrint = findViewById<Switch>(R.id.swAutoPrint)
        val sp = getSharedPreferences(GatewayService.PREF_NAME, MODE_PRIVATE)
        swAutoPrint.isChecked = sp.getBoolean(GatewayService.KEY_AUTO_PRINT, true)
        swAutoPrint.setOnCheckedChangeListener { _, isChecked ->
            GatewayService.setAutoPrint(this, isChecked)
        }

        ipText.text = "本机局域网IP：" + getIpAddress()

        // 绑定服务状态回调，实时刷新 UI
        GatewayService.uiListener = { status, info ->
            statusText.text = status
            infoText.text = info
        }
        // 立即用当前值刷新一次
        statusText.text = GatewayService.lastStatus
        infoText.text = GatewayService.lastInfo
    }

    private fun getIpAddress(): String {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) "未连接 WiFi" else
                "${(ip and 0xff)}.${(ip shr 8 and 0xff)}.${(ip shr 16 and 0xff)}.${(ip shr 24 and 0xff)}"
        } catch (e: Exception) { "获取失败" }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注意：不停止网关服务，让其常驻后台。仅解除本界面的 UI 回调。
        if (GatewayService.uiListener != null) GatewayService.uiListener = null
    }
}
