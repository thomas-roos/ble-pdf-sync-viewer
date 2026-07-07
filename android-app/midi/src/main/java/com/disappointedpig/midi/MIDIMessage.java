package com.disappointedpig.midi;


import android.os.Bundle;
import android.util.Log;

import com.disappointedpig.midi.internal_events.PacketEvent;
import com.disappointedpig.midi.utility.DataBuffer;
import com.disappointedpig.midi.utility.OutDataBuffer;

import java.util.ArrayList;
import java.util.List;

public class MIDIMessage extends RTPMessage {

    private Boolean valid;
    private DataBuffer m;

    private final List<Bundle> commands = new ArrayList<>();

    public int command;
    public int channel;
    public int note;
    public int velocity;

    public static MIDIMessage newUsing(int cs, int c, int n, int v) {
        MIDIMessage m = new MIDIMessage();
        m.createNote(cs,c,n,v);
        return m;
    }

    public static MIDIMessage newUsing(Bundle m) {
        return newUsing(   m.getInt(com.disappointedpig.midi.MIDIConstants.MSG_COMMAND,0x09),
                    m.getInt(com.disappointedpig.midi.MIDIConstants.MSG_CHANNEL,0),
                    m.getInt(com.disappointedpig.midi.MIDIConstants.MSG_NOTE,0),
                    m.getInt(com.disappointedpig.midi.MIDIConstants.MSG_VELOCITY,0));
    }


    public MIDIMessage() {
    }

    public boolean parseMessage(PacketEvent packet) {
        this.valid = false;
        commands.clear();
        parse(packet);

        // Parse the MIDI list (RFC 6295, section 3.2): commands separated by
        // variable-length delta times, running status allowed after the first
        // command. Two-byte commands (program change 0xC, channel pressure 0xD)
        // and three-byte channel commands are supported; system commands (0xF)
        // terminate parsing.
        int pos = 0;
        int runningStatus = 0;
        boolean deltaTimeExpected = firstCommandHasDeltaTime;

        while (pos < payload_length) {
            if (deltaTimeExpected) {
                while (pos < payload_length && (payload[pos] & 0x80) != 0) {
                    pos++;
                }
                pos++; // final delta-time octet (bit 7 clear)
                if (pos >= payload_length) break;
            }
            deltaTimeExpected = true; // all commands after the first have one

            int status = payload[pos] & 0xFF;
            if ((status & 0x80) != 0) {
                pos++;
            } else {
                status = runningStatus;
            }
            int type = status >> 4;
            if (type < 0x8 || type == 0xF) break;
            runningStatus = status;

            int dataLength = (type == 0xC || type == 0xD) ? 1 : 2;
            if (pos + dataLength > payload_length) break;
            int data1 = payload[pos++] & 0x7F;
            int data2 = (dataLength == 2) ? (payload[pos++] & 0x7F) : 0;

            Bundle midi = new Bundle();
            midi.putInt(com.disappointedpig.midi.MIDIConstants.MSG_COMMAND, type);
            midi.putInt(com.disappointedpig.midi.MIDIConstants.MSG_CHANNEL, status & 0xF);
            midi.putInt(com.disappointedpig.midi.MIDIConstants.MSG_NOTE, data1);
            midi.putInt(com.disappointedpig.midi.MIDIConstants.MSG_VELOCITY, data2);
            commands.add(midi);

            if (commands.size() == 1) {
                command = type;
                channel = status & 0xF;
                note = data1;
                velocity = data2;
            }
            Log.d("MIDIMessage", "cs:" + type + " c:" + (status & 0xF) + " n:" + data1 + " v" + data2);
        }

        this.valid = !commands.isEmpty();
        return this.valid;
    }

    public List<Bundle> getCommands() {
        return commands;
    }

    public Bundle toBundle() {
        Bundle midi = new Bundle();
        midi.putInt(com.disappointedpig.midi.MIDIConstants.MSG_COMMAND,this.command);
        midi.putInt(com.disappointedpig.midi.MIDIConstants.MSG_CHANNEL,this.channel);
        midi.putInt(com.disappointedpig.midi.MIDIConstants.MSG_NOTE, this.note);
        midi.putInt(com.disappointedpig.midi.MIDIConstants.MSG_VELOCITY, this.velocity);
        return midi;
    }

    public void createNote(int command, int channel, int note, int velocity) {
        this.command = command;
        this.channel = channel;
        this.note = note;
        this.velocity = velocity;
    }
    public void createNote(int note, int velocity) {
        this.note = note;
        this.velocity = velocity;
    }

    public Boolean isValid() {
        return valid;
    }

    public byte[] generateBuffer() {
        OutDataBuffer buffer = generatePayload();
// TODO : this doesn't handle channel_status or channel correctly
//        buffer.write8(0x00);
        buffer.write16(0x0390);
        buffer.write8(note);
        buffer.write8(velocity);
        return buffer.toByteArray();
    }
}