# History metric drawer: native acceptance

The drawer and ordered selection list render production components. The debug-only
`HistoryUiProbeActivity` hosts them with `DefaultHistoryComponent` and a synthetic,
read-only repository: two equally named batteries, each with three scalar metrics
and 1,000 sparse cell keys. It does not access user recordings, BLE or location.
The fixture is a rendering/input check, not activation of the new history screens.

## Observed on Android

Emulator: Volty_Nearby_UI_API34, API 34, 1080 × 2220. Debug APK 0.7.27.

- Portrait: expanding the second battery and searching `Pack 2 1001` exposes
  Cell 1001 from Pack · 2 above the open keyboard. Sparse cell indices retain
  their recorded numbers; similarly named sources remain distinct.
- Editing within that query inserts text at the caret (`Paxck 2 1001`). Deleting
  the insertion restores the query and matching row without an asynchronous
  editor reset.
- Selecting Cell 1001, closing the drawer, adding Voltage from Pack · 1 and
  moving Voltage upward updates the ordered source-aware selection list.
- Landscape initially hid the search field behind the keyboard. A first change
  made search visible but left the result below the useful area. The verified
  compact layout keeps search and the matching cell visible together, hides
  decorative headers and puts source identity directly under the metric title.
  Search and results remain in one scrollable lazy list.

Evidence is archived locally in the ignored execution directory:
`h408-metrics-keyboard.png`, `h408-landscape-keyboard-before.png`, and
`h410-landscape-keyboard.png`. The final landscape image was taken after installing
the successful h410 debug build. Original emulator rotation settings were restored.

## Limits and remaining work

The fixture recreates its owner on Activity rotation. It does not prove Activity
state restoration, production navigation, actual recording reads, frame timing,
large-font accessibility or phone acceptance. Pure tests cover search, stable keys,
source identity, selected unavailable metrics and reorder policy; they do not
claim to test Compose rendering.

Persistent presets, the historical map/chart screens, root navigation integration
and recorder activation remain separate unfinished parts of the approved plan.
