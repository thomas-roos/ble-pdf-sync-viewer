package com.disappointedpig.midi.internal_events;

import android.os.Bundle;

import com.disappointedpig.midi.MIDIConstants;

import java.net.DatagramPacket;
import java.net.InetAddress;

public class PacketEvent {
    private InetAddress address;
    private int port;
    private int receivingPort;
    private byte[] data;
    private int length;

    public PacketEvent(final DatagramPacket packet) {
        this(packet, -1);
    }

    public PacketEvent(final DatagramPacket packet, int receivingPort) {
        address = packet.getAddress();
        port = packet.getPort();
        data = packet.getData();
        length = packet.getLength();
        this.receivingPort = receivingPort;
    }

    public Bundle getRInfo() {
        Bundle rinfo = new Bundle();
        rinfo.putString(com.disappointedpig.midi.MIDIConstants.RINFO_ADDR,address.getHostAddress());
        rinfo.putInt(MIDIConstants.RINFO_PORT,port);
        rinfo.putInt("local_port", receivingPort);
        return rinfo;
    }

    public int getReceivingPort() {
        return receivingPort;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public byte[] getData() {
        return data;
    }

    public int getLength() {
        return length;
    }
}
