package com.headunit.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripMathTest {
    private fun place(name: String, lat: Double, lon: Double) = Place(name, "", LL(lat, lon))
    private val depart = 1_800_000_000_000L
    private fun plan(stops: List<TripStop>, l100: Double = 8.0, price: Double = 20.0, tank: Double = 0.0) =
        TripPlan("t", "Test", place("A", -26.0, 28.0), place("Z", -34.0, 18.5), stops, depart, l100, price, tank)
    private fun leg(km: Double, hours: Double) = LegCalc(km * 1000, hours * 3600)

    @Test fun totalsWithNoStops() {
        val c = TripMath.timeline(plan(emptyList()), listOf(leg(1000.0, 2.0)))
        assertEquals(1_000_000.0, c.dist, 0.1)
        assertEquals(80.0, c.litres, 1e-6)          // 1000 km at 8 L/100 km
        assertEquals(1600.0, c.cost, 1e-6)          // 80 L at 20 a litre
        assertEquals(depart + 2 * 3_600_000L, c.arrive)
        assertTrue(c.fuelAtEnd.isNaN())             // no tank size given: no range check
        assertTrue(c.warnings.isEmpty())
    }

    @Test fun staysPushTheArrivalBack() {
        val stops = listOf(TripStop(place("Lunch", -29.0, 26.0), "food", 45), TripStop(place("Hotel", -31.0, 24.0), "sleep", 600))
        val c = TripMath.timeline(plan(stops), listOf(leg(200.0, 2.0), leg(200.0, 2.0), leg(200.0, 2.0)))
        assertEquals(depart + 2 * 3_600_000L, c.stops[0].arrive)
        assertEquals(depart + 2 * 3_600_000L + 45 * 60_000L, c.stops[0].leave)
        assertEquals(depart + (6 * 60 + 45 + 600) * 60_000L, c.arrive)
        assertEquals((45 + 600) * 60.0, c.staySec, 1e-6)
        assertEquals(6 * 3600.0, c.driveSec, 1e-6)
    }

    @Test fun tankIsFilledAtFuelStopsAndRunningDryIsFlagged() {
        // 10 L/100 km and a 50 L tank: 500 km on a tank
        val fuel = TripStop(place("Fuel", -29.0, 26.0), "fuel", 15)
        val ok = TripMath.timeline(plan(listOf(fuel), l100 = 10.0, tank = 50.0), listOf(leg(300.0, 2.0), leg(400.0, 2.0)))
        assertEquals(20.0, ok.stops[0].fuelLeft, 1e-6)
        assertEquals(30.0, ok.stops[0].fill, 1e-6)
        assertEquals(10.0, ok.fuelAtEnd, 1e-6)
        assertTrue(ok.warnings.none { it.kind == "empty" || it.kind == "low" })

        val dry = TripMath.timeline(plan(listOf(fuel), l100 = 10.0, tank = 50.0), listOf(leg(300.0, 2.0), leg(600.0, 2.0)))
        val w = dry.warnings.single { it.kind == "empty" }
        assertEquals(1, w.leg)
        assertEquals(10.0, w.value, 1e-6)

        // a stop that isn't a fuel stop doesn't fill the tank
        val rest = TripStop(place("Rest", -29.0, 26.0), "rest", 20)
        val low = TripMath.timeline(plan(listOf(rest), l100 = 10.0, tank = 50.0), listOf(leg(300.0, 2.0), leg(180.0, 2.0)))
        assertEquals(0.0, low.stops[0].fill, 1e-6)
        assertEquals("low", low.warnings.single().kind)
    }

    @Test fun longStintsAndLongDaysAreFlagged() {
        val quick = TripStop(place("Splash", -29.0, 26.0), "fuel", 5)     // under ten minutes: not a break
        val c = TripMath.timeline(plan(listOf(quick)), listOf(leg(180.0, 2.0), leg(180.0, 2.0)))
        assertEquals(1, c.warnings.single { it.kind == "nobreak" }.leg)

        val rest = TripStop(place("Rest", -29.0, 26.0), "rest", 20)
        assertTrue(TripMath.timeline(plan(listOf(rest)), listOf(leg(180.0, 2.0), leg(180.0, 2.0))).warnings.isEmpty())

        // 12 hours of driving with breaks but no sleep is a long day; a sleep in the middle makes two fine days
        fun six(kind: String) = (1..5).map { TripStop(place("S$it", -27.0 - it, 27.0 - it), if (it == 3) kind else "rest", 20) }
        val legs = (1..6).map { leg(200.0, 2.0) }
        assertTrue(TripMath.timeline(plan(six("rest")), legs).warnings.any { it.kind == "longday" })
        assertTrue(TripMath.timeline(plan(six("sleep")), legs).warnings.none { it.kind == "longday" })
    }

    @Test fun newStopsGoWhereTheyFallOnTheWay() {
        val from = LL(-26.2, 28.0); val dest = LL(-33.9, 18.4)      // roughly Johannesburg to Cape Town
        val bloem = LL(-29.1, 26.2); val beaufort = LL(-32.35, 22.58); val colesberg = LL(-30.72, 25.1)
        assertEquals(0, TripMath.insertIndex(from, emptyList(), dest, bloem))
        assertEquals(1, TripMath.insertIndex(from, listOf(bloem), dest, beaufort))
        assertEquals(1, TripMath.insertIndex(from, listOf(bloem, beaufort), dest, colesberg))
        assertEquals(0, TripMath.insertIndex(from, listOf(colesberg, beaufort), dest, bloem))
    }

    @Test fun savedTripsSurviveBeingWrittenAndReadBack() {
        val p = plan(listOf(TripStop(place("Fuel \"1-Stop\"", -29.0, 26.0), "fuel", 15), TripStop(place("Hotel", -31.0, 24.0), "sleep", 600)), tank = 55.0)
        val back = TripPlanStore.parse(TripPlanStore.toJson(listOf(p, p.copy(id = "u", from = null, dest = null, stops = emptyList()))))
        assertEquals(listOf(p, p.copy(id = "u", from = null, dest = null, stops = emptyList())), back)
        assertTrue(TripPlanStore.parse("not json").isEmpty())
    }

    @Test fun fuelUseComesFromTheAppsMpgSetting() {
        assertEquals(7.06, TripMath.l100FromMpg(40.0), 0.01)
        assertEquals(0.0, TripMath.l100FromMpg(null), 0.0)
    }
}
