package com.github.blebrowserbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

class BluetoothController(private val context: Context) {

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    
    private val advertiser: BluetoothLeAdvertiser? 
        get() = bluetoothAdapter?.bluetoothLeAdvertiser
    private val scanner: BluetoothLeScanner? 
        get() = bluetoothAdapter?.bluetoothLeScanner

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "BluetoothController"
        private const val MANUFACTURER_ID = 0xFFFF
        private const val MAX_ADVERTISEMENT_BYTES = 20
    }

    var onPdfNameReceived: ((String) -> Unit)? = null
    val bleEvents = mutableListOf<String>()
    
    private var lastReceivedPdfName: String? = null
    private var lastReceivedCounter: Byte = -1
    private var advertisementCounter: Byte = (System.currentTimeMillis() % 128).toByte()

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    fun startServer() {
        Log.d(TAG, "Starting BLE Server")
        sendPdfNameViaAdvertisement("server-ready")
    }

    @SuppressLint("MissingPermission")
    fun sendPdfNameViaAdvertisement(pdfName: String) {
        if (!hasAdvertisePermission()) {
            bleEvents.add("ERROR: Missing advertising permission.")
            return
        }
        
        advertisementCounter++
        Log.d(TAG, "Updating advertisement: $pdfName (v$advertisementCounter)")
        bleEvents.add("Advertising PDF: $pdfName (v$advertisementCounter)")

        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertising", e)
        }

        // Delay to ensure the BLE stack processes the stop before starting again
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            startAdvertisingInternal(pdfName, advertisementCounter)
        }, 200)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingInternal(pdfName: String, counter: Byte) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        var nameBytes = pdfName.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > MAX_ADVERTISEMENT_BYTES - 1) {
            nameBytes = nameBytes.sliceArray(0 until MAX_ADVERTISEMENT_BYTES - 1)
        }

        val dataBytes = ByteArray(nameBytes.size + 1)
        dataBytes[0] = counter
        System.arraycopy(nameBytes, 0, dataBytes, 1, nameBytes.size)

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(MANUFACTURER_ID, dataBytes)
            .build()

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting advertising", e)
            bleEvents.add("ERROR: Start advertising failed.")
        }
    }

    @SuppressLint("MissingPermission")
    fun startClient() {
        if (!hasScanPermission()) {
            bleEvents.add("ERROR: Missing scan permission.")
            return
        }
        Log.d(TAG, "Starting BLE Client")
        lastReceivedPdfName = null
        lastReceivedCounter = -1
        
        val scanFilters = listOf(ScanFilter.Builder().build())
        val scanSettingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            scanSettingsBuilder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            scanSettingsBuilder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
        }

        try {
            scanner?.startScan(scanFilters, scanSettingsBuilder.build(), scanCallback)
            bleEvents.add("Client scan started.")
        } catch(e: Exception) {
            Log.e(TAG, "Error starting scan", e)
            bleEvents.add("ERROR: Start scan failed.")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        Log.d(TAG, "Stopping BLE operations")
        handler.removeCallbacksAndMessages(null)
        try {
            if (hasAdvertisePermission()) advertiser?.stopAdvertising(advertiseCallback)
            if (hasScanPermission()) scanner?.stopScan(scanCallback)
            bleEvents.add("BLE operations stopped.")
        } catch(e: Exception) {
            Log.e(TAG, "Error stopping BLE", e)
        }
    }

    private fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failure: $errorCode")
            bleEvents.add("Advertising failure: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)?.let { data ->
                if (data.size >= 1) {
                    val counter = data[0]
                    val pdfName = String(data, 1, data.size - 1, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                    
                    if (pdfName != lastReceivedPdfName || counter != lastReceivedCounter) {
                        lastReceivedPdfName = pdfName
                        lastReceivedCounter = counter
                        Log.d(TAG, "Received update: $pdfName (v$counter)")
                        bleEvents.add("Received update: $pdfName (v$counter)")
                        onPdfNameReceived?.invoke(pdfName)
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            bleEvents.add("Scan failure: $errorCode")
        }
    }
}
