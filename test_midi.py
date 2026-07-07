#!/usr/bin/env python3
"""SongBook emulator: connects to the app's RTP-MIDI (AppleMIDI) session and
sends page-turn messages, the same way LinkeSOFT SongBook does.

Protocol (RFC 6295 / AppleMIDI):
  1. Invitation (IN) on the control port (5004) -> expect OK
  2. Invitation (IN) on the MIDI port (5005)    -> expect OK, session connected
  3. Answer the app's clock sync (CK) requests while connected
  4. MIDI data as RTP packets (payload type 0x61) on the MIDI port
  5. End session (BY) on the control port

Usage:
  python3 test_midi.py <ANDROID_IP> 14_20             # select song "14_20.pdf" (bank 14, program 20,
                                                      #   1-based display values like in SongBook)
  python3 test_midi.py <ANDROID_IP> 5                 # bank select 0 + program change 5 (wire values)
  python3 test_midi.py <ANDROID_IP> 5 --simple        # bare program change
  python3 test_midi.py <ANDROID_IP> 5 --note          # note on (page turn within the current PDF)
"""

import argparse
import random
import select
import socket
import struct
import sys
import time

APPLEMIDI_SIGNATURE = 0xFFFF
CMD_IN = 0x494E  # invitation
CMD_OK = 0x4F4B  # invitation accepted
CMD_NO = 0x4E4F  # invitation rejected
CMD_BY = 0x4259  # end session
CMD_CK = 0x434B  # clock synchronization
CMD_RS = 0x5253  # receiver feedback

START_TIME = time.monotonic()


def now_timestamp():
    """AppleMIDI timestamps are in 100-microsecond (10 kHz) units."""
    return int((time.monotonic() - START_TIME) * 10000)


class SongBookEmulator:
    def __init__(self, host, control_port, name):
        self.host = host
        self.control_port = control_port
        self.midi_port = control_port + 1
        self.name = name.encode() + b"\x00"
        self.token = random.getrandbits(32)
        self.ssrc = random.getrandbits(32)
        self.sequence = random.getrandbits(15)
        self.remote_ssrc = None
        self.remote_name = None

        # AppleMIDI convention: initiator uses an even/odd local port pair
        self.control_sock, self.midi_sock = self._bind_port_pair()

    @staticmethod
    def _bind_port_pair():
        for base in range(16384, 16584, 2):
            a = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            b = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                a.bind(("", base))
                b.bind(("", base + 1))
                return a, b
            except OSError:
                a.close()
                b.close()
        raise RuntimeError("no free local port pair found")

    # --- AppleMIDI control packets ---------------------------------------

    def _session_packet(self, command):
        return struct.pack(">HHIII", APPLEMIDI_SIGNATURE, command, 2,
                           self.token, self.ssrc) + self.name

    def _invite(self, sock, port, label):
        packet = self._session_packet(CMD_IN)
        deadline = time.monotonic() + 3.0
        while time.monotonic() < deadline:
            sock.sendto(packet, (self.host, port))
            readable, _, _ = select.select([sock], [], [], 1.0)
            if not readable:
                continue
            data, addr = sock.recvfrom(1500)
            sig, cmd = struct.unpack_from(">HH", data)
            if sig != APPLEMIDI_SIGNATURE:
                continue
            if cmd == CMD_OK:
                _, token, ssrc = struct.unpack_from(">III", data, 4)
                name = data[16:].split(b"\x00")[0].decode(errors="replace")
                self.remote_ssrc = ssrc
                self.remote_name = name
                print(f"[{label}] invitation accepted by '{name}' (ssrc=0x{ssrc:08X})")
                return
            if cmd == CMD_NO:
                raise RuntimeError(f"[{label}] invitation rejected")
        raise TimeoutError(
            f"[{label}] no reply from {self.host}:{port} - is the app in "
            "server mode and on the same network?")

    def connect(self):
        print(f"Connecting to {self.host}:{self.control_port} as "
              f"'{self.name[:-1].decode()}' (ssrc=0x{self.ssrc:08X})")
        self._invite(self.control_sock, self.control_port, "control")
        self._invite(self.midi_sock, self.midi_port, "midi")
        print("Session established.")

    def disconnect(self):
        self.control_sock.sendto(self._session_packet(CMD_BY),
                                 (self.host, self.control_port))
        print("Sent end-session (BY).")
        self.control_sock.close()
        self.midi_sock.close()

    # --- clock synchronization --------------------------------------------

    def _answer_clock_sync(self, data, addr):
        ssrc, count = struct.unpack_from(">IB", data, 4)
        ts1, ts2, ts3 = struct.unpack_from(">QQQ", data, 12)
        if count == 0:
            reply = struct.pack(">HHIB3xQQQ", APPLEMIDI_SIGNATURE, CMD_CK,
                                self.ssrc, 1, ts1, now_timestamp(), 0)
            self.midi_sock.sendto(reply, addr)
            print("[sync] answered CK0 with CK1")
        elif count == 2:
            print("[sync] clock sync round complete (CK2)")

    def listen(self, duration):
        """Service sync requests (and log anything else) for `duration` s."""
        end = time.monotonic() + duration
        while time.monotonic() < end:
            readable, _, _ = select.select(
                [self.control_sock, self.midi_sock], [], [],
                max(0.0, end - time.monotonic()))
            for sock in readable:
                data, addr = sock.recvfrom(1500)
                if len(data) >= 4:
                    sig, cmd = struct.unpack_from(">HH", data)
                    if sig == APPLEMIDI_SIGNATURE and cmd == CMD_CK:
                        self._answer_clock_sync(data, addr)
                        continue
                    if sig == APPLEMIDI_SIGNATURE and cmd == CMD_RS:
                        continue
                print(f"[recv] {len(data)} bytes from {addr}: {data.hex()}")

    # --- RTP-MIDI data ------------------------------------------------------

    def send_midi(self, midi_list):
        """Wrap a MIDI command list in an RTP packet (RFC 6295)."""
        if len(midi_list) > 15:
            raise ValueError("command list too long for the short header form")
        self.sequence = (self.sequence + 1) % 0x10000
        rtp = struct.pack(">BBHII", 0x80, 0x61, self.sequence,
                          now_timestamp() & 0xFFFFFFFF, self.ssrc)
        # command section header: B=0 J=0 Z=0 P=0, LEN = list length
        packet = rtp + bytes([len(midi_list)]) + midi_list
        self.midi_sock.sendto(packet, (self.host, self.midi_port))

    def send_song_select(self, program, bank=0):
        """What SongBook sends when a song is selected: bank select + program
        change in one packet, using running status for the second CC."""
        midi_list = bytes([
            0xB0, 0x00, (bank >> 7) & 0x7F,  # CC0  bank MSB
            0x00, 0x20, bank & 0x7F,         # delta 0, CC32 bank LSB (running status)
            0x00, 0xC0, program & 0x7F,      # delta 0, program change
        ])
        self.send_midi(midi_list)
        print(f"Sent bank select {bank} + program change {program}")

    def send_program_change(self, program):
        self.send_midi(bytes([0xC0, program & 0x7F]))
        print(f"Sent program change {program}")

    def send_note_on(self, note, velocity=100):
        self.send_midi(bytes([0x90, note & 0x7F, velocity & 0x7F]))
        print(f"Sent note on {note} (velocity {velocity})")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("host", help="IP of the Android device")
    parser.add_argument("target",
                        help="song as BANK_PROGRAM in 1-based display values "
                             "(e.g. 14_20 for 14_20.pdf), or a bare 0-based "
                             "program/note number")
    parser.add_argument("--port", type=int, default=5004,
                        help="control port of the session (default 5004)")
    parser.add_argument("--name", default="SongBook Emulator",
                        help="session name to announce")
    parser.add_argument("--simple", action="store_true",
                        help="send a bare program change (no bank select)")
    parser.add_argument("--note", action="store_true",
                        help="send a note on instead of a program change")
    parser.add_argument("--wait", type=float, default=5.0,
                        help="seconds to stay connected and answer clock sync")
    args = parser.parse_args()

    if "_" in args.target:
        # SongBook display values are 1-based, the wire is 0-based
        bank_display, program_display = (int(x) for x in args.target.split("_"))
        bank, program = bank_display - 1, program_display - 1
    else:
        bank, program = 0, int(args.target)

    emu = SongBookEmulator(args.host, args.port, args.name)
    try:
        emu.connect()
        emu.listen(1.0)  # give the app a chance to run its first sync round
        if args.note:
            emu.send_note_on(program)
        elif args.simple:
            emu.send_program_change(program)
        else:
            emu.send_song_select(program, bank)
        emu.listen(args.wait)
    finally:
        emu.disconnect()


if __name__ == "__main__":
    sys.exit(main())
