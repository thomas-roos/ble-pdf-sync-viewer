package com.github.blebrowserbridge

import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * Mobly snippet exposing the app's real BLE sync layer to the host-side
 * multi-device tests (multi-device-tests/ble_sync_test.py). One device is
 * driven as server, the others as clients, over the actual BLE radio (or the
 * emulator's virtual radio in CI).
 */
class BleSyncSnippet : Snippet {
    private val controller =
        BluetoothController(InstrumentationRegistry.getInstrumentation().targetContext)

    private val received = Collections.synchronizedList(mutableListOf<JSONObject>())

    init {
        controller.onPdfNameReceived = { name, page ->
            received.add(JSONObject().put("name", name).put("page", page))
        }
    }

    @Rpc(description = "Whether the Bluetooth adapter is enabled.")
    fun isBluetoothEnabled(): Boolean = controller.isBluetoothEnabled()

    @Rpc(description = "Set the shared group code used to tag and filter advertisements.")
    fun setGroupCode(code: String) {
        controller.setGroupCode(code)
    }

    @Rpc(description = "Start scanning for sync advertisements (client role).")
    fun startClient() {
        controller.startClient()
    }

    @Rpc(description = "Advertise the given file name and page index (server role).")
    fun broadcast(
        pdfName: String,
        pageIndex: Int,
    ) {
        controller.sendPdfNameViaAdvertisement(pdfName, pageIndex)
    }

    @Rpc(description = "All {name, page} updates received since the snippet was loaded.")
    fun getReceived(): JSONArray = JSONArray(received.toList())

    @Rpc(description = "The BLE event log, for debugging test failures.")
    fun getBleEvents(): JSONArray = JSONArray(controller.bleEvents.toList())

    @Rpc(description = "Stop advertising and scanning.")
    fun stopBle() {
        controller.stop()
    }

    override fun shutdown() {
        controller.stop()
    }
}
