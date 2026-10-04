package com.example.sentieri

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Spot(val lat: Double, val lon: Double, val alt: Double?, val time: Long)

class MainActivity : Activity() {

    private lateinit var map: MapView
    private lateinit var info: TextView
    private lateinit var btnMap: Button
    private lateinit var btnList: Button

    private val prefs by lazy { getSharedPreferences("sentieri", MODE_PRIVATE) }
    private val spots = mutableListOf<Spot>()
    private val markers = mutableListOf<Marker>()
    private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    private var locOverlay: MyLocationNewOverlay? = null
    private var compass: CompassOverlay? = null

    private val handler = Handler(Looper.getMainLooper())
    private var sourceIdx = 0

    private val labels = listOf("Topografica", "Sentieri/ciclo", "OSM standard")
    private val sources: List<ITileSource> by lazy {
        listOf(
            XYTileSource(
                "OpenTopoMap", 0, 17, 256, ".png",
                arrayOf(
                    "https://a.tile.opentopomap.org/",
                    "https://b.tile.opentopomap.org/",
                    "https://c.tile.opentopomap.org/"
                ),
                "© OpenTopoMap (CC-BY-SA), dati © OpenStreetMap"
            ),
            XYTileSource(
                "CyclOSM", 0, 18, 256, ".png",
                arrayOf(
                    "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/",
                    "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/",
                    "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/"
                ),
                "© CyclOSM, dati © OpenStreetMap contributors"
            ),
            TileSourceFactory.MAPNIK
        )
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateInfo()
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val cfg = Configuration.getInstance()
        cfg.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        cfg.userAgentValue = packageName
        cfg.osmdroidBasePath = File(filesDir, "osmdroid")
        cfg.osmdroidTileCache = File(filesDir, "osmdroid/tiles")

        val root = FrameLayout(this)
        root.fitsSystemWindows = true

        map = MapView(this).apply {
            setTileSource(sources[0])
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            val v = prefs.getString("view", null)?.split(";")
            if (v != null && v.size == 3) {
                controller.setZoom(v[2].toDouble())
                controller.setCenter(GeoPoint(v[0].toDouble(), v[1].toDouble()))
            } else {
                controller.setZoom(6.0)
                controller.setCenter(GeoPoint(42.5, 12.5))
            }
            overlays.add(CopyrightOverlay(this@MainActivity))
            overlays.add(ScaleBarOverlay(this).apply { setAlignBottom(true) })
        }
        root.addView(
            map,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            textSize = 14f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            text = "In attesa del GPS…"
        }
        root.addView(
            info,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        val btnCenter = Button(this).apply {
            text = "Centra"
            setOnClickListener {
                locOverlay?.let { ov ->
                    ov.enableFollowLocation()
                    ov.myLocation?.let { map.controller.animateTo(it) }
                }
            }
        }
        btnMap = Button(this).apply {
            text = labels[0]
            setOnClickListener {
                sourceIdx = (sourceIdx + 1) % sources.size
                map.setTileSource(sources[sourceIdx])
                text = labels[sourceIdx]
            }
        }
        val btnSpot = Button(this).apply {
            text = "Segna punto"
            textSize = 16f
            setOnClickListener { addSpot() }
        }
        btnList = Button(this).apply {
            text = "Punti (0)"
            setOnClickListener { showSpotList() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(btnSpot)
            addView(btnList)
            addView(btnCenter)
            addView(btnMap)
        }
        root.addView(
            buttons,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { rightMargin = dp(8) }
        )

        setContentView(root)

        loadSpots()
        refreshMarkers()

        if (hasLocationPermission()) {
            startLocation()
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                1
            )
        }
    }

    // ---------- Punti salvati ----------

    private fun loadSpots() {
        spots.clear()
        val raw = prefs.getString("spots", "") ?: ""
        for (line in raw.split("\n")) {
            val p = line.split("|")
            if (p.size != 4) continue
            val lat = p[0].toDoubleOrNull() ?: continue
            val lon = p[1].toDoubleOrNull() ?: continue
            val time = p[3].toLongOrNull() ?: continue
            spots.add(Spot(lat, lon, p[2].toDoubleOrNull(), time))
        }
    }

    private fun saveSpots() {
        val raw = spots.joinToString("\n") { "${it.lat}|${it.lon}|${it.alt ?: "x"}|${it.time}" }
        prefs.edit().putString("spots", raw).apply()
    }

    private fun spotDescription(s: Spot): String {
        val alt = if (s.alt != null) String.format(Locale.getDefault(), " · %.0f m", s.alt) else ""
        return String.format(Locale.US, "%.5f, %.5f", s.lat, s.lon) + alt
    }

    private fun refreshMarkers() {
        map.overlays.removeAll(markers.toSet())
        markers.clear()
        for (s in spots) {
            val m = Marker(map).apply {
                position = GeoPoint(s.lat, s.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = dateFmt.format(Date(s.time))
                snippet = spotDescription(s)
            }
            markers.add(m)
            map.overlays.add(m)
        }
        btnList.text = "Punti (${spots.size})"
        map.invalidate()
    }

    private fun addSpot() {
        val fix = locOverlay?.lastFix
        if (fix == null) {
            Toast.makeText(this, "Nessun segnale GPS, riprova tra poco", Toast.LENGTH_SHORT).show()
            return
        }
        val alt = if (fix.hasAltitude()) fix.altitude else null
        spots.add(Spot(fix.latitude, fix.longitude, alt, System.currentTimeMillis()))
        saveSpots()
        refreshMarkers()
        Toast.makeText(this, "Punto salvato", Toast.LENGTH_SHORT).show()
    }

    private fun showSpotList() {
        if (spots.isEmpty()) {
            Toast.makeText(this, "Nessun punto salvato", Toast.LENGTH_SHORT).show()
            return
        }
        val order = spots.indices.reversed().toList() // dal più recente
        val items = order.map { dateFmt.format(Date(spots[it].time)) + "\n" + spotDescription(spots[it]) }
        AlertDialog.Builder(this)
            .setTitle("Punti salvati")
            .setItems(items.toTypedArray()) { _, which -> showSpotActions(order[which]) }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun showSpotActions(index: Int) {
        val s = spots[index]
        AlertDialog.Builder(this)
            .setTitle(spotDescription(s))
            .setPositiveButton("Vai al punto") { _, _ ->
                locOverlay?.disableFollowLocation()
                map.controller.setZoom(17.0)
                map.controller.animateTo(GeoPoint(s.lat, s.lon))
            }
            .setNeutralButton("Elimina") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("Eliminare questo punto?")
                    .setPositiveButton("Elimina") { _, _ ->
                        spots.removeAt(index)
                        saveSpots()
                        refreshMarkers()
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasLocationPermission()) {
            startLocation()
        } else {
            info.text = "Permesso di posizione negato: attivalo dalle impostazioni dell'app."
        }
    }

    private fun startLocation() {
        if (locOverlay != null) return
        val overlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        overlay.enableMyLocation()
        overlay.enableFollowLocation()
        overlay.runOnFirstFix {
            runOnUiThread {
                map.controller.setZoom(16.0)
                overlay.myLocation?.let { map.controller.animateTo(it) }
            }
        }
        map.overlays.add(overlay)
        locOverlay = overlay

        compass = CompassOverlay(this, InternalCompassOrientationProvider(this), map).also {
            it.enableCompass()
            map.overlays.add(it)
        }
        map.invalidate()
    }

    private fun updateInfo() {
        val fix = locOverlay?.lastFix
        if (fix == null) {
            if (locOverlay != null) info.text = "In attesa del segnale GPS…"
            return
        }
        val alt = if (fix.hasAltitude()) {
            String.format(Locale.getDefault(), "%.0f m", fix.altitude)
        } else {
            "n/d"
        }
        val speed = if (fix.hasSpeed()) {
            String.format(Locale.getDefault(), "%.1f km/h", fix.speed * 3.6f)
        } else {
            "n/d"
        }
        info.text = String.format(
            Locale.getDefault(),
            "Lat %.5f   Lon %.5f\nQuota %s   Precisione ±%.0f m   Velocità %s",
            fix.latitude, fix.longitude, alt, fix.accuracy, speed
        )
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        locOverlay?.enableMyLocation()
        compass?.enableCompass()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        val c = map.mapCenter
        prefs.edit().putString("view", "${c.latitude};${c.longitude};${map.zoomLevelDouble}").apply()
        locOverlay?.disableMyLocation()
        compass?.disableCompass()
        map.onPause()
        super.onPause()
    }
}
