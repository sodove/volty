package ru.sodovaya.volty.domain.video

import ru.sodovaya.volty.domain.video.OverlayEditorAction.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayEditorStateTest {
    private val hud = OverlayTemplates.hud()
    private val minimal = OverlayTemplates.minimal()
    private val topRow = OverlayTemplates.byId("top-row")!!

    private fun open(selected: OverlayPreset = hud, saved: List<OverlayPreset> = emptyList()) =
        OverlayEditorState.open(OverlayDocument(selected = selected, saved = saved))

    private fun OverlayEditorState.run(vararg actions: OverlayEditorAction) =
        actions.fold(this) { state, action -> reduceOverlayEditor(state, action) }

    private fun OverlayEditorState.box(id: String): OverlayBox {
        val placement = draft.widgets.first { it.id == id }.placement!!
        return (if (landscape) placement.landscape else placement.portrait)!!
    }

    private fun OverlayEditorState.rect(id: String) =
        overlayBoxRect(box(id), overlayDesignWidth(landscape), overlayDesignHeight(landscape))

    // --- snapping ---

    @Test
    fun aRectNearTheFrameCentreSnapsToItAndReportsTheGuide() {
        val rect = OverlayRect(390f, 1000f, 703f, 1176f) // centre x 546.5, 6.5 from 540
        val snap = overlaySnap(rect, emptyList(), 1080f, 1920f)
        assertEquals(540f, snap.rect.centerX)
        assertTrue(OverlayGuide(true, 540f) in snap.guides)
    }

    @Test
    fun aRectFarFromEveryLineStaysPutWithNoGuide() {
        val rect = OverlayRect(130f, 800f, 230f, 900f)
        val snap = overlaySnap(rect, emptyList(), 1080f, 1920f, distance = 5f)
        assertEquals(rect, snap.rect)
        assertTrue(snap.guides.isEmpty())
    }

    @Test
    fun theClosestLineWinsAndEachAxisSnapsOnce() {
        val other = OverlayRect(500f, 300f, 600f, 400f)
        val rect = OverlayRect(592f, 395f, 692f, 495f)
        val snap = overlaySnap(rect, listOf(other), 1080f, 1920f)
        assertEquals(600f, snap.rect.left)
        assertEquals(400f, snap.rect.top)
        assertEquals(2, snap.guides.size)
    }

    @Test
    fun marginsHoldAWidgetInsideTheSafeArea() {
        val rect = overlayClamped(OverlayRect(-100f, -100f, 200f, 200f), 1080f, 1920f)
        assertEquals(54f, rect.left)
        assertEquals(54f, rect.top)
        assertEquals(300f, rect.width)
        val huge = overlayClamped(OverlayRect(0f, 0f, 1000f, 100f), 1080f, 1920f)
        assertEquals(40f, huge.left, "wider than the safe area: centred, not jammed")
    }

    // --- anchors ---

    @Test
    fun aPositionIsStoredAgainstItsNearestAnchorAndReadsBackExactly() {
        val box = OverlayBox(OverlayAnchor.CENTER, 0f, 0f, 100f, 50f)
        for ((rect, anchor) in listOf(
            OverlayRect(60f, 60f, 160f, 110f) to OverlayAnchor.TOP_START,
            OverlayRect(490f, 60f, 590f, 110f) to OverlayAnchor.TOP_CENTER,
            OverlayRect(900f, 900f, 1000f, 950f) to OverlayAnchor.CENTER_END,
            OverlayRect(900f, 1800f, 1000f, 1850f) to OverlayAnchor.BOTTOM_END,
        )) {
            val placed = overlayBoxAt(box, rect, 1080f, 1920f)
            assertEquals(anchor, placed.anchor)
            assertEquals(rect, overlayBoxRect(placed, 1080f, 1920f))
        }
    }

    @Test
    fun aWidgetDraggedToACornerStaysInThatCornerOnAnotherAspect() {
        val placed = overlayBoxAt(OverlayBox(OverlayAnchor.CENTER, 0f, 0f, 100f, 50f), OverlayRect(900f, 1800f, 1000f, 1850f), 1080f, 1920f)
        assertEquals(1440f - 80f, overlayBoxRect(placed, 1440f, 1920f).right)
    }

    // --- hit test ---

    @Test
    fun aTouchPicksTheSmallestWidgetUnderItAndMissesEmptySpace() {
        val widgets = listOf(OverlayWidgetBounds("dial", 0f, 0f, 500f, 500f), OverlayWidgetBounds("card", 100f, 100f, 200f, 200f))
        assertEquals("card", overlayHitTest(widgets, 150f, 150f))
        assertEquals("dial", overlayHitTest(widgets, 400f, 400f))
        assertEquals("card", overlayHitTest(widgets, 210f, 150f, slop = 16f))
        assertNull(overlayHitTest(widgets, 700f, 700f))
    }

    // --- dragging ---

    @Test
    fun aDragMovesOnlyItsOwnOrientationSnapsAndIsOneUndoStep() {
        val start = open().run(DragStart("esc-temperature", lift = 0f))
        assertNotNull(start.drag)
        assertEquals("esc-temperature", start.selectedId)
        val moved = start.run(DragBy(-1f, -200f), DragBy(0f, -200f))
        assertTrue(moved.drag!!.guides.isNotEmpty() || moved.rect("esc-temperature").top < 1500f)
        val done = moved.run(DragEnd)
        assertNull(done.drag)
        assertEquals(1, done.undo.size)
        assertEquals(
            hud.widgets.first { it.id == "esc-temperature" }.placement!!.landscape,
            done.draft.widgets.first { it.id == "esc-temperature" }.placement!!.landscape,
        )
        assertEquals(hud, done.run(Undo).draft)
    }

    @Test
    fun theWidgetRidesAboveTheFinger() {
        val before = open().rect("esc-temperature")
        val lifted = open().run(DragStart("esc-temperature", lift = 100f))
        assertTrue(lifted.rect("esc-temperature").top <= before.top - 100f + OVERLAY_SNAP_DISTANCE)
    }

    @Test
    fun aDragThatGoesNowhereLeavesNoHistoryAndACancelRestores() {
        val zero = open().run(DragStart("esc-temperature", 0f))
        val cancelled = zero.run(DragBy(300f, -300f), DragCancel)
        assertEquals(hud, cancelled.draft)
        assertNull(cancelled.drag)
        assertTrue(cancelled.undo.isEmpty())
    }

    @Test
    fun aDragCannotLeaveTheSafeArea() {
        val state = open().run(DragStart("esc-temperature", 0f), DragBy(-5000f, -5000f))
        assertEquals(54f, state.rect("esc-temperature").left)
        assertEquals(54f, state.rect("esc-temperature").top)
    }

    @Test
    fun aHiddenOrUnknownWidgetCannotBeDragged() {
        assertNull(open().run(DragStart("nope", 0f)).drag)
        val racing = OverlayEditorState.open(OverlayDocument(selected = OverlayTemplates.racing()), landscape = true)
        assertNull(racing.run(DragStart("motor-temperature", 0f)).drag)
    }

    @Test
    fun dragByWithoutADragDoesNothing() {
        val state = open()
        assertEquals(state, state.run(DragBy(10f, 10f), DragEnd))
    }

    // --- flow presets ---

    @Test
    fun theFirstDragOfAFlowPresetFreezesItsWholeLayoutInBothOrientations() {
        val state = open(topRow).run(DragStart("voltage", 0f))
        assertTrue(state.draft.widgets.all { it.placement?.portrait != null && it.placement?.landscape != null })
        assertNotNull(state.draft.layout)
        assertTrue(validOverlayPreset(state.draft))
        assertEquals(topRow, state.run(DragBy(200f, 300f), DragEnd, Undo).draft)
    }

    @Test
    fun freezingKeepsEveryWidgetWhereTheFlowHadIt() {
        val strings = VideoOverlayStrings()
        val env = OverlayEditorEnv(strings)
        val frozen = overlayMaterialized(topRow, strings, env.system, env.measure)
        for ((w, h) in listOf(1080 to 1920, 1920 to 1080)) {
            val before = buildOverlayScene(OverlaySceneInput(topRow, null, strings, env.system, w, h))
            val after = buildOverlayScene(OverlaySceneInput(frozen, null, strings, env.system, w, h))
            before.widgets.zip(after.widgets).forEach { (a, b) ->
                assertEquals(a.left, b.left, 0.01f, a.id)
                assertEquals(a.top, b.top, 0.01f, a.id)
                assertEquals(a.valueSize, b.valueSize, 0.01f, a.id)
            }
        }
    }

    @Test
    fun aPlacedPresetIsNotTouchedByFreezing() {
        assertEquals(hud, overlayMaterialized(hud, VideoOverlayStrings(), ru.sodovaya.volty.util.UnitSystem.METRIC, EstimatedOverlayTextMeasure))
    }

    // --- widget edits ---

    @Test
    fun changingTheMetricTakesLabelRulesAndScaleWithIt() {
        val state = open().run(Select("esc-temperature"), SetMetric(VideoMetric.VOLTAGE))
        val widget = state.selected!!
        assertEquals(VideoMetric.VOLTAGE, widget.metric)
        assertEquals("", widget.label)
        assertTrue(widget.rules.isEmpty(), "a temperature rule must not colour a voltage")
        assertEquals(1, state.undo.size)
    }

    @Test
    fun aDefaultAccentFollowsTheMetricAndAChosenOneStays() {
        val follows = open().run(Select("esc-temperature"), SetMetric(VideoMetric.DUTY))
        assertEquals(overlayDefaultAccent(VideoMetric.DUTY), follows.selected!!.style.accent)
        val pink = OverlayPalette.hex(OverlayPalette.PINK)
        val chosen = open().run(Select("esc-temperature"), SetAccent(pink), SetMetric(VideoMetric.DUTY))
        assertEquals(pink, chosen.selected!!.style.accent)
    }

    @Test
    fun aTripMetricCannotBeADialOrABar() {
        val state = open().run(Select("speed"), SetMetric(VideoMetric.ELAPSED))
        assertEquals(OverlayWidgetKind.VALUE_CARD, state.selected!!.kind)
        assertEquals(state.selected, state.run(SetKind(OverlayWidgetKind.ARC)).selected)
        assertFalse(overlayKindAllowed(OverlayWidgetKind.BAR, VideoMetric.DISTANCE))
        assertTrue(overlayKindAllowed(OverlayWidgetKind.TEXT, VideoMetric.DISTANCE))
    }

    @Test
    fun aSecondaryNumberOnlyStaysOnTheChargeBar() {
        assertNull(open().run(Select("soc"), SetMetric(VideoMetric.DUTY)).selected!!.bar.secondary)
    }

    @Test
    fun aSizeScalesBothOrientationsAroundTheCentreAndComesBackExactly() {
        val before = open().rect("esc-temperature")
        val big = open().run(Select("esc-temperature"), SetSize(OverlayWidgetSize.L))
        assertEquals(OverlayWidgetSize.L, big.selected!!.size)
        assertEquals(64f * 1.25f, big.box("esc-temperature").valueSize)
        assertEquals(before.centerX, big.rect("esc-temperature").centerX, 0.5f)
        val back = big.run(SetSize(OverlayWidgetSize.M))
        assertEquals(64f, back.box("esc-temperature").valueSize, 0.01f)
        assertEquals(before.width, back.rect("esc-temperature").width, 0.5f)
    }

    @Test
    fun aKindChangeGivesTheWidgetTheRightShapeInsideTheFrame() {
        val state = open().run(Select("esc-temperature"), SetKind(OverlayWidgetKind.ARC))
        val rect = state.rect("esc-temperature")
        assertEquals(400f, rect.width)
        assertEquals(400f, rect.height)
        assertTrue(rect.bottom <= 1920f - 54f)
    }

    @Test
    fun aNudgeMovesAndStopsAtTheMargin() {
        val before = open().rect("esc-temperature")
        val moved = open().run(Select("esc-temperature"), Nudge(OVERLAY_NUDGE_STEP, 0f))
        assertEquals(before.left + OVERLAY_NUDGE_STEP, moved.rect("esc-temperature").left, 0.01f)
        val pinned = open().run(Select("esc-temperature"), Nudge(-5000f, 0f))
        assertEquals(54f, pinned.rect("esc-temperature").left)
    }

    @Test
    fun restorePutsAWidgetBackWhereTheSavedPresetHasIt() {
        val state = open().run(Select("esc-temperature"), Nudge(100f, -100f), RestorePlace)
        assertEquals(hud.widgets.first { it.id == "esc-temperature" }.placement!!.portrait, state.box("esc-temperature"))
    }

    @Test
    fun hidingIsPerOrientationAndShowingBringsItBackInsideTheFrame() {
        val hidden = open().run(Select("esc-temperature"), SetHidden(true))
        assertNull(hidden.selected!!.placement!!.portrait)
        assertNotNull(hidden.selected!!.placement!!.landscape)
        val shown = hidden.run(SetHidden(false))
        assertTrue(shown.rect("esc-temperature").left >= 54f)
    }

    @Test
    fun aNewWidgetIsPlacedSelectedAndComesWithItsMetricsRules() {
        val state = open().run(AddWidget("new", VideoMetric.BATTERY_TEMPERATURE))
        assertEquals("new", state.selectedId)
        assertEquals(hud.widgets.size + 1, state.draft.widgets.size)
        assertTrue(state.selected!!.rules.isNotEmpty())
        assertEquals(OverlayAnchor.CENTER, state.box("new").anchor)
        assertTrue(validOverlayPreset(state.draft))
    }

    @Test
    fun aDuplicateIdOrAFullPresetAddsNothing() {
        assertEquals(hud.widgets.size, open().run(AddWidget("speed", VideoMetric.SOC)).draft.widgets.size)
        var state = open()
        repeat(30) { state = state.run(AddWidget("w$it", VideoMetric.SOC)) }
        assertEquals(24, state.draft.widgets.size)
    }

    @Test
    fun theLastWidgetCannotBeDeleted() {
        val one = hud.copy(id = "one", widgets = hud.widgets.take(1))
        val state = open(one).run(Select("speed"), DeleteWidget)
        assertEquals(1, state.draft.widgets.size)
        val two = open().run(Select("speed"), DeleteWidget)
        assertNull(two.selectedId)
        assertEquals(hud.widgets.size - 1, two.draft.widgets.size)
    }

    @Test
    fun selectingSomethingThatIsNotThereClearsTheSelection() {
        assertNull(open().run(Select("speed"), Select("gone")).selectedId)
    }

    // --- undo stack ---

    @Test
    fun undoAndRedoWalkTheEditsAndANewEditDropsTheRedo() {
        val a = open().run(Select("speed"), SetSize(OverlayWidgetSize.L))
        val b = a.run(SetSize(OverlayWidgetSize.XL))
        val undone = b.run(Undo)
        assertEquals(a.draft, undone.draft)
        assertTrue(undone.canRedo)
        assertEquals(b.draft, undone.run(Redo).draft)
        assertFalse(undone.run(SetSize(OverlayWidgetSize.S)).canRedo)
        assertEquals(hud, undone.run(Undo).draft)
        assertFalse(undone.run(Undo).canUndo)
    }

    @Test
    fun anEmptyStackIsANoOp() {
        val state = open()
        assertEquals(state, state.run(Undo, Redo))
    }

    @Test
    fun anEditThatChangesNothingIsNotAStep() {
        val state = open().run(Select("speed"), SetSize(OverlayWidgetSize.M))
        assertTrue(state.undo.isEmpty())
    }

    @Test
    fun historyIsBounded() {
        var state = open().run(Select("speed"))
        repeat(60) { state = state.run(Nudge(if (it % 2 == 0) 4f else -4f, 0f)) }
        assertEquals(50, state.undo.size)
    }

    @Test
    fun undoingAWidgetAwayDropsItsSelection() {
        val state = open().run(AddWidget("new", VideoMetric.SOC), Undo)
        assertNull(state.selectedId)
    }

    @Test
    fun aPresetSwitchCanBeUndoneAndTheDocumentFollows() {
        val state = open().run(RequestPreset(minimal))
        assertEquals(minimal, state.document.selected)
        val undone = state.run(Undo)
        assertEquals(hud, undone.draft)
        assertEquals(hud, undone.document.selected)
        assertFalse(undone.edited)
    }

    // --- built-ins are never overwritten ---

    @Test
    fun editingABuiltInLeavesTheDocumentAloneAndSaveCannotOverwriteIt() {
        val state = open().run(Select("speed"), SetSize(OverlayWidgetSize.L))
        assertEquals(hud, state.document.selected)
        assertTrue(state.edited)
        assertTrue(state.isBuiltIn)
        assertFalse(state.canOverwrite)
        assertEquals(state, state.run(Save))
    }

    @Test
    fun aBuiltInIsSavedAsACopyAndBecomesTheSelection() {
        val state = open().run(Select("speed"), SetSize(OverlayWidgetSize.L), SaveAs("copy", " Мой HUD "))
        val copy = state.document.saved.single()
        assertEquals("copy", copy.id)
        assertEquals("Мой HUD", copy.name)
        assertEquals(copy, state.document.selected)
        assertFalse(state.edited)
        assertFalse(state.isBuiltIn)
        assertTrue(state.canOverwrite)
        assertFalse(state.canUndo, "history does not reach back past a save")
        assertEquals(hud, OverlayTemplates.hud())
    }

    @Test
    fun aDuplicateNeedsNoEditsAtAll() {
        val state = open().run(SaveAs("copy", "Копия"))
        assertEquals(hud.widgets, state.document.saved.single().widgets)
    }

    @Test
    fun aSavedPresetIsRewrittenInPlaceAndNowhereElse() {
        val mine = hud.copy(id = "mine", name = "Мой")
        val other = hud.copy(id = "other", name = "Другой")
        val state = open(mine, listOf(mine, other)).run(Select("speed"), SetSize(OverlayWidgetSize.XL), Save)
        assertEquals(state.draft, state.document.saved.first { it.id == "mine" })
        assertEquals(state.draft, state.document.selected)
        assertEquals(other, state.document.saved.first { it.id == "other" })
        assertEquals(2, state.document.saved.size)
    }

    @Test
    fun aCopyCannotTakeABuiltInOrSavedIdAnEmptyNameOrASlotPastTheLimit() {
        val mine = hud.copy(id = "mine", name = "Мой")
        val state = open(mine, listOf(mine))
        assertEquals(state, state.run(SaveAs("hud", "x")))
        assertEquals(state, state.run(SaveAs("mine", "x")))
        assertEquals(state, state.run(SaveAs("new", "   ")))
        val full = List(OVERLAY_MAX_SAVED) { hud.copy(id = "p$it", name = "P$it") }
        val crowded = open(full[0], full)
        assertFalse(crowded.canSaveCopy)
        assertEquals(crowded, crowded.run(SaveAs("new", "x")))
    }

    // --- the unsaved-edits hazard ---

    @Test
    fun aPresetTapOverUnsavedEditsAsksAndChangesNothing() {
        val edited = open().run(Select("speed"), SetSize(OverlayWidgetSize.L))
        val asked = edited.run(RequestPreset(minimal))
        assertEquals(OverlayEditorConfirm.SwitchTo(minimal), asked.confirm)
        assertEquals(edited.draft, asked.draft)
        assertEquals(hud, asked.document.selected)
    }

    @Test
    fun keepEditingCancelsAndDiscardGoesThrough() {
        val asked = open().run(Select("speed"), SetSize(OverlayWidgetSize.L), RequestPreset(minimal))
        assertEquals(asked.draft, asked.run(ConfirmCancel).draft)
        assertNull(asked.run(ConfirmCancel).confirm)
        val gone = asked.run(ConfirmDiscard)
        assertEquals(minimal, gone.draft)
        assertEquals(minimal, gone.document.selected)
        assertNull(gone.confirm)
    }

    @Test
    fun evenADiscardCanBeUndone() {
        val asked = open().run(Select("speed"), SetSize(OverlayWidgetSize.L), RequestPreset(minimal))
        val gone = asked.run(ConfirmDiscard)
        assertEquals(asked.draft, gone.run(Undo).draft)
    }

    @Test
    fun savingFromTheQuestionFinishesWhatItWasAskedFor() {
        val asked = open().run(Select("speed"), SetSize(OverlayWidgetSize.L), RequestPreset(minimal))
        val state = asked.run(SaveAs("copy", "Моё"))
        assertEquals(minimal, state.draft)
        assertEquals(minimal, state.document.selected)
        assertEquals("copy", state.document.saved.single().id)
        assertNull(state.confirm)
    }

    @Test
    fun aPresetLeftEditedByTheOldEditorIsNotReplacedWithoutAsking() {
        val orphan = hud.copy(widgets = hud.widgets.dropLast(1))
        val state = open(orphan)
        assertFalse(state.edited)
        assertIs<OverlayEditorConfirm.SwitchTo>(state.run(RequestPreset(minimal)).confirm)
    }

    @Test
    fun aCleanPresetSwitchesWithoutAQuestion() {
        val state = open().run(RequestPreset(minimal))
        assertNull(state.confirm)
        assertEquals(minimal, state.draft)
        assertEquals(state, state.run(RequestPreset(minimal)))
    }

    @Test
    fun closingWithEditsAsksAndWithoutCloses() {
        assertTrue(open().run(RequestClose).closed)
        val asked = open().run(Select("speed"), SetSize(OverlayWidgetSize.L), RequestClose)
        assertFalse(asked.closed)
        assertEquals(OverlayEditorConfirm.Close, asked.confirm)
        val discarded = asked.run(ConfirmDiscard)
        assertTrue(discarded.closed)
        assertEquals(hud, discarded.document.selected)
        assertTrue(asked.run(SaveAs("c", "Копия")).closed)
    }

    @Test
    fun deletingASavedPresetAsksFirstAndAtTheSelectedOneFallsBackToTheHud() {
        val mine = minimal.copy(id = "mine", name = "Мой")
        val asked = open(mine, listOf(mine)).run(RequestDelete("mine"))
        assertEquals(listOf(mine), asked.document.saved)
        val gone = asked.run(ConfirmDiscard)
        assertTrue(gone.document.saved.isEmpty())
        assertEquals(hud, gone.document.selected)
        assertEquals(hud, gone.draft)
    }

    @Test
    fun deletingAnotherSavedPresetKeepsTheDraft() {
        val mine = minimal.copy(id = "mine", name = "Мой")
        val edited = open(hud, listOf(mine)).run(Select("speed"), SetSize(OverlayWidgetSize.L))
        val gone = edited.run(RequestDelete("mine"), ConfirmDiscard)
        assertEquals(edited.draft, gone.draft)
        assertTrue(gone.document.saved.isEmpty())
    }

    @Test
    fun theSwitchHazardIsDefinedOnTheDocument() {
        assertFalse(overlayPresetSwitchLosesEdits(OverlayDocument(selected = hud)))
        val saved = hud.copy(id = "mine", name = "Мой")
        assertFalse(overlayPresetSwitchLosesEdits(OverlayDocument(selected = saved, saved = listOf(saved))))
        assertTrue(overlayPresetSwitchLosesEdits(OverlayDocument(selected = saved, saved = listOf(saved.copy(name = "Старый")))))
        assertTrue(overlayPresetSwitchLosesEdits(OverlayDocument(selected = hud.copy(widgets = hud.widgets.take(2)))))
    }

    @Test
    fun keepingACopyAddsItAndRefusesWhenItCannot() {
        val orphan = hud.copy(widgets = hud.widgets.take(2))
        val kept = overlayKeepAsCopy(OverlayDocument(selected = orphan), "k", " Черновик ")!!
        assertEquals("Черновик", kept.saved.single().name)
        assertFalse(overlayPresetSwitchLosesEdits(kept))
        assertNull(overlayKeepAsCopy(OverlayDocument(selected = orphan), "k", "  "))
        val full = List(OVERLAY_MAX_SAVED) { hud.copy(id = "p$it", name = "P$it") }
        assertNull(overlayKeepAsCopy(OverlayDocument(selected = orphan, saved = full), "k", "x"))
    }

    @Test
    fun aCopyOfALongNameIsCutToFitInsteadOfBeingRefused() {
        val orphan = hud.copy(widgets = hud.widgets.take(2))
        val kept = overlayKeepAsCopy(OverlayDocument(selected = orphan), "k", "Я".repeat(80) + ", копия")!!
        assertEquals(64, kept.selected.name.length)
        assertTrue(validOverlayPreset(kept.selected))
    }

    // --- persistence ---

    @Test
    fun everythingTheEditorProducesSurvivesTheCodec() {
        var state = open(topRow).run(
            Select("voltage"), SetSize(OverlayWidgetSize.XL), SetKind(OverlayWidgetKind.BAR),
            SetAccent(OverlayPalette.hex(OverlayPalette.PINK)), SetLabel("Вольты"), Nudge(8f, 8f),
            AddWidget("extra", VideoMetric.DISTANCE), SetOrientation(true), SetHidden(true), SaveAs("mine", "Мой ряд"),
        )
        state = state.run(Select("soc"), SetMetric(VideoMetric.DUTY), Save)
        val decoded = OverlayCodec.decode(OverlayCodec.encode(state.document))
        assertEquals(state.document, decoded)
        assertEquals(OverlayWidgetSize.XL, decoded.selected.widgets.first { it.id == "voltage" }.size)
    }

    @Test
    fun aFullListOfEditedPresetsFitsTheDocumentLimit() {
        val preset = open().run(Select("speed"), SetSize(OverlayWidgetSize.L)).draft
        val document = OverlayDocument(saved = List(OVERLAY_MAX_SAVED) { preset.copy(id = "p$it", name = "Пресет $it") })
        assertEquals(document, OverlayCodec.decode(OverlayCodec.encode(document)))
    }

    @Test
    fun aDocumentWithoutTheSizeFieldStillDecodes() {
        val json = OverlayCodec.encode(OverlayDocument()).replace("\"size\":\"M\",", "").replace(",\"size\":\"M\"", "")
        assertNotEquals(OverlayCodec.encode(OverlayDocument()), json)
        assertEquals(OverlayDocument(), OverlayCodec.decode(json))
    }

    // --- the sample ---

    @Test
    fun theStageSampleFillsEveryMetricTheEditorOffers() {
        val snapshot = overlaySampleSnapshot()
        VideoMetric.entries.filter { it != VideoMetric.ELAPSED && it != VideoMetric.DISTANCE }.forEach {
            assertNotNull(snapshot.readings[it]?.value, it.name)
        }
        assertNotNull(OVERLAY_SAMPLE_TRIP.elapsedSeconds)
        assertNotNull(OVERLAY_SAMPLE_TRIP.distanceKm)
    }
}
