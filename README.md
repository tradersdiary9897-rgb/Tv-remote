# Magic Gyro Remote

Minimal native Android Bluetooth HID air-mouse prototype. The phone advertises as a standard Bluetooth mouse and sends relative X/Y reports derived from the phone gyroscope.

## Intended setup
1. Install the APK on an Android phone that has a gyroscope and Bluetooth.
2. Turn on Bluetooth.
3. On the Android TV, open Bluetooth/accessories and pair with **Magic Gyro Remote**.
4. Open the app and move the phone.

No hotspot, internet, ADB, or TV companion app is used.

## Important compatibility note
The TV must accept Bluetooth HID mouse devices. The app does not use Android TV Remote v2; it is a Bluetooth HID mouse. The pointer behavior therefore depends on the TV firmware exposing a mouse cursor.

## Build
Open in Android Studio or run `gradle assembleDebug`. A GitHub Actions workflow is included under `.github/workflows/build.yml` to produce the APK as an artifact.
