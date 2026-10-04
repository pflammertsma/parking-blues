package dev.lammertsma.parkingblues.car.car

import android.content.Context
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * osmdroid's own setup requirement: a distinct user agent (tile servers and
 * osmdroid's own usage-policy enforcement expect one; requests without it
 * can be throttled or blocked) plus a writable tile cache directory, both
 * set once before any MapView is created. Called from each process's own
 * Application.onCreate (ParkingBluesApp / ParkingAutomotiveApp are two
 * separate installed APKs, so each needs its own call).
 */
fun configureOsmdroid(context: Context) {
    val config = Configuration.getInstance()
    config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    config.userAgentValue = context.packageName
    config.osmdroidTileCache = context.cacheDir.resolve("osmdroid-tiles").apply { mkdirs() }
}

/**
 * CartoDB's "Positron" basemap -- the same light, minimal OSM-derived style
 * web/app.js uses (see its own comment there for why: keeps streets/labels/
 * buildings but drops the restaurant/shop POI clutter of default OSM
 * tiles). Matching it here gives the car screen's map the same look as the
 * web MVP, and replaces MapSearchScreen's old map_style.json hack, which
 * was a Google-Maps-specific JSON styling mechanism with no osmdroid
 * equivalent -- moot anyway now that Positron is already decluttered.
 * CARTO started requiring a (free) API key for this on 2026-08-28; same
 * key as web/app.js uses.
 */
private const val CARTO_API_KEY = "cb1_40s3_1_3dff2462a8c5ca48a8208963"

// Standard resolution, same as web/app.js's tiles -- tried @2x (retina)
// tiles with tileSizePixels=512 for sharper text, but that shifts
// osmdroid's zoom/tile-grid math (each tile then covers a visibly larger
// geographic area, confirmed live as mismatched tile-quadrant seams and a
// much-less-zoomed-in view than intended) without also compensating the
// zoom level elsewhere, which needs more care than a one-line change.
// Standard raster tiles read a bit softer up close than our own natively-
// rendered markers, same inherent characteristic as any 256px-tile basemap
// (including this exact tile source in the web MVP) -- not a regression
// worth chasing further right now.
val positronTileSource: XYTileSource = XYTileSource(
    "CartoPositron",
    0,
    19,
    256,
    ".png?key=$CARTO_API_KEY",
    arrayOf(
        "https://a.basemaps.cartocdn.com/light_all/",
        "https://b.basemaps.cartocdn.com/light_all/",
        "https://c.basemaps.cartocdn.com/light_all/",
        "https://d.basemaps.cartocdn.com/light_all/",
    ),
    "© OpenStreetMap contributors © CARTO",
)
