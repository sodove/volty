# Google Play listing: Volty (en-US default, ru-RU)

Status: draft for review. Built from the code in this repo on 2026-10-29 (versionName 0.7.59, versionCode 82). Placeholders are in [square brackets]. The older `play-listing-draft.md` in this folder describes a BMS-only app and is stale; this file supersedes it.

Play limits: app name 30, short description 80, full description 4000 characters. Counts below include spaces, bullets and line breaks.

---

## 1. App name

en-US (30 max)
- Option A: `Volty` (5)
- Option B: `Volty: EV & BMS Monitor` (23)
- Option C: `Volty: EV Telemetry` (19)

ru-RU (30 max)
- Option A: `Volty` (5)
- Option B: `Volty: монитор EV и BMS` (23)
- Option C: `Volty: телеметрия EV` (20)

Recommendation: Option A for the store name, since it matches the launcher label (`android:label="Volty"`). Option B adds search terms if the name must carry a descriptor.

---

## 2. Short description

en-US
<!-- BEGIN short-en -->
Live Bluetooth dashboard for e-unicycles, VESC and smart BMS. Read-only. Beta.
<!-- END short-en -->
Count: 78 characters (limit 80)

ru-RU
<!-- BEGIN short-ru -->
Живая панель по Bluetooth для моноколёс, VESC и умных BMS. Только чтение. Бета.
<!-- END short-ru -->
Count: 79 characters (limit 80)

---

## 3. Full description

en-US
<!-- BEGIN full-en -->
Volty shows your electric vehicle and its battery in one live dashboard. It connects over Bluetooth to electric unicycles, VESC controllers and smart battery management systems (BMS), and records every ride on your phone.

BETA: this is an early public beta. Some values are estimated, support differs by model, and some features are still being finished. Ride safely, and check important readings against your vehicle's own display.

READ-ONLY
Volty only reads data from your vehicle. It sends status requests, for example asking a VESC controller for its values. It does not write wheel or controller settings, modes, lights or limits.

SUPPORTED DEVICES
• Electric unicycles: Begode (GotWay), KingSong, Veteran / LeaperKim, InMotion, Ninebot. Support depends on the model.
• VESC controllers: direct Bluetooth link, and through a CAN gateway.
• Smart BMS batteries: ANT, JK, JBD, Daly (Bluetooth).
Not every model of these brands has been tested. When Volty cannot read a value, it shows it as unknown instead of guessing.

DASHBOARD
• Speed, power, battery charge (SOC) and estimated range
• PWM (duty): measured when the vehicle reports it, or estimated from your wheel calibration when it does not
• Battery: voltage, current, every cell with the cell spread highlighted, temperatures, MOSFET state and BMS faults
• Vehicle faults, motor and controller temperatures
• Smooth charge tracking by coulomb counting
• Approximate values are marked as approximate

ALERTS
• Alerts for low charge, charge cut-off, temperature, cell spread and faults
• Sound, vibration and notifications. Monitoring keeps running in the background with a persistent notification.
• A test signal lets you hear each alert level before you ride

RIDE HISTORY
• Every ride is recorded on your phone, with the route on a map, charts and totals
• Map colors show a value only where it was confirmed. Grey means no confirmed value.
• Export a ride as CSV for VESC Tool (logui format) or as GPX for other apps
• Rides stay on your phone unless you export them

NAVIGATION (BETA)
• Route search and route planning on the map
• Searching and routing need an internet connection to the Volty server
• Download map and routing data for your region to use offline (Settings > Offline navigation)

NEARBY (OPTIONAL, BETA)
• Vehicle monitoring works without an account
• An optional account adds friends, groups, a shared map and voice rooms
• Your location is shared only when you turn sharing on
• The microphone is used only after you join a voice room
• Voice rooms may be unavailable in some builds

PRIVACY AND OPEN SOURCE
• Volty is open source under the AGPL-3.0 licence: [source code URL]
<!-- Retraction (2026-11-05): the earlier claim that Volty had no third-party analytics SDK is withdrawn; Firebase Analytics, Crashlytics and Performance Monitoring are now included. -->
• No ads. Analytics logs first opens, sessions, engagement and lifecycle events for active-user counts; screen views and custom button/navigation events are off. Crashlytics and Performance Monitoring collect crash/ANR and performance data automatically. Always on, including signed out; no switch.
• Firebase may receive a pseudonymous installation ID and device/app details, timestamps and durations; crash reports may include stacks, messages and runtime context. Google may infer approximate location from network data. Routes, vehicle/BMS addresses, names, email and ride values are not deliberately added as custom telemetry. Ad ID, ad storage and personalization are disabled.
• Firebase Cloud Messaging delivers notifications about incoming voice calls
• Privacy policy: [privacy policy URL]

REQUIREMENTS
• Android 8.0 or newer
• Bluetooth LE. On Android 8 to 11, location permission is needed for Bluetooth scanning.
• Interface in Russian and English

DISCLAIMER
Volty is an independent project. It is not affiliated with Begode, GotWay, KingSong, Veteran, LeaperKim, InMotion, Ninebot or the VESC project. Brand names are used only to describe compatibility. Volty is an information tool. It does not control your vehicle and does not replace watching the road.
<!-- END full-en -->
Count: 3940 characters (limit 4000)

ru-RU
<!-- BEGIN full-ru -->
Volty показывает ваш электротранспорт и его батарею на одной живой панели. Приложение подключается по Bluetooth к электромоноколёсам, контроллерам VESC и умным системам управления батареей (BMS) и записывает каждую поездку на телефон.

БЕТА-ВЕРСИЯ: это ранняя публичная бета. Часть значений оценочная, поддержка зависит от модели, часть функций ещё дорабатывается. Ездите безопасно и сверяйте важные показания с собственным дисплеем транспорта.

ТОЛЬКО ЧТЕНИЕ
Volty только читает данные с транспорта: отправляет запросы состояния, например запрашивает у контроллера VESC его значения. Приложение не записывает настройки колеса или контроллера, режимы, свет и ограничения.

ПОДДЕРЖИВАЕМЫЕ УСТРОЙСТВА
• Моноколёса: Begode (GotWay), KingSong, Veteran / LeaperKim, InMotion, Ninebot. Поддержка зависит от модели.
• Контроллеры VESC: прямое Bluetooth-подключение и через CAN-шлюз.
• Умные BMS: ANT, JK, JBD, Daly (Bluetooth).
Не все модели этих марок проверены. Если Volty не может прочитать значение, оно показывается как неизвестное, а не угадывается.

ПАНЕЛЬ
• Скорость, мощность, заряд батареи (SOC) и расчётный запас хода
• PWM (загрузка): измеренный, если транспорт его передаёт, или оценочный по калибровке колеса, если не передаёт
• Батарея: напряжение, ток, напряжение каждой ячейки с разбросом, температуры, состояние MOSFET и ошибки BMS
• Ошибки транспорта, температуры мотора и контроллера
• Плавный подсчёт заряда (кулонометрия)
• Приблизительные значения помечены как приблизительные

ОПОВЕЩЕНИЯ
• Оповещения о низком заряде, отключении по заряду, температуре, разбросе ячеек и ошибках
• Звук, вибрация и уведомления. Мониторинг работает в фоне с постоянным уведомлением.
• Тестовый сигнал: каждый уровень оповещения можно услышать до поездки

ИСТОРИЯ ПОЕЗДОК
• Каждая поездка записывается на телефон: маршрут на карте, графики и итоги
• Цвет на карте показывает величину только там, где её значение подтверждено. Серый означает, что подтверждённого значения нет.
• Экспорт поездки в CSV для VESC Tool (формат logui) или в GPX для других приложений
• Поездки остаются на телефоне, пока вы их не экспортируете

НАВИГАЦИЯ (БЕТА)
• Поиск места и планирование маршрута на карте
• Поиск и маршруты требуют интернета (сервер Volty)
• Скачайте карты и данные маршрутизации своего региона для работы без сети (Настройки > Офлайн-навигация)

БЛИЗКИЕ (НЕОБЯЗАТЕЛЬНО, БЕТА)
• Мониторинг транспорта работает без аккаунта
• Необязательный аккаунт открывает друзей, группы, общую карту и голосовые комнаты
• Геопозиция передаётся только после того, как вы включили передачу
• Микрофон используется только после входа в голосовую комнату
• В некоторых сборках голосовые комнаты могут быть недоступны

КОНФИДЕНЦИАЛЬНОСТЬ И ОТКРЫТЫЙ КОД
• Volty — открытый код по лицензии AGPL-3.0: [ссылка на исходный код]
<!-- Отзыв прежнего утверждения (2026-11-05): фраза об отсутствии сторонних аналитических SDK больше не верна; добавлены Firebase Analytics, Crashlytics и Performance Monitoring. -->
• Нет рекламы. Analytics автоматически считает первые запуски, сеансы и активность; просмотры экранов и пользовательские события/ID/свойства отключены. Crashlytics и Performance Monitoring собирают сбои/ANR и производительность. Всегда включено, без аккаунта и выключателя.
• SDK передают псевдонимный ID установки и данные устройства/приложения. Google может вывести примерное местоположение по сети. Маршруты, адреса транспорта/BMS, имена, email и данные поездок намеренно не добавляются. Рекламный ID, хранение/персонализация отключены.
• Firebase Cloud Messaging доставляет уведомления о входящих голосовых вызовах
• Политика конфиденциальности: [ссылка на политику]

ТРЕБОВАНИЯ
• Android 8.0 и новее
• Bluetooth LE. На Android 8–11 для поиска Bluetooth-устройств нужен доступ к геопозиции.
• Интерфейс на русском и английском

ОТКАЗ ОТ ОТВЕТСТВЕННОСТИ
Volty — независимый проект. Он не связан с Begode, GotWay, KingSong, Veteran, LeaperKim, InMotion, Ninebot и проектом VESC. Названия марок используются только для описания совместимости. Volty — информационный инструмент: он не управляет транспортом и не заменяет внимание на дороге.
<!-- END full-ru -->
Count: 3940 characters (limit 4000)

---

## 4. Category, tags, contacts

- Category: Auto & Vehicles (primary). Alternative if Play rejects it: Tools.
- Tags: pick from Play's list if it offers them, otherwise none. Play has no keyword field, so search terms are already in the descriptions: electric unicycle, EUC, VESC, BMS, battery monitor, Bluetooth, e-scooter, telemetry, GPX.
- Contact email: [support email]
- Website: [project website, optional]
- Privacy policy URL: [https://YOUR-DOMAIN/privacy]. No policy page exists in this repo. Create one before submitting, covering the data listed in section 6.
- Release track: Open testing for the beta, not Production.

---

## 5. Graphics checklist

**App icon (512 x 512 PNG)**
- Source exists: `composeApp/src/androidMain/ic_launcher-playstore.png`, verified as 512 x 512 RGBA, 27 KB. No rasterizing needed.
- Check that the square is full-bleed and opaque in the corners. Play applies its own mask.
- The adaptive launcher icon is `composeApp/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`. Its background is `#000000` (`composeApp/src/main/res/values/ic_launcher_background.xml`). Foreground and monochrome are raster WebP at `composeApp/src/main/res/mipmap-*/ic_launcher_foreground.webp` and `ic_launcher_monochrome.webp`. Only needed if the store icon is re-exported from these layers.

**Feature graphic (1024 x 500 PNG or JPG)**
- Concept: black background (matching the launcher's `#000000`). Left: the Volty mark from the icon. Right: a stylised speed and battery readout, with a faint route line behind it. No real vehicle photos.
- Text, en-US: "Volty: live EV telemetry" with a small "Beta" tag.
- Text, ru-RU: "Volty: телеметрия EV в реальном времени" with a small "Бета" tag.
- Keep text off the outer 10 percent of each edge.

**Phone screenshots (2 to 8, in this order)**

Capture in demo mode ("Try demo: unicycle" on the welcome screen). Do not capture real MAC addresses, account names, email addresses, or map positions near a home address.

| # | Screen | Caption en-US | Caption ru-RU |
|---|---|---|---|
| 1 | Ride tab (dashboard) | Live speed, battery and power | Скорость, батарея и мощность в реальном времени |
| 2 | Battery tab, cells | Every cell, with the spread highlighted | Каждая ячейка и разброс напряжения |
| 3 | Graph tab | Charts from one minute to several hours | Графики от минуты до нескольких часов |
| 4 | Ride history, map | Recorded routes, colored only where confirmed | Маршруты, цвет только там, где значение подтверждено |
| 5 | Export sheet | Export to VESC Tool CSV or GPX | Экспорт в CSV для VESC Tool или в GPX |
| 6 | Alerts settings | Alerts with sound and vibration | Оповещения со звуком и вибрацией |
| 7 | Garage | Add and switch between vehicles | Добавление и переключение транспорта |
| 8 | Navigator (optional) | Offline maps and routing for your region | Офлайн-карты и маршруты для вашего региона |

Skip the Nearby and voice screens for the first release. Voice rooms may be unavailable in some builds, and the screenshots must match what the store build does. Screen 8 is optional. Use it only if the offline download works in the release build.

---

## 6. Data safety draft

Every answer below is taken from the code. Check items marked "verify" before submitting.

**Data collected and where it goes**

**Retraction (2026-11-05):** The earlier draft said there was no crash SDK and no analytics SDK. Those statements described the pre-monitoring build and are withdrawn; the Firebase rows below supersede them.

| Play data type | Collected | Leaves the device | Source in code and notes |
|---|---|---|---|
| Precise location | Yes | Yes, in some cases | Ride route is recorded on the phone and stays there. Route search and planning send the origin and destination to `volty.sodove.ru` when online. Nearby location sharing (opt-in, time-limited) sends position to the Volty server. Map tiles come from OpenFreeMap, which sees the requesting IP and map area. |
| Name, email address (account) | Only with an optional account | Yes, to the Volty server | Nearby registration sends name, email and password. The server stores the email, display name and a password hash. Passwords travel over HTTPS only. Google's form has no password category, so state this in the notes. |
| User IDs (account, friends) | Only with an account | Yes, to the Volty server | Account and friend identifiers exist server-side. |
| Device or other IDs (Firebase installation ID and push token) | Yes, automatically for monitoring; FCM token only with an account | Yes, to Google Firebase; the FCM token also goes to the Volty server | The monitoring SDKs use a pseudonymous Firebase installation ID and device/app details. Existing FCM registration remains account-gated and is used for voice-call notifications. No Advertising ID is collected. Purposes: analytics and app functionality/diagnostics. |
| Audio | Only in voice rooms | Yes, to the voice media server | Microphone is requested only after joining a voice room. The app does not record audio to disk (verify). The media host is configured at build time. Confirm the production host before submitting. |
| Bluetooth device addresses | Stored on the phone | No (verify) | Vehicle and BMS addresses are stored locally. Confirm that Nearby never sends them. |
| Ride and telemetry history | Stored on the phone | No, unless the user exports | Ride history has no upload endpoint in the client. Export is user-initiated. |
| Diagnostics and logs | Only when the user shares them | No, unless the user shares | Logcat and radio diagnostics go out only through the Android share sheet, and only when the user chooses. |
| App activity (app interactions) | Yes, automatically | Yes, to Google Firebase | Analytics sends first-open, session, engagement and other standard SDK lifecycle/update events for active-user counts. Automatic screen views are disabled; there are no custom events, screen/navigation hooks, user IDs or user properties. Purpose: analytics. |
| App info and performance (crash logs, diagnostics and performance data) | Yes, automatically | Yes, to Google Firebase | Crashlytics collects fatal Java/Kotlin crashes and ANRs; reports may contain stack traces, messages and runtime context. Performance Monitoring collects built-in startup/lifecycle metrics and may include automatic Activity rendering traces. No custom nonfatal reports/log uploads, NDK symbol integration, network instrumentation or custom feature traces. Purpose: app functionality and crash/performance diagnostics. |
| Advertising ID and advertising data | No | No | Advertising-ID collection, ad storage and advertising personalization are disabled. There are no ads. |

Monitoring is on by default for signed-in and signed-out use, with no consent dialog or in-app opt-out. SDK traffic may include pseudonymous installation identifiers, device/app information, timestamps and durations; Google may infer approximate location from network information. Routes, vehicle/BMS addresses, names, email addresses and ride values are not deliberately added as custom telemetry. This does not promise that runtime context in an automatically collected crash report can never contain app data.

**Other answers**
- Data is encrypted in transit: yes. The app uses HTTPS. `network_security_config.xml` allows cleartext only for 127.0.0.1 and localhost.
- Data can be requested for deletion: yes, with two parts.
  - Local data: rides can be deleted in the app. Clearing app data or uninstalling removes everything on the phone.
  - Account data: the backend has `DELETE /v1/account`. The client repository has a `deleteAccount` method, but no presentation code calls it, so the app has no in-app deletion button yet. See section 7.
- Optional data: vehicle monitoring and local ride history work without an account. Account data is optional.
- Purposes: analytics (active-user measurement) and app functionality (crash, ANR and performance diagnostics). No data is used for advertising.

---

## 7. Blockers and checks before submission

1. **In-app account deletion (blocker).** Play requires an in-app way to delete an account when an app lets users create one, plus a web link for deletion. `DefaultSocialRepository.deleteAccount` exists, but no presentation code calls it. Add a delete button wired to `DELETE /v1/account`, or hide registration in the store build. Also publish the web link.
2. **Privacy policy page.** None exists. Publish one that matches section 6.
3. **Play Console permission declarations.** Expect forms for: precise location, Bluetooth, microphone (RECORD_AUDIO), full-screen intent (USE_FULL_SCREEN_INTENT), and foreground service types `connectedDevice`, `location`, `microphone`. Each needs a justification and a video or screenshots.
4. **Firebase monitoring check.** Follow [firebase-monitoring.md](firebase-monitoring.md) to check SDK startup and Analytics events with `adb`/DebugView. Device logs and DebugView do not by themselves establish that the Firebase project is linked to the intended Google Analytics property; verify that linkage in Firebase Console before relying on standard reports. The earlier no-SDK dependency check is obsolete (retracted 2026-11-05).
5. **Field-tested brands.** Confirm which brands and models are field-tested. The description says "not every model has been tested", but if you can name the tested ones, list them and say so.
6. **Voice status.** Confirm that voice rooms work in the release build. The string "Voice provider is not connected yet" exists. If voice is not ready, delete the Nearby voice bullet or mark it "coming soon".
7. **Read-only claim.** The app has one BLE write path (`ConnectionSession.writeCommand`). Its guard excludes Begode and live Begode advertisements. It sends the VESC CAN ping (`COMM_PING_CAN`). No VESC setter opcode was found in the source. Confirm this on hardware before publishing the claim.
8. **Version.** The store build should be versionName 0.7.59, versionCode 82, or later.
9. **Trademarks.** The disclaimer covers the brand names used for compatibility.
