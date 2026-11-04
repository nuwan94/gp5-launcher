package com.gp5.launcher.core.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.gp5.launcher.core.model.NavApp

/**
 * Starts turn-by-turn navigation to a saved place (DESIGN.md section 6.2).
 * A place is free text ("Kollupitiya", an address) or "lat,lng". Returns false when no maps app can take it.
 */
object Navigator {
    const val GOOGLE_MAPS = "com.google.android.apps.maps"
    const val WAZE = "com.waze"

    private val LAT_LNG = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

    fun navigate(context: Context, place: String, app: NavApp): Boolean {
        val target = place.trim()
        if (target.isEmpty()) return false
        val ll = LAT_LNG.matchEntire(target)?.let { "${it.groupValues[1]},${it.groupValues[2]}" }
        val q = Uri.encode(target)
        val google = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${ll ?: q}")).setPackage(GOOGLE_MAPS)
        val waze = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(if (ll != null) "https://waze.com/ul?ll=$ll&navigate=yes" else "https://waze.com/ul?q=$q&navigate=yes"),
        ).setPackage(WAZE)
        val any = Intent(Intent.ACTION_VIEW, Uri.parse(if (ll != null) "geo:$ll?q=$ll" else "geo:0,0?q=$q"))
        val order = when (app) {
            NavApp.Waze -> listOf(waze, google, any)
            NavApp.GoogleMaps, NavApp.Auto -> listOf(google, waze, any)
        }
        for (i in order) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }
}
