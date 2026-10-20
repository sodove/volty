package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryPreviewImagesComponentTest {
    private val image = HistoryPreviewImage(byteArrayOf(1,2,3))
    private fun key(id:String="a",revision:Long=0) = HistoryPreviewImageKey(id,revision,128,128,false,0,
        listOf(HistoryMapSegment(listOf(HistoryGpsFix(HistoryTick(1000,10,1),56.0,60.0,2.0,null)))))
    private fun TestScope.owner(entries:Int=16,bytes:Int=8*1024*1024,loader:HistoryPreviewImageLoader)=HistoryPreviewImagesComponent(
        DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)),backgroundScope,loader,2,entries,bytes)
    @Test fun sameVisibleRequestIsDeduplicatedAndCachedOnReentry()=runTest {
        val reads=mutableListOf<HistoryPreviewImageKey>();val c=owner{reads+=it;image}
        try{
            c.setVisible(listOf(key()));c.setVisible(listOf(key()));runCurrent();assertEquals(listOf(key()),reads)
            assertSame(image,c.state.value.images["a"])
            c.setVisible(emptyList());runCurrent();assertTrue(c.state.value.images.isEmpty())
            c.setVisible(listOf(key()));runCurrent();assertEquals(1,reads.size);assertSame(image,c.state.value.images["a"])
        }finally{c.close()}
    }
    @Test fun lateRetiredImageCannotPublishOrPoisonReentryCache()=runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val reads=mutableListOf<String>()
        val c=owner{request->reads+=request.rideId;if(request.rideId=="a" && reads.count{it=="a"}==1)
            withContext(NonCancellable){entered.complete(Unit);release.await()};image}
        try{
            c.setVisible(listOf(key()));runCurrent();assertTrue(entered.isCompleted)
            c.setVisible(listOf(key("b")));runCurrent();release.complete(Unit);runCurrent()
            assertEquals(setOf("b"),c.state.value.images.keys)
            c.setVisible(listOf(key()));runCurrent();assertEquals(2,reads.count{it=="a"})
        }finally{release.complete(Unit);c.close();runCurrent()}
    }
    @Test fun revisionDimensionsStyleColorAndGeometryDoNotBorrowAnOlderImage()=runTest {
        val reads=mutableListOf<HistoryPreviewImageKey>();val c=owner{reads+=it;image}
        val base=key();val changed=listOf(base,base.copy(revision=1),base.copy(width=256),base.copy(darkTheme=true),
            base.copy(styleRevision="next"),base.copy(routeColor=123),base.copy(segments=listOf(HistoryMapSegment(listOf(
                base.segments.single().fixes.single().copy(lon=61.0))))))
        try{for(request in changed){c.setVisible(listOf(request));runCurrent()};assertEquals(changed,reads)}finally{c.close()}
    }
    @Test fun lruAndEncodedByteBudgetEvictOldImages()=runTest {
        val reads=mutableListOf<String>();val c=owner(entries=2,bytes=6,loader=HistoryPreviewImageLoader{reads+=it.rideId;image})
        try{
            for(id in listOf("a","b","a","c","b")){c.setVisible(listOf(key(id)));runCurrent()}
            assertEquals(listOf("a","b","c","b"),reads)
        }finally{c.close()}
    }
    @Test fun rendererConcurrencyIsBoundedEvenWhenCancellationIsIgnored()=runTest {
        val release=CompletableDeferred<Unit>();var active=0;var peak=0;var issued=0
        val c=owner{issued++;active++;peak=maxOf(peak,active);try{withContext(NonCancellable){release.await()};image}finally{active--}}
        try{
            c.setVisible((0 until 20).map{key("r$it")});runCurrent();assertEquals(2,issued);assertEquals(2,peak)
            c.setVisible(listOf(key("new")));runCurrent();assertEquals(2,issued)
            release.complete(Unit);runCurrent();assertEquals(3,issued);assertEquals(setOf("new"),c.state.value.images.keys)
        }finally{release.complete(Unit);c.close();runCurrent()}
    }
    @Test fun offlineFailureKeepsFallbackWithoutHotRetryAndEmptyGeometryNeverLoads()=runTest {
        var issued=0;val c=owner{issued++;null}
        try{
            c.setVisible(listOf(key()));runCurrent();assertEquals(setOf("a"),c.state.value.unavailable);assertTrue(c.state.value.images.isEmpty())
            c.setVisible(listOf(key()));runCurrent();assertEquals(1,issued)
            c.setVisible(emptyList());c.setVisible(listOf(key()));runCurrent();assertEquals(2,issued)
            c.setVisible(listOf(key("empty").copy(segments=emptyList())));runCurrent();assertEquals(2,issued);assertTrue(c.state.value.loading.isEmpty())
        }finally{c.close()}
    }
    @Test fun closingOwnerFreezesStateDespiteLateReply()=runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val c=owner{withContext(NonCancellable){entered.complete(Unit);release.await()};image}
        try{
            c.setVisible(listOf(key()));runCurrent();assertTrue(entered.isCompleted);c.close();val retired=c.state.value
            release.complete(Unit);runCurrent();c.setVisible(listOf(key("b")));runCurrent();assertEquals(retired,c.state.value)
        }finally{release.complete(Unit);c.close();runCurrent()}
    }
}
