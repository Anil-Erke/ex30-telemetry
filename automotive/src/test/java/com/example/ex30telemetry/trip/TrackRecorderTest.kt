package com.example.ex30telemetry.trip

import com.example.ex30telemetry.Constants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream

class TrackRecorderTest {

    private lateinit var dir: File
    private lateinit var rec: TrackRecorder

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("track").toFile()
        rec = TrackRecorder(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun point(t: Long, dist: Double) = rec.append(
        tMs = t, lat = 41.0123456, lon = 28.9765432, altM = 63.44, hAccM = 3.8, vAccM = null,
        gpsKmh = 47.23, kmh = 48.0, kw = -12.346, soc = 61.0, distM = dist,
    )

    private fun gunzip(f: File): String =
        GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() }

    @Test
    fun `kapanan iz baslik ve satirlarla gzip olarak saklanir`() {
        rec.begin(1000L)
        point(1_790_000_000_000L, 0.0)
        point(1_790_000_001_000L, 12.5)

        val out = rec.finish(1000L)!!

        assertEquals("trip-1000.csv.gz", out.name)
        val lines = gunzip(out).trimEnd().lines()
        assertEquals("# ex30-track;1;1000", lines[0])
        assertEquals(TrackRecorder.COLUMNS, lines[1])
        assertEquals("1790000001000;41.012346;28.976543;63.4;3.8;;47.2;48.0;-12.35;61.00;12.5", lines[3])
        assertEquals(4, lines.size)
        assertEquals(2, rec.rows)
        assertFalse(File(dir, TrackRecorder.LIVE_NAME).exists())
    }

    @Test
    fun `ondalik ayraci her zaman nokta`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            val row = TrackRecorder.row(1L, 1.5, 2.5, null, null, null, null, null, null, null, 3.25)
            assertEquals("1;1.500000;2.500000;;;;;;;;3.3\n", row)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `atilan yolculugun izi silinir`() {
        rec.begin(1000L)
        point(1L, 0.0)
        rec.discard()

        assertFalse(File(dir, TrackRecorder.LIVE_NAME).exists())
        assertTrue(rec.savedTracks().isEmpty())
    }

    @Test
    fun `baska yolculuga ait yarim iz kurtarilmaz`() {
        rec.begin(1000L)
        point(1L, 0.0)

        // Yeni surec: kurtarma baska bir startEpoch ile geliyor.
        val fresh = TrackRecorder(dir)
        assertNull(fresh.finish(2000L))
        assertFalse(File(dir, TrackRecorder.LIVE_NAME).exists())
        assertTrue(fresh.savedTracks().isEmpty())
    }

    @Test
    fun `surec olunce yarim iz yeni ornekle kurtarilir`() {
        rec.begin(1000L)
        repeat(7) { point(it.toLong(), it * 10.0) }
        // Surec oldu: writer kapanmadi. FLUSH_EVERY (5) satir diske inmis olmali.
        val fresh = TrackRecorder(dir)
        val out = fresh.finish(1000L)!!

        val data = gunzip(out).trimEnd().lines().drop(2)
        assertTrue("en az 5 satir kurtarilmali, gelen ${data.size}", data.size >= 5)
    }

    @Test
    fun `en eski izler MAX_TRACKS asilinca silinir`() {
        val start = 1_000_000L
        repeat(Constants.MAX_TRACKS + 2) { i ->
            rec.begin(start + i)
            point(1L, 0.0)
            rec.finish(start + i)
        }
        val saved = rec.savedTracks()
        assertEquals(Constants.MAX_TRACKS, saved.size)
        assertEquals("trip-${start + Constants.MAX_TRACKS + 1}.csv.gz", saved.first().name)
        assertFalse(rec.trackFile(start).exists())
        assertFalse(rec.trackFile(start + 1).exists())
    }
}
