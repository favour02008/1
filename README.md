# LG Cast

Android app for casting supported video media from a phone to LG/DLNA TVs on the same Wi-Fi network.

## Features
- SSDP discovery of DLNA/UPnP MediaRenderer devices.
- TV selection.
- Cast a direct video URL.
- Choose a video from the phone and serve it locally over Wi-Fi.
- Play, pause and stop controls.
- Codemagic-ready Android APK build.

## Important limitation
This app performs media casting, not full Android screen mirroring. Full screen mirroring to many 2016 LG TVs normally uses Miracast/Wi-Fi Display, which is controlled by Android and TV system components rather than a normal media-cast app.

## Build
Use the android-debug workflow in Codemagic. The APK is generated at:
app/build/outputs/apk/debug/app-debug.apk

For testing, connect the phone and TV to the same Wi-Fi network and enable SmartShare/DLNA features on the TV if required.
