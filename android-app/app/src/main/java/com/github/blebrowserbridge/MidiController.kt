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

    fun start() {
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

        // Map MIDI Program Change or Note On to page changes
        // Note On (0x09) and Program Change (0x0C)
        if ((command == 0x09 || command == 0x08) && velocity > 0) {
            onPageChangeRequested?.invoke(note)
        } else if (command == 0x0C) { // Program Change
            onPageChangeRequested?.invoke(note)
        }
    }
}
