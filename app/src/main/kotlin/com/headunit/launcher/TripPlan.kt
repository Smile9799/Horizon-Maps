package com.headunit.launcher

import org.json.JSONArray
import org.json.JSONObject

/*
 * Trip planner (a self-build addition, not in upstream Horizon Nav): long drives planned ahead of time, with fuel, rest, meal and
 * overnight stops, a timeline of when you reach and leave each one, and the fuel the trip needs and what it costs.
 * This file holds the saved trips and the sums; the screen is ui/TripPlanner.kt.
 */

/** A stop on a planned trip. [kind]: "fuel", "rest", "food", "sleep" or "other". [stayMin]: how long you stay there. */
data class TripStop(val place: Place, val kind: String, val stayMin: Int) {
    companion object {
        val KINDS = listOf("fuel", "rest", "food", "sleep", "other")
        /** How long a stop of each kind usually takes, in minutes. */
        fun defaultStay(kind: String): Int = when (kind) { "fuel" -> 15; "rest" -> 20; "food" -> 45; "sleep" -> 600; else -> 15 }
    }
}

/**
 * A planned trip. [from] null means "wherever I am when I look at it". [l100] is litres per 100 km, [price] the pump price of a
 * litre in your own money, [tankL] the size of the tank in litres (0 = not given, so no range check).
 */
data class TripPlan(
    val id: String, val name: String, val from: Place?, val dest: Place?, val stops: List<TripStop>, val departAt: Long,
    val l100: Double, val price: Double, val tankL: Double,
)

/** One stretch of a trip (from the start or a stop to the next stop or the end): metres, seconds, and a point about half way. */
class LegCalc(val dist: Double, val time: Double, val mid: LL? = null)

/** When you reach and leave a stop, and (with a tank size) the litres left on arriving and the litres a fuel stop puts in. */
class StopCalc(val arrive: Long, val leave: Long, val fuelLeft: Double, val fill: Double)

/**
 * Something worth a second look. [kind]: "empty" (the tank runs dry [value] litres short on stretch [leg]), "low" ([value] litres
 * left at the end of it), "nobreak" ([value] hours at the wheel in one go) or "longday" ([value] hours of driving in one day).
 * [leg] counts from 0; the last one ends at the destination.
 */
class TripWarning(val kind: String, val leg: Int, val value: Double)

class TripCalc(
    val legs: List<LegCalc>, val stops: List<StopCalc>, val arrive: Long,
    val dist: Double, val driveSec: Double, val staySec: Double,
    /** Litres the whole trip burns, and what they cost (0 when the fuel use or price isn't set). */
    val litres: Double, val cost: Double,
    /** Litres left on arriving (NaN without a tank size). */
    val fuelAtEnd: Double,
    val warnings: List<TripWarning>,
)

object TripMath {
    /** Longer than this at the wheel without stopping gets a warning. */
    const val MAX_STINT_SEC = 2.5 * 3600
    /** More driving than this between sleeps gets a warning. */
    const val MAX_DAY_SEC = 10.0 * 3600
    /** Arriving with less than this share of the tank gets a warning. */
    const val RESERVE = 0.10

    /**
     * The timeline and fuel sums for [plan], given how long each stretch is ([legs]: one more than there are stops).
     * The tank is taken to be full when you set off and filled to the top at every fuel stop.
     */
    fun timeline(plan: TripPlan, legs: List<LegCalc>): TripCalc {
        require(legs.size == plan.stops.size + 1) { "A trip with ${plan.stops.size} stops has ${plan.stops.size + 1} stretches, not ${legs.size}" }
        val warnings = ArrayList<TripWarning>()
        val stops = ArrayList<StopCalc>()
        val perKm = if (plan.l100 > 0) plan.l100 / 100.0 else 0.0
        val tank = if (perKm > 0 && plan.tankL > 0) plan.tankL else 0.0
        var t = plan.departAt
        var level = tank
        var stint = 0.0     // seconds driven since the last proper break
        var day = 0.0       // seconds driven since setting off or the last sleep
        var dist = 0.0; var drive = 0.0; var stay = 0.0
        for ((i, leg) in legs.withIndex()) {
            dist += leg.dist; drive += leg.time
            stint += leg.time; day += leg.time
            t += (leg.time * 1000).toLong()
            if (tank > 0) {
                level -= leg.dist / 1000.0 * perKm
                if (level < 0) warnings += TripWarning("empty", i, -level)
                else if (level < tank * RESERVE) warnings += TripWarning("low", i, level)
            }
            if (stint > MAX_STINT_SEC) warnings += TripWarning("nobreak", i, stint / 3600.0)
            val stop = plan.stops.getOrNull(i)
            if (stop == null || stop.kind == "sleep") {
                if (day > MAX_DAY_SEC) warnings += TripWarning("longday", i, day / 3600.0)
                day = 0.0
            }
            if (stop == null) break
            val arrive = t
            val left = if (tank > 0) level else Double.NaN
            var fill = 0.0
            if (tank > 0 && stop.kind == "fuel") { fill = tank - maxOf(level, 0.0); level = tank }
            val mins = maxOf(0, stop.stayMin)
            t += mins * 60_000L
            stay += mins * 60.0
            // a stop of ten minutes or more counts as a break from driving
            if (mins >= 10) stint = 0.0
            stops += StopCalc(arrive, t, left, fill)
        }
        val litres = dist / 1000.0 * perKm
        return TripCalc(legs, stops, t, dist, drive, stay, litres, if (plan.price > 0) litres * plan.price else 0.0,
            if (tank > 0) level else Double.NaN, warnings)
    }

    /**
     * Where a new stop at [p] fits best among the stops there already are: the place in the order that adds the least distance
     * (as the crow flies), so stops found in any order still come out in the order you'll reach them.
     */
    fun insertIndex(from: LL?, stops: List<LL>, dest: LL?, p: LL): Int {
        val chain = listOfNotNull(from) + stops + listOfNotNull(dest)
        if (chain.size < 2) return stops.size
        val offset = if (from != null) 0 else 1   // without a start, a stop can't go before the first one
        var best = stops.size; var bestAdd = Double.MAX_VALUE
        for (i in 0 until chain.size - 1) {
            val add = Geo.dist(chain[i], p) + Geo.dist(p, chain[i + 1]) - Geo.dist(chain[i], chain[i + 1])
            if (add < bestAdd) { bestAdd = add; best = i + offset }
        }
        return best.coerceIn(0, stops.size)
    }

    /**
     * Fuel use the way it's typed and shown: litres per 100 km as it is, or (with [kmPerL]) as kilometres per litre, which is
     * 100 divided by it. The two go both ways with the same sum, and nothing (0) stays nothing.
     */
    fun fuelUseShown(l100: Double, kmPerL: Boolean): Double = if (!kmPerL) l100 else if (l100 > 0) 100.0 / l100 else 0.0
    fun fuelUseTyped(value: Double, kmPerL: Boolean): Double = fuelUseShown(value, kmPerL)

    /** Litres per 100 km from the app's own fuel economy setting (kept as miles per UK gallon), or 0 when it isn't set. */
    fun l100FromMpg(mpg: Double?): Double = if (mpg != null && mpg > 0) 282.481 / mpg else 0.0
}

/** The saved trips, newest first, kept with the rest of the app's settings (so Settings > Back up includes them). */
class TripPlanStore(private val prefs: Prefs) {
    fun all(): List<TripPlan> = parse(prefs.s(KEY, "[]"))

    /** Saves [p] (in place of the trip with the same id, if there is one) at the top of the list. */
    fun save(p: TripPlan) = write(listOf(p) + all().filter { it.id != p.id })

    fun delete(id: String) = write(all().filter { it.id != id })

    private fun write(list: List<TripPlan>) = prefs.put(KEY, toJson(list.take(MAX)))

    companion object {
        const val KEY = "trip_plans"
        const val MAX = 30

        private fun place(p: Place) = JSONObject().put("n", p.name).put("s", p.sub).put("la", p.pos.lat).put("lo", p.pos.lon)
        private fun place(o: JSONObject?): Place? = if (o == null || !o.has("la")) null else Place(o.optString("n"), o.optString("s"), LL(o.getDouble("la"), o.getDouble("lo")))

        fun toJson(list: List<TripPlan>): String {
            val a = JSONArray()
            for (p in list) {
                val o = JSONObject().put("id", p.id).put("name", p.name).put("at", p.departAt).put("l100", p.l100).put("price", p.price).put("tank", p.tankL)
                p.from?.let { o.put("from", place(it)) }
                p.dest?.let { o.put("dest", place(it)) }
                o.put("stops", JSONArray().apply { p.stops.forEach { s -> put(place(s.place).put("k", s.kind).put("m", s.stayMin)) } })
                a.put(o)
            }
            return a.toString()
        }

        /** Reads saved trips back; anything that can't be read is left out rather than losing the rest. */
        fun parse(text: String): List<TripPlan> = try {
            val a = JSONArray(text)
            (0 until a.length()).mapNotNull { n ->
                try {
                    val o = a.getJSONObject(n)
                    val sa = o.optJSONArray("stops") ?: JSONArray()
                    val stops = (0 until sa.length()).mapNotNull { i ->
                        val s = sa.getJSONObject(i)
                        place(s)?.let { TripStop(it, s.optString("k", "other").takeIf { k -> k in TripStop.KINDS } ?: "other", s.optInt("m", 15).coerceIn(0, 7 * 24 * 60)) }
                    }
                    TripPlan(o.getString("id"), o.optString("name"), place(o.optJSONObject("from")), place(o.optJSONObject("dest")), stops,
                        o.optLong("at"), o.optDouble("l100", 0.0), o.optDouble("price", 0.0), o.optDouble("tank", 0.0))
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }
    }
}
