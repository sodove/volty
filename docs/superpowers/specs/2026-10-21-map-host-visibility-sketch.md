# Общая карта: пауза вместо прозрачности и переходы без мигания (набросок)

Дата: 2026-10-21. Статус: **набросок**, не план и не код. Ждёт, пока Codex
закоммитит свою работу по карте истории (`RootScreen.kt`, `RideMapHostPolicy.kt`,
`HistoryMapHost.kt` — у него сейчас незакоммичены), потом сверяем и пишем план.

## Что сейчас и почему мигает

- Одна `MapView` (MapLibre, TextureView) живёт на уровне `RootScreen` **под**
  стеком экранов Decompose (`RootScreen.kt` ~283-298). Когда она не нужна —
  `Modifier.alpha(0f)`.
- Экраны переключаются `stackAnimation(fade())` (по умолчанию ~300 мс): в
  середине перехода оба экрана полупрозрачны, и всё, что под ними, просвечивает.
- Видимость карты (`rideMapHostState(...).visible`) меняется **мгновенно** в
  момент начала перехода, а не синхронно с ним:
  - Настройки → Nearby: карта сразу непрозрачна, а настройки ещё тают сверху →
    карта «промаргивает» сквозь настройки.
  - Nearby → Battery: карта гаснет сразу, а прозрачный экран Nearby ещё тает →
    его содержимое на миг висит на пустом фоне.
- Ресурсы: `mapView.onStart()/onStop()` вызываются только по жизненному циклу
  Activity (`PlatformMapLayer.android.kt` ~230-240). Пока приложение на экране,
  скрытая карта продолжает рисовать GL-кадры на каждое обновление сцены (свой
  GPS-фикс, трек, маркеры группы) — с прозрачностью 0, впустую.

## Решено с пользователем

- Мгновенное переключение вкладок — **нет** (некрасиво). Fade остаётся.
- Держать одну живую карту (не пересоздавать стиль и тайлы) — оставляем.

## Шаг 1. Пауза отрисовки, когда карта скрыта (главная польза)

Идея: карта «работает» = Activity в STARTED **и** карта видна (или идёт её
появление/исчезновение). Иначе `mapView.onStop()` + `View.INVISIBLE`.
MapLibre при `onStop` останавливает поток отрисовки, но держит стиль, тайлы и
камеру; `onStart` возвращает картинку без перезагрузки. Обновления сцены,
пришедшие во время паузы, применяются к источникам и рисуются при старте.

Набросок (`PlatformMapLayer.android.kt`), новый параметр `active: Boolean`:

```kotlin
// active приходит из RootScreen: true пока карта видна или ещё анимируется.
DisposableEffect(lifecycleOwner, mapView, active) {
    var started = false
    fun reconcile() {
        val want = active && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (want && !started) { mapView.visibility = View.VISIBLE; mapView.onStart(); started = true }
        if (!want && started) { mapView.onStop(); mapView.visibility = View.INVISIBLE; started = false }
    }
    val observer = LifecycleEventObserver { _, _ -> reconcile() }
    lifecycleOwner.lifecycle.addObserver(observer)
    reconcile()
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer); if (started) mapView.onStop() }
}
```

Открытые вопросы:
- Haze-эффект «стекла» семплирует текстуру карты (`rideMapTextureModeRequiredForMapCapture`):
  убедиться, что на скрытой/остановленной карте он не держит последний кадр.
- `onStop` останавливает и анимации камеры: при возврате камера должна встать
  в актуальную позицию (проверить follow-режим поездки).

## Шаг 2. Прозрачность карты синхронно с переходом

Вместо мгновенного 0/1:

```kotlin
val target = if (mapHost.visible) 1f else 0f
val mapAlpha by animateFloatAsState(target, tween(MapFadeMillis), label = "mapAlpha")
// активна, пока видна ИЛИ ещё не догасла — шаг 1 не должен останавливать её на полпути
val mapActive = mapHost.visible || mapAlpha > 0f
PlatformRideMapLayer(..., active = mapActive, modifier = Modifier.fillMaxSize().alpha(mapAlpha))
```

`MapFadeMillis` = длительность `fade()` у `stackAnimation` (вынести в одну
константу и передать в оба места, чтобы не разъехались).

Результат: карта проявляется вместе со входящим экраном и тает вместе с
уходящим — получается обычный crossfade, а не «вспышка».

Лучше, но сложнее: брать прогресс перехода из самого Decompose (свой
`StackAnimator`, который отдаёт progress наружу) — тогда синхронизация точная,
в том числе для predictive back. Решать после шага 2, если глазом будет
заметна рассинхронизация.

## Шаг 3 (позже, после Codex). Карта внутри экранов через `retain`

Архитектурно чище: каждый экран с картой (поездка, Nearby, групповая карта,
история) встраивает карту у себя, а один `MapView` переезжает между ними —
`retain { }` (рантайм Compose ≥ 1.10) или `movableContentOf`. Тогда нет
глобального слоя под всеми экранами, и переходы корректны по построению.

Риски/проверить:
- Доступность `retain` в common-коде Compose Multiplatform 1.11 (не проверено).
- При переезде `TextureView` пересоздаёт поверхность → возможен кадр чёрного.
- Две карты одновременно (переход с карты на карту) — нужна одна владеющая.

Имеет смысл только вместе с картой истории Codex, иначе переделаем дважды.

## Проверка (эмулятор `emulator-5554` + телефон)

- Переходы Настройки → Nearby → Battery → Поездка (светлая тема) и обратно:
  нет вспышки карты, нет «висящего» содержимого.
- Ресурсы: `adb shell dumpsys gfxinfo ru.sodovaya.volty` и профайлер GPU при
  открытых Настройках с активной поездкой — до и после шага 1 кадры карты не
  должны рендериться. Батарея: `dumpsys batterystats` за одинаковую поездку.
- Возврат на карту: без перезагрузки стиля и тайлов (нет белого/серого кадра),
  камера в правильной позиции.
- Сворачивание приложения и возврат — как раньше.
