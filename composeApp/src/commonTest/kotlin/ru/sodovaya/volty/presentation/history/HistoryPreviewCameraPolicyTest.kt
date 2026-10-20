package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*
import kotlin.math.*
import kotlin.test.*

class HistoryPreviewCameraPolicyTest {
    private fun key(points:List<Pair<Double,Double>>,width:Int=256,height:Int=256)=HistoryPreviewImageKey("a",0,width,height,false,0,
        listOf(HistoryMapSegment(points.mapIndexed{index,p->HistoryGpsFix(HistoryTick(index.toLong(),index.toLong(),1),p.first,p.second,2.0,null)})))
    @Test fun fitKeepsActualRouteInsidePaddedViewportAndResourceBounds() {
        val k=key(listOf(56.0 to 60.0,56.02 to 60.03));val camera=assertNotNull(historyPreviewCamera(k))
        val scale=512.0*2.0.pow(camera.zoom)
        fun y(lat:Double)=(1.0-ln(tan(PI/4+lat*PI/360))/PI)/2
        for(fix in k.segments.single().fixes){
            val x=k.width/2.0+(fix.lon-camera.longitude)/360*scale
            val py=k.height/2.0+(y(fix.lat)-y(camera.latitude))*scale
            assertTrue(x in 15.99..240.01);assertTrue(py in 15.99..240.01)
            assertTrue(fix.lat in camera.south..camera.north);assertTrue(fix.lon in camera.west..camera.east)
        }
    }
    @Test fun stationaryFixHasStreetContextInsteadOfUnlimitedZoom() {
        val c=assertNotNull(historyPreviewCamera(key(listOf(56.0 to 60.0))))
        assertEquals(56.0,c.latitude,0.000001);assertEquals(60.0,c.longitude,0.000001)
        assertTrue(c.zoom in 10.0..18.0);assertTrue(c.north>c.south);assertTrue(c.east>c.west)
    }
    @Test fun portraitAspectFitsBothAxesAndUsesWholeTileZoomCoverage() {
        val c=assertNotNull(historyPreviewCamera(key(listOf(56.0 to 60.0,56.01 to 60.04),128,256)))
        assertTrue(c.zoom.isFinite());assertEquals(floor(c.zoom),c.minTileZoom);assertEquals(ceil(c.zoom),c.maxTileZoom)
        assertTrue(c.west<60.0 && c.east>60.04)
    }
    @Test fun noGpsOrTinyViewportNeverRequestsNativeMapResources() {
        assertNull(historyPreviewCamera(key(emptyList())))
        assertNull(historyPreviewCamera(key(listOf(56.0 to 60.0),20,100)))
    }
    @Test fun antimeridianRouteKeepsTheAlreadySupportedCanvasFallback() {
        assertNull(historyPreviewCamera(key(listOf(10.0 to 179.9,10.0 to -179.9))))
    }
    @Test fun unsupportedPolarSnapshotKeepsActualCoordinatesInCanvas() {
        assertNull(historyPreviewCamera(key(listOf(89.0 to 60.0))))
    }
    @Test fun resourcesIncludeNeighborTileMarginNotOnlyTheRecordedLine() {
        val c=assertNotNull(historyPreviewCamera(key(listOf(0.0 to 0.0,0.001 to 0.001))))
        val tileSpan=360.0/2.0.pow(c.minTileZoom)
        assertTrue(c.east-c.west>2*tileSpan)
        assertTrue(c.minTileZoom>=0 && c.maxTileZoom<=18)
    }
}
