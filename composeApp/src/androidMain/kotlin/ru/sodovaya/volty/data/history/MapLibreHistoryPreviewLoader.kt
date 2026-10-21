package ru.sodovaya.volty.data.history

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.*
import org.maplibre.android.snapshotter.MapSnapshotter
import ru.sodovaya.volty.presentation.history.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One snapshotter at a time, shared native resource cache, no per-row MapView.
 * Snapshot-ready alone is insufficient: failed tiles can count as complete in
 * MapLibre. A fresh temporary region must finish precisely with NO download
 * errors before its resources can back an image. Unsupported/failed views fall
 * back to the caller's Canvas route. Attribution/logo defaults are preserved.
 */
class MapLibreHistoryPreviewLoader(context: Context, provider: ru.sodovaya.volty.data.db.VoltyDatabaseProvider,
    private val derived: HistoryDerivedFiles = HistoryDerivedFiles(
        HistoryPreviewFileCache(File(context.applicationContext.cacheDir, "history-preview-images-v1")),
        File(context.applicationContext.cacheDir, "history-exports-v1"), provider)) : HistoryPreviewImageLoader {
    private val app = context.applicationContext
    private val files = derived.previewFiles
    private val serial = derived.gate
    private val database = provider.database
    private val mainHandler = Handler(Looper.getMainLooper())
    private var needsCleanup = true
    private val styles = mutableMapOf<Boolean,PreparedStyle>()
    private val styleServer=HistoryPreviewStyleServer()
    // Retraction: even canonical file:/// styles stalled offline preflight at
    // 0/1 on API34/SDK13.0.2. Like the existing PMTiles transport, this serves
    // only bundled public style bytes over loopback; map tiles remain OFM URLs.

    override suspend fun reconcileDeletedRides(): Boolean = serial.withLock {
        withContext(Dispatchers.IO) { derived.reconcileLocked() }
    }

    override suspend fun load(key: HistoryPreviewImageKey): HistoryPreviewImage? = try { serial.withLock {
        if (!withContext(Dispatchers.IO) { derived.reconcileLocked() && exists(key.rideId) }) return@withLock null
        val camera = historyPreviewCamera(key) ?: return@withLock null
        val style = withContext(Dispatchers.IO) { prepareStyle(key.darkTheme) } ?: return@withLock null
        val digest = digest(key,style.hash)
        val cached = withContext(Dispatchers.IO) {
            try { files.read(digest)?.takeIf { validPng(it,key) } } catch (_: IOException) { null }
        }
        if (cached != null) return@withLock if (withContext(Dispatchers.IO) { exists(key.rideId) }) HistoryPreviewImage(cached) else null
        val styleUri=withContext(Dispatchers.IO) { styleServer.register(style.hash,style.json) }
        var phase="cleanup"
        val image = withContext(Dispatchers.Main.immediate) {
            val manager = OfflineManager.getInstance(app)
            var region: OfflineRegion? = null
            try {
                withTimeoutOrNull(8_000) {
                    if (needsCleanup) needsCleanup = !removeOrphans(manager)
                    phase="creation"
                    val definition = OfflineTilePyramidRegionDefinition(styleUri,
                        LatLngBounds.from(camera.north,camera.east,camera.south,camera.west),camera.minTileZoom,camera.maxTileZoom,1f,false)
                    create(manager,definition,digest) { region = it }
                    phase="resources"
                    ready(checkNotNull(region))
                    phase="snapshot"
                    snapshot(key,camera,styleUri)
                }
            } finally {
                region?.let { owned ->
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (withTimeoutOrNull(1_500) { delete(owned) } != true) needsCleanup = true
                    }
                }
            }
        } ?: run { Log.w(TAG,"Preview background timeout at $phase"); return@withLock null }
        currentCoroutineContext().ensureActive()
        val retained = withContext(Dispatchers.IO) {
            if (!exists(key.rideId)) return@withContext false
            try { files.write(digest,image.copyBytes()) } catch (_: IOException) { Log.w(TAG,"Cannot cache history preview") }
            true
        }
        if (retained) image else null
    } } catch (cancel: CancellationException) { throw cancel }
    catch (failure: IOException) { Log.w(TAG,"Preview background unavailable: ${failure.message}"); null }

    private fun exists(rideId: String) = database.rideHistoryV2Queries.getRide(rideId).executeAsOneOrNull() != null

    private suspend fun create(manager: OfflineManager,definition: OfflineTilePyramidRegionDefinition,digest: String,
        acquired: (OfflineRegion) -> Unit) = suspendCancellableCoroutine<Unit> { cont ->
        manager.createOfflineRegion(definition,(PREFIX+digest).toByteArray(),object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(region: OfflineRegion) {
                debug("Preview region created")
                if (cont.isActive) { acquired(region); cont.resume(Unit) } else discardLate(region)
            }
            override fun onError(error: String) { if (cont.isActive) cont.resumeWithException(IOException("Cannot prepare preview resources")) }
        })
    }

    private suspend fun ready(region: OfflineRegion) = suspendCancellableCoroutine<Unit> { cont ->
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (!cont.isActive) return
                debug("Preview resources ${status.completedResourceCount}/${status.requiredResourceCount}, precise=${status.isRequiredResourceCountPrecise}, bytes=${status.completedResourceSize}")
                if (status.completedResourceSize > 4L*1024*1024 || status.requiredResourceCount > 256) {
                    cont.resumeWithException(IOException("Preview resource budget exceeded (${status.completedResourceSize} bytes / ${status.requiredResourceCount} resources)"))
                } else if (status.isRequiredResourceCountPrecise && status.requiredResourceCount > 0 && status.isComplete) {
                    Log.d(TAG,"Preview resources ready: ${status.completedResourceSize} bytes / ${status.requiredResourceCount} resources")
                    cont.resume(Unit)
                }
            }
            override fun onError(error: OfflineRegionError) {
                if (cont.isActive) cont.resumeWithException(IOException("Preview resource download failed"))
            }
            override fun mapboxTileCountLimitExceeded(limit: Long) {
                if (cont.isActive) cont.resumeWithException(IOException("Preview resource tile limit exceeded"))
            }
        })
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        if (ru.sodovaya.volty.BuildConfig.DEBUG) region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) { if (status != null) debug("Preview initial status ${status.completedResourceCount}/${status.requiredResourceCount}, precise=${status.isRequiredResourceCountPrecise}") }
            override fun onError(error: String?) { debug("Preview status read failed") }
        })
    }

    private suspend fun snapshot(key: HistoryPreviewImageKey,camera: HistoryPreviewCamera,style: String): HistoryPreviewImage {
        val (bitmap,paths) = suspendCancellableCoroutine<Pair<Bitmap,List<List<PointF>>>> { cont ->
            val shot = MapSnapshotter(app,MapSnapshotter.Options(key.width,key.height).withStyle(style)
                .withCameraPosition(CameraPosition.Builder().target(LatLng(camera.latitude,camera.longitude)).zoom(camera.zoom).build()))
            cont.invokeOnCancellation { mainHandler.post { shot.cancel() } }
            shot.start({ snapshot ->
                if (cont.isActive) {
                    val points = key.segments.map { segment -> segment.fixes.map { snapshot.pixelForLatLng(LatLng(it.lat,it.lon)) } }
                    if (points.flatten().any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..key.width.toFloat() || it.y !in 0f..key.height.toFloat() })
                        cont.resumeWithException(IOException("Invalid preview projection"))
                    else cont.resume(snapshot.bitmap to points)
                }
            }, { if (cont.isActive) cont.resumeWithException(IOException("Preview snapshot failed")) })
        }
        return withContext(Dispatchers.Default) {
            check(bitmap.width == key.width && bitmap.height == key.height)
            val drawn = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888,true))
            try {
                val canvas = Canvas(drawn)
                val stroke = 2f*key.width/86f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=key.routeColor;strokeWidth=stroke;strokeCap=Paint.Cap.ROUND;this.style=Paint.Style.STROKE }
                for (points in paths) {
                    if (points.isEmpty()) continue
                    val path=Path();path.moveTo(points.first().x,points.first().y)
                    points.drop(1).forEach { path.lineTo(it.x,it.y) };canvas.drawPath(path,paint)
                    paint.style=Paint.Style.FILL
                    canvas.drawCircle(points.first().x,points.first().y,stroke,paint);canvas.drawCircle(points.last().x,points.last().y,stroke,paint)
                    paint.style=Paint.Style.STROKE
                }
                val output=ByteArrayOutputStream();check(drawn.compress(Bitmap.CompressFormat.PNG,100,output))
                HistoryPreviewImage(output.toByteArray())
            } finally { drawn.recycle() }
        }
    }

    private suspend fun removeOrphans(manager: OfflineManager): Boolean {
        val regions = suspendCancellableCoroutine<Array<OfflineRegion>> { cont ->
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) { if (cont.isActive) cont.resume(offlineRegions ?: emptyArray()) }
                override fun onError(error: String) { if (cont.isActive) cont.resumeWithException(IOException("Cannot inspect preview resources")) }
            })
        }
        var removed = true
        for (region in regions) if (region.metadata.toString(Charsets.UTF_8).startsWith(PREFIX)) {
            if (!delete(region)) removed = false
        }
        return removed
    }
    private suspend fun delete(region: OfflineRegion) = suspendCancellableCoroutine<Boolean> { cont ->
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { if (cont.isActive) cont.resume(true) }
            override fun onError(error: String) {
                needsCleanup=true
                if (cont.isActive) cont.resume(false)
            }
        })
    }
    private fun discardLate(region: OfflineRegion) {
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = Unit
            override fun onError(error: String) { needsCleanup=true }
        })
    }
    private fun validPng(bytes: ByteArray,key: HistoryPreviewImageKey): Boolean {
        val info=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,info)
        return info.outMimeType == "image/png" && info.outWidth == key.width && info.outHeight == key.height
    }
    private fun prepareStyle(dark: Boolean): PreparedStyle? {
        styles[dark]?.let { return it }
        val theme=if(dark) "dark" else "bright"
        val template=app.assets.open("offline-map-styles/openfreemap-$theme.json").bufferedReader().use { it.readText() }
        val json=historyPreviewStyle(template) ?: return null
        val bytes=json.toByteArray()
        return PreparedStyle(bytes,hash(bytes)).also { styles[dark]=it }
    }
    private fun digest(key: HistoryPreviewImageKey,styleHash: String): String {
        val output=ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            fun text(value:String) { val bytes=value.toByteArray();data.writeInt(bytes.size);data.write(bytes) }
            data.writeInt(1);text(key.rideId);data.writeLong(key.revision);data.writeInt(key.width);data.writeInt(key.height)
            data.writeBoolean(key.darkTheme);data.writeInt(key.routeColor);text(key.styleRevision);text(styleHash)
            data.writeInt(key.segments.size)
            for(segment in key.segments){data.writeInt(segment.fixes.size);for(fix in segment.fixes){data.writeLong(fix.lat.toBits());data.writeLong(fix.lon.toBits())}}
        }
        return hash(output.toByteArray())
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private data class PreparedStyle(val json: ByteArray,val hash: String)
    private companion object {
        const val PREFIX="volty-history-preview-v1:"
        const val TAG="HistoryPreview"
        fun debug(message: String) { if (ru.sodovaya.volty.BuildConfig.DEBUG) Log.d(TAG,message) }
    }
}
