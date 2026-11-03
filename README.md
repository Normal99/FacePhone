# Faces

A phone and messaging app for people who can't read. Faces, icons and sound come first, with names and short labels as a fallback for anyone who can use them. It's styled after Samsung's One UI, with light and dark modes, but isn't made by or affiliated with Samsung.

It was built for one grandmother on a Samsung Galaxy A50 running Android 11, and should work on other phones running Android 10 or newer.

## What it does

- **Contacts:** people come automatically from the phone's contacts, with their photos, and stay in step when those change. Starred contacts come first.
- **Faces:** tap a person's photo to hear their name in their own voice if they've recorded it, then call or message them.
- **Voice calling:** tap the microphone and say who to call. The app shows and says who it found, and only calls after a tap.
- **Calls:** calls start from a face and run in the phone's own phone app (Samsung's on a Samsung), which is the most reliable call screen. Her people's photos show there from the phone's contacts. A history screen lists recent calls as faces, with arrows for the direction and red for missed. Missed calls from her people put a red dot on the photo. WhatsApp calls don't appear in the history, because WhatsApp keeps its own.
- **Messages:** voice notes and pictures over MMS, so relatives don't need to install anything. New messages make the photo pulse, and one tap plays them.
- **Texts:** texts from known contacts are read aloud when tapped, and shown on screen too. Texts from anyone else stay silent and are kept in the family settings.
- **WhatsApp:** for contacts who use it, buttons for calls, video calls, voice notes and opening the chat, plus a pulsing face for new messages.
- **Help:** hold the help tile for 3 seconds to text family her location and call them in order. An optional tile calls an emergency number.
- **Battery:** a spoken reminder when the battery is low, and a text to a family member.

## Voice calling and AI

Speech recognition is done by the phone's recognizer, normally Google's. The app then tries to match what it heard on the phone itself, which handles plain names, accents, Turkish and other grammatical endings, and nicknames from the contacts app. Saying a word like "mesaj" offers a message instead of a call.

When that isn't sure, and if you've added an [OpenRouter](https://openrouter.ai) API key in the family settings, the app asks an AI model to work out unusual sentences or badly misheard names. Family words like "oğlum" work if the person is saved under that name, or you've listed it under their "Other names". The model's answer can only point at someone in the contact list, and she still has to confirm. Without a key, the phone's own matching still works.

Without internet, speech recognition depends on the offline languages your phone's Google app offers. The tested phone's list has no Turkish, so offline it can't understand her, and she gets all her people's faces to tap instead.

An optional setting lets the model hear her voice directly instead of reading the recognizer's guess. OpenRouter requires at least $0.50 of balance for audio. If the request fails, the phone's recognizer takes over and she's asked again.

**Privacy and cost:** when the model is asked, what she said (or a recording of it) and her contact names go to OpenRouter and the model's provider. The default is `google/gemini-3.5-flash-lite`, at about $0.0003 per request, with `google/gemini-2.5-flash-lite` as an automatic backup. You can pick any other OpenRouter model in the settings.

## WhatsApp's limits

WhatsApp has no official way for another app to read its contact list, or to send or play its messages. This project doesn't use unofficial connection libraries, because they can get a number banned.

- People come from the phone's contacts. WhatsApp marks which of them use it, and they get its buttons.
- Calls follow the home country code in the family settings. Numbers in that country are regular calls. Foreign numbers, and anyone only in WhatsApp, are called over WhatsApp, and never by a regular call. Without WhatsApp, they have no call button. Save foreign numbers with their + country code, because a number without one counts as local. The help and emergency buttons and the family's keypad always use regular calls.
- Someone with no phone number can't be called, so they stay off her screen. The family settings list them, so you can add a number.
- Sending a voice note opens WhatsApp with the note ready, and the person taps its send arrow once.
- Received voice notes play in WhatsApp's own chat screen.
- Calls use undocumented WhatsApp contact entries and fall back to opening the chat.
- Profile pictures come from WhatsApp's notifications, so one appears only after that person has messaged, and only if their privacy settings allow it. It's used when the contact has no photo. These images are small, and a photo in the contacts app takes priority.

## Build and install

You need JDK 17 and the Android SDK (platform 34). Set `sdk.dir` in `local.properties`, and change `org.gradle.java.home` in `gradle.properties` to your JDK 17.

```bash
./gradlew testDebugUnitTest assembleRelease
```

Turn on USB debugging on the phone, connect it, and run:

```bash
tools/provision.sh
```

This makes the app the default SMS app and keeps the phone's own phone app as the default for calls. To undo it, choose the original apps again in Android's default apps settings. With more than one phone connected, set `ANDROID_SERIAL` first.

Release builds are signed with the debug key in `~/.config/.android/debug.keystore` (or `~/.android/`). Back that file up: phones only accept updates signed with the same key.

### Updates from GitHub

The family settings have an **Update now** button. It downloads the newest release from this project's GitHub releases and installs it. Android asks once to confirm. To publish a release:

```bash
tools/release.sh "what changed"
```

This builds, tests and uploads the APK as release `v<versionCode>`. The repository is `Normal99/grandma-phone` unless you pass `UPDATE_REPO=owner/name` (it must match the `updateRepo` the installed app was built with).

## Setting it up for someone

Add people in the phone's contacts app, with a clear photo. They appear by themselves.

Open the family settings by tapping the top-right corner of the faces screen 7 times quickly. With no contacts yet, the grey plus tile also opens them. There you can:

- Finish the setup list, which also checks that the phone isn't muted.
- For each person: record voice clips, list other names she uses, choose who gets help calls, or hide them.
- Set the country code, speech language, emergency and family numbers, and the optional API key.

Also check that:

- Mobile data is on. MMS needs it, even on Wi-Fi.
- Location is on, so help texts can include it.
- There's a speech voice for the chosen language, in Android's text-to-speech settings.

Her screens and speech follow the language in the family settings, like `tr-TR`, even if the phone itself uses another language. Use "Test the speech voice" there to check a voice is installed for it. English and Turkish are included. To add another language, add a `values-xx/strings.xml` with words short enough to fit under the buttons.

## What's been tested

Tested on a Samsung A50 with no SIM:

- Unit tests cover the MMS format, number matching, message routing, contact import, voice matching and checking the model's answers, including random malformed MMS data.
- The app's screens, recording, and the hold-to-confirm tiles.
- Contacts appearing automatically from Samsung's contacts app.
- Reaching OpenRouter from the phone with a fake key, which was correctly rejected.
- 15 hard Turkish text requests against the default model from a computer, using typed phrases: all correct, in under a second each.
- The AI hearing audio, from a computer, using 7 robotic synthetic Turkish clips: 5 correct, in about 1.2 seconds each. Real voices may do better or worse.
- The AI hearing a real voice on the phone, close up and from arm's length, in a few informal tries by one adult. Recordings are turned up first, because this phone's mic is very quiet.
- Incoming, outgoing, missed, declined and unknown calls, and the help sequence, using the fake line below.

**Not yet tested:**

- On a real network: real calls, sending and receiving SMS and MMS, and whether voice notes play on the other person's phone. MMS depends on the carrier, so test it with the actual SIM first.
- With WhatsApp installed: its buttons, notifications, contact marking and profile pictures.
- With her own voice: how well the AI and the phone's recognizer understand her, and how it copes with background noise like a TV.
- Falling back to the phone's recognizer when a request to the AI fails.
- WhatsApp calling for foreign numbers and for people only in WhatsApp, which needs WhatsApp installed.

### Testing calls without a SIM

Debug builds include a pretend phone line that sends calls through Android's real calling system:

```bash
adb shell am broadcast -n com.family.grandmaphone/.debug.FakeCallControl -a register
adb shell telecom set-phone-account-enabled com.family.grandmaphone/com.family.grandmaphone.debug.FakeCallProvider fake 0
adb shell am broadcast -n com.family.grandmaphone/.debug.FakeCallControl -a ring --es number 07700900123
```

Other actions are `answer`, `miss`, `far_end_hangup` and `unregister`. Release builds don't contain this.

## Licence

Copyright (C) 2026 the Faces contributors.

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, version 3 of the License. It comes with no warranty. See [LICENSE](LICENSE).

The help and emergency features can fail, for example with no signal or a flat battery. Don't rely on them as the only way to get help.

WhatsApp and One UI are trademarks of their owners. This project isn't affiliated with them and ships none of their logos or code.
