package com.headunit.launcher

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/*
 * South African touches (a self-build addition, not in upstream Horizon Nav, which is written for the UK first): rand for money,
 * South African wording for the English text and spoken directions, and local chains for the logos. Everything here is off
 * outside South Africa, so the app is unchanged anywhere else.
 */
object Region {
    @Volatile private var prefs: Prefs? = null

    /** Called once when the app or the car screen starts. */
    fun init(p: Prefs) { prefs = p }

    /**
     * In South Africa: the country chosen in Settings, else the one the GPS found, else (before either is known) the phone's own
     * region. Read from the settings each time, so it follows them as they change.
     */
    val za: Boolean get() {
        val p = prefs ?: return Locale.getDefault().country == "ZA"
        val set = try { p.s(PreferenceKeys.COUNTRY, "auto") } catch (e: Exception) { "auto" }
        val cc = if (set == "auto" || set.isBlank()) (try { p.s(PreferenceKeys.COUNTRYDETECTED, "") } catch (e: Exception) { "" }) else set
        return if (cc.isBlank()) Locale.getDefault().country == "ZA" else cc.equals("ZA", ignoreCase = true)
    }

    /** South African wording is wanted (Settings > Sound & voice; on unless switched off) and applies here. */
    val zaWords: Boolean get() = za && (try { prefs?.b(WORDS_KEY, true) } catch (e: Exception) { null } ?: true)

    const val WORDS_KEY = "zawords"

    /** The region whose money format prices are shown in: rand in South Africa; otherwise as upstream had it. */
    fun currencyLocale(imperial: Boolean): Locale = if (za) Locale("en", "ZA") else if (imperial) Locale.UK else Locale.getDefault()

    /** English text the South African way, when that's wanted; otherwise as it is. */
    fun say(english: String): String = if (zaWords) ZaWording.apply(english) else english

    // ---- well-known South African chains -> their website, for the logos (looked up before the UK and European list)
    val zaChains: Map<String, String> = mapOf(
        // fuel
        "engen" to "engen.co.za", "sasol" to "sasol.com", "astron energy" to "astronenergy.co.za", "astron" to "astronenergy.co.za",
        "caltex" to "caltex.com", "shell" to "shell.co.za", "bp" to "bp.com", "total" to "totalenergies.co.za", "totalenergies" to "totalenergies.co.za",
        "puma" to "pumaenergy.com", "puma energy" to "pumaenergy.com",
        // supermarkets and shops
        "pick n pay" to "pnp.co.za", "pnp" to "pnp.co.za", "checkers" to "checkers.co.za", "shoprite" to "shoprite.co.za", "usave" to "shoprite.co.za",
        "woolworths" to "woolworths.co.za", "woolworths food" to "woolworths.co.za", "spar" to "spar.co.za", "superspar" to "spar.co.za", "kwikspar" to "spar.co.za",
        "tops" to "spar.co.za", "boxer" to "boxer.co.za", "food lovers market" to "foodloversmarket.co.za", "makro" to "makro.co.za", "game" to "game.co.za",
        "clicks" to "clicks.co.za", "dis chem" to "dischem.co.za", "dischem" to "dischem.co.za", "builders" to "builders.co.za",
        "builders warehouse" to "builders.co.za", "cashbuild" to "cashbuild.co.za", "pep" to "pepstores.com", "ackermans" to "ackermans.co.za",
        "mr price" to "mrp.com", "incredible connection" to "incredible.co.za", "midas" to "midas.co.za",
        // food and coffee
        "wimpy" to "wimpy.co.za", "steers" to "steers.co.za", "nandos" to "nandos.co.za", "kfc" to "kfc.co.za", "mcdonalds" to "mcdonalds.co.za",
        "burger king" to "burgerking.co.za", "spur" to "spur.co.za", "spur steak ranch" to "spur.co.za", "debonairs" to "debonairs.co.za",
        "debonairs pizza" to "debonairs.co.za", "romans pizza" to "romanspizza.co.za", "chicken licken" to "chickenlicken.co.za",
        "fishaways" to "fishaways.co.za", "mugg bean" to "muggandbean.co.za", "ocean basket" to "oceanbasket.com", "vida e caffe" to "vidaecaffe.com",
        "seattle coffee" to "seattlecoffeecompany.co.za", "seattle coffee company" to "seattlecoffeecompany.co.za", "kauai" to "kauai.co.za",
        "panarottis" to "panarottis.co.za", "john dorys" to "johndorys.com", "hungry lion" to "hungrylion.co.za", "dominos" to "dominos.co.za",
        // hotels, banks and services
        "city lodge" to "clhg.com", "town lodge" to "clhg.com", "road lodge" to "clhg.com", "protea hotel" to "marriott.com", "sun international" to "suninternational.com",
        "southern sun" to "southernsun.com", "garden court" to "southernsun.com", "capitec" to "capitecbank.co.za", "capitec bank" to "capitecbank.co.za",
        "fnb" to "fnb.co.za", "absa" to "absa.co.za", "standard bank" to "standardbank.co.za", "nedbank" to "nedbank.co.za",
        "post office" to "postoffice.co.za", "supa quick" to "supaquick.com", "tiger wheel tyre" to "twt.to", "virgin active" to "virginactive.co.za",
    )

    /** Chain names that are everyday words too: they only count when they're the whole name ("Game", not "Game Reserve Lodge"). */
    val zaExactOnly = setOf("game", "tops", "spur", "pep", "total", "puma", "spar", "builders", "boxer", "midas", "clicks", "astron")

    /** The website of a South African chain with this (tidied) name, or null: outside South Africa, or not one of them. */
    fun zaChain(norm: String): String? {
        if (!za) return null
        return zaChains.entries.filter { norm == it.key || (it.key !in zaExactOnly && norm.startsWith(it.key + " ")) }.maxByOrNull { it.key.length }?.value
    }
}

/**
 * British road words swapped for the ones used in South Africa: robots, circles, freeways, trucks. It works on the English text
 * before any names are put into it, so a road or a place is never renamed. Each piece of text is worked out once and kept.
 */
object ZaWording {
    // (longer phrases first, so "lorry park" becomes "truck stop" before "lorry" is looked at)
    private val words = listOf(
        "traffic lights" to "robots", "traffic light" to "robot",
        "roundabouts" to "circles", "roundabout" to "circle",
        "motorways" to "freeways", "motorway" to "freeway",
        "lorry parks" to "truck stops", "lorry park" to "truck stop", "lorries" to "trucks", "lorry" to "truck",
        "car parks" to "parking", "car park" to "parking",
        "postcodes" to "postal codes", "postcode" to "postal code",
        "slip roads" to "off-ramps", "slip road" to "off-ramp",
        "zebra crossings" to "pedestrian crossings", "zebra crossing" to "pedestrian crossing",
        "sat nav" to "GPS",
    )
    private val rules = words.map { (uk, sa) -> Regex("\\b" + Regex.escape(uk) + "\\b", RegexOption.IGNORE_CASE) to sa }
    private val cache = ConcurrentHashMap<String, String>()

    fun apply(text: String): String = cache.getOrPut(text) {
        var out = text
        for ((re, sa) in rules) if (re.containsMatchIn(out)) out = re.replace(out) { m -> matchCase(m.value, sa) }
        out
    }

    /** [sa] written the way [uk] was: ALL CAPITALS, Starting with a capital, or as it comes. */
    private fun matchCase(uk: String, sa: String): String = when {
        uk.length > 1 && uk == uk.uppercase() -> sa.uppercase()
        uk.first().isUpperCase() -> sa.replaceFirstChar { it.uppercase() }
        else -> sa
    }
}
