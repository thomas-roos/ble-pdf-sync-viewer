# Multi-Device BLE Sync Tests

End-to-end tests of the BLE sync between real app installations on two (or
more) Android devices, built on [Mobly](https://github.com/google/mobly) -
Google's multi-device test framework. One device is driven as the server
(advertiser), another as a client (scanner), and the tests assert what
actually arrives over the air:

- client receives the advertised file name and page
- page turns propagate
- long file names are truncated to the 13 bytes that fit the advertisement
- a client with a different group code ignores the broadcast
- a client with the matching group code receives it
- UI end-to-end: the real app is launched in client mode (via the
  `autostart` intent extra) and must show the broadcast number fullscreen;
  screenshots of the client are saved to `SCREENSHOT_DIR` (or the Mobly log
  dir) and uploaded as a `screenshots` artifact in CI

<img src="../docs/screenshots/test-number-display.png" width="280"
  alt="Client tablet showing 7/3 fullscreen during the UI test">

*Captured by the UI test: the client device (no files selected) displaying
`7/3` after the server broadcast `7_3.pdf`.*

## How it works

- `android-app/app/src/androidTest/.../BleSyncSnippet.kt` is a
  [Mobly snippet](https://github.com/google/mobly-snippet-lib) packaged as
  the instrumentation APK. It exposes the app's real `BluetoothController`
  (`setGroupCode`, `startClient`, `broadcast`, `getReceived`) as RPCs.
- `ble_sync_test.py` runs on the host, picks up every device visible to
  `adb` (`testbed.yml`), loads the snippet on each and orchestrates
  server/client roles.

## Run locally (physical devices)

Connect at least two devices with USB debugging enabled, then:

```bash
pip3 install -r requirements.txt
./run_tests.sh            # builds, installs and runs
./run_tests.sh --skip-build
```

Bluetooth must be on (the tests try to enable it via adb first). Logs and a
test summary land in `/tmp/logs/mobly/`.

## Run in CI (emulators)

`.github/workflows/multi-device-test.yml` runs the same suite on every push:
two API-34 emulators are booted with `-feature BluetoothEmulation`, which
connects both to the emulator's shared virtual radio (netsim/rootcanal), so
BLE advertising and scanning genuinely travel between the two Android
Bluetooth stacks. Mobly logs are uploaded as a workflow artifact.

This covers the full app-to-app protocol; only real RF behavior (range,
interference, coexistence) still needs the physical-device run above.
