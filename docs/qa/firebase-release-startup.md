# Firebase release startup regression

The first Firebase-enabled release crashed on Pixel 7 at startup inside
`AppStartTrace.onAppEnteredForeground`. R8 renamed `TraceMetric.name_` and
removed `AndroidApplicationInfo.packageName_`, but Protobuf Lite resolves
generated fields by their original names. Debug builds and ordinary JVM
tests do not exercise this minified path.

`composeApp/proguard-rules.pro` now preserves generated Protobuf Lite fields
using the [upstream field rule](https://github.com/protocolbuffers/protobuf/blob/main/java/lite/proguard.pgcfg).
Firebase Performance collection stays enabled.

After building a signed production APK, verify its actual DEX, supplying the
mapping from that same build and the Android SDK's `apkanalyzer.bat`:

```powershell
py -3 tools/verify-firebase-protobuf-release.py `
  composeApp/build/outputs/mapping/release/mapping.txt `
  composeApp/build/outputs/apk/release/volty-0.7.69-release.apk `
  C:/Users/sodovaya/AppData/Local/Android/Sdk/cmdline-tools/latest/bin/apkanalyzer.bat
```

The check fails against the original crashing APK and passes against the
fixed APK. It inspects DEX because R8 can omit unchanged fields from mapping.
It covers the two observed failures; it is not a complete Firebase runtime test.

The signed fix was installed over the existing app on Pixel 7 (Android 17).
Three subsequent launches kept the process alive and added no crash-buffer
entries. The final version-only rebuild to 0.7.69/code 92 was not installed
on the phone, at the user's request.
