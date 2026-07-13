# PDF Sync Viewer

Android app (`android-app/`) that synchronizes PDF/JPG sheet viewing across
devices via BLE advertisements; the server can also be controlled through
RTP MIDI (AppleMIDI). See README.md for features and usage.

## Testing policy

**Every new feature must come with a test.** Pick the right layer:

- **Device-spanning behavior** (BLE sync, MIDI control, UI following a
  broadcast): add a test to the Mobly multi-device suite in
  `multi-device-tests/ble_sync_test.py`. It drives two real devices (or two
  emulators with virtual Bluetooth) through the app's actual code — see
  `multi-device-tests/README.md` for how it works.
- **Pure logic** (payload encoding, name matching, margin cropping): add a
  plain JUnit test under `android-app/app/src/test/`.

If a feature needs app cooperation to be testable, prefer small explicit
hooks (like the `autostart` intent extra in `MainActivity`) over UI
coordinate tapping.

## Running the tests

- Local, 2+ devices via adb (physical or emulators):
  `multi-device-tests/run_tests.sh` (add `--skip-build` to reuse built APKs)
- CI runs the suite on every push via `.github/workflows/multi-device-test.yml`
  on two API-34 emulators booted with `-feature BluetoothEmulation`;
  screenshots and Mobly logs are uploaded as artifacts, linked in the job
  summary.
- Test RPCs into the app come from the Mobly snippet
  `android-app/app/src/androidTest/.../BleSyncSnippet.kt` — extend it when a
  test needs new access to app internals.

## Gotchas learned the hard way

- BLE advertisement payload is 20 bytes: 4-byte group tag + counter +
  2-byte page + max 13 bytes of file name (truncated).
- Fresh emulators show a first-run "Viewing full screen" overlay that hides
  the app from `uiautomator dump`; the test setup suppresses it via
  `settings put secure immersive_mode_confirmations confirmed`.
- The AppleMIDI/RTP MIDI ports (UDP 5004/5005) are reached on emulators via
  `adb emu redir add udp:...`; adb only forwards TCP.
- Mobly runs test methods in alphabetical order, and the snippet shares the
  app's process — never `am force-stop` the app mid-suite; finish activities
  with BACK instead.
