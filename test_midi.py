import socket
import struct
import sys
import time

def test_rtp_midi(ip, page_index):
    port = 5004
    # 1. Send Invitation (IN)
    # Header: AppleMIDI (0xFF 0xFF), Command: IN (0x49 0x4e)
    # Version: 2, Token: random, SSRC: random, Name: PythonTest
    token = 0x12345678
    ssrc = 0x11223344
    name = b'PythonTest\x00'

    invitation = struct.pack('>HHII I', 0xffff, 0x494e, 2, token, ssrc) + name

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(2.0)

    print(f"Sending invitation to {ip}:{port}...")
    sock.sendto(invitation, (ip, port))

    try:
        data, addr = sock.recvfrom(1024)
        print(f"Received response from {addr}")

        # 2. Send MIDI Message (Program Change) on port+1
        # Header: AM (0x41 0x4d), Command: MD (0x4d 0x44)
        # SSRC, Sequence, Timestamp
        # MIDI Data: 0xC0 (Program Change, Channel 1), Program Index
        msg_port = port + 1
        sequence = 1
        timestamp = int(time.time()) & 0xFFFFFFFF

        # RTP MIDI Header + MIDI Message
        # B1: 0x80 (Full), Delta: 0, MIDI: 0xC0, page_index
        # Note: Some implementations expect Program Change as 0xC0 followed by the program number
        # DPMIDI's MIDIReceivedEvent maps 'note' to the first data byte.
        midi_data = struct.pack('>BB', 0xC0, page_index)
        header = struct.pack('>HHIII B B', 0x414d, 0x4d44, ssrc, sequence, timestamp, 0x80, 0x00)

        print(f"Sending Program Change to page {page_index} on port {msg_port}...")
        sock.sendto(header + midi_data, (ip, msg_port))

        # Also send a Note On as an alternative (command 0x90, but DPMIDI might report 0x09 depending on processing)
        # In MidiController.kt: if (command == 0x09 && velocity > 0)
        time.sleep(0.5)
        sequence += 1
        note_on_data = struct.pack('>BBB', 0x90, page_index, 100)
        header_note = struct.pack('>HHIII B B', 0x414d, 0x4d44, ssrc, sequence, timestamp + 100, 0x80, 0x00)
        print(f"Sending Note On (page {page_index}) to port {msg_port}...")
        sock.sendto(header_note + note_on_data, (ip, msg_port))

        print("Done.")

    except socket.timeout:
        print("Timeout: No response from server. Is the app in Server mode and on the same Wi-Fi?")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python3 test_midi.py <ANDROID_IP> <PAGE_INDEX>")
        sys.exit(1)
    test_rtp_midi(sys.argv[1], int(sys.argv[2]))
