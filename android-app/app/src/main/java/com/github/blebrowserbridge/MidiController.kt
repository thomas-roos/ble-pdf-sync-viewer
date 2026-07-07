package com.github.blebrowserbridge

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.disappointedpig.midi.MIDIConstants
import com.disappointedpig.midi.MIDISession
import com.disappointedpig.midi.events.MIDIReceivedEvent
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MidiController(private val context: Context) {
    private val TAG = "MidiController"
    private var multicastLock: WifiManager.MulticastLock? = null
    private var midiSession: MIDISession? = null

    var onPageChangeRequested: ((Int) -> Unit)? = null
    var onSongSelectRequested: ((bank: Int, program: Int) -> Unit)? = null

    // Bank select state (CC0 = MSB, CC32 = LSB), applied by the next program change
    private var bankMsb = 0
    private var bankLsb = 0

    fun start(groupCode: String = "") {
        Log.i(TAG, "Starting MIDI Controller")
        
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("MidiMulticastLock")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

        try {
            midiSession = MIDISession.getInstance()
            val sessionName = if (groupCode.isEmpty()) "pdf-sync-viewer"
                              else "pdf-sync-viewer-$groupCode"
            midiSession?.setBonjourName(sessionName)
            midiSession?.start(context)
            Log.d(TAG, "MIDI Session started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MIDI session", e)
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping MIDI Controller")
        midiSession?.stop()
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        multicastLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMidiEvent(event: MIDIReceivedEvent) {
        val command = event.midi.getInt(MIDIConstants.MSG_COMMAND)
        val note = event.midi.getInt(MIDIConstants.MSG_NOTE)
        val velocity = event.midi.getInt(MIDIConstants.MSG_VELOCITY)

        Log.d(TAG, "Received MIDI Event: cmd=$command, note=$note, vel=$velocity")

        when (command) {
            0x0B -> when (note) { // Control Change: remember bank select
                0 -> bankMsb = velocity
                32 -> bankLsb = velocity
            }
            // Program Change selects a song (SongBook sends bank select + program change)
            0x0C -> onSongSelectRequested?.invoke(bankMsb * 128 + bankLsb, note)
            // Note On turns to an absolute page within the current PDF
            0x09, 0x08 -> if (velocity > 0) onPageChangeRequested?.invoke(note)
        }
    }
}
