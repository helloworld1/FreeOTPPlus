#!/bin/sh

jarsigner -verbose -digestalg SHA1 -sigalg MD5withRSA -keystore ~/Documents/keys/liberty-android-release.keystore "app/build/outputs/apk/release/app-release-unsigned.apk" liberty-android-key
zipalign -v 4 "app/build/outputs/apk/release/app-release-unsigned.apk" "app/build/outputs/apk/release/FreeOTPPlus-release.apk"
