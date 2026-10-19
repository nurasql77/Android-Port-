# OpenBGI Android

Android port work for OpenBGI. Upstream: https://github.com/Cytlan/openbgi

This repository starts the Android application layer. The OpenBGI C engine is not copied here yet; it remains under its upstream GPL-2.0-only repository.

## Port plan

1. Add OpenBGI source as the native engine source/submodule-equivalent.
2. Implement `src/os/android/` against OpenBGI's `inc/bgi/os.h`.
3. Use Android NDK for arm64-v8a.
4. Implement rendering, touch/input, audio, filesystem, fonts and lifecycle.
5. First test with a legally obtained BGI game directory.

The upstream engine already has POSIX and WebAssembly backends, so those are the references for the Android backend. Android is not currently an upstream build target.
