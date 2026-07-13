#!/usr/bin/env python3
"""Multi-device BLE sync tests.

Drives two real Android devices (or two emulators with virtual Bluetooth)
through the app's actual BLE code: one device advertises as the server, the
other scans as a client, and the tests assert that file/page selections
arrive over the air - including the group-code isolation behavior.

Requires the app APK and the androidTest snippet APK to be installed on all
devices (run_tests.sh does that), at least two devices connected via adb,
and Bluetooth enabled on each.

Usage:
  pip3 install -r requirements.txt
  python3 ble_sync_test.py -c testbed.yml
"""

import os
import re
import sys
import time

from mobly import asserts
from mobly import base_test
from mobly import test_runner
from mobly.controllers import android_device

# Reuse the AppleMIDI client from the manual test script in the repo root
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from test_midi import SongBookEmulator

APP_PACKAGE = 'com.github.blebrowserbridge'
SNIPPET_PACKAGE = APP_PACKAGE + '.test'

# BLE advertisements are broadcast continuously, so the first scan match can
# take a few seconds - especially on the emulator's virtual radio.
SYNC_TIMEOUT_S = 30
# How long to listen before concluding an advertisement was (correctly) not
# received.
NO_SYNC_WAIT_S = 10

# Payload layout is [group tag (4)] [counter] [page hi] [page lo] [name...]
# within 20 bytes, so file names are truncated to 13 bytes on the air.
MAX_NAME_BYTES = 13


class BleSyncTest(base_test.BaseTestClass):

    def setup_class(self):
        self.ads = self.register_controller(android_device, min_number=2)
        self.server = self.ads[0]
        self.client = self.ads[1]
        for ad in self.ads:
            self._prepare_device(ad)

    def _prepare_device(self, ad):
        sdk = int(ad.build_info['build_version_sdk'])
        permissions = []
        if sdk >= 23:
            # Location powers BLE scan results up to Android 11.
            permissions.append('android.permission.ACCESS_FINE_LOCATION')
        if sdk >= 31:
            permissions.extend([
                'android.permission.BLUETOOTH_ADVERTISE',
                'android.permission.BLUETOOTH_SCAN',
                'android.permission.BLUETOOTH_CONNECT',
            ])
        for permission in permissions:
            try:
                ad.adb.shell(['pm', 'grant', APP_PACKAGE, permission])
            except Exception:
                pass  # not grantable on this API level - install -g covers it
        for command in (
            ['settings', 'put', 'secure', 'location_mode', '3'],
            # The first-run "Viewing full screen" hint is a focused system
            # window that hides the app from uiautomator dumps
            ['settings', 'put', 'secure', 'immersive_mode_confirmations',
             'confirmed'],
            ['svc', 'bluetooth', 'enable'],
            ['cmd', 'bluetooth_manager', 'enable'],
        ):
            try:
                ad.adb.shell(command)
            except Exception:
                pass
        ad.load_snippet('ble', SNIPPET_PACKAGE)
        asserts.abort_class_if(
            not ad.ble.isBluetoothEnabled(),
            'Bluetooth is disabled on %s and could not be enabled via adb - '
            'turn it on manually and rerun.' % ad.serial)

    def setup_test(self):
        for ad in self.ads:
            ad.ble.setGroupCode('')

    def teardown_test(self):
        for ad in self.ads:
            ad.ble.stopBle()

    def teardown_class(self):
        for ad in getattr(self, 'ads', []):
            try:
                ad.unload_snippet('ble')
            except Exception:
                pass

    def _wait_for_update(self, ad, name, page):
        """Waits until `ad` has received the given file/page selection."""
        deadline = time.time() + SYNC_TIMEOUT_S
        while time.time() < deadline:
            for update in ad.ble.getReceived():
                if update['name'] == name and update['page'] == page:
                    return
            time.sleep(1)
        asserts.fail(
            '%s did not receive %s:%d within %ds.\nclient BLE events: %s\n'
            'server BLE events: %s' %
            (ad.serial, name, page, SYNC_TIMEOUT_S,
             ad.ble.getBleEvents(), self.server.ble.getBleEvents()))

    def _assert_never_received(self, ad, name):
        """Listens for NO_SYNC_WAIT_S and asserts `name` never arrived."""
        time.sleep(NO_SYNC_WAIT_S)
        received_names = [update['name'] for update in ad.ble.getReceived()]
        asserts.assert_not_in(
            name, received_names,
            '%s received %s although the group codes differ.' %
            (ad.serial, name))

    def test_client_receives_file_and_page(self):
        self.client.ble.startClient()
        self.server.ble.broadcast('march.pdf', 2)
        self._wait_for_update(self.client, 'march.pdf', 2)

    def test_page_turns_follow(self):
        self.client.ble.startClient()
        for page in (0, 1, 2):
            self.server.ble.broadcast('waltz.pdf', page)
            self._wait_for_update(self.client, 'waltz.pdf', page)

    def test_long_file_names_truncate_consistently(self):
        self.client.ble.startClient()
        long_name = 'symphony_no_9_finale.pdf'
        self.server.ble.broadcast(long_name, 4)
        self._wait_for_update(self.client, long_name[:MAX_NAME_BYTES], 4)

    def test_wrong_group_code_is_ignored(self):
        self.server.ble.setGroupCode('bandA')
        self.client.ble.setGroupCode('bandB')
        self.client.ble.startClient()
        self.server.ble.broadcast('secret.pdf', 1)
        self._assert_never_received(self.client, 'secret.pdf')

    def test_matching_group_code_is_received(self):
        self.server.ble.setGroupCode('bandA')
        self.client.ble.setGroupCode('bandA')
        self.client.ble.startClient()
        self.server.ble.broadcast('anthem.pdf', 3)
        self._wait_for_update(self.client, 'anthem.pdf', 3)

    def _save_screenshot(self, ad, name):
        """Saves a screenshot into SCREENSHOT_DIR (or the Mobly log dir)."""
        out_dir = os.environ.get('SCREENSHOT_DIR') or self.current_test_info.output_path
        os.makedirs(out_dir, exist_ok=True)
        device_path = '/data/local/tmp/%s.png' % name
        ad.adb.shell(['screencap', '-p', device_path])
        local_path = os.path.join(out_dir, '%s_%s.png' % (name, ad.serial))
        ad.adb.pull([device_path, local_path])
        ad.adb.shell(['rm', device_path])
        ad.log.info('Screenshot saved to %s', local_path)

    def _launch_app(self, ad, role):
        ad.adb.shell([
            'am', 'start', '-W', '-n', APP_PACKAGE + '/.MainActivity',
            '--es', 'autostart', role])
        time.sleep(3)  # activity animation + BLE/MIDI startup

    def _close_app(self, ad):
        # BACK finishes the activity, which stops its BLE and MIDI so later
        # tests don't see stray advertisements
        ad.adb.shell(['input', 'keyevent', 'KEYCODE_BACK'])
        time.sleep(1)

    def _ui_contains(self, ad, text):
        ad.adb.shell(['uiautomator', 'dump', '/data/local/tmp/ui.xml'])
        dump = ad.adb.shell(['cat', '/data/local/tmp/ui.xml'])
        return ('"%s"' % text).encode() in dump

    def _wait_for_ui_text(self, ad, text, screenshot_name):
        """Waits until `text` appears in the UI, saving a screenshot."""
        deadline = time.time() + SYNC_TIMEOUT_S
        while time.time() < deadline:
            if self._ui_contains(ad, text):
                self._save_screenshot(ad, screenshot_name)
                return
            time.sleep(2)
        self._save_screenshot(ad, screenshot_name + '_FAILED')
        asserts.fail('%s UI never showed "%s". server BLE events: %s' %
                     (ad.serial, text, self.server.ble.getBleEvents()))

    def test_ui_number_display_follows_server(self):
        """End-to-end through the real UI: the client app is launched in
        client mode (no files selected, so it acts as a number display) and
        must show the number of the file the server broadcasts."""
        self._launch_app(self.client, 'client')
        self._save_screenshot(self.client, 'client_waiting')

        self.server.ble.broadcast('7_3.pdf', 0)
        try:
            # 7_3.pdf is not on the device, so the app shows "7/3" fullscreen
            self._wait_for_ui_text(self.client, '7/3', 'client_number_display')
        finally:
            self._close_app(self.client)

    def _midi_endpoint(self, ad):
        """Returns (host, control_port) reaching the app's RTP MIDI ports."""
        if ad.serial.startswith('emulator-'):
            # Let the emulator NAT forward host ports to the guest's 5004/5005
            for redir in ('udp:15004:5004', 'udp:15005:5005'):
                ad.adb.emu(['redir', 'add', redir])
            return '127.0.0.1', 15004
        out = ad.adb.shell(['ip', 'route', 'get', '1.1.1.1']).decode()
        match = re.search(r'src (\S+)', out)
        if not match:
            asserts.skip('%s has no IP reachable from the host' % ad.serial)
        return match.group(1), 5004

    def test_midi_song_select_shows_number(self):
        """RTP MIDI end-to-end: the host connects to the server app's
        AppleMIDI session like SongBook would and selects song 14_20; with
        no files on the device the app must show "14/20" fullscreen."""
        host, port = self._midi_endpoint(self.server)
        self._launch_app(self.server, 'server')
        emu = SongBookEmulator(host, port, 'Mobly MIDI test')
        shown = False
        try:
            emu.connect()
            emu.listen(2.0)  # answer the app's first clock sync round
            # UDP has no delivery guarantee and the app may still be busy
            # with clock sync: keep the session open and resend the
            # selection until the UI shows it.
            # Display value 14_20 -> wire values bank 13, program 19.
            deadline = time.time() + SYNC_TIMEOUT_S
            while not shown and time.time() < deadline:
                emu.send_song_select(program=19, bank=13)
                emu.listen(3.0)
                shown = self._ui_contains(self.server, '14/20')
        finally:
            emu.disconnect()
        try:
            name = 'server_midi_number_display' + ('' if shown else '_FAILED')
            self._save_screenshot(self.server, name)
            asserts.assert_true(
                shown, 'Server UI never showed "14/20" after MIDI song select.')
        finally:
            self._close_app(self.server)


if __name__ == '__main__':
    test_runner.main()