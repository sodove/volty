# Android 16 KiB page size: RNNoise release correction

Google Play identified `base/lib/arm64-v8a/libvolty_rnnoise.so` in the previous
release as incompatible with 16 KiB pages. Direct inspection of the 0.7.62 AAB
confirmed that its LOAD segments used 0x1000 alignment, with one segment also
failing file-offset/virtual-address congruence modulo 16 KiB. The other five
arm64 shared libraries passed the ELF check. BundleConfig already requested
`PAGE_ALIGNMENT_16K`, so changing ZIP packaging alone could not fix this.

Volty uses NDK 27.0.12077973. The RNNoise CMake target now explicitly links with
`-Wl,-z,max-page-size=16384` and `-Wl,-z,common-page-size=16384`, matching
[Android's NDK r27 guidance](https://developer.android.com/guide/practices/page-sizes).
The native sources use the compiled-in model, contain no active PAGE_SIZE
dependency, and have no active mmap call; the mmap example is inside `#if 0`.

The rebuilt arm64 library's LOAD alignment is 0x4000. A separate native smoke
probe on the existing Android API 34 x86_64 emulator loaded the rebuilt library,
created a state, processed 100 silent frames with finite outputs and a valid
speech probability, and destroyed the state. That emulator uses 4096-byte pages;
this verifies continued 4 KiB operation, not execution on a 16 KiB device.
There is no installed 16 KiB Android image on this workstation.

## Release verification

Verify ELF segments in the actual archive, including prebuilt dependencies:

```powershell
py -3.12 tools/verify-android-native-alignment.py path/to/release.aab
py -3.12 tools/verify-android-native-alignment.py path/to/release.apk
```

The check must fail on the old 0.7.62 AAB and pass on the rebuilt archive.
Separately check the produced APK's ZIP alignment with SDK Build-Tools 35+:

```powershell
& "$env:ANDROID_HOME/build-tools/36.0.0/zipalign.exe" -c -P 16 4 path/to/release.apk
```

For an AAB, `bundletool dump config --bundle=path/to/release.aab` must report
`PAGE_ALIGNMENT_16K`. ELF alignment, ZIP packaging and native runtime behavior
are separate checks; one does not substitute for another.

## Verified release: 0.7.63 / versionCode 86

`bundleRelease` and `assembleRelease` completed. All six arm64 libraries pass
the ELF check in both the final AAB and APK; APK `zipalign -c -P 16 4` passes,
and the AAB's BundleConfig requests `PAGE_ALIGNMENT_16K`. The AAB signature
verifies with jarsigner; the APK signature verifies with apksigner. The release
still omits the unused media-projection permission/service and debug BRouter
payload. Use 0.7.63 instead of the earlier 0.7.62 artifact.

AAB: `.artifacts/play-console/volty-0.7.63-release.aab`.
APK: `.artifacts/play-console/volty-0.7.63-release.apk`.
AAB SHA-256: `36b79dd5ea77174d9c5be3faa813add1ce10804abce2e96e8170ef3d51d351a8`.
