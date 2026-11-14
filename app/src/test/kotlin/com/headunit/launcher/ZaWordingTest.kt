package com.headunit.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZaWordingTest {
    @Test fun britishRoadWordsBecomeSouthAfricanOnes() {
        assertEquals("at the circle, take the {0} exit", ZaWording.apply("at the roundabout, take the {0} exit"))
        assertEquals("Robots", ZaWording.apply("Traffic lights"))
        assertEquals("Avoid freeways", ZaWording.apply("Avoid motorways"))
        assertEquals("+ Truck stop", ZaWording.apply("+ Lorry park"))
        assertEquals("Routes avoid low bridges, weight limits and truck bans for your size", ZaWording.apply("Routes avoid low bridges, weight limits and lorry bans for your size"))
        assertEquals("EXIT / OFF-RAMP", ZaWording.apply("EXIT / SLIP ROAD"))
        assertEquals("Search for the address or postal code", ZaWording.apply("Search for the address or postcode"))
        assertEquals("Parking", ZaWording.apply("Car parks"))
    }

    @Test fun otherTextIsLeftAlone() {
        for (s in listOf("Turn left", "Fuel stations", "Arrive {0}", "", "Carparkia", "Motorwayside Inn")) assertEquals(s, ZaWording.apply(s))
    }

    @Test fun parkingPricesInRand() {
        assertEquals("R20/hr", Overpass.priceText("20 ZAR/hour"))
        assertEquals("R15/hr", Overpass.priceText("R15/hour"))
        assertEquals("£1.80/hr", Overpass.priceText("£1.80/hour"))
        assertNull(Overpass.priceText("free"))
    }

    @Test fun southAfricanChainsAreKnownByTheStartOfTheirName() {
        assertEquals("engen.co.za", Region.zaChains["engen"])
        // a chain's name that is an everyday word only counts as the whole name
        assert("game" in Region.zaExactOnly && "spur" in Region.zaExactOnly)
    }
}
