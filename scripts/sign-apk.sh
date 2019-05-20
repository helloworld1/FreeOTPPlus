#!/bin/sh

APK_DIR="app/build/outputs/apk/release"
UNSIGNED_APK_FILE_NAME="app-release-unsigned.apk"
SIGNED_APK_FILE_NAME="FreeOtpPlus-release.apk"

jarsigner -verbose -digestalg SHA1 -sigalg MD5withRSA -keystore ~/Documents/keys/liberty-android-release.keystore "$APK_DIR/$UNSIGNED_APK_FILE_NAME" liberty-android-key
zipalign -v 4 "$APK_DIR/$UNSIGNED_APK_FILE_NAME" "$APK_DIR/$SIGNED_APK_FILE_NAME"
