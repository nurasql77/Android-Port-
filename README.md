# OpenBGI Android (experimental)

An Android wrapper for the upstream OpenBGI engine: https://github.com/Cytlan/openbgi

## Current implementation

The APK bundles OpenBGI's existing **WebAssembly** build and runs it in Android WebView. A small loopback HTTP server serves the bundled HTML/JavaScript/WebAssembly files; a loopback WebSocket server exposes the game folder selected through Android's Storage Access Framework. The file protocol supports the three read-only operations used by OpenBGI's WASM backend (STAT, READ and LIST). The app does not package commercial game data or copy the selected game folder.

**This is not yet a native Android/NDK backend.** The C engine is compiled by Emscripten and executed by WebView. Performance, keyboard/input behaviour and game compatibility may be limited by OpenBGI's experimental WASM backend. Upstream's own WASM documentation describes it as a demonstration target and warns that the early-games title screen is the initial aim; do not assume that every game runs or is completable.

## Build

GitHub Actions builds the APK from the openbgi-android branch:

1. Fetch the upstream OpenBGI source at commit 40b144a03e15f159cadc957387e6f94bce8de65f.
2. Compile the WASM engine with Emscripten.
3. Bundle openbgi.html, openbgi.js, openbgi.wasm and the upstream GPL-2.0 license notice into the app.
4. Build a debug APK and upload it as the workflow artifact openbgi-android-debug.

Open the repository's Actions → Android APK workflow, then download its openbgi-android-debug artifact. The artifact is a ZIP containing the APK.

## Using the APK

1. Install the APK.
2. Press Папка игры and select a folder using Android's folder picker.
3. Select a compatible BGI game folder. Depending on the game and upstream support, resources may need to be extracted into the layout expected by OpenBGI. The app does not supply or extract copyrighted game data.
4. Tap the canvas to give it focus and try touch input. The engine and its WASM backend are experimental.

Use game data that you are legally entitled to access. Compatibility is determined by the upstream engine and its per-game implementation status, not by the Android wrapper.

## Upstream and license

OpenBGI source code is GPL-2.0-only. The build includes its license notice and pins the upstream source commit for repeatable builds. See upstream's README and docs/wasm.md for the current engine status, game compatibility notes, and asset requirements.

- Upstream repository: https://github.com/Cytlan/openbgi
- Upstream WebAssembly documentation: https://github.com/Cytlan/openbgi/blob/main/docs/wasm.md
