package com.example.ex30telemetry.sync

import com.example.ex30telemetry.trip.TrackRecorder
import com.example.ex30telemetry.trip.Trip
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream

class TripOutboxTest {

    private lateinit var dir: File
    private lateinit var outbox: TripOutbox
    private lateinit var tracks: TrackRecorder

    /** Drive'a ne gittigi: ad -> gonderilen dosya. */
    private val sent = LinkedHashMap<String, TripOutbox.TripFile>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("outbox").toFile()
        outbox = TripOutbox(File(dir, "outbox"))
        tracks = TrackRecorder(dir)
        sent.clear()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun trip(epoch: Long) = Trip(
        startEpoch = epoch, endEpoch = epoch + 600_000, durationSec = 600, distanceKm = 7.5,
        energyKwh = 1.2, regenKwh = 0.3, socStart = 60.0, socEnd = 58.0, rangeStart = 250.0,
        rangeEnd = 242.0, avgSpeedKmh = 45.0, maxSpeedKmh = 82.0, tempStart = 18.0, tempAvg = 18.5,
        altGainM = 40.0, altLossM = 12.0, potentialKwh = 0.2, consumptionKwh100 = 16.0,
        rangeBiasFactor = 1.07,
    )

    private fun gunzip(gz: ByteArray) = GZIPInputStream(gz.inputStream()).bufferedReader().readText()

    private val okSender = TripOutbox.Sender { f ->
        sent[f.name] = f
        null
    }

    private fun writeTrack(epoch: Long) {
        tracks.begin(epoch)
        tracks.append(1L, 41.0, 29.0, 100.0, 3.0, null, null, 50.0, 10.0, 60.0, 0.0)
        tracks.finish(epoch)
    }

    @Test
    fun `ozet ve iz gider, kuyruk bosalir`() {
        val t = trip(1_790_000_000_000L)
        writeTrack(t.startEpoch)
        outbox.enqueue(t.startEpoch)

        val r = outbox.drain({ t.takeIf { e -> e.startEpoch == it } }, { tracks.trackFile(it) }, okSender)

        assertEquals(1, r.uploaded)
        assertNull(r.error)
        assertTrue(outbox.pending().isEmpty())
        assertEquals(listOf("trip-1790000000000.json", "trip-1790000000000.csv.gz"), sent.keys.toList())

        // Ozet DUZ JSON gidiyor (Drive'da okunabilsin), iz gzip'li.
        val summary = sent.getValue("trip-1790000000000.json")
        assertEquals(TripOutbox.KIND_SUMMARY, summary.kind)
        assertEquals(TripOutbox.MIME_JSON, summary.mime)
        assertEquals(1_790_000_000_000L, JSONObject(String(summary.bytes)).getLong("startEpoch"))

        val track = sent.getValue("trip-1790000000000.csv.gz")
        assertEquals(TripOutbox.KIND_TRACK, track.kind)
        assertEquals(1_790_000_000_000L, track.startEpoch)
        assertTrue(gunzip(track.bytes).startsWith("# ex30-track;1;1790000000000\n"))
    }

    @Test
    fun `izi olmayan yolculukta yalnizca ozet gider`() {
        val t = trip(1_790_000_000_000L)
        outbox.enqueue(t.startEpoch)
        val r = outbox.drain({ t }, { tracks.trackFile(it) }, okSender)
        assertEquals(1, r.uploaded)
        assertEquals(listOf("trip-1790000000000.json"), sent.keys.toList())
    }

    @Test
    fun `gecici hata turu durdurur, kuyruk korunur, siralama eskiden yeniye`() {
        listOf(3L, 1L, 2L).forEach { outbox.enqueue(1_790_000_000_000L + it) }
        val seen = mutableListOf<String>()
        val flaky = TripOutbox.Sender { f ->
            seen += f.name
            if (f.name.contains("0002")) "Unable to resolve host" else null
        }
        val r = outbox.drain({ trip(it) }, { null }, flaky)

        assertEquals(1, r.uploaded)
        assertEquals("Unable to resolve host", r.error)
        assertEquals(listOf(1_790_000_000_002L, 1_790_000_000_003L), outbox.pending())
        assertEquals(listOf("trip-1790000000001.json", "trip-1790000000002.json"), seen)
    }

    @Test
    fun `kalici red kuyrugu tikamaz`() {
        listOf(1L, 2L).forEach { outbox.enqueue(1_790_000_000_000L + it) }
        val sender = TripOutbox.Sender { f ->
            if (f.name.contains("0001")) "${TripOutbox.PERMANENT_PREFIX} Drive: Invalid value" else null
        }
        val r = outbox.drain({ trip(it) }, { null }, sender)

        assertEquals(1, r.uploaded)
        assertEquals(1, r.rejected)
        assertNull(r.error)
        assertTrue(outbox.pending().isEmpty())
        assertEquals(1, outbox.rejectedCount())
    }

    @Test
    fun `bagli olmayan hesap gecici sayilir, kuyrugu RED'e cevirmez`() {
        outbox.enqueue(1_790_000_000_001L)
        val r = outbox.drain({ trip(it) }, { null }, { "Google hesabı bağlı değil" })
        assertEquals("Google hesabı bağlı değil", r.error)
        assertEquals(0, outbox.rejectedCount())
        assertEquals(1, outbox.pending().size)
    }

    @Test
    fun `aractan silinmis yolculuk kuyruktan duser`() {
        outbox.enqueue(1_790_000_000_001L)
        val r = outbox.drain({ null }, { null }, okSender)
        assertEquals(1, r.dropped)
        assertTrue(outbox.pending().isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `ayni yolculuk iki kez kuyruga girse de tek isaret`() {
        outbox.enqueue(5L)
        outbox.enqueue(5L)
        assertEquals(listOf(5L), outbox.pending())
    }

    @Test
    fun `bozuk iz dosyasi kalici reddedilir, gonderilmez`() {
        val epoch = 1_790_000_000_000L
        tracks.trackFile(epoch).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        outbox.enqueue(epoch)
        val r = outbox.drain({ trip(it) }, { tracks.trackFile(it) }, okSender)
        assertEquals(1, r.rejected)
        assertEquals(listOf("trip-$epoch.json"), sent.keys.toList())
    }
}
