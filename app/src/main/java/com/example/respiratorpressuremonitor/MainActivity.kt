package com.example.respiratorpressuremonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class MainActivity : AppCompatActivity() {

    private val TAG = "RespiratorBLE"

    // 藍牙 UUID 設定，必須與 RTL8735 一致
    private val SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID    = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isAlertShowing = false

    // 多裝置動態掃描相關變數
    private val discoveredDevices = ArrayList<BluetoothDevice>()
    private val deviceNamesList = ArrayList<String>()
    private var isScanning = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD: Long = 10000 // 掃描超時時間設定為 10 秒

    // UI 元件
    private lateinit var tvNose: TextView
    private lateinit var tvLeftCheek: TextView
    private lateinit var tvRightCheek: TextView
    private lateinit var tvChin: TextView
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 綁定介面元件
        tvNose = findViewById(R.id.tvNose)
        tvLeftCheek = findViewById(R.id.tvLeftCheek)
        tvRightCheek = findViewById(R.id.tvRightCheek)
        tvChin = findViewById(R.id.tvChin)
        tvStatus = findViewById(R.id.tvStatus)

        // 點擊狀態列可以手動重啟掃描連線
        tvStatus.setOnClickListener {
            if (bluetoothGatt == null && !isScanning) {
                startBleDiscovery()
            }
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // 開始進行多裝置搜尋與選擇
        startBleDiscovery()
    }

    // 啟動動態 BLE 掃描
    @SuppressLint("MissingPermission")
    private fun startBleDiscovery() {
        // 檢查權限
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 101)
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            tvStatus.text = "狀態: 請先開啟手機藍牙功能！"
            return
        }

        // 清空舊的搜尋紀錄
        discoveredDevices.clear()
        deviceNamesList.clear()

        tvStatus.text = "狀態: 正在搜尋病房監測裝置中..."
        isScanning = true

        // 10秒後自動停止掃描並跳出選擇框
        scanHandler.postDelayed({
            stopBleScanAndShowDialog()
        }, SCAN_PERIOD)

        bluetoothAdapter!!.bluetoothLeScanner?.startScan(bleScanCallback)
    }

    // 藍牙掃描監聽器
    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name

            // 關鍵過濾：只抓取名字開頭為 "V_MASK_" 的榮總呼吸器裝置
            if (name != null && name.startsWith("V_MASK")) {
                if (!discoveredDevices.contains(device)) {
                    discoveredDevices.add(device)
                    deviceNamesList.add("${device.name} (${device.address})")
                    Log.d(TAG, "發現目標監測裝置: ${device.name}")
                }
            }
        }
    }

    // 停止掃描並跳出選擇清單
    @SuppressLint("MissingPermission")
    private fun stopBleScanAndShowDialog() {
        if (!isScanning) return
        isScanning = false
        bluetoothAdapter!!.bluetoothLeScanner?.stopScan(bleScanCallback)

        if (discoveredDevices.isEmpty()) {
            tvStatus.text = "狀態: 未搜尋到裝置，點選此處重新搜尋"
            AlertDialog.Builder(this)
                .setTitle("搜尋結果")
                .setMessage("周圍未搜尋到任何以 V_MASK 開頭的壓力監測裝置。\n\n請確認 HUB 8735 電源已開啟，且已正常啟動藍牙廣播。")
                .setPositiveButton("重新搜尋") { _, _ -> startBleDiscovery() }
                .setNegativeButton("關閉", null)
                .show()
        } else {
            tvStatus.text = "狀態: 搜尋完畢，請選擇病床裝置"
            // 彈出選擇對話框
            val items = deviceNamesList.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("請選擇要連線的病床裝置")
                .setItems(items) { _, which ->
                    val selectedDevice = discoveredDevices[which]
                    tvStatus.text = "狀態: 正在連線至 ${selectedDevice.name}..."
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
                Log.i(TAG, "已成功連線至呼吸器模組")
                runOnUiThread { tvStatus.text = "狀態: 已連線至 ${gatt.device.name}" }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.e(TAG, "藍牙連線中斷")
                bluetoothGatt = null
                runOnUiThread { tvStatus.text = "狀態: 連線斷開，點選此處重新搜尋" }
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
            }
        }

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

                    runOnUiThread {
                        tvNose.text = "鼻樑: ${String.format("%.1f", pNose)} mmHg"
                        tvLeftCheek.text = "左臉頰: ${String.format("%.1f", pLCheek)} mmHg"
                        tvRightCheek.text = "右臉頰: ${String.format("%.1f", pRCheek)} mmHg"
                        tvChin.text = "下巴: ${String.format("%.1f", pChin)} mmHg"

                        if (alarmStatus == 0x01) {
                            tvStatus.text = "⚠️ 警報：病患臉部皮膚受壓超標！"
                            triggerEmergencyPopup(pNose, pLCheek, pRCheek, pChin, gatt.device.name)
                        } else {
                            tvStatus.text = "監測中: 已連上 ${gatt.device.name}"
                        }
                    }
                }
            }
        }
    }

    private fun triggerEmergencyPopup(nose: Float, lCheek: Float, rCheek: Float, chin: Float, deviceName: String) {
        if (isAlertShowing) return
        isAlertShowing = true

        try {
            val alertSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(applicationContext, alertSound)
            ringtone.play()

            Handler(Looper.getMainLooper()).post {
                val dialog = AlertDialog.Builder(this)
                    .setTitle("⚠️ 護理緊急警告：${deviceName} 壓迫超標 ⚠️")
                    .setMessage("系統偵測到 ${deviceName} 病患面罩壓力持續超標(>32 mmHg)！\n\n" +
                            "即時數據：\n" +
                            "• 鼻樑: ${String.format("%.1f", nose)} mmHg\n" +
                            "• 左臉頰: ${String.format("%.1f", lCheek)} mmHg\n" +
                            "• 右臉頰: ${String.format("%.1f", rCheek)} mmHg\n" +
                            "• 下巴: ${String.format("%.1f", chin)} mmHg\n\n" +
                            "請護理人員立刻前往病房，調整配戴面罩或頭帶鬆緊！")
                    .setCancelable(false)
                    .setPositiveButton("已處理並重置警告") { _, _ ->
                        ringtone.stop()
                        isAlertShowing = false
                    }
                    .create()

                dialog.show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isAlertShowing = false
        }
    }

    // 權限請求結果回傳
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startBleDiscovery()
            } else {
                tvStatus.text = "狀態: 護理人員拒絕藍牙權限，無法連線監測！"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}