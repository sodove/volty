# Credits and licenses implementation plan

> Execute with superpowers:subagent-driven-development and TDD.

Approved design: `docs/superpowers/specs/2026-10-29-credits-licenses-design.md`.
The rider accepted implementation with «+». All projects use one alphabetical
list; no featured entries or detailed contribution histories.

Reuse the current feature checkout and preserve earlier uncommitted work.
No Kotlin/Compose/AGP upgrades, no new runtime UI framework, no release version
change or server deployment. Gradle workers and ActiveProcessorCount remain four.

- [x] 1. Add a small serializable credits catalog, pure search/navigation state,
  lazy resource loading and integration with SettingsPage CREDITS/back handling.
  Own new `presentation/settings/credits/CreditsCatalog.kt`, `CreditsComponent.kt`,
  SettingsComponent.kt and SettingsPages.kt, plus focused common tests.
  Test malformed/missing references, alphabetical search, real zero/empty metadata,
  query/list-position retention and license→project→catalog back. Write tests first.
- [x] 2. Add AboutLibraries 13.2.1 build-only plugin; prove export works on this
  build, select actual release runtime graph. Generate and validate an offline
  catalog, manual direct-source/native records, full license/notice texts, and a
  release gate against stale graph inputs. Cover generator contracts with tests.
  Primary owns Gradle/config/catalog tooling/resources/third-party input inventory.
- [x] 3. Render credits catalog/detail/license views in existing Material theme.
  UI worker owns SettingsScreen.kt/new CreditsScreen.kt and both string locales.
  Bounded scroll root outside shared settings verticalScroll; no nested lazy list.
  Plain uniform rows, names/authors/version/licenses, search/empty/error/retry,
  source links and offline selectable texts. State decisions stay in component.
- [x] 4. Review integration, run fresh app unit suite/migration/catalog checks,
  inspect packaged data and perform an emulator visual/navigation/offline smoke
  check. Record exact results and limitations; do not claim a full legal audit.

Shared schema: JSON `projects` array (`id`, `name`, optional `version`, `authors`
array, optional HTTPS `website`, `licenseIds` array, optional `notice`) and
`licenses` map with entries (`id`, `name`, `content`). Unknown versions/authors are
omitted; never invented. All required texts are local. Resources are loaded from
`Res.readBytes("files/credits/catalog.json")`.
