package ru.sodovaya.volty.presentation.history

data class HistoryPreviewData(val segments: List<HistoryMapSegment>, val partial: Boolean)

/** Shape approximation retains actual fixes and each retained segment's ends.
 * If the endpoint budget cannot represent every segment, keep an explicit partial
 * prefix rather than join segments across a gap. No timestamp/coordinate rewrite.
 */
fun reduceHistoryPreview(segments: List<HistoryMapSegment>, maxPoints: Int = 128): HistoryPreviewData {
    require(maxPoints in 2..4096)
    val input = segments.filter { it.fixes.isNotEmpty() }
    val retained = mutableListOf<HistoryMapSegment>()
    var count = 0
    for (segment in input) {
        val endpoints = minOf(2, segment.fixes.size)
        if (count + endpoints > maxPoints) break
        retained += segment.copy(fixes = segment.fixes.toList()); count += endpoints
    }
    val partial = retained.size < input.size
    if (retained.sumOf { it.fixes.size } <= maxPoints) return HistoryPreviewData(retained.toList(), partial)
    val coordinates = historyPreviewPaths(retained, 1000.0, 1000.0, 0.0)
    check(coordinates.size == retained.size)
    val selected = retained.map { segment -> mutableSetOf(0, segment.fixes.lastIndex) }
    val candidates = mutableListOf<PreviewCandidate>()
    fun candidate(segment: Int, left: Int, right: Int): PreviewCandidate? {
        val points = coordinates[segment]; val a = points[left]; val b = points[right]
        val dx = b.x - a.x; val dy = b.y - a.y; val squaredLength = dx * dx + dy * dy
        var bestIndex = -1; var bestScore = MIN_PREVIEW_DEVIATION_SQUARED
        for (index in left + 1 until right) {
            val point = points[index]
            val fraction = if (squaredLength == 0.0) 0.0 else
                (((point.x - a.x) * dx + (point.y - a.y) * dy) / squaredLength).coerceIn(0.0, 1.0)
            val x = point.x - (a.x + fraction * dx); val y = point.y - (a.y + fraction * dy)
            val score = x * x + y * y
            if (score > bestScore) { bestScore = score; bestIndex = index }
        }
        return if (bestIndex < 0) null else PreviewCandidate(segment, left, right, bestIndex, bestScore)
    }
    retained.forEachIndexed { index, segment -> candidate(index, 0, segment.fixes.lastIndex)?.let(candidates::add) }
    while (count < maxPoints && candidates.isNotEmpty()) {
        val index = candidates.indices.maxBy { candidates[it].score }
        val next = candidates.removeAt(index)
        selected[next.segment] += next.index; count++
        candidate(next.segment, next.left, next.index)?.let(candidates::add)
        candidate(next.segment, next.index, next.right)?.let(candidates::add)
    }
    return HistoryPreviewData(retained.mapIndexed { index, segment ->
        HistoryMapSegment(selected[index].sorted().map { segment.fixes[it] })
    }, partial)
}

private data class PreviewCandidate(val segment: Int, val left: Int, val right: Int, val index: Int, val score: Double)
// Suppress floating point noise on a normalized 1000-unit preview frame.
private const val MIN_PREVIEW_DEVIATION_SQUARED = 1e-12
