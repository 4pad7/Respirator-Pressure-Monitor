package com.example.respiratorpressuremonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class MainActivity : AppCompatActivity() {

    private val TAG = "RespiratorBLE"

    // 藍牙 UUID 設定，必須與 RTL8735 一致
    private val SERVICE_UUID     = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID        = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb") // Notify
    private val CONFIG_CHAR_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb") // Write (動態調參)
    private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var lastConnectedDevice: BluetoothDevice? = null
    private var isAlertShowing = false
    private var isDisconnectAlertShowing = false

    // 警報音效與震動控制物件
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    // 動態調參本地變數
    private var customThresholdMmHg = 32.0f
    private var customDurationSec   = 1.0f

    // 搜尋與自動重連相關 Handler & 變數
    private val discoveredDevices = ArrayList<BluetoothDevice>()
    private val deviceNamesList = ArrayList<String>()
    private var isScanning = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private val autoReconnectHandler = Handler(Looper.getMainLooper())
    private val disconnectTimerHandler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD: Long = 10000

    // UI 元件
    private lateinit var layoutStatusCard: LinearLayout
    private lateinit var tvStatusLed: TextView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSub: TextView
    private lateinit var etThreshold: EditText
    private lateinit var etDuration: EditText
    private lateinit var btnApplySettings: Button
    private lateinit var tvNose: TextView
    private lateinit var tvLeftCheek: TextView
    private lateinit var tvRightCheek: TextView
    private lateinit var tvChin: TextView

    // 連線狀態 Enum
    enum class ConnectionState {
        CONNECTED,     // 🟢 綠燈：正常連線中
        RECONNECTING,  // 🟠 橘燈：背景重連中 / 搜尋中
        DISCONNECTED   // 🔴 紅燈：連線中斷 / 超時警告
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 保持螢幕常亮，防止系統 Doze 休眠關閉藍牙
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        // 綁定 UI 元件
        layoutStatusCard = findViewById(R.id.layoutStatusCard)
        tvStatusLed = findViewById(R.id.tvStatusLed)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusSub = findViewById(R.id.tvStatusSub)
        etThreshold = findViewById(R.id.etThreshold)
        etDuration = findViewById(R.id.etDuration)
        btnApplySettings = findViewById(R.id.btnApplySettings)
        tvNose = findViewById(R.id.tvNose)
        tvLeftCheek = findViewById(R.id.tvLeftCheek)
        tvRightCheek = findViewById(R.id.tvRightCheek)
        tvChin = findViewById(R.id.tvChin)

        btnApplySettings.setOnClickListener {
            applyAndSyncSettings()
        }

        // 點擊狀態卡片可手動重試搜尋
        layoutStatusCard.setOnClickListener {
            if (bluetoothGatt == null && !isScanning) {
                startBleDiscovery()
            }
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        startBleDiscovery()
    }

    // 💡 動態更新 APP 藍牙指示燈與狀態說明註解
    private fun updateConnectionUI(state: ConnectionState, deviceName: String = "") {
        runOnUiThread {
            when (state) {
                ConnectionState.CONNECTED -> {
                    tvStatusLed.text = "🟢"
                    layoutStatusCard.setBackgroundColor(Color.parseColor("#E8F5E9")) // 淺綠背景
                    tvStatusTitle.text = "已正常連線：$deviceName"
                    tvStatusTitle.setTextColor(Color.parseColor("#2E7D32"))
                    tvStatusSub.text = "數據即時穩定接收中 (門檻: ${customThresholdMmHg}mmHg / ${customDurationSec}s)"
                    tvStatusSub.setTextColor(Color.parseColor("#1B5E20"))
                }
                ConnectionState.RECONNECTING -> {
                    tvStatusLed.text = "🟠"
                    layoutStatusCard.setBackgroundColor(Color.parseColor("#FFF3E0")) // 淺橘背景
                    tvStatusTitle.text = "連線訊號微弱 / 背景嘗試重連中..."
                    tvStatusTitle.setTextColor(Color.parseColor("#E65100"))
                    tvStatusSub.text = "正在每 3 秒自動嘗試復原通訊，請將手機靠近裝置"
                    tvStatusSub.setTextColor(Color.parseColor("#EF6C00"))
                }
                ConnectionState.DISCONNECTED -> {
                    tvStatusLed.text = "🔴"
                    layoutStatusCard.setBackgroundColor(Color.parseColor("#FFEBEE")) // 淺紅背景
                    tvStatusTitle.text = "🚨 藍牙連線已斷開！"
                    tvStatusTitle.setTextColor(Color.parseColor("#C62828"))
                    tvStatusSub.text = "點擊此處重新搜尋 (若斷線超過 10 秒將響起醫療警告音)"
                    tvStatusSub.setTextColor(Color.parseColor("#B71C1C"))
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyAndSyncSettings() {
        val strThresh = etThreshold.text.toString().trim()
        val strDur = etDuration.text.toString().trim()

        if (strThresh.isEmpty() || strDur.isEmpty()) {
            Toast.makeText(this, "請輸入完整的門檻數值！", Toast.LENGTH_SHORT).show()
            return
        }

        val threshold = strThresh.toFloatOrNull()
        val duration = strDur.toFloatOrNull()

        if (threshold == null || threshold <= 0 || duration == null || duration <= 0) {
            Toast.makeText(this, "請輸入大於 0 的有效數值！", Toast.LENGTH_SHORT).show()
            return
        }

        customThresholdMmHg = threshold
        customDurationSec = duration

        if (bluetoothGatt != null) {
            sendConfigToBoard(customThresholdMmHg, customDurationSec)
        } else {
            Toast.makeText(this, "門檻已更新(本地)，連線後將自動同步至裝置！", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendConfigToBoard(threshold: Float, durationSec: Float) {
        val service = bluetoothGatt?.getService(SERVICE_UUID)
        val configChar = service?.getCharacteristic(CONFIG_CHAR_UUID)

        if (configChar != null) {
            val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putFloat(threshold)
            buffer.putFloat(durationSec)

            configChar.value = buffer.array()
            val success = bluetoothGatt?.writeCharacteristic(configChar) ?: false

            if (success) {
                Toast.makeText(this, "已成功同步新門檻 (${threshold}mmHg / ${durationSec}s) 至裝置！", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "同步失敗，請檢查藍牙連線！", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleDiscovery() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 101)
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            updateConnectionUI(ConnectionState.DISCONNECTED)
            tvStatusTitle.text = "🔴 請先開啟手機藍牙功能！"
            return
        }

        discoveredDevices.clear()
        deviceNamesList.clear()
        updateConnectionUI(ConnectionState.RECONNECTING)
        tvStatusTitle.text = "🟠 正在搜尋病房監測裝置中..."
        isScanning = true

        scanHandler.postDelayed({ stopBleScanAndShowDialog() }, SCAN_PERIOD)
        bluetoothAdapter!!.bluetoothLeScanner?.startScan(bleScanCallback)
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name
            if (name != null && name.startsWith("V_MASK")) {
                if (!discoveredDevices.contains(device)) {
                    discoveredDevices.add(device)
                    deviceNamesList.add("${device.name} (${device.address})")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScanAndShowDialog() {
        if (!isScanning) return
        isScanning = false
        bluetoothAdapter!!.bluetoothLeScanner?.stopScan(bleScanCallback)

        if (discoveredDevices.isEmpty()) {
            updateConnectionUI(ConnectionState.DISCONNECTED)
        } else {
            val items = deviceNamesList.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("請選擇要連線的病床裝置")
                .setItems(items) { _, which ->
                    val selectedDevice = discoveredDevices[which]
                    updateConnectionUI(ConnectionState.RECONNECTING)
                    tvStatusTitle.text = "🟠 正在連線至 ${selectedDevice.name}..."
                    bluetoothGatt = selectedDevice.connectGatt(this@MainActivity, false, gattCallback)
                }
                .setCancelable(false)
                .show()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                cancelDisconnectTimers()
                lastConnectedDevice = gatt.device
                val devName = gatt.device.name ?: "V_MASK 裝置"

                // 🟢 綠燈：正常連線
                updateConnectionUI(ConnectionState.CONNECTED, devName)
                gatt.discoverServices()

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                bluetoothGatt?.close()
                bluetoothGatt = null

                val devName = lastConnectedDevice?.name ?: "V_MASK 裝置"

                // 🟠 橘燈：背景重連中
                updateConnectionUI(ConnectionState.RECONNECTING, devName)

                startDisconnectTimeout(devName)
                scheduleAutoReconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHAR_UUID)

                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }

                runOnUiThread {
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendConfigToBoard(customThresholdMmHg, customDurationSec)
                    }, 1000)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHAR_UUID) {
                val data = characteristic.value
                if (data.size == 17) {
                    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    val pNose = buffer.float
                    val pLCheek = buffer.float
                    val pRCheek = buffer.float
                    val pChin = buffer.float
                    val alarmStatus = data[16].toInt()

                    val devName = gatt.device.name ?: "V_MASK 裝置"

                    runOnUiThread {
                        tvNose.text = "鼻樑: ${String.format("%.1f", pNose)} mmHg"
                        tvLeftCheek.text = "左臉頰: ${String.format("%.1f", pLCheek)} mmHg"
                        tvRightCheek.text = "右臉頰: ${String.format("%.1f", pRCheek)} mmHg"
                        tvChin.text = "下巴: ${String.format("%.1f", pChin)} mmHg"

                        if (alarmStatus == 0x01) {
                            triggerEmergencyPopup(pNose, pLCheek, pRCheek, pChin, devName)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleAutoReconnect() {
        autoReconnectHandler.postDelayed({
            if (bluetoothGatt == null && lastConnectedDevice != null) {
                Log.i(TAG, "背景自動嘗試重連至: ${lastConnectedDevice?.name}")
                bluetoothGatt = lastConnectedDevice?.connectGatt(this@MainActivity, false, gattCallback)
                if (bluetoothGatt == null) {
                    scheduleAutoReconnect()
                }
            }
        }, 3000)
    }

    private fun startDisconnectTimeout(deviceName: String) {
        disconnectTimerHandler.postDelayed({
            if (bluetoothGatt == null && !isDisconnectAlertShowing) {
                // 🔴 切換為紅燈並發出警報
                updateConnectionUI(ConnectionState.DISCONNECTED, deviceName)
                triggerDisconnectEmergencySound(deviceName)
            }
        }, 10000)
    }

    private fun cancelDisconnectTimers() {
        disconnectTimerHandler.removeCallbacksAndMessages(null)
        autoReconnectHandler.removeCallbacksAndMessages(null)
        stopSoundAndVibration()
        isDisconnectAlertShowing = false
    }

    private fun triggerDisconnectEmergencySound(deviceName: String) {
        if (isDisconnectAlertShowing) return
        isDisconnectAlertShowing = true

        try {
            startSoundAndVibration()

            Handler(Looper.getMainLooper()).post {
                val msg = """
                    系統已超過 10 秒無法接收到 $deviceName 的壓力數據！
                    
                    自動背景重連中... 請護理人員立刻確認：
                    1. HUB 8735 模組電源與電池狀態
                    2. 藍牙通訊距離是否過遠
                    3. 載板硬體開關是否關閉
                """.trimIndent()

                AlertDialog.Builder(this)
                    .setTitle("🚨 醫療安全警告：$deviceName 通訊中斷！")
                    .setMessage(msg)
                    .setCancelable(false)
                    .setPositiveButton("我知道了 (繼續背景自動重連)") { _, _ ->
                        stopSoundAndVibration()
                        isDisconnectAlertShowing = false
                    }
                    .create()
                    .show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isDisconnectAlertShowing = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerEmergencyPopup(nose: Float, lCheek: Float, rCheek: Float, chin: Float, deviceName: String) {
        if (isAlertShowing) return
        isAlertShowing = true

        try {
            startSoundAndVibration()

            Handler(Looper.getMainLooper()).post {
                val msg = """
                    系統偵測到 $deviceName 病患面罩壓力持續超過 $customThresholdMmHg mmHg (持續 > $customDurationSec 秒)！
                    
                    即時數據：
                    • 鼻樑: ${String.format("%.1f", nose)} mmHg
                    • 左臉頰: ${String.format("%.1f", lCheek)} mmHg
                    • 右臉頰: ${String.format("%.1f", rCheek)} mmHg
                    • 下巴: ${String.format("%.1f", chin)} mmHg
                    
                    請護理人員立刻前往病房，調整配戴面罩或頭帶鬆緊！
                """.trimIndent()

                AlertDialog.Builder(this)
                    .setTitle("⚠️ 護理緊急警告：$deviceName 壓迫超標 ⚠️")
                    .setMessage(msg)
                    .setCancelable(false)
                    .setPositiveButton("已處理並重置警告") { _, _ ->
                        stopSoundAndVibration()
                        isAlertShowing = false
                    }
                    .create()
                    .show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopSoundAndVibration()
            isAlertShowing = false
        }
    }

    private fun startSoundAndVibration() {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(this, R.raw.alarm_sound)
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
        }

        if (vibrator == null) {
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 500, 200, 500), 0)
            }
        }
    }

    private fun stopSoundAndVibration() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null

        vibrator?.cancel()
        vibrator = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelDisconnectTimers()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}