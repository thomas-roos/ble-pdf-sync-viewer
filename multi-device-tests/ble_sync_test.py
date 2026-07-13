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
import time

from mobly import asserts
from mobly import base_test
from mobly import test_runner
from mobly.controllers import android_device

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
        for ad in self.ads:
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

    def test_ui_number_display_follows_server(self):
        """End-to-end through the real UI: the client app is launched in
        client mode (no files selected, so it acts as a number display) and
        must show the number of the file the server broadcasts."""
        self.client.adb.shell([
            'am', 'start', '-W', '-n', APP_PACKAGE + '/.MainActivity',
            '--es', 'autostart', 'client'])
        time.sleep(3)  # activity animation + BLE scan start
        self._save_screenshot(self.client, 'client_waiting')

        self.server.ble.broadcast('7_3.pdf', 0)

        # 7_3.pdf is not on the device, so the app shows "7/3" fullscreen
        deadline = time.time() + SYNC_TIMEOUT_S
        while time.time() < deadline:
            self.client.adb.shell(
                ['uiautomator', 'dump', '/data/local/tmp/ui.xml'])
            dump = self.client.adb.shell(['cat', '/data/local/tmp/ui.xml'])
            if b'"7/3"' in dump:
                break
            time.sleep(2)
        else:
            self._save_screenshot(self.client, 'client_number_display_FAILED')
            asserts.fail('Client UI never showed the number display "7/3". '
                         'server BLE events: %s' %
                         self.server.ble.getBleEvents())
        self._save_screenshot(self.client, 'client_number_display')
        self.client.adb.shell(['input', 'keyevent', 'KEYCODE_HOME'])


if __name__ == '__main__':
    test_runner.main()