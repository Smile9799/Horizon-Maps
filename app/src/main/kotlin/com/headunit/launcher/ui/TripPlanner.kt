package com.headunit.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.headunit.launcher.Clock
import com.headunit.launcher.Geo
import com.headunit.launcher.L
import com.headunit.launcher.LL
import com.headunit.launcher.LegCalc
import com.headunit.launcher.NavEngine
import com.headunit.launcher.Nearby
import com.headunit.launcher.Overpass
import com.headunit.launcher.Place
import com.headunit.launcher.PreferenceKeys
import com.headunit.launcher.Route
import com.headunit.launcher.StopCalc
import com.headunit.launcher.TripCalc
import com.headunit.launcher.TripMath
import com.headunit.launcher.TripPlan
import com.headunit.launcher.TripPlanStore
import com.headunit.launcher.TripStop
import com.headunit.launcher.TripWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Trip planner (a self-build addition, not in upstream Horizon Nav): plan a long drive ahead of time. Pick where to, add fuel,
 * rest, meal and overnight stops with how long you'll stay at each, and it shows when you reach and leave every stop, when you
 * arrive, and the fuel the trip needs and what it costs. The saved trips and the sums are in TripPlan.kt.
 */

private fun kindName(k: String): String = when (k) {
    "fuel" -> L.t("Fuel"); "rest" -> L.t("Rest"); "food" -> L.t("Meal"); "sleep" -> L.t("Overnight"); else -> L.t("Stop")
}

private fun kindIcon(k: String): ImageVector = when (k) {
    "fuel" -> Icons.Rounded.LocalGasStation; "rest" -> Icons.Rounded.Coffee; "food" -> Icons.Rounded.Restaurant; "sleep" -> Icons.Rounded.Hotel; else -> Icons.Rounded.Place
}

private fun kindTint(k: String): Color = when (k) {
    "fuel" -> Color(0xFFEA4335); "rest" -> Color(0xFFA56BFF); "food" -> Color(0xFFF29900); "sleep" -> Color(0xFF3F6BD8); else -> Color(0xFF12A4B4)
}

private fun money(v: Double): String = try {
    java.text.NumberFormat.getCurrencyInstance(com.headunit.launcher.Region.currencyLocale(false)).apply { maximumFractionDigits = 0 }.format(v)
} catch (e: Exception) { v.roundToInt().toString() }

private fun litres(v: Double): String = if (v < 10) String.format(Locale.getDefault(), "%.1f L", v) else "${v.roundToInt()} L"

private fun dayTime(ms: Long): String = try { Clock.format("EEE d MMM, ").format(Date(ms)) } catch (e: Exception) { "" }

private fun numText(v: Double): String = if (v <= 0) "" else if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

private fun num(s: String): Double = s.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it > 0 && it < 100_000 } ?: 0.0

/** A point about half way along [r], for looking up places on that stretch. */
private fun midOf(r: Route): LL? {
    val i = r.cum.indexOfFirst { it >= r.len / 2 }
    return r.pts.getOrNull(if (i < 0) r.pts.size / 2 else i)
}

private fun newPlan(engine: NavEngine): TripPlan {
    val mpg = engine.prefs.s(PreferenceKeys.FUELMPG, "").toDoubleOrNull()
    val ppl = engine.prefs.s(PreferenceKeys.FUELPPL, "").toDoubleOrNull()
    val depart = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 6); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return TripPlan(java.util.UUID.randomUUID().toString(), "", null, null, emptyList(), depart,
        Math.round(TripMath.l100FromMpg(mpg) * 10) / 10.0, if (ppl != null && ppl > 0) ppl / 100.0 else 0.0, 0.0)
}

@Composable
fun TripPlannerDialog(engine: NavEngine, onClose: () -> Unit) {
    val c = LocalVw.current
    val store = remember { TripPlanStore(engine.prefs) }
    var plans by remember { mutableStateOf(store.all()) }
    var editing by remember { mutableStateOf<TripPlan?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(12.dp).widthIn(max = 720.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.surface).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val e = editing
            if (e == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.t("Trip planner"), color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.Close, L.t("Close"), tint = c.sub, modifier = Modifier.size(28.dp).clickable { onClose() })
                }
                Text(L.t("Plan a long drive ahead of time: fuel, rest, meal and overnight stops, when you'll reach each one, and what the fuel will cost."), color = c.sub, fontSize = 13.sp)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in plans) Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.tile).clickable { editing = p }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name.ifBlank { p.dest?.name ?: L.t("Trip") }, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(p.dest?.name, when (p.stops.size) { 0 -> L.t("no stops"); 1 -> L.t("1 stop"); else -> L.t("{0} stops", p.stops.size) }, dayTime(p.departAt)).joinToString(" · "),
                                color = c.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SButton(L.t("New trip"), primary = true) { editing = newPlan(engine) } }
            } else TripEditor(
                engine, e,
                onChange = { p -> editing = p; if (p.dest != null) { store.save(p); plans = store.all() } },
                onBack = { editing = null },
                onDelete = { store.delete(e.id); plans = store.all(); editing = null },
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TripEditor(engine: NavEngine, plan: TripPlan, onChange: (TripPlan) -> Unit, onBack: () -> Unit, onDelete: () -> Unit) {
    val c = LocalVw.current
    val scope = rememberCoroutineScope()
    // where "current location" is, fixed while the planner is open (so driving along doesn't keep working the trip out again)
    val here = remember { engine.fix?.pos ?: engine.map.center() }
    val start: LL? = plan.from?.pos ?: here
    val points: List<LL> = if (start != null && plan.dest != null) listOf(start) + plan.stops.map { it.place.pos } + plan.dest.pos else emptyList()
    val routeKey = points.joinToString("|") { String.format(Locale.US, "%.4f,%.4f", it.lat, it.lon) }
    // stretches already worked out, so moving or adding a stop only asks for the new ones
    val legCache = remember { HashMap<String, LegCalc>() }
    var legs by remember { mutableStateOf<List<LegCalc>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(routeKey) {
        legs = null; error = ""
        if (points.size < 2) return@LaunchedEffect
        delay(600)
        busy = true
        try {
            engine.prepareTripPlanning()
            val out = ArrayList<LegCalc>()
            var t = plan.departAt
            for (i in 0 until points.size - 1) {
                val a = points[i]; val b = points[i + 1]
                val key = String.format(Locale.US, "%.4f,%.4f>%.4f,%.4f", a.lat, a.lon, b.lat, b.lon)
                val departMs = if (t > System.currentTimeMillis() + 600_000L) t else 0L
                val leg = legCache[key] ?: withContext(Dispatchers.IO) { engine.planLeg(a, b, departMs).let { LegCalc(it.len, it.time, midOf(it)) } }.also { legCache[key] = it }
                out += leg
                t += (leg.time * 1000).toLong() + (plan.stops.getOrNull(i)?.stayMin ?: 0) * 60_000L
            }
            legs = out
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { error = L.t("Couldn't work out the route: {0}", e.message ?: e.javaClass.simpleName)
        } finally { busy = false }
    }
    val calc: TripCalc? = remember(plan, legs) { legs?.takeIf { it.size == plan.stops.size + 1 }?.let { try { TripMath.timeline(plan, it) } catch (e: Exception) { null } } }

    var picking by remember { mutableStateOf(if (plan.dest == null) "dest" else "") }
    var addKind by remember { mutableStateOf("fuel") }
    var findLeg by remember { mutableStateOf(-1) }
    var found by remember { mutableStateOf<List<Nearby>>(emptyList()) }
    var foundKind by remember { mutableStateOf("") }
    var finding by remember { mutableStateOf(false) }
    var name by remember(plan.id) { mutableStateOf(plan.name) }
    // fuel use typed as litres per 100 km or as km per litre, whichever you think in (remembered for next time)
    var kmPerL by remember { mutableStateOf(engine.prefs.b(KM_PER_L_KEY, false)) }
    var l100 by remember(plan.id) { mutableStateOf(numText(TripMath.fuelUseShown(plan.l100, kmPerL))) }
    var price by remember(plan.id) { mutableStateOf(numText(plan.price)) }
    var tank by remember(plan.id) { mutableStateOf(numText(plan.tankL)) }

    fun setStops(s: List<TripStop>) { findLeg = -1; found = emptyList(); onChange(plan.copy(stops = s.take(MAX_STOPS))) }
    fun addStop(p: Place, kind: String, at: Int = -1) {
        val i = if (at >= 0) at else TripMath.insertIndex(start, plan.stops.map { it.place.pos }, plan.dest?.pos, p.pos)
        setStops(plan.stops.toMutableList().also { it.add(i.coerceIn(0, it.size), TripStop(p, kind, TripStop.defaultStay(kind))) })
    }
    fun start(fromLeg: Int) { plan.dest?.let { d -> engine.startPlannedTrip(d, plan.stops.drop(fromLeg).map { it.place }) } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.ArrowBack, L.t("Back"), tint = c.text, modifier = Modifier.size(28.dp).clickable { onBack() })
        Text(plan.name.ifBlank { plan.dest?.name?.let { L.t("Trip to {0}", it) } ?: L.t("New trip") }, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = c.accent, strokeWidth = 2.dp)
    }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SField(name, { name = it.take(60); onChange(plan.copy(name = name.trim())) }, placeholder = L.t("Name (e.g. Visiting my parents)"), width = 680.dp, fill = true)

        // ---- from and to
        EndRow(Icons.Rounded.MyLocation, c.accent, L.t("From"), plan.from?.name ?: L.t("Where I am now"), picking == "from") { picking = if (picking == "from") "" else "from" }
        if (picking == "from") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Pill(L.t("Where I am now"), plan.from == null, { picking = ""; onChange(plan.copy(from = null)) }) }
            PlaceSearch(engine, L.t("Search for where you set off from")) { p -> picking = ""; onChange(plan.copy(from = p)) }
        }
        EndRow(Icons.Rounded.Flag, Color(0xFFEA4335), L.t("To"), plan.dest?.name ?: L.t("Choose where you're going"), picking == "dest") { picking = if (picking == "dest") "" else "dest" }
        if (picking == "dest") PlaceSearch(engine, L.t("Search for where you're going")) { p -> picking = ""; onChange(plan.copy(dest = p)) }

        // ---- when
        Heading(L.t("SET OFF"))
        Text(dayTime(plan.departAt), color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, d) in listOf(L.t("− day") to -86_400_000L, L.t("+ day") to 86_400_000L, "−1 h" to -3_600_000L, "+1 h" to 3_600_000L, "−15 min" to -900_000L, "+15 min" to 900_000L))
                Pill(label, false, { onChange(plan.copy(departAt = plan.departAt + d)) })
        }

        // ---- fuel
        Heading(L.t("FUEL"))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(L.t("Fuel use in"), color = c.sub, fontSize = 13.sp, modifier = Modifier.padding(end = 4.dp))
            for ((label, perL) in listOf(L.t("Litres per 100 km") to false, L.t("Km per litre") to true)) Pill(label, kmPerL == perL, {
                if (kmPerL != perL) { kmPerL = perL; engine.prefs.put(KM_PER_L_KEY, perL); l100 = numText(TripMath.fuelUseShown(plan.l100, perL)) }
            })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumField(if (kmPerL) L.t("Km per litre") else L.t("Litres per 100 km"), l100, if (kmPerL) "13.5" else "7.5") { l100 = it; onChange(plan.copy(l100 = TripMath.fuelUseTyped(num(it), kmPerL))) }
            NumField(L.t("Price per litre"), price, "21.50") { price = it; onChange(plan.copy(price = num(it))) }
            NumField(L.t("Tank size (litres)"), tank, "60") { tank = it; onChange(plan.copy(tankL = num(it))) }
        }
        Text(L.t("The tank size is optional: with it, the planner checks you can reach each fuel stop (starting full, and filling up at every fuel stop)."), color = c.sub, fontSize = 12.sp)

        // ---- the trip, stop by stop
        if (plan.dest != null) {
            Heading(L.t("THE TRIP"))
            EndRow(Icons.Rounded.MyLocation, c.accent, L.t("Set off {0}", dayTime(plan.departAt)), plan.from?.name ?: L.t("Where I am now"), false, null)
            for (i in 0..plan.stops.size) {
                val leg = calc?.legs?.getOrNull(i)
                val legWarn = calc?.warnings?.any { it.leg == i } == true
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (leg != null) "↓  " + Geo.fmtDist(leg.dist, engine.imperial) + " · " + Geo.fmtDur(leg.time) else "↓", color = if (legWarn) Warn else c.sub, fontSize = 13.sp,
                        fontWeight = if (legWarn) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    if (leg?.mid != null && plan.stops.size < MAX_STOPS) TextButton(if (findLeg == i) L.t("Hide") else L.t("Find a stop")) { findLeg = if (findLeg == i) -1 else i; found = emptyList(); foundKind = "" }
                    if (i > 0) TextButton(L.t("Start from here")) { start(i) }
                }
                if (findLeg == i && leg?.mid != null) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.tile).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(L.t("Around the middle of this stretch:"), color = c.sub, fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        for ((kind, osm) in listOf("fuel" to "fuel", "food" to "food", "rest" to "services")) Pill(if (kind == "rest") L.t("Rest area") else kindName(kind), foundKind == kind, {
                            foundKind = kind; finding = true; found = emptyList()
                            scope.launch {
                                found = try { withContext(Dispatchers.IO) { Overpass.nearby(osm, leg.mid!!, 30_000) }.take(8) } catch (e: Exception) { emptyList() }
                                finding = false
                            }
                        })
                        if (finding) CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                    }
                    if (foundKind.isNotBlank() && !finding && found.isEmpty()) Text(L.t("Nothing found there (or no signal). Search for a town or place by name below instead."), color = c.sub, fontSize = 12.sp)
                    for (n in found) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { addStop(n.place, foundKind, i) }.padding(horizontal = 6.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(n.place.name, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (n.place.sub.isNotBlank()) Text(n.place.sub, color = c.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(L.t("Add"), color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                val s = plan.stops.getOrNull(i) ?: break
                StopRow(s, calc?.stops?.getOrNull(i), plan.price, i > 0, i < plan.stops.size - 1,
                    onKind = { val k = TripStop.KINDS[(TripStop.KINDS.indexOf(s.kind) + 1) % TripStop.KINDS.size]; setStops(plan.stops.toMutableList().also { it[i] = s.copy(kind = k, stayMin = TripStop.defaultStay(k)) }) },
                    onStay = { d -> setStops(plan.stops.toMutableList().also { it[i] = s.copy(stayMin = (s.stayMin + d).coerceIn(0, 72 * 60)) }) },
                    onMove = { d -> setStops(plan.stops.toMutableList().also { val t = it[i]; it[i] = it[i + d]; it[i + d] = t }) },
                    onRemove = { setStops(plan.stops.filterIndexed { n, _ -> n != i }) })
            }
            EndRow(Icons.Rounded.Flag, Color(0xFFEA4335), calc?.let { L.t("Arrive {0}", dayTime(it.arrive)) } ?: L.t("Arrive"), plan.dest.name, false, null)

            // ---- add a stop
            if (plan.stops.size < MAX_STOPS) {
                Heading(L.t("ADD A STOP"))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in TripStop.KINDS) Pill(kindName(k), addKind == k, { addKind = k }, selectedColor = kindTint(k))
                }
                PlaceSearch(engine, L.t("Search for a town or place to stop at")) { p -> addStop(p, addKind) }
            }

            // ---- what it all comes to
            Heading(L.t("SUMMARY"))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.tile).padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (error.isNotBlank()) Text(error, color = Bad, fontSize = 13.sp)
                else if (calc == null) Text(if (start == null) L.t("No location yet: choose where you set off from.") else L.t("Working out the route…"), color = c.sub, fontSize = 13.sp)
                else {
                    Text(Geo.fmtDist(calc.dist, engine.imperial) + " · " + L.t("{0} driving", Geo.fmtDur(calc.driveSec)) + (if (calc.staySec > 0) " · " + L.t("{0} stopped", Geo.fmtDur(calc.staySec)) else ""),
                        color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(L.t("Arrive {0}", dayTime(calc.arrive)) + " (" + L.t("{0} door to door", Geo.fmtDur(calc.driveSec + calc.staySec)) + ")", color = c.text, fontSize = 14.sp)
                    if (calc.litres > 0) Text(
                        L.t("Fuel: about {0}", litres(calc.litres)) + (if (calc.cost > 0) " · " + money(calc.cost) else "") +
                            (if (!calc.fuelAtEnd.isNaN() && calc.fuelAtEnd >= 0) " · " + L.t("{0} left on arriving", litres(calc.fuelAtEnd)) else ""),
                        color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    else Text(L.t("Fill in your fuel use and the price per litre above to see the fuel cost."), color = c.sub, fontSize = 13.sp)
                    if (calc.litres > 0 && calc.cost <= 0) Text(L.t("Add the price per litre to see the cost."), color = c.sub, fontSize = 12.sp)
                    for (w in calc.warnings) Text("⚠ " + warningText(w, plan), color = Warn, fontSize = 13.sp)
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (plan.dest != null) SButton(L.t("Start trip"), primary = true) { start(0) }
        SButton(L.t("Done")) { onBack() }
        Box(Modifier.weight(1f))
        SButton(L.t("Delete"), danger = true) { onDelete() }
    }
}

private const val MAX_STOPS = 12
private const val KM_PER_L_KEY = "trip_kmperl"

private fun warningText(w: TripWarning, plan: TripPlan): String {
    val to = plan.stops.getOrNull(w.leg)?.place?.name ?: plan.dest?.name ?: L.t("the end")
    val v = String.format(Locale.getDefault(), "%.1f", w.value)
    return when (w.kind) {
        "empty" -> L.t("The tank runs dry before {0} (about {1} L short): add a fuel stop on that stretch.", to, v)
        "low" -> L.t("Only about {1} L left on reaching {0}.", to, v)
        "nobreak" -> L.t("{1} hours at the wheel without a break before {0}.", to, v)
        "longday" -> L.t("{1} hours of driving in one day, ending at {0}.", to, v)
        else -> ""
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = LocalVw.current.sub, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun TextButton(text: String, onClick: () -> Unit) {
    Text(text, color = LocalVw.current.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onClick() }.padding(horizontal = 8.dp, vertical = 6.dp))
}

@Composable
private fun NumField(label: String, value: String, example: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = LocalVw.current.sub, fontSize = 12.sp)
        SField(value, { s -> onChange(s.filter { it.isDigit() || it == '.' || it == ',' }.take(7)) }, placeholder = example, number = true, width = 150.dp)
    }
}

/** The start or the end of the trip: a round icon, a small label and the place. [onClick] null: just shown. */
@Composable
private fun EndRow(icon: ImageVector, tint: Color, label: String, value: String, open: Boolean, onClick: (() -> Unit)?) {
    val c = LocalVw.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).then(if (onClick != null) Modifier.background(if (open) c.accent.copy(alpha = 0.14f) else c.tile).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 8.dp) else Modifier.padding(vertical = 2.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(label, color = c.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) Text(if (open) L.t("Close") else L.t("Change"), color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StopRow(s: TripStop, sc: StopCalc?, price: Double, canUp: Boolean, canDown: Boolean, onKind: () -> Unit, onStay: (Int) -> Unit, onMove: (Int) -> Unit, onRemove: () -> Unit) {
    val c = LocalVw.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.tile).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(kindTint(s.kind)), contentAlignment = Alignment.Center) { Icon(kindIcon(s.kind), null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(s.place.name, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sc != null) {
                    // an overnight stop leaves on another day, so its leaving time comes with the day
                    Text(L.t("Arrive {0} · leave {1}", dayTime(sc.arrive), if (s.kind == "sleep" || sc.leave - sc.arrive > 12 * 3_600_000L) dayTime(sc.leave) else Clock.hm(sc.leave)),
                        color = c.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!sc.fuelLeft.isNaN()) Text(
                        (if (sc.fuelLeft >= 0) L.t("{0} left in the tank", litres(sc.fuelLeft)) else L.t("Tank empty before here")) +
                            (if (sc.fill > 0) " · " + L.t("fill up {0}", litres(sc.fill)) + (if (price > 0) " (" + money(sc.fill * price) + ")" else "") else ""),
                        color = if (sc.fuelLeft < 0) Bad else c.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.Rounded.KeyboardArrowUp, L.t("Earlier"), tint = if (canUp) c.text else c.sub.copy(alpha = 0.3f), modifier = Modifier.size(34.dp).clickable(enabled = canUp) { onMove(-1) })
            Icon(Icons.Rounded.KeyboardArrowDown, L.t("Later"), tint = if (canDown) c.text else c.sub.copy(alpha = 0.3f), modifier = Modifier.size(34.dp).clickable(enabled = canDown) { onMove(1) })
            Icon(Icons.Rounded.Close, L.t("Remove"), tint = Bad, modifier = Modifier.padding(start = 4.dp).size(28.dp).clickable { onRemove() })
        }
        val step = if (s.kind == "sleep") 60 else 5
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(kindName(s.kind), true, onKind, selectedColor = kindTint(s.kind))
            Text(L.t("Stay"), color = c.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
            Pill("−", false, { onStay(-step) })
            Text(Geo.fmtDur(s.stayMin * 60.0), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Pill("+", false, { onStay(step) })
        }
    }
}

/** A search box with its matches underneath; picking one hands it over and clears the box. */
@Composable
private fun PlaceSearch(engine: NavEngine, hint: String, onPick: (Place) -> Unit) {
    val c = LocalVw.current
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        if (q.trim().length < 3) { results = emptyList(); return@LaunchedEffect }
        delay(500)
        busy = true
        results = try { engine.findPlaces(q) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
        busy = false
    }
    val mine = remember { listOfNotNull(engine.places.slot("home")?.let { L.t("Home") to it }, engine.places.slot("work")?.let { L.t("Work") to it }) + engine.places.favourites().take(4).map { "★ " + it.name to it } }
    if (mine.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, p) in mine) Pill(label, false, { onPick(p) })
    }
    TextField(
        value = q, onValueChange = { q = it }, singleLine = true,
        placeholder = { Text(hint, color = c.sub) },
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = c.sub) },
        trailingIcon = { if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp) },
        shape = RoundedCornerShape(50),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.tile, unfocusedContainerColor = c.tile, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    if (results.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.tile)) {
        for (p in results.take(6)) Row(Modifier.fillMaxWidth().clickable { onPick(p); q = ""; results = emptyList() }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(p.name, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.sub.isNotBlank()) Text(p.sub, color = c.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
