# Настройки, редактор ТС и мастер добавления — редизайн

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** настройки, редактор ТС и мастер добавления читаются за секунды: на главном экране каждого — разделы с текущими значениями, каждый раздел — своя страница; второе колесо или «VESC-самокат + ANT BMS» добавляется за минуту без конструктора; без активного ТС приложение всегда возвращается в гараж.

**Architecture:** экраны-«хабы» поверх существующих компонентов. Какая страница открыта — состояние компонента (`StateFlow<…Page?>`), назад сначала закрывает страницу; все сводные строки («Li-ion · 24S») и все решения «что показать» — чистые функции с тестами; `@Composable` только рисует. Черновик ТС, мастер и их сохранение не переписываются — меняются их экраны и несколько точечных состояний.

**Tech Stack:** Kotlin Multiplatform (Android), Compose Multiplatform + Material 3, Decompose, SQLDelight, kotlin.test.

**Spec:** этот документ (раздел «Решения»). Мастер — по одобренному макету Part L: `docs/superpowers/plans/2026-08-01-vehicle-platform-L-setup-wizard.mockup.html` («макет прям как надо»); где этот план и тот макет расходятся по мастеру, **побеждает макет**.

## Решения (согласованы с пользователем 2026-10-23/24)

1. **Настройки — хаб.** Группы с подписями: «Езда» (Дашборд, Единицы, Подключение), «Звук» (Алерты, Голос, Микрофон), «Карты и приложение» (Офлайн-карты, Тема), «Мои ТС» (список ТС + «Добавить ТС»), внизу отдельной группой одна строка «Для разработчиков» (бывшая вкладка «Отладка» + «Диагностика»). Строка = иконка в цветной плашке, название, текущее значение одной строкой, шеврон. **Стрелка «назад» в заголовке и группа «Мои ТС» остаются** (решение пользователя). Вкладки «Общие/Отладка» наверху и аккордеон убираются.
2. **Редактор ТС — хаб внутри того же черновика.** «Отмена»/«Сохранить» наверху всегда; шапка: иконка + имя (нажатие — смена имени и иконки); группы «Устройство» (Контроллер, Батарея, Колесо и мотор, Схема и источники) и «В поездке» (Дашборд, Алерты, Громкость от скорости); внизу «Удалить ТС». Ничего не пишется до «Сохранить».
3. **Поле S** («банок последовательно») на странице «Батарея» — и для своей BMS (`Pack.cellCount`), и для батареи из контроллера (новое `Controller.derivedCellCount`). Если S выведено колесом само, строка подписана «определено по колесу»; введённое вручную значение главнее выведенного.
4. **Мастер** доводится до макета Part L: каждый шаг — одно решение; у батареи сначала выбор из трёх выходов («своя BMS», «из контроллера», «это одно устройство»), поиск — только после выбора «своя BMS»/«одно устройство»; имя и иконка — на шаге «Проверка» рядом со схемой; «Пропустить» ведёт в новый редактор (хаб), а не в старую форму.
5. **Без активного ТС «дом» — гараж**, а не дашборд батареи: выход из мастера и долгое отсутствие ТС больше не открывают «Нет активного ТС» без подсвеченной вкладки (баг, найден 2026-10-24 на эмуляторе).

## Global Constraints

- Строки — в `composeApp/src/commonMain/composeResources/values/strings.xml` **и** `values-ru/strings.xml`; Compose Multiplatform не обрабатывает Android-экранирование — не использовать `\'`, `\n`.
- Compose не тестируется в этом репозитории (CLAUDE.md, правило 3): решения — в компонентах и чистых функциях; вид проверяется только на устройстве — так и писать в отчёте.
- Миграции: `N.sqm` мигрирует `N.db` в `(N+1).db`; последняя — `22.sqm`, база v23 → новая `23.sqm` и снимок `24.db`; `.\gradlew.bat :composeApp:verifyCommonMainVoltyDatabaseMigration` должен пройти. `ADD COLUMN` разрешён (SQLite ≥ 3.19), `DROP COLUMN` — нет.
- `.\gradlew.bat :composeApp:testDebugUnitTest` перед каждым коммитом; число тестов только растёт (сейчас 4910).
- Иконки — `androidx.compose.material.icons` (уже в проекте); цвета — `MaterialTheme.colorScheme`, никаких захардкоженных цветов (тёмная тема и динамический цвет).
- Ни одно поведение, кроме перечисленного в «Решениях», не меняется: все существующие настройки и действия остаются достижимыми (каждая — ровно на одной странице).

## Review Focus

1. **Системная «назад» на странице раздела** закрывает страницу, а не весь экран; на хабе — уходит как раньше. Тесты: задача 2 `backClosesTheOpenPageFirst`, задача 4 `backClosesTheSectionNotTheDraft`.
2. **Черновик ТС при переходах между разделами** не теряет правок и не сохраняется сам. Тест: задача 4 `aSectionEditSurvivesLeavingTheSection`.
3. **Выведенное колесом S против введённого**: введённое главнее, пустое возвращает выведенное. Тест: задача 3 `aTypedCountWinsAndClearingItRestoresTheLearnedOne`.
4. **«VESC + ANT»** проходится в мастере без захода в редактор. Тест: задача 5 `aVescScooterWithAnAntBatteryIsBuiltInTheWizard`.
5. **Выход в «дом» при живом ТС** по-прежнему ведёт на его дашборд (Ride), а не в гараж. Тест: задача 1 `withAVehicleHomeIsStillTheRide`.

---

### Task 1: Без ТС «дом» — гараж

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/root/RootComponent.kt` (`homeConfigFor`, ~212; KDoc над ней с отозванной формулировкой «battery dashboard remains the safe fallback» — пометить как ретракцию, CLAUDE.md «Record retractions»)
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/root/RootNavigationTest.kt`

**Interfaces:** Produces: `homeConfigFor(null) == Config.Picker(mode = "cold")`. `configForTab(Tab.Battery)` по-прежнему `Config.Dashboard` (вкладку батареи не трогаем). `absentSummaryApplies` — без изменений.

- [ ] **Step 1: Тесты** `withoutAVehicleHomeIsTheGarage` (`homeConfigFor(null) == Config.Picker("cold")`), `withAVehicleHomeIsStillTheRide`, `leavingTheWizardWithoutAVehicleLandsInTheGarage` (через `leaveSetupWizard` на фейковой `StackNavigation` со стеком `[Picker(cold), SetupWizard]` → активный `Picker(cold)`), поправить существующие тесты, которые закрепляли старую заглушку — с комментарием-ретракцией.
- [ ] **Step 2:** FAIL. **Step 3:** реализовать. **Step 4:** PASS + весь набор.
- [ ] **Step 5:** commit `fix(navigation): without a vehicle, home is the garage`.

### Task 2: Хаб настроек

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/common/settings/SettingsRows.kt` — общий набор строк (используется и в задаче 4): `SettingsGroup(label: String?, content)`, `SettingsNavRow(icon: ImageVector, tint: SettingsTint, title: String, summary: String?, onClick)`, `SettingsSwitchRow(title, hint, checked, onChange)`, `SettingsSliderRow(title, valueText, value, range, steps, hint, onChange)`, `SettingsChoiceRow<T>(title, options: List<Pair<T,String>>, selected, onSelect)` (сегменты до 4 вариантов, иначе список), `SettingsDangerRow(title, onClick)`; `enum class SettingsTint { DRIVE, SOUND, MAPS, VEHICLE, NEUTRAL }` → цвета `colorScheme` (primaryContainer/tertiaryContainer/secondaryContainer…).
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/settings/SettingsPages.kt` — `enum class SettingsPage { DASHBOARD, UNITS, CONNECTION, ALERT_SOUND, VOICE, MICROPHONE, OFFLINE_MAPS, THEME, DEVELOPER }` и чистые сводки.
- Modify: `presentation/settings/SettingsComponent.kt` (`page: StateFlow<SettingsPage?>`, `onOpenPage(page)`, `onBack()` сначала закрывает страницу), `SettingsScreen.kt` (929 строк: хаб + по `@Composable` на страницу, переносом существующих блоков; `DebugSettingsTab.kt` становится содержимым `DEVELOPER`).
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/settings/SettingsPagesTest.kt`

**Interfaces:**
- Produces: `data class OfflineMapsSummary(val installedRegions: Int, val installedBytes: Long, val downloading: Int)` + `fun offlineMapsSummary(regions: List<OfflineRegionUiState /*существующий тип строки региона*/>): OfflineMapsSummary`; `fun micSummaryParts(mic: MicProcessingSettings): List<MicSummaryPart>` (`enum MicSummaryPart { AUTO_GAIN, PHONE_NS, SPEECH_GATE }` — включённые по порядку; пусто → «без обработки»); `fun voiceSummary(listen: VoiceListenSettings): Pair<Float /*masterDb*/, Boolean /*duck*/>`.
- «Алерты» (ALERT_SOUND) — глобальные `AlertSoundSettings` + `AlarmMusicMode` из `AppPrefs`; в экране алертов ТС эти же контролы остаются (тот же источник, дублирование безопасно).

- [ ] **Step 1: Тесты** `backClosesTheOpenPageFirst` (open VOICE → `onBack()` → `page == null`, навигация наружу не вызвана; второй `onBack()` — вызвана), `offlineSummaryCountsOnlyInstalledRegions`, `micSummaryListsEnabledStagesInOrder` (`autoGain+speechGate` → `[AUTO_GAIN, SPEECH_GATE]`; всё выкл → пусто).
- [ ] **Step 2:** FAIL. **Step 3:** реализовать; хаб по решению 1; каждая существующая настройка переносится на одну страницу без изменения поведения; группа «Мои ТС» — существующий `VehicleRow`, но «Удалить» уходит с строки в редактор ТС (строка открывает редактор).
- [ ] **Step 4:** PASS + весь набор.
- [ ] **Step 5:** commit `feat(settings): a hub of sections with their current values`. Устройство: не проверялось — пометить в отчёте.

### Task 3: S для батареи из контроллера

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/Controller.kt` (`val derivedCellCount: Int? = null`), `sqldelight/.../ControllerRow.sq` (колонка `derivedCellCount INTEGER`), Create: `sqldelight/.../23.sqm` (`ALTER TABLE ControllerRow ADD COLUMN derivedCellCount INTEGER;`) и снимок `24.db` (сгенерировать задачей схемы SQLDelight), маппинг строки ↔ модели (там же, где `providesDerivedBattery`).
- Modify: `data/ble/KableBmsRepository.kt` — синтезированный слот батареи контроллера получает `cellCount = controller.derivedCellCount` (до выведенного колесом); в `makeLinkOnSample` порядок: явный `pack.cellCount` → `controller.derivedCellCount` → `BegodeProtocol.derivedCellCount()`.
- Modify: `presentation/vehicle/VehicleEditComponent.kt` — черновик контроллера несёт `derivedCellCount`, `fun onControllerDerivedCellCountChanged(key: String, cellCount: Int?)` (1..48, иначе null).
- Test: `commonTest/.../data/ble/KableBmsRepositoryDumbBegodeTest.kt`, `commonTest/.../presentation/vehicle/VehicleEditComponentTest.kt`

**Interfaces:** Produces: `Controller.derivedCellCount`, `VehicleEditComponent.onControllerDerivedCellCountChanged`. Consumed by Task 4 (страница «Батарея»).

- [ ] **Step 1: Тесты** `aTypedCountWinsAndClearingItRestoresTheLearnedOne` (T4-фикстура `BegodeT4Fixture` с `derivedCellCount = 20` → SoC считается по 20S; с `null` → по выведенным 24S), `aControllerOnlyWheelWithoutBranchFramesUsesTheTypedCount` (только кадры 0x00 из фикстуры, `derivedCellCount = 24` → `socKnown`), `theDerivedCountSurvivesSaveAndReload` (компонент → репозиторий → снова в черновик).
- [ ] **Step 2:** FAIL. **Step 3:** реализовать + миграция; `verifyCommonMainVoltyDatabaseMigration` проходит.
- [ ] **Step 4:** PASS + весь набор.
- [ ] **Step 5:** commit `feat(battery): a typed series count for a controller-derived battery`.

### Task 4: Хаб редактора ТС

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/vehicle/VehicleEditSections.kt` — `enum class VehicleEditSection { IDENTITY, CONTROLLER, BATTERY, MOTOR, LAYOUT, DASHBOARD, SPEED_VOLUME }` + чистые сводки: `fun batterySummary(draft: VehicleDraft, learnedCellCount: Int?): BatterySummary` (`data class BatterySummary(val source: BatterySource, val chemistry: Chemistry, val cellCount: Int?, val cellCountLearned: Boolean)`, `enum BatterySource { OWN_BMS, FROM_CONTROLLER, DEVICE_IS_BOTH, NONE }`), `fun controllerSummary(draft): ControllerSummary?` (тип + адрес первого контроллера, число остальных), `fun motorSummary(draft): MotorSummary` (пары полюсов, диаметр или «не задан»).
- Modify: `VehicleEditComponent.kt` (`section: StateFlow<VehicleEditSection?>`, `onOpenSection`, `onCloseSection`; `onBack()`/системная «назад» сначала закрывает раздел), `VehicleEditScreen.kt` (611 строк → хаб + страницы на общих строках из Task 2; содержимое разделов — перенос существующих блоков: CONTROLLER = тип + метка + «батарея из контроллера»; BATTERY = источник, химия, S, окно усреднения; MOTOR = пары полюсов, диаметр, передаточное; LAYOUT = дерево `DraftDiagram`, поиск, «+ Контроллер», «+ Батарея», CAN; DASHBOARD = стиль + внутренняя шкала; SPEED_VOLUME = 4 ползунка; «Алерты» — существующая навигация; «Единицы» уходят из редактора — они глобальные и есть в настройках).
- Test: `commonTest/.../presentation/vehicle/VehicleEditSectionsTest.kt` + `VehicleEditComponentTest.kt`

**Interfaces:** Consumes: `SettingsRows.kt` (Task 2), `onControllerDerivedCellCountChanged` (Task 3), `BegodeProtocol.derivedCellCount()` уже доступна через активную связь — `learnedCellCount` компонент берёт из репозитория (null, если ТС не подключено).

- [ ] **Step 1: Тесты** `backClosesTheSectionNotTheDraft`, `aSectionEditSurvivesLeavingTheSection` (BATTERY: химия → LiFePO4 → закрыть → черновик несёт LiFePO4, сохранения не было), `batterySummaryNamesTheSourceAndMarksALearnedCount` (контроллер с `providesDerivedBattery`, `derivedCellCount=null`, learned 24 → `FROM_CONTROLLER, cellCount=24, cellCountLearned=true`; введено 20 → `20, false`), `motorSummarySaysWhenTheDiameterIsUnset`.
- [ ] **Step 2:** FAIL. **Step 3:** реализовать по решению 2.
- [ ] **Step 4:** PASS + весь набор.
- [ ] **Step 5:** commit `feat(vehicle): the vehicle editor as a hub of sections`.

### Task 5: Мастер по макету Part L

**Files:**
- Modify: `presentation/vehicle/wizard/SetupWizardComponent.kt` (680 строк) — в `State`: `batteryExit: BatteryExit?` (`enum BatteryExit { OWN_BMS, FROM_CONTROLLER, DEVICE_IS_BOTH }`), `BatteryStage.onChooseExit(exit)`; поиск на шаге батареи активен только при `OWN_BMS`/`DEVICE_IS_BOTH` и показывает только подходящие роли (`fun batteryScanRows(rows: List<ScanRow>, exit: BatteryExit): List<ScanRow>`); `ReviewStage.onNameChanged`/`onIconChanged` (имя и иконка переезжают сюда); «Пропустить» в `ArchetypeStage` → открывает редактор ТС (хаб из Task 4) с тем же черновиком (существующий маршрут «Skip → composer», меняется только назначение экрана, если он уже ведёт в `VehicleEdit`).
- Modify: `SetupWizardScreen.kt` (986 строк) — вид по макету Part L: одна мысль на экран, строки найденных устройств с адресом и «теплее/холоднее», пустое состояние поиска с объяснением, CAN-блок только если устройство ответило как шлюз, на «Проверке» — схема `DraftDiagram` + имя + иконка.
- Test: `commonTest/.../presentation/vehicle/wizard/SetupWizardComponentTest.kt`

**Interfaces:** Consumes: существующие `VehicleDraft`, `DraftDiagram`, `ScanRow`, `ScannedAdd`.

- [ ] **Step 1: Тесты** `aVescScooterWithAnAntBatteryIsBuiltInTheWizard` (SCOOTER → контроллер из фейкового скана VESC → `onChooseExit(OWN_BMS)` → ANT из скана → Review → имя → `onSave()`: сохранённое ТС = VESC + пак ANT, редактор не открывался), `theBatteryScanShowsNothingUntilAnExitIsChosen`, `ownBmsHidesControllerOnlyDevices`, `fromControllerNeedsNoScan` (exit FROM_CONTROLLER → `providesDerivedBattery = true`, поиск не запускался), `skipOpensTheEditorHubWithTheSameDraft`.
- [ ] **Step 2:** FAIL. **Step 3:** реализовать; где этот план расходится с макетом — макет главнее, расхождение записать в отчёт.
- [ ] **Step 4:** PASS + весь набор.
- [ ] **Step 5:** commit `feat(wizard): one decision per step, battery exits first`.

### Task 6: Сборка

- [ ] Поднять версию (`composeApp/build.gradle.kts`), полный набор + `verifyCommonMainVoltyDatabaseMigration`, коммит; слияние в `main` — по слову пользователя (CI-релиз опубликует).
