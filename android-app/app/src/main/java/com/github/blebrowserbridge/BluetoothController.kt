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
        private const val GROUP_TAG_SIZE = 4
    }

    // First bytes of SHA-256 of the shared group code. Advertisements carry
    // it as a prefix and clients filter on it, so unrelated senders (or a
    // second group in the same venue) cannot confuse the clients.
    private var groupTag = deriveGroupTag("")

    fun setGroupCode(code: String) {
        groupTag = deriveGroupTag(code)
    }

    private fun deriveGroupTag(code: String): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("pdf-sync-viewer:$code".toByteArray(Charsets.UTF_8))
            .copyOf(GROUP_TAG_SIZE)

    var onPdfNameReceived: ((String, Int) -> Unit)? = null
    val bleEvents = mutableListOf<String>()
    
    private var lastReceivedPdfName: String? = null
    private var lastReceivedPageIndex: Int = -1
    private var lastReceivedCounter: Byte = -1
    private var advertisementCounter: Byte = (System.currentTimeMillis() % 128).toByte()

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    fun startServer() {
        Log.d(TAG, "Starting BLE Server")
        sendPdfNameViaAdvertisement("server-ready", 0)
    }

    @SuppressLint("MissingPermission")
    fun sendPdfNameViaAdvertisement(pdfName: String, pageIndex: Int) {
        if (!hasAdvertisePermission()) {
            bleEvents.add("ERROR: Missing advertising permission.")
            return
        }
        
        advertisementCounter++
        Log.d(TAG, "Updating advertisement: $pdfName:$pageIndex (v$advertisementCounter)")
        bleEvents.add("Advertising PDF: $pdfName:$pageIndex (v$advertisementCounter)")

        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertising", e)
        }

        // Delay to ensure the BLE stack processes the stop before starting again
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            startAdvertisingInternal(pdfName, advertisementCounter, pageIndex)
        }, 200)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingInternal(pdfName: String, counter: Byte, pageIndex: Int) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        // Payload: [group tag (4)] [counter] [page hi] [page lo] [name...]
        val p1 = (pageIndex shr 8 and 0xFF).toByte()
        val p2 = (pageIndex and 0xFF).toByte()

        val headerSize = GROUP_TAG_SIZE + 3
        var nameBytes = pdfName.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > MAX_ADVERTISEMENT_BYTES - headerSize) {
            nameBytes = nameBytes.sliceArray(0 until MAX_ADVERTISEMENT_BYTES - headerSize)
        }

        val dataBytes = ByteArray(nameBytes.size + headerSize)
        System.arraycopy(groupTag, 0, dataBytes, 0, GROUP_TAG_SIZE)
        dataBytes[GROUP_TAG_SIZE] = counter
        dataBytes[GROUP_TAG_SIZE + 1] = p1
        dataBytes[GROUP_TAG_SIZE + 2] = p2
        System.arraycopy(nameBytes, 0, dataBytes, headerSize, nameBytes.size)

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
        
        // Firmware-level filter: only advertisements whose manufacturer data
        // starts with our group tag reach the callback (the filter compares
        // just the tag-length prefix)
        val filterMask = ByteArray(GROUP_TAG_SIZE) { 0xFF.toByte() }
        val scanFilters = listOf(
            ScanFilter.Builder()
                .setManufacturerData(MANUFACTURER_ID, groupTag.copyOf(), filterMask)
                .build()
        )
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
                val headerSize = GROUP_TAG_SIZE + 3
                if (data.size >= headerSize) {
                    // Double-check the group tag in case the firmware filter
                    // was not applied
                    for (i in 0 until GROUP_TAG_SIZE) {
                        if (data[i] != groupTag[i]) return
                    }
                    val counter = data[GROUP_TAG_SIZE]
                    val pageIndex = ((data[GROUP_TAG_SIZE + 1].toInt() and 0xFF) shl 8) or (data[GROUP_TAG_SIZE + 2].toInt() and 0xFF)
                    val pdfName = String(data, headerSize, data.size - headerSize, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                    
                    if (pdfName != lastReceivedPdfName || pageIndex != lastReceivedPageIndex || counter != lastReceivedCounter) {
                        lastReceivedPdfName = pdfName
                        lastReceivedPageIndex = pageIndex
                        lastReceivedCounter = counter
                        Log.d(TAG, "Received update: $pdfName:$pageIndex (v$counter)")
                        bleEvents.add("Received update: $pdfName:$pageIndex (v$counter)")
                        onPdfNameReceived?.invoke(pdfName, pageIndex)
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
