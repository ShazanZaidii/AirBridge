(writing this at 2am on August 14th)

AirBridge is a cross platform tool that allows you to:
- Discover AirBridge devices nearby (should be on same network, Supported Devices - Android, MacOS, Windows)
- Once connected, have shared clipboard,
- Send media in one click like AirDrop,
- Control your mac from Android or Android from Mac 


Also, NO DATA IS COLLECTED. The android source code is opensource and you are free to inspect. There is literally no server/ DB connected. 


The Windows version is here now, in the `windows/` folder. It sat on my drive in a different city for a while, which is why it took so long. The build will show up in the releases tab once I publish it :) 

Also yes, the Linux support is coming soon. But i cant promise about the iOS support as the app needs several "Trust me bro" permissions.. haha, such as - Accessibility to control device remotely, Clipboard access to actually share clipboard data, Draw over other apps and all. But yeah i will give it a shot.

The android and macOS release can be found in the releases tab.

And if you are asking for a tutorial, please try to discover the app yourself. 😤 (I will attach once i have the windows and linux release with me..) 


## What is in this repo

- `android/` the Android app
- `macOS/` the Mac app
- `windows/` the Windows app
- `linux/` and `iOS/` not started yet
- `docs/` the protocol every app speaks, the AirWeb page description and the shared test files
