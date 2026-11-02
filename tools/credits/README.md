# Offline credits catalog

AboutLibraries **13.2.1** is a build-only Gradle plugin. Volty renders its own
Compose screen; no AboutLibraries runtime/UI dependency is shipped.

Refresh from the repository root (JDK 21, Python 3.10+):

```powershell
.\gradlew.bat :composeApp:exportLibraryDefinitions :composeApp:writeCreditsDependencyGraph --max-workers=4
py -3 tools/credits/generate.py
py -3 -m unittest discover -s tools/credits -p test_generate.py
.\gradlew.bat :composeApp:verifyCreditsCatalog --max-workers=4
```

Keep the repository JVM setting `-XX:ActiveProcessorCount=4` when overriding JVM
arguments. No Python or network access is needed to package the checked-in catalog.

The exporter can include compilation metadata. The normalizer intersects it with
the actual resolved `releaseRuntimeClasspath` artifact graph, checks versions,
and fails on missing metadata, license references or full texts. It does not
invent a license when a POM contains only a URL.

`third-party/credits/registry.json` supplements Maven metadata:

- `projects`: directly copied sources, native code, resources and local archives.
  An `artifact` links a project to an exact runtime coordinate; without one the
  project represents material copied into the app. Unknown versions are omitted.
- `licenses` and `licenseOverrides`: IDs mapped to a local `path` and display
  `name`; overrides complete otherwise empty AboutLibraries texts.
- `libraryOverrides`: optional explicit `licenseIds` and `noticePath` per Maven
  module. This preserves multiple terms, for example WebRTC wrapper/native code.
- `inputs`: original notice/source files tracked in addition to copied texts.
- `binaryInputs`: local archives whose raw SHA-256 must match the inventory.

All paths are repository-relative. Text hashes normalize CRLF/CR to LF; binary
hashes use original bytes. The generated resource is
`composeApp/src/commonMain/composeResources/files/credits/catalog.json`.
Its `sources` object records the graph and hashes. `verifyCreditsCatalog`, wired
to `preReleaseBuild`, rejects graph drift, changed inputs/archives, and missing
offline license texts. It intentionally does not run the exporter automatically:
changed attribution is reviewed as a source change.

The app uses one alphabetical list for every project. Search covers project,
authors and license names; project details link to all applicable local texts.

## Scope and evidence

This is an attribution inventory of the app distribution, not a compatibility
opinion or a complete audit of nested native dependencies. Maven declarations
can omit native subcomponents and original NOTICE files. Preserve existing map
attribution and source notices as well as this screen.

Manual text provenance and remaining questions are in
`third-party/credits/README.md`. Backend dependencies are outside this Android
catalog. After changing a copied component, update its registry and text inputs
as well as refreshing the graph.
