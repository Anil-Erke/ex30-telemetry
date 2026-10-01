package com.example.ex30telemetry.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Surface
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.example.ex30telemetry.R
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * LiveScreen'in NavigationTemplate'inden gelen Surface'e cizim yapar.
 *
 * Iki kural baglayici (prompt.md §5.1, §5.4):
 *  - Once tum canvas CLEAR edilir, arka plan YALNIZCA onVisibleAreaChanged ile
 *    gelen alana cizilir. Aksi halde host'un kendi dugmeleri ortulur ve
 *    "dugme gorunmuyor" hatasi olarak geri doner.
 *  - Bu yuzeye dokunma olayi HIC gelmiyor; buraya dugme cizilmez. Butun
 *    aksiyonlar ActionStrip / MapActionStrip uzerinde.
 */
class LiveRenderer(
    /** Dizgeler ve ondalik ayraci aracin dilinden geliyor (values/ + values-tr/). */
    private val context: Context,
    private val stateProvider: () -> LiveState,
    /** Araç gece modunda mi — her cizimde okunuyor, host anlik degistirebiliyor. */
    private val darkModeProvider: () -> Boolean = { true },
) : SurfaceCallback {

    private var surface: Surface? = null
    private var visibleArea: Rect? = null

    /** Aktif palet; [draw] basinda gece moduna gore guncelleniyor. */
    private var pal: Palette = Palette.DARK

    private val bgPaint = Paint()
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint().apply { strokeWidth = 1.5f }
    // Izgara etiketi cizgiden acik olmali: ikisi ayni renk olunca etiket
    // gorunmuyordu.
    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // --- SurfaceCallback ---

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
        render()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        // Host action strip'i ~10 sn sonra gizleyip dokununca geri getiriyor;
        // gorunur alan bu yuzden tekrar tekrar degisir, normaldir (§5.2).
        this.visibleArea = visibleArea
        render()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (visibleArea == null) {
            visibleArea = stableArea
            render()
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
    }

    // --- Cizim ---

    init {
        // Paint'lerin varsayilan rengi siyah; ilk cizimden once paleti uygula.
        forcePalette(pal)
    }

    private fun applyPalette(p: Palette) {
        if (p == pal) return
        forcePalette(p)
    }

    private fun forcePalette(p: Palette) {
        pal = p
        bgPaint.color = p.bg
        panelPaint.color = p.panel
        gridPaint.color = p.grid
        gridTextPaint.color = p.gridText
    }

    fun render() {
        val s = surface ?: return
        if (!s.isValid) return
        val canvas = try {
            s.lockCanvas(null)
        } catch (_: Exception) {
            return
        }
        try {
            draw(canvas, stateProvider())
        } finally {
            runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    private fun draw(canvas: Canvas, st: LiveState) {
        applyPalette(Palette.of(darkModeProvider()))

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val area = visibleArea ?: Rect(0, 0, canvas.width, canvas.height)
        if (area.width() <= 0 || area.height() <= 0) return
        canvas.drawRect(area, bgPaint)

        // Olcek birimi: hem 800x1280 dikey hem 1024x768 yatay ekranda makul
        // punto veren tek sayi. Sabit piksel yazmak yatayda tasmaya yol aciyordu.
        val u = sqrt((area.width().toDouble() * area.height())).toFloat() / 28f
        val pad = u * 0.7f
        val left = area.left + pad
        val right = area.right - pad
        var y = area.top + pad

        // BASLIK SATIRI VE "dugmeler icin dokun" IPUCU KALDIRILDI.
        //
        // Ikisi birlikte ~2,4u yer kapliyordu ve ikisi de her karede ayni seyi
        // soyluyordu: uygulamanin adi (zaten arac menusunden aciliyor) ve bir
        // kez ogrenildikten sonra gereksiz bir kullanim ipucu. Kazanilan yer
        // grafiklere verildi — grafik alani ~%20 buyudu.
        //
        // Yolculuk durumu rozeti KAYBOLMADI: tuketim panelinin bos duran sag
        // alt kosesine tasindi (bkz. drawConsumption).
        y = drawConsumption(canvas, st, left, right, y, u)

        val stripH = u * 2.9f
        val barH = u * 2.6f
        val chartsTop = y + u * 0.4f
        val barTop = area.bottom - pad - stripH - u * 0.4f - barH
        val chartsBottom = barTop - u * 0.4f
        if (chartsBottom > chartsTop + u) {
            drawCharts(canvas, st, left, right, chartsTop, chartsBottom, u, area.width() > area.height())
        }
        drawSpeedBar(canvas, st, left, right, barTop, barH, u)

        val stripTop = area.bottom - pad - stripH
        drawBottomStrip(canvas, st, left, right, stripTop, stripH, u)

        // A4 seridi ALT SERIDIN uzerini kapliyor.
        //
        // Eskiden sag ust kosedeydi; baslik satiri kaldirilinca orasi tuketim
        // paneli oldu ve serit "yolculuk ortalamasi" sayisinin uzerine binip
        // ikisini de okunmaz yapti. Alt serit (kalan enerji, kapasite, dis
        // sicaklik, mesafe, sure) ekrandaki en az zaman-kritik bilgi: bir 0-100
        // olcumunun yakalandigi saniyede kimse batarya kapasitesine bakmiyor.
        // Bir paneli tam olarak kaplamak, iki paneli yarim kapatmaktan okunakli.
        drawBanner(canvas, st, left, right, stripTop, stripH, u)
    }

    /** Ust blok: kayan pencere tuketimi (buyuk) + yolculuk ortalamasi. */
    private fun drawConsumption(
        canvas: Canvas, st: LiveState, left: Float, right: Float, top: Float, u: Float,
    ): Float {
        val height = u * 4.2f
        canvas.drawRoundRect(
            RectF(left, top, right, top + height), u * 0.3f, u * 0.3f, panelPaint,
        )

        val innerL = left + u * 0.7f
        val big = st.consumptionWindow
        boldPaint.color = if (big != null) pal.text else pal.muted
        boldPaint.textSize = u * 2.5f
        val bigText = big?.let { fmt(it, 1) } ?: "—"
        canvas.drawText(bigText, innerL, top + u * 2.6f, boldPaint)

        val bigW = boldPaint.measureText(bigText)
        textPaint.color = pal.muted
        textPaint.textSize = u * 0.8f
        canvas.drawText(
            context.getString(R.string.live_consumption_unit),
            innerL + bigW + u * 0.4f, top + u * 2.6f, textPaint,
        )

        textPaint.textSize = u * 0.62f
        val subtitle = st.consumptionPending ?: windowLabel(st)
        canvas.drawText(subtitle, innerL, top + u * 3.5f, textPaint)

        // Yolculuk durumu rozeti. Eskiden ekranin en ustunde ayri bir baslik
        // satirindaydi; o satir grafiklere yer acmak icin kaldirildi ve rozet
        // panelin bos duran sag alt kosesine indi. Bilgi duruyor, satir gitti.
        val badge = stateLabel(st.tripState)
        boldPaint.color = if (st.tripState == "AKTİF") pal.good else pal.muted
        boldPaint.textSize = u * 0.62f
        canvas.drawText(
            badge, right - u * 0.7f - boldPaint.measureText(badge), top + u * 3.5f, boldPaint,
        )

        // Sag tarafta yolculuk ortalamasi
        val trip = st.consumptionTrip
        boldPaint.color = if (trip != null) pal.accent else pal.muted
        boldPaint.textSize = u * 1.25f
        val tripText = trip?.let { fmt(it, 1) } ?: "—"
        val tripW = boldPaint.measureText(tripText)
        canvas.drawText(tripText, right - u * 0.7f - tripW, top + u * 1.8f, boldPaint)

        textPaint.color = pal.muted
        textPaint.textSize = u * 0.62f
        val lbl = context.getString(R.string.live_trip_avg)
        canvas.drawText(lbl, right - u * 0.7f - textPaint.measureText(lbl), top + u * 2.7f, textPaint)

        return top + height
    }

    /** Irtifa ve hiz sparkline'leri. Yatay ekranda yan yana, dikeyde alt alta. */
    private fun drawCharts(
        canvas: Canvas, st: LiveState,
        left: Float, right: Float, top: Float, bottom: Float,
        u: Float, sideBySide: Boolean,
    ) {
        val gap = u * 0.4f
        // A5 ve azami hiz, ait olduklari grafigin alt satirinda; ayri bir panel
        // acmak dikey ekranda yer birakmiyor.
        val altFooter = altitudeFooter(st)
        val speedFooter = st.maxSpeedKmh?.let {
            context.getString(R.string.live_max_speed, fmt(it, 0))
        }

        // Baslikta pencere de yaziyor: grafikler artik butun yolculugu degil
        // secilen son N kilometreyi cizdigi icin, x ekseninin ne kadar oldugunu
        // soylemeyen bir grafik yaniltici olurdu.
        val altTitle = "${context.getString(R.string.live_altitude)} · ${windowLabel(st)}"
        val speedTitle = "${context.getString(R.string.live_speed)} · ${windowLabel(st)}"

        if (sideBySide) {
            val w = (right - left - gap) / 2f
            drawSpark(canvas, st.altitudeSeries, altTitle, "m", pal.earth,
                RectF(left, top, left + w, bottom), u, altFooter, markMin = true)
            drawSpark(canvas, st.speedSeries, speedTitle, "km/h", pal.good,
                RectF(left + w + gap, top, right, bottom), u, speedFooter, zeroBased = true)
        } else {
            val h = (bottom - top - gap) / 2f
            drawSpark(canvas, st.altitudeSeries, altTitle, "m", pal.earth,
                RectF(left, top, right, top + h), u, altFooter, markMin = true)
            drawSpark(canvas, st.speedSeries, speedTitle, "km/h", pal.good,
                RectF(left, top + h + gap, right, bottom), u, speedFooter, zeroBased = true)
        }
    }

    /** "son 20 km" — pencereyi gosteren tek dizge; uc yerde de ayni okunsun. */
    private fun windowLabel(st: LiveState): String =
        context.getString(R.string.live_window, st.windowKm.toInt())

    /** A5 ozeti: tirmanis/inis ve tirmanisa harcanan potansiyel enerji. */
    private fun altitudeFooter(st: LiveState): String? {
        val gain = st.altGainM ?: return null
        val loss = st.altLossM ?: return null
        val parts = StringBuilder("+${fmt(gain, 0)} / −${fmt(loss, 0)} m")
        // GPS coktugunde irtifa da cope gidiyor: yukseklik YALNIZCA konumdan
        // geliyor, hiz integrali yukseklik bilmiyor. 2026-09-19'da 90 km'lik bir
        // yolculukta 39 m tirmanis yazmisti — fiziksel olarak imkansiz. Sayiyi
        // gostermek yerine neden guvenilmez oldugunu soylemek dogrusu.
        if (st.gpsHealthy == false) {
            return "$parts  ${context.getString(R.string.live_alt_gps_bad)}"
        }
        st.climbKwh?.let {
            parts.append(" ").append(context.getString(R.string.live_alt_climb, fmt(it, 2)))
        }
        st.regenKwh?.let {
            parts.append(" ").append(context.getString(R.string.live_alt_regen, fmt(it, 2)))
        }
        // Yolun bir kismi konumsuz gectiyse tirmanis/inis O KADAR EKSIK sayiliyor:
        // irtifa yalnizca GPS'ten geliyor, hiz integrali yukseklik bilmiyor.
        // Eksik oldugunu soylemeden vermek uydurma hassasiyet olur.
        st.bridgedFraction?.let {
            if (it >= BRIDGE_NOTICE) {
                parts.append(" ")
                    .append(context.getString(R.string.live_alt_bridged, fmt(it * 100, 0)))
            }
        }
        return parts.toString()
    }

    private fun drawSpark(
        canvas: Canvas,
        series: List<LiveState.Sample>,
        title: String,
        unit: String,
        color: Int,
        box: RectF,
        u: Float,
        footer: String? = null,
        /** Irtifada dip de anlamli; hizda dip neredeyse hep 0, isaretlenmiyor. */
        markMin: Boolean = false,
        /**
         * Hiz gibi eksiye inemeyen buyukluklerde eksen tabani 0'a sabitlenir.
         * Otomatik aralikta "−1 km/h" gibi anlamsiz referans cizgileri cikiyordu.
         */
        zeroBased: Boolean = false,
    ) {
        canvas.drawRoundRect(box, u * 0.3f, u * 0.3f, panelPaint)

        textPaint.color = pal.muted
        textPaint.textSize = u * 0.62f
        canvas.drawText(title, box.left + u * 0.6f, box.top + u * 1.0f, textPaint)

        // Alt satir (A5 ozeti / azami hiz) grafigin altinda yer aliyor.
        val footerH = if (footer != null) u * 0.95f else 0f
        if (footer != null) {
            textPaint.color = pal.muted
            textPaint.textSize = u * 0.56f
            canvas.drawText(footer, box.left + u * 0.6f, box.bottom - u * 0.35f, textPaint)
        }

        val plotTop = box.top + u * 1.4f
        val plotBottom = box.bottom - u * 0.6f - footerH
        val plotLeft = box.left + u * 0.6f
        val plotRight = box.right - u * 0.6f
        if (series.size < 2 || plotBottom <= plotTop) {
            textPaint.color = pal.muted
            textPaint.textSize = u * 0.72f
            canvas.drawText(
                context.getString(R.string.live_calculating),
                plotLeft, (plotTop + plotBottom) / 2f, textPaint,
            )
            return
        }

        var rawMin = Double.MAX_VALUE
        var rawMax = -Double.MAX_VALUE
        for (s in series) {
            rawMin = min(rawMin, s.value); rawMax = max(rawMax, s.value)
        }
        /** Veri hic degismiyorsa zirve/dip isareti anlamsiz. */
        val flat = rawMax - rawMin < 1e-6

        var minV = rawMin
        var maxV = rawMax
        if (flat) { minV -= 1.0; maxV += 1.0 }
        val vPad = (maxV - minV) * 0.12
        minV -= vPad; maxV += vPad
        if (zeroBased) minV = 0.0
        if (maxV <= minV) maxV = minV + 1.0

        val d0 = series.first().distanceM
        val d1 = max(series.last().distanceM, d0 + 1.0)

        fun xOf(d: Double) = plotLeft + ((d - d0) / (d1 - d0) * (plotRight - plotLeft)).toFloat()
        fun yOf(v: Double) = plotBottom - ((v - minV) / (maxV - minV) * (plotBottom - plotTop)).toFloat()

        // --- Zirve/dip isaretlerinin yeri ONCE hesaplaniyor ---
        // Isaret etiketi ile izgara etiketi cakisip birbirini okunmaz hale
        // getiriyordu (yatay ekranda goruldu). Isaretler en son ciziliyor ama
        // yerleri simdiden bilinirse alttaki izgara etiketi hic yazilmayabilir.
        val markers = mutableListOf<Marker>()
        if (!flat) {
            val maxSample = series.maxBy { it.value }
            markers += Marker(
                xOf(maxSample.distanceM), yOf(maxSample.value),
                "▲ ${fmt(maxSample.value, 0)} $unit", pal.warn, above = true,
            )
            if (markMin) {
                val minSample = series.minBy { it.value }
                markers += Marker(
                    xOf(minSample.distanceM), yOf(minSample.value),
                    "▼ ${fmt(minSample.value, 0)} $unit", pal.low, above = false,
                )
            }
        }
        val markerRects = markers.map {
            markerLabelRect(it, plotLeft, plotRight, plotTop, plotBottom, u)
        }

        // --- Referans cizgileri ---
        // Cizgiler yuvarlak degerlere oturuyor (10, 25, 50…); grafigin kendi
        // min/max'ina oturan cizgiler okunakli bir olcek vermiyordu.
        // Duz seride olcek gostermenin anlami yok; "1 / 1" gibi tekrar eden
        // etiketler cikiyordu.
        val gridStep = if (flat) 0.0 else niceStep(maxV - minV, GRID_TARGET_LINES)
        var gv = if (flat) maxV else ceil(minV / gridStep) * gridStep
        gridTextPaint.textSize = u * 0.5f
        while (gridStep > 0 && gv < maxV) {
            val y = yOf(gv)
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            // §5.3: en ust ve en alt cizgiyi etiketleme — baslikla ve zirve
            // isaretiyle cakisiyor.
            if (y > plotTop + u * 0.7f && y < plotBottom - u * 0.3f) {
                val label = fmt(gv, 0)
                val lx = plotLeft + u * 0.15f
                val ly = y - u * 0.15f
                val rect = RectF(lx, ly - u * 0.5f, lx + gridTextPaint.measureText(label), ly)
                if (markerRects.none { RectF.intersects(it, rect) }) {
                    canvas.drawText(label, lx, ly, gridTextPaint)
                }
            }
            gv += gridStep
        }
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, gridPaint)

        // Ekran genisliginden fazla nokta varsa seyrelt (§5.3)
        val maxPoints = (plotRight - plotLeft).toInt().coerceAtLeast(64)
        val step = max(1, series.size / maxPoints)

        val line = Path()
        val fill = Path()
        var i = 0
        var first = true
        while (i < series.size) {
            val s = series[i]
            val x = xOf(s.distanceM)
            val yy = yOf(s.value)
            if (first) {
                line.moveTo(x, yy); fill.moveTo(x, plotBottom); fill.lineTo(x, yy); first = false
            } else {
                line.lineTo(x, yy); fill.lineTo(x, yy)
            }
            if (i == series.size - 1) break
            i = min(i + step, series.size - 1)
        }
        fill.lineTo(xOf(series.last().distanceM), plotBottom)
        fill.close()

        fillPaint.color = (color and 0x00FFFFFF) or (pal.fillAlpha shl 24)
        canvas.drawPath(fill, fillPaint)
        linePaint.color = color
        linePaint.strokeWidth = u * 0.13f
        canvas.drawPath(line, linePaint)

        // --- Zirve (ve irtifada dip) isaretleri ---
        markers.forEachIndexed { idx, m -> drawMarker(canvas, m, markerRects[idx], u) }

        // Sagda guncel deger
        boldPaint.color = color
        boldPaint.textSize = u * 0.8f
        val cur = "${fmt(series.last().value, 0)} $unit"
        canvas.drawText(cur, plotRight - boldPaint.measureText(cur), box.top + u * 1.0f, boldPaint)
    }

    /** Zirve/dip isaretinin cizim oncesi tanimi. */
    private data class Marker(
        val x: Float,
        val y: Float,
        val label: String,
        val dotColor: Int,
        val above: Boolean,
    )

    /**
     * Isaret etiketinin kaplayacagi alan. Cizimden once hesaplaniyor ki
     * altinda kalacak izgara etiketleri hic yazilmasin.
     */
    private fun markerLabelRect(
        m: Marker, plotLeft: Float, plotRight: Float, plotTop: Float, plotBottom: Float, u: Float,
    ): RectF {
        boldPaint.textSize = u * 0.56f
        val w = boldPaint.measureText(m.label)
        val tx = (m.x - w / 2f).coerceIn(plotLeft, (plotRight - w).coerceAtLeast(plotLeft))
        val ty = (if (m.above) m.y - u * 0.45f else m.y + u * 0.95f)
            .coerceIn(plotTop + u * 0.55f, plotBottom - u * 0.1f)
        return RectF(tx - u * 0.2f, ty - u * 0.5f, tx + w + u * 0.2f, ty + u * 0.15f)
    }

    /**
     * Zirve/dip isareti: nokta + arkasi koyu zeminli etiket.
     *
     * Koyu zemin sart: etiket izgara cizgilerinin ve grafik dolgusunun uzerine
     * denk geldiginde okunmuyor. Arac ekraninda okunabilirlik her seyden
     * onemli (prompt.md §5.3).
     */
    private fun drawMarker(canvas: Canvas, m: Marker, rect: RectF, u: Float) {
        fillPaint.color = m.dotColor
        canvas.drawCircle(m.x, m.y, u * 0.22f, fillPaint)

        fillPaint.color = pal.markerBg
        canvas.drawRoundRect(rect, u * 0.12f, u * 0.12f, fillPaint)

        boldPaint.color = pal.markerText
        boldPaint.textSize = u * 0.56f
        canvas.drawText(m.label, rect.left + u * 0.2f, rect.bottom - u * 0.15f, boldPaint)
    }

    /**
     * Referans cizgileri icin "yuvarlak" adim secer: 1, 2, 2,5, 5, 10 ve
     * bunlarin 10'un katlari. Ham araligi kac'a bolersen bol, cikan sayilar
     * (137,4 gibi) grafigi okunakli yapmiyor.
     */
    private fun niceStep(range: Double, targetLines: Int): Double {
        if (range <= 0 || targetLines <= 0) return 1.0
        val raw = range / targetLines
        val magnitude = Math.pow(10.0, floor(log10(raw)))
        val normalized = raw / magnitude
        val step = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 2.5 -> 2.5
            normalized <= 5.0 -> 5.0
            else -> 10.0
        }
        return step * magnitude
    }

    /** Alt serit: SoC, menzil, dis sicaklik, mesafe, sure. */
    /**
     * Ortalama hiz bari: 0–150 km/h'lik sabit olcek, 25 km/h'de bir ince cizgi,
     * uzerinde iki top — son 10 km (yesil, hiz grafigiyle ayni renk) ve yolculuk
     * ortalamasi (mavi, tuketim panelindeki "yolculuk ort." ile ayni renk).
     *
     * **Neden sabit olcek:** eksen yolculuga gore esnerse iki top hep ortada
     * durur ve "bu yolculuk hizli miydi" sorusu cevapsiz kalir. Sabit olcekte
     * topun ekrandaki YERI tek basina anlam tasiyor; solda kalmasi sehir ici,
     * sagda kalmasi otoyol demek — bakmadan okunabiliyor.
     *
     * **Neden sayilar da yazili:** araç sarsilirken 3 mm'lik bir topun yerinden
     * deger okumak zor. Rakamlar ustte, toplar konumu veriyor.
     */
    private fun drawSpeedBar(
        canvas: Canvas, st: LiveState,
        left: Float, right: Float, top: Float, height: Float, u: Float,
    ) {
        canvas.drawRoundRect(
            RectF(left, top, right, top + height), u * 0.3f, u * 0.3f, panelPaint,
        )

        val innerL = left + u * 0.9f
        val innerR = right - u * 0.9f
        if (innerR <= innerL) return

        // --- Ust satir: baslik solda, iki deger sagda ---
        textPaint.color = pal.muted
        textPaint.textSize = u * 0.6f
        canvas.drawText(
            context.getString(R.string.live_avg_speed), innerL, top + u * 0.85f, textPaint,
        )

        val legend = listOf(
            st.avgSpeedWindowKmh to pal.good,
            st.avgSpeedTripKmh to pal.accent,
        )
        boldPaint.textSize = u * 0.72f
        textPaint.textSize = u * 0.6f
        val unitW = textPaint.measureText(" km/h")
        var lx = innerR - unitW
        textPaint.color = pal.muted
        canvas.drawText(" km/h", lx, top + u * 0.85f, textPaint)
        // Sagdan sola diziliyor: yolculuk ortalamasi en sagda, son 10 km solunda.
        for ((value, color) in legend.reversed()) {
            val txt = value?.let { fmt(it, 0) } ?: "—"
            boldPaint.color = color
            lx -= boldPaint.measureText(txt) + u * 0.5f
            canvas.drawText(txt, lx, top + u * 0.85f, boldPaint)
            fillPaint.color = color
            canvas.drawCircle(lx - u * 0.3f, top + u * 0.63f, u * 0.16f, fillPaint)
            lx -= u * 0.75f
        }

        // --- Sarit ---
        val trackTop = top + u * 1.25f
        val trackH = u * 0.52f
        val track = RectF(innerL, trackTop, innerR, trackTop + trackH)
        fillPaint.color = pal.grid
        canvas.drawRoundRect(track, trackH / 2f, trackH / 2f, fillPaint)

        fun xOf(kmh: Double): Float {
            val c = kmh.coerceIn(0.0, SPEED_BAR_MAX)
            return innerL + ((innerR - innerL) * (c / SPEED_BAR_MAX)).toFloat()
        }

        // 25 km/h'de bir ince cizgi. Uc noktalar zaten seridin kenari.
        gridPaint.color = pal.panel
        var tick = SPEED_BAR_TICK
        while (tick < SPEED_BAR_MAX) {
            val x = xOf(tick)
            canvas.drawLine(x, trackTop + u * 0.06f, x, trackTop + trackH - u * 0.06f, gridPaint)
            tick += SPEED_BAR_TICK
        }

        // Etiketler: her cizgiye yazmak 800 px'de okunmuyor, 50'de bir yeter.
        gridTextPaint.color = pal.gridText
        gridTextPaint.textSize = u * 0.52f
        val labelY = trackTop + trackH + u * 0.75f
        for (v in intArrayOf(0, 50, 100, 150)) {
            val txt = v.toString()
            val w = gridTextPaint.measureText(txt)
            val x = (xOf(v.toDouble()) - w / 2f).coerceIn(innerL, innerR - w)
            canvas.drawText(txt, x, labelY, gridTextPaint)
        }

        // --- Toplar ---
        // Yolculuk ortalamasi altta, son 10 km ustte: canli olan gorunur kalsin.
        val cy = trackTop + trackH / 2f
        val r = u * 0.42f
        for ((value, color) in legend.reversed()) {
            val v = value ?: continue
            val cx = xOf(v)
            // Panel rengiyle halka: iki top ust uste gelince ayrilabilsinler.
            fillPaint.color = pal.panel
            canvas.drawCircle(cx, cy, r + u * 0.1f, fillPaint)
            fillPaint.color = color
            canvas.drawCircle(cx, cy, r, fillPaint)
        }
    }

    private fun drawBottomStrip(
        canvas: Canvas, st: LiveState,
        left: Float, right: Float, top: Float, height: Float, u: Float,
    ) {
        canvas.drawRoundRect(
            RectF(left, top, right, top + height), u * 0.3f, u * 0.3f, panelPaint,
        )

        // SoC ve kalan menzil BILEREK KALDIRILDI: ikisi de aracin kendi
        // gostergesinde zaten duruyor, burada tekrarlamak yer israfiydi.
        // Yerlerine aracin HICBIR ekraninda gorunmeyen iki sayi kondu:
        //   - kalan enerji kWh (araç yalnizca % ve km gosteriyor)
        //   - anlik kullanilabilir kapasite (yaslanma ancak boyle izlenebilir)
        // Batarya sicakligi da adaydi ama arac o property'yi hic yayinlamiyor
        // (2026-09-09 sondasi: "not in carPropertyConfig list").
        val cells = listOf(
            context.getString(R.string.live_energy) to
                st.batteryKwh?.let { "${fmt(it, 1)} kWh" },
            context.getString(R.string.live_capacity) to
                st.usableCapacityKwh?.let { "${fmt(it, 1)} kWh" },
            context.getString(R.string.live_outside) to
                st.outsideTempC?.let { "${fmt(it, 0)}°C" },
            context.getString(R.string.live_distance) to
                st.distanceKm?.let { "${fmt(it, 1)} km" },
            context.getString(R.string.live_duration) to
                st.durationSec?.let { hms(it) },
        )
        val cellW = (right - left) / cells.size
        cells.forEachIndexed { idx, (label, value) ->
            val cx = left + cellW * (idx + 0.5f)
            if (idx > 0) {
                val x = left + cellW * idx
                canvas.drawLine(x, top + u * 0.5f, x, top + height - u * 0.5f, gridPaint)
            }
            boldPaint.color = if (value != null) pal.text else pal.muted
            boldPaint.textSize = u * 0.95f
            val v = value ?: "—"
            canvas.drawText(v, cx - boldPaint.measureText(v) / 2f, top + u * 1.5f, boldPaint)

            textPaint.color = pal.muted
            textPaint.textSize = u * 0.58f
            canvas.drawText(label, cx - textPaint.measureText(label) / 2f, top + u * 2.35f, textPaint)
        }
    }

    /** A4 olcumu yakalandiginda 5 sn alt seridin yerini alan serit. */
    private fun drawBanner(
        canvas: Canvas, st: LiveState,
        left: Float, right: Float, top: Float, height: Float, u: Float,
    ) {
        val banner = st.banner ?: return
        if (SystemClock.elapsedRealtime() > banner.untilElapsedMs) return

        val box = RectF(left, top, right, top + height)
        fillPaint.color = pal.warn
        canvas.drawRoundRect(box, u * 0.3f, u * 0.3f, fillPaint)

        // Serit her iki temada da kehribar; uzerindeki yazi bu yuzden hep koyu.
        boldPaint.color = Color.parseColor("#1A1206")
        boldPaint.textSize = u * 1.0f
        // Uzun bir olcum metni dar ekranda tasabilir; sigana kadar kucult.
        while (boldPaint.measureText(banner.text) > box.width() - u && boldPaint.textSize > u * 0.5f) {
            boldPaint.textSize -= u * 0.05f
        }
        canvas.drawText(
            banner.text,
            box.centerX() - boldPaint.measureText(banner.text) / 2f,
            box.centerY() + boldPaint.textSize * 0.35f,
            boldPaint,
        )
    }

    // --- Bicimleme ---

    private fun fmt(v: Double, decimals: Int): String {
        // Once yuvarla: cok kucuk negatif degerler (izgara adimindan gelen
        // -1e-14 gibi) ekrana "-0" olarak dusuyordu.
        val factor = Math.pow(10.0, decimals.toDouble())
        var r = Math.round(v * factor) / factor
        if (r == 0.0) r = 0.0
        return String.format(locale(), "%.${decimals}f", r)
    }

    /** Aracin gecerli dili; ondalik ayraci buna gore degisiyor. */
    private fun locale(): Locale = context.resources.configuration.locales[0]

    /** Durum makinesi adlari Turkce sabitler; ekrandaki rozet dile bagli. */
    private fun stateLabel(name: String): String = when (name) {
        "AKTİF" -> context.getString(R.string.state_active)
        "HAZIR" -> context.getString(R.string.state_ready)
        "KAPANIYOR" -> context.getString(R.string.state_closing)
        else -> context.getString(R.string.state_idle)
    }

    private companion object {
        /** Hedeflenen referans cizgisi sayisi; gercek sayi yuvarlamaya gore degisir. */
        const val GRID_TARGET_LINES = 4

        /**
         * Ortalama hiz barinin sag ucu (km/h). Yalnizca topun YERINI belirliyor:
         * ortalama bunu asarsa top en sagda kaliyor, yazan sayi gercek deger.
         */
        const val SPEED_BAR_MAX = 150.0
        /** Barda ince cizgi araligi (km/h); SPEED_BAR_MAX'i tam bolmeli. */
        const val SPEED_BAR_TICK = 25.0

        /** Bu orandan sonra "konumsuz" uyarisi irtifa satirina yaziliyor. */
        const val BRIDGE_NOTICE = 0.05
    }

    private fun hms(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}
