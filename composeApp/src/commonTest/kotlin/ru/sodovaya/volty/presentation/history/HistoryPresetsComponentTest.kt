package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryPresetsComponentTest {
    private val preset = HistoryMetricPreset("p","Ride","bike",listOf(HistoryPresetMetric("pack",HistorySourceKind.BATTERY,"voltage_v")))
    private open inner class Store : HistoryMetricPresetStore {
        var reads=0;var writes=0;var items=listOf(preset)
        override suspend fun read():List<HistoryMetricPreset> {reads++;return items}
        override suspend fun save(preset:HistoryMetricPreset):List<HistoryMetricPreset> {writes++;items=listOf(preset);return items}
        override suspend fun delete(id:String):List<HistoryMetricPreset> {writes++;items=items.filterNot{it.id==id};return items}
    }
    private fun TestScope.owner(store:HistoryMetricPresetStore?)=HistoryPresetsComponent(
        DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)),backgroundScope,store)
    @Test fun readsAreExplicitAndSaveDeletePublishOnlyPersistedLayouts()=runTest {
        val store=Store();val c=owner(store)
        try {
            runCurrent();assertEquals(0,store.reads);assertFalse(c.state.value.loaded)
            c.refresh();runCurrent();assertEquals(listOf(preset),c.state.value.presets);assertTrue(c.state.value.loaded)
            val new=preset.copy(name="Other");c.save(new);runCurrent();assertEquals(listOf(new),c.state.value.presets)
            c.delete("p");runCurrent();assertTrue(c.state.value.presets.isEmpty());assertEquals(2,store.writes)
        } finally{c.close()}
    }
    @Test fun failureRetainsDisplayedLayoutsAndExplicitRetryRecovers()=runTest {
        var fail=false
        val store=object:Store(){override suspend fun read():List<HistoryMetricPreset>{if(fail)error("disk");return super.read()}}
        val c=owner(store)
        try {
            c.refresh();runCurrent();fail=true;c.refresh();runCurrent()
            assertEquals(listOf(preset),c.state.value.presets);assertEquals(HistoryPresetError.READ,c.state.value.error);assertFalse(c.state.value.busy)
            fail=false;c.refresh();runCurrent();assertNull(c.state.value.error);assertEquals(2,store.reads)
        }finally{c.close()}
    }
    @Test fun heldOperationDoesNotAcceptDuplicateCommandsAndLateCloseCannotPublish()=runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val store=object:Store(){override suspend fun save(preset:HistoryMetricPreset):List<HistoryMetricPreset> = withContext(NonCancellable){
            entered.complete(Unit);release.await();super.save(preset)
        }}
        val c=owner(store)
        try {
            c.refresh();runCurrent();c.save(preset.copy(name="Late"));runCurrent();assertTrue(entered.isCompleted)
            c.delete("p");c.refresh();runCurrent();assertEquals(1,store.reads);assertEquals(0,store.writes)
            c.close();val retired=c.state.value;release.complete(Unit);runCurrent()
            assertEquals(retired,c.state.value);assertEquals(1,store.writes)
        }finally{release.complete(Unit);c.close();runCurrent()}
    }
    @Test fun unavailableStoreNeverPretendsItSavedAnything()=runTest {
        val c=owner(null)
        try{c.refresh();c.save(preset);c.delete("p");runCurrent();assertEquals(HistoryPresetsState(),c.state.value)}finally{c.close()}
    }
}
