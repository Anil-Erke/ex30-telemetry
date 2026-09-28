package com.example.ex30telemetry.car

import android.content.Context
import com.example.ex30telemetry.R
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * `android.car.CarPropertyManager`'a yansimayla baglanip istenen property'leri
 * akis olarak dinler. EX30 0-100'deki `CarPropertySpeedSource`'un tek yerine
 * cok property destekleyen ve Float disindaki tipleri de tasiyan hali.
 *
 * Neden yansima: `android.car.*` siniflari normal SDK'da yok, `compileSdk` ile
 * derlenmiyor (prompt.md §10.1). Property ID sabitleri de elle yazilmiyor;
 * `VehiclePropertyIds` alanlari calisma aninda okunuyor — arac ve yazilim
 * surumune gore degisebiliyorlar.
 *
 * Zaman damgasi olarak `CarPropertyValue.getTimestamp()` kullaniliyor: bu, VHAL'in
 * olcum ani. Callback'in bize ulastigi an kullanilirsa IPC ve ana-thread
 * gecikmesi dogrudan olcum hatasina donusur (prompt.md §10.6).
 */
class CarPropertyStream(private val context: Context) {

    /** Tek bir property olayi. [value] Float, Int, Boolean ya da dizi olabilir. */
    data class Event(
        val propId: Int,
        val name: String,
        val value: Any?,
        val tNanos: Long,
        val status: Int,
    )

    /** Bir property'ye nasil abone olundugu — Faz 0'in 4. sorusunun cevabi. */
    sealed class Subscription {
        /** CONTINUOUS dinleyici kabul edildi. */
        data class Continuous(val rateHz: Float) : Subscription()
        /** registerCallback reddetti, periyodik getProperty'ye dusuldu. */
        data class Polled(val intervalMs: Long) : Subscription()
        /** Property bu araçta yok ya da hic okunamiyor. */
        data class Unavailable(val reasonRes: Int) : Subscription()

        /** Gunluge yazilan teknik hali — dile bagli degil. */
        val debugLabel: String
            get() = when (this) {
                is Continuous -> "CONTINUOUS @ ${rateHz.toInt()} Hz"
                is Polled -> "polled @ $intervalMs ms"
                is Unavailable -> "unavailable($reasonRes)"
            }

        /** Olcum ekraninda gosterilen hali; dil aracin ayarindan geliyor. */
        fun label(context: Context): String = when (this) {
            is Continuous -> "CONTINUOUS @ ${rateHz.toInt()} Hz"
            is Polled -> context.getString(R.string.car_sub_polled, intervalMs)
            is Unavailable ->
                context.getString(R.string.car_sub_none, context.getString(reasonRes))
        }
    }

    companion object {
        private const val TAG = "JourneyStream"

        /** Denenecek ornekleme hizlari (Hz); arac kabul etmezse sirayla dusuluyor. */
        private val RATE_CANDIDATES = floatArrayOf(50f, 20f, 10f, 5f, 1f)

        /** registerCallback calismazsa bu araliktaki dogrudan okumaya dusulur. */
        private const val POLL_INTERVAL_MS = 200L
    }

    private var car: Any? = null
    private var manager: Any? = null

    private val callbacks = mutableListOf<Any>()
    private val pollThreads = mutableListOf<Thread>()
    @Volatile private var polling = false

    var running = false
        private set

    var lastError: String? = null
        private set

    /** `VehiclePropertyIds` alan adi -> id. Calisma aninda dolduruluyor. */
    private val nameToId = HashMap<String, Int>()

    fun start(): Boolean {
        if (running) return true
        try {
            val carClass = Class.forName("android.car.Car")
            val carObj = carClass.getMethod("createCar", Context::class.java)
                .invoke(null, context) ?: run {
                lastError = context.getString(R.string.car_err_service_missing)
                return false
            }
            val pm = carClass.getMethod("getCarManager", String::class.java)
                .invoke(carObj, "property") ?: run {
                lastError = context.getString(R.string.car_err_no_manager)
                return false
            }
            car = carObj
            manager = pm

            val idsClass = Class.forName("android.car.VehiclePropertyIds")
            for (f in idsClass.fields) {
                if (f.type == Int::class.javaPrimitiveType) nameToId[f.name] = f.getInt(null)
            }

            polling = true
            running = true
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "Car property akisi acilamadi", e)
            lastError = "${e.javaClass.simpleName}"
            return false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        polling = false
        val pm = manager
        if (pm != null) callbacks.forEach { unregister(pm, it) }
        callbacks.clear()
        pollThreads.clear()
        runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
        car = null
        manager = null
    }

    /** Aracin bildirdigi desteklenen property id'leri. Bos donerse liste alinamadi. */
    fun supportedPropertyIds(): Set<Int> {
        val pm = manager ?: return emptySet()
        return runCatching {
            val getList = pm.javaClass.methods.firstOrNull {
                it.name == "getPropertyList" && it.parameterCount == 0
            }
            @Suppress("UNCHECKED_CAST")
            val configs = getList?.invoke(pm) as? List<Any> ?: return emptySet()
            configs.mapNotNull { cfg ->
                runCatching { cfg.javaClass.getMethod("getPropertyId").invoke(cfg) as Int }.getOrNull()
            }.toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * [name] property'sini akis olarak dinlemeye calisir. Once CONTINUOUS
     * dinleyici denenir; kabul edilmezse periyodik okumaya dusulur.
     */
    /**
     * @param preferredHz varsa once bu hiz denenir. Aracin verebilecegi azami
     *   hizi istemek her zaman dogru degil: gercek EX30'da `EV_BATTERY_LEVEL`
     *   100 Hz kabul ediyor ama deger %1 SoC (0,66 kWh) adimlarla degistigi icin
     *   saniyede yuz kez ayni sayi geliyor. Ihtiyac kadarini iste.
     */
    fun listen(name: String, preferredHz: Float? = null, onEvent: (Event) -> Unit): Subscription {
        val pm = manager ?: return Subscription.Unavailable(R.string.car_sub_service_off)
        val propId = nameToId[name]
            ?: return Subscription.Unavailable(R.string.car_sub_undefined)

        val sink: (Any?) -> Unit = { pv -> emit(propId, name, pv, onEvent) }

        // Once CONTINUOUS dinleyici, olmazsa periyodik okuma. `getPropertyList()`
        // ile onden elemek yaniltici: liste izin verilmemis property'leri de
        // gizliyor, "araç desteklemiyor" gibi okunuyor (bkz. Permissions).
        registerContinuous(pm, propId, sink, preferredHz)?.let { return it }
        startPolling(pm, propId, name, onEvent)?.let { return it }

        val supported = supportedPropertyIds()
        return if (supported.isNotEmpty() && propId !in supported) {
            Subscription.Unavailable(R.string.car_sub_not_reported)
        } else {
            Subscription.Unavailable(R.string.car_sub_unreadable)
        }
    }

    // --- Sonda (CarPropertyProbe) icin genel erisimler ---

    /**
     * Aracin `VehiclePropertyIds` sinifinda TANIMLI olan her alan.
     *
     * Dikkat: burada olmak "arac bu veriyi veriyor" demek DEGIL, yalnizca
     * "aracin Android surumu bu sabiti taniyor" demek. §10.3'teki
     * `EV_CHARGE_STATE` "SDK'da tanimsiz" tespiti tam olarak bu tablonun
     * eksikligiydi; Android 15 ile tablonun buyumesi bekleniyor.
     */
    fun knownProperties(): Map<String, Int> = HashMap(nameToId)

    /** Bir property'nin aracin bildirdigi yapilandirmasi. */
    data class Config(
        val propId: Int,
        val areaIds: List<Int>,
        val changeMode: Int?,
        val maxSampleRate: Float?,
        val minSampleRate: Float?,
        val access: Int?,
        val valueType: String?,
    )

    /**
     * `getPropertyList()` ciktisinin tamami — yalnizca id degil, yapilandirma da.
     *
     * §10.8'de yayin hizlarini araçta tek tek olcerek bulmustuk; `maxSampleRate`
     * ve `changeMode` zaten burada duruyormus. Sonda bunu da doksun ki bir
     * dahaki sefere olcmeden bilelim.
     */
    fun configs(): List<Config> {
        val pm = manager ?: return emptyList()
        val getList = pm.javaClass.methods.firstOrNull {
            it.name == "getPropertyList" && it.parameterCount == 0
        } ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        val raw = runCatching { getList.invoke(pm) as? List<Any> }.getOrNull() ?: return emptyList()
        return raw.mapNotNull { toConfig(it) }
    }

    /**
     * TEK bir property'nin yapilandirmasi, dogrudan sorgulanarak.
     *
     * **Neden [configs] yetmiyor:** `getPropertyList()` IZINE GORE FILTRELI —
     * izin verilmemis property listede hic gorunmuyor (§10.5/1). Dolayisiyla
     * "listede yok" ile "araçta yok" ayni sey degil. `getCarPropertyConfig(id)`
     * bazen listede gorunmeyen property icin de yapilandirma donduruyor; bu da
     * "araçta var ama izin yok" durumunu ayirt etmemizi sagliyor.
     *
     * Bolgeli property'lerde (lastik basinci, koltuk) gercek areaId'leri almanin
     * da tek yolu bu: areaId 0 ile okuma denemesi
     * `area ID: 0x0 not supported` ile dusuyor.
     */
    fun configOf(propId: Int): Config? {
        val pm = manager ?: return null
        val cfg = pm.javaClass.methods
            .firstOrNull { it.name == "getCarPropertyConfig" && it.parameterCount == 1 }
            ?.let { m -> runCatching { m.invoke(pm, propId) }.getOrNull() }
            ?: return null
        return toConfig(cfg)
    }

    /**
     * `CarPropertyConfig.getConfigArray()` — property'ye ozel sabitler.
     *
     * `WHEEL_TICK` icin hayati: configArray[1..4] tekerlek basina
     * mikrometre/tick veriyor ve mesafe ancak bu sabitlerle hesaplanabiliyor.
     */
    fun configArrayOf(propId: Int): List<Int> {
        val pm = manager ?: return emptyList()
        val cfg = pm.javaClass.methods
            .firstOrNull { it.name == "getCarPropertyConfig" && it.parameterCount == 1 }
            ?.let { m -> runCatching { m.invoke(pm, propId) }.getOrNull() }
            ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return runCatching {
            (cfg.javaClass.getMethod("getConfigArray").invoke(cfg) as? List<Int>).orEmpty()
        }.getOrDefault(emptyList())
    }

    /** [configArrayOf] icin ad ile cagirilabilen hali. */
    fun configArrayOf(name: String): List<Int> =
        nameToId[name]?.let { configArrayOf(it) }.orEmpty()

    private fun toConfig(cfg: Any): Config? = runCatching {
        fun <T> call(name: String): T? =
            @Suppress("UNCHECKED_CAST")
            runCatching { cfg.javaClass.getMethod(name).invoke(cfg) as? T }.getOrNull()

        val id = call<Int>("getPropertyId") ?: return@runCatching null
        val areas = runCatching {
            cfg.javaClass.getMethod("getAreaIds").invoke(cfg) as? IntArray
        }.getOrNull()?.toList() ?: listOf(0)

        Config(
            propId = id,
            areaIds = if (areas.isEmpty()) listOf(0) else areas,
            changeMode = call("getChangeMode"),
            maxSampleRate = call("getMaxSampleRate"),
            minSampleRate = call("getMinSampleRate"),
            access = call("getAccess"),
            valueType = call<Class<*>>("getPropertyType")?.simpleName,
        )
    }.getOrNull()

    /**
     * Belirli bir bolge (areaId) icin okuma. Tekerlek basinci gibi
     * VEHICLE_AREA_WHEEL property'lerinde areaId 0 ISE YARAMAZ; gercek areaId
     * [configs] uzerinden alinmali.
     */
    fun readArea(name: String, areaId: Int): Event? {
        val pm = manager ?: return null
        val propId = nameToId[name] ?: return null
        val getProperty = pm.javaClass.methods.firstOrNull {
            it.name == "getProperty" && it.parameterCount == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return null
        return runCatching {
            val pv = getProperty.invoke(pm, propId, areaId) ?: return null
            toEvent(propId, name, pv)
        }.getOrNull()
    }

    /**
     * Okuma denemesinin NEDEN basarisiz oldugunu da veren hali.
     * `read` null donunce "arac vermiyor" ile "istisna atti" ayirt edilemiyordu.
     */
    fun readDetailed(name: String, areaId: Int = 0): Result<Event?> {
        val pm = manager ?: return Result.failure(IllegalStateException("CarPropertyManager yok"))
        val propId = nameToId[name]
            ?: return Result.failure(NoSuchFieldException("VehiclePropertyIds.$name yok"))
        val getProperty = pm.javaClass.methods.firstOrNull {
            it.name == "getProperty" && it.parameterCount == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return Result.failure(NoSuchMethodException("getProperty(int,int) yok"))
        return runCatching {
            val pv = getProperty.invoke(pm, propId, areaId)
            if (pv == null) null else toEvent(propId, name, pv)
        }.recoverCatching { throw it.cause ?: it }
    }

    /**
     * Android 15'in getirdigi abonelik API'si var mi?
     *
     * `subscribePropertyEvents` + `Subscription.Builder` degisken guncelleme
     * hizini (VUR) varsayilan olarak aciyor ve cozunurluk ayari sunuyor. §10.8'de
     * olctugumuz "25.240 olayin hepsi ayni 42,90 kWh" israfinin cozumu bu.
     * Burada yalnizca VARLIGINI olcuyoruz; gecis ayri is.
     */
    fun platformCapabilities(): Map<String, Boolean> {
        val pm = manager
        fun hasMethod(name: String): Boolean =
            pm != null && pm.javaClass.methods.any { it.name == name }
        fun hasClass(fqcn: String): Boolean =
            runCatching { Class.forName(fqcn); true }.getOrDefault(false)
        return linkedMapOf(
            "subscribePropertyEvents" to hasMethod("subscribePropertyEvents"),
            "unsubscribePropertyEvents" to hasMethod("unsubscribePropertyEvents"),
            "getPropertiesAsync" to hasMethod("getPropertiesAsync"),
            "Subscription sinifi" to hasClass("android.car.hardware.property.Subscription"),
            "AreaIdConfig sinifi" to hasClass("android.car.hardware.property.AreaIdConfig"),
        )
    }

    /** Tek seferlik okuma — degismeyen bilgiler (ornegin batarya kapasitesi) icin. */
    fun read(name: String): Event? {
        val pm = manager ?: return null
        val propId = nameToId[name] ?: return null
        val getProperty = pm.javaClass.methods.firstOrNull {
            it.name == "getProperty" && it.parameterCount == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return null
        return runCatching {
            val pv = getProperty.invoke(pm, propId, 0) ?: return null
            toEvent(propId, name, pv)
        }.getOrNull()
    }

    // --- Ic isler ---

    private fun emit(propId: Int, name: String, pv: Any?, onEvent: (Event) -> Unit) {
        if (pv == null) return
        val ev = runCatching { toEvent(propId, name, pv) }.getOrNull() ?: return
        onEvent(ev)
    }

    private fun toEvent(propId: Int, name: String, pv: Any): Event {
        val value = runCatching { pv.javaClass.getMethod("getValue").invoke(pv) }.getOrNull()
        val status = runCatching {
            pv.javaClass.getMethod("getStatus").invoke(pv) as? Int
        }.getOrNull() ?: 0
        val ts = runCatching {
            pv.javaClass.getMethod("getTimestamp").invoke(pv) as Long
        }.getOrDefault(0L)
        return Event(propId, name, value, sanitize(ts), status)
    }

    /** Damga 0 ya da sacmaysa yerel saate dus (prompt.md §10.6). */
    private fun sanitize(ts: Long): Long {
        val now = SystemClock.elapsedRealtimeNanos()
        return if (ts <= 0L || ts > now + 1_000_000_000L) now else ts
    }

    private fun registerContinuous(
        pm: Any,
        propId: Int,
        sink: (Any?) -> Unit,
        preferredHz: Float? = null,
    ): Subscription.Continuous? {
        val callbackClass = runCatching {
            Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
        }.getOrNull() ?: return null

        val register = pm.javaClass.methods.firstOrNull {
            it.name == "registerCallback" && it.parameterCount == 3 &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return null

        val proxy = Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass),
            EventCallbackHandler(propId, sink),
        )

        // Istenen hiz varsa once o; yoksa aracin bildirdigi azamiden baslayip
        // kabul edilene kadar asagi in.
        val rates = buildList {
            preferredHz?.let { if (it > 0f) add(it) }
            maxSampleRate(pm, propId)?.let { if (it > 0f) add(it) }
            addAll(RATE_CANDIDATES.toList())
        }
        for (rate in rates) {
            val result = runCatching { register.invoke(pm, proxy, propId, rate) }
                .onFailure { Log.d(TAG, "prop=$propId rate=$rate reddedildi: ${it.cause?.javaClass?.simpleName}") }
            // Metot boolean donuyorsa true bekleriz; void donen surumlerde
            // istisna atilmamis olmasi basari sayilir.
            val ok = result.isSuccess && (result.getOrNull() as? Boolean ?: true)
            if (ok) {
                callbacks += proxy
                Log.i(TAG, "prop=$propId dinleniyor, rate=$rate Hz")
                return Subscription.Continuous(rate)
            }
        }
        return null
    }

    private fun maxSampleRate(pm: Any, propId: Int): Float? = runCatching {
        val cfg = pm.javaClass.methods
            .firstOrNull { it.name == "getCarPropertyConfig" && it.parameterCount == 1 }
            ?.invoke(pm, propId) ?: return null
        cfg.javaClass.getMethod("getMaxSampleRate").invoke(cfg) as? Float
    }.getOrNull()

    private fun unregister(pm: Any, callback: Any) {
        runCatching {
            pm.javaClass.methods.firstOrNull {
                it.name == "unregisterCallback" && it.parameterCount == 1
            }?.invoke(pm, callback)
        }
    }

    /** CONTINUOUS kurulamadiginda periyodik okumaya duser; o da olmazsa null. */
    private fun startPolling(
        pm: Any,
        propId: Int,
        name: String,
        onEvent: (Event) -> Unit,
    ): Subscription? {
        val getProperty = pm.javaClass.methods.firstOrNull {
            it.name == "getProperty" && it.parameterCount == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return null

        // Ilk okuma basarisizsa yoklamaya hic baslamayalim; "yok" demek daha dogru.
        val first = runCatching { getProperty.invoke(pm, propId, 0) }
        if (first.isFailure || first.getOrNull() == null) return null
        emit(propId, name, first.getOrNull(), onEvent)

        val t = Thread {
            while (polling) {
                runCatching { emit(propId, name, getProperty.invoke(pm, propId, 0), onEvent) }
                SystemClock.sleep(POLL_INTERVAL_MS)
            }
        }.apply { isDaemon = true; start() }
        pollThreads += t
        Log.i(TAG, "prop=$propId registerCallback kabul etmedi — $POLL_INTERVAL_MS ms yoklama")
        return Subscription.Polled(POLL_INTERVAL_MS)
    }

    /** Proxy uzerinden gelen CarPropertyEventCallback cagrilarini karsilar. */
    private class EventCallbackHandler(
        private val propId: Int,
        private val sink: (Any?) -> Unit,
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
            when (method.name) {
                "onChangeEvent" -> {
                    sink(args?.getOrNull(0))
                    null
                }
                "onErrorEvent" -> null
                // Proxy'nin Object metotlari — null donmek NPE uretir (§10.6).
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "CarPropertyCallback(prop=$propId)"
                else -> null
            }
    }
}
