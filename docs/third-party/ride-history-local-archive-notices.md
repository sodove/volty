# Ride history local archive and font notice inventory

Checked 2026-10-13 in the `nearby-foundations` worktree. This is a scoped inventory of the two named local archives and files under `composeApp/src/commonMain/composeResources`. It records observed contents and separate upstream license evidence; it does not conclude that a local binary matches an upstream revision or make a blanket compatibility determination.

## Local archives

| File | SHA-256 | Identity and version information observed in the archive | Embedded license/notice files |
|---|---|---|---|
| `composeApp/libs/audioswitch-039a35aefab7747c557242fa216c9ea11743b604.aar` | `C8240221DAA9A96D4EA01A4DC6F6F6B10B4903D2A71F9B57F838BDFEB6C3FCBC` | Five AAR entries: `AndroidManifest.xml`, `classes.jar`, `R.txt`, `proguard.txt`, and `META-INF/com/android/build/gradle/aar-metadata.properties`. The manifest package is `com.twilio.audioswitch`, with `minSdkVersion=16`; it declares `BLUETOOTH` (max SDK 30) and `MODIFY_AUDIO_SETTINGS`. The nested JAR has 76 entries, including 66 class files under `com/twilio/`. AAR metadata reports format and metadata version `1.0`, `minCompileSdk=1`, and minimum Android Gradle Plugin `1.0.0`. No library version is declared in the archive. | None found in the AAR or its nested `classes.jar` (`LICENSE*`, `NOTICE*`, `COPYING*`). |
| `composeApp/libs/brouter-1.7.10-all.jar` | `6FBF6B07341A1AC69E9B07B11CA89A75D92691A92D7C73B90CD5F816F1827F52` | 122 entries, including 115 class files under `btools/codec` (14), `btools/expressions` (10), `btools/mapaccess` (24), `btools/router` (39), and `btools/util` (28). Its only non-class file is `META-INF/MANIFEST.MF`, which reports `Manifest-Version: 1.0` and `Created-By: 21.0.1 (Azul Systems, Inc.)`; it declares no implementation version, title, or vendor. The filename contains `1.7.10`, but the archive itself does not identify that version. | None found (`LICENSE*`, `NOTICE*`, `COPYING*`). |

### Separate upstream source evidence

- The local AudioSwitch source checkout `C:\Users\sodovaya\.codex\research\audioswitch-license-20261013` has pinned commit `039a35aefab7747c557242fa216c9ea11743b604` (subject: “Silently swallow exception if audioManager throws when unregistering communication device listeners (#9)”). At that commit, `LICENSE.txt` is Apache License 2.0 and attributes copyright to Twilio, Inc. (2020). The copy at [`licenses/AudioSwitch-Apache-2.0.txt`](licenses/AudioSwitch-Apache-2.0.txt) matches that file after newline normalization. This establishes the license text for the inspected source commit; the AAR hash and package do not establish that the AAR was built from that commit.
- The upstream [BRouter `v1.7.10` LICENSE](https://github.com/abrensch/brouter/blob/v1.7.10/LICENSE) declares MIT and copyright 2019 BRouter contributors. The local JAR's `btools.*` packages identify its code family, but the JAR has no embedded source revision or implementation version; its filename alone does not establish a match to that tag.

The existing [source/dependency evidence](vescape-history.md) and [resolved runtime POM inventory](ride-history-runtime-licenses.md) contain the wider source and POM context. This note checks only the two named local archives and does not audit their complete build provenance or bundled/transitive components.

## Common Compose resources

The recursive file inventory under `composeApp/src/commonMain/composeResources` contains only:

- `values/strings.xml`
- `values-ru/strings.xml`

No font files or adjacent font license/notice files were found in that directory, so there is no bundled common Compose font to identify from this scope.

## Read-only Git provenance follow-up, 2026-10-15

The two SHA-256 values and archive contents above were rechecked unchanged.
AudioSwitch's current Git blob (`152dfc89…`) is the blob added by commit
`4d9ca242` on 2026-08-22 (95318 bytes). That change adds the local AAR dependency
and excludes LiveKit's Maven AudioSwitch dependency. BRouter's current blob
(`4623226f…`) appears in sibling commits `e235f3de` (2026-09-01) and `bb30b627`
(2026-09-03), both sharing the same parent and recording the same 307438-byte
JAR. No reproducible archive-build record was found in the narrowly searched
repository history/build configuration. Neither archive contains native libraries.

This strengthens checked-in binary identity, not binary-to-source correspondence.
No archive was rebuilt or replaced; the earlier source-provenance limitation is
retained. The scoped Luna xhigh inventory performed no edits, Gradle or network
research.
