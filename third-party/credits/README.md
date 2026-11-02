# Attribution input provenance

These are local offline texts for the credits screen, alongside existing notices
in the source tree. Registry entries are ordinary alphabetical projects; no
project receives special placement.

| Material | Text/evidence |
|---|---|
| loeuc-core | MIT notice retained in root `THIRD_PARTY_NOTICES.md`, Nikita Vasilevskiy; revisions ae10acc6e6de20b459928925531f2cbfd10bd56a / 83f2d99 |
| Vescape | GPL v3 from `presentation/history/ported/LICENSE`; Kacper Kozak, upstream 2527c346bbdb25d4e018fdd68e916621e20fac27; ported README and source headers remain authoritative |
| RNNoise code | Vendored `rnnoise/COPYING` and `VERSION`, xiph/rnnoise 70f1d256acd4b34a572f999a05c87bf00b67730d; upstream copyright preserved |
| AudioSwitch | Existing `docs/third-party/licenses/AudioSwitch-Apache-2.0.txt`; source reference 039a35aefab7747c557242fa216c9ea11743b604; local AAR hash tracked |
| BRouter | https://github.com/abrensch/brouter/blob/v1.7.10/LICENSE; local JAR hash tracked |
| Noto Sans | Existing glyph NOTICE plus https://github.com/notofonts/noto-fonts/blob/main/LICENSE (OFL 1.1) |
| OpenFreeMap styles | https://github.com/hyperknot/openfreemap-styles/blob/72e1480dfc92858d334647037988bd2591fdb021/LICENSE.md plus existing style NOTICE |
| OpenMapTiles style | https://github.com/openmaptiles/osm-bright-gl-style/blob/master/LICENSE.md; BSD code/design attribution retained |
| CC BY 4.0 text | https://github.com/spdx/license-list-data/blob/main/text/CC-BY-4.0.txt; canonical https://creativecommons.org/licenses/by/4.0/legalcode |
| Concentus | https://github.com/lostromb/concentus/blob/master/LICENSE (copyright list and BSD conditions preserved) |
| Bouncy Castle | `org/bouncycastle/LICENSE.java` inside cached **1.84 sources JAR**, not the unrelated Java runtime `legal/LICENSE` bundled in generated Javadoc |
| Google Android SDK terms | `android-sdk-license` element of https://dl.google.com/android/repository/repository2-3.xml; POM terms reference https://developer.android.com/studio/terms.html |
| JAIN SIP | https://github.com/usnistgov/jsip/blob/master/LICENSE and `licenses/JSIP Spec License.pdf` (text extracted with pypdf); API Sun/BEA copyright and NIST implementation terms are separate |
| WebRTC | Native source pinned in `notices/WebRTC.txt`; wrapper MIT text from https://github.com/webrtc-sdk/android/blob/main/LICENSE; both terms included |
| CameraX / libyuv | CameraX 1.5.3 camera-core POM declares Apache-2.0 plus libyuv BSD; the libyuv text is from https://chromium.googlesource.com/libyuv/libyuv/+/refs/heads/main/LICENSE (retrieved 2026-10-31). Its README.chromium identifies BSD-3-Clause. The complete text is preserved in `licenses/libyuv-BSD.txt`. |

Other modules use AboutLibraries/POM license declarations and provided SPDX texts.
The checked-in normalized catalog and input hashes make later changes visible;
they do not prove binary/source equivalence for the two pre-existing local
archives, so their UI versions are deliberately omitted.

## Remaining upstream questions

The RNNoise default generated model archive (SHA-256
`0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37`)
contains no separate license file. The RNNoise project entry reproduces the
vendored code's COPYING; it is not a new assertion of model-specific rights.
Upstream requests for clarification exist at
https://github.com/xiph/rnnoise/issues/284 and
https://github.com/xiph/rnnoise/issues/286.

The app's packaged native libraries were traced to AndroidX Graphics Path,
AndroidX DataStore, WebRTC, MapLibre, Valhalla Mobile, and vendored RNNoise.
Maven-level licenses do not exhaust notices for all code linked inside native
libraries. A full recursive native/source license audit remains separate work;
this inventory must not be described as such an audit.
