#!/usr/bin/env bash
# Installs the app on a phone connected over USB and makes it the default SMS app.
# Calls go through the phone's own phone app, which is set back as the default here.
# Pass the phone's serial as ANDROID_SERIAL when more than one phone is connected.
# Undo: in Android Settings > Apps > Default apps, choose the phone's original apps again.
set -euo pipefail

PKG=com.family.grandmaphone
APK="${1:-$(dirname "$0")/../app/build/outputs/apk/release/app-release.apk}"

adb install -r "$APK"

for permission in \
    CALL_PHONE READ_PHONE_STATE READ_CALL_LOG SEND_SMS RECEIVE_SMS READ_SMS RECEIVE_MMS RECEIVE_WAP_PUSH \
    RECORD_AUDIO READ_CONTACTS WRITE_CONTACTS ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
    adb shell pm grant "$PKG" "android.permission.$permission"
done

# Samsung's phone app on Samsung phones, otherwise Google's.
for dialer in com.samsung.android.dialer com.google.android.dialer; do
    if adb shell pm path "$dialer" >/dev/null 2>&1; then
        adb shell cmd role add-role-holder android.app.role.DIALER "$dialer"
        break
    fi
done
adb shell cmd role add-role-holder android.app.role.SMS "$PKG"
adb shell cmd notification allow_listener "$PKG/$PKG.whatsapp.WhatsAppListener"
adb shell dumpsys deviceidle whitelist "+$PKG"
# Lets the update button install new versions from GitHub.
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow

echo "--- Default apps now:"
adb shell dumpsys role | grep -A1 -E 'name=android.app.role.(DIALER|SMS)$' | grep -E 'name=|holders='
