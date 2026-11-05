package com.gp5.launcher.car3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gp5.launcher.core.model.CarRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Home car stage. Live Filament 3D on OpenGL ES 3.0 units; otherwise (or if live 3D fails or misses
 * the frame budget) the Blender-rendered turntable, which looks the same from any of its 72 angles.
 */
@Composable
fun CarStage(scene: CarScene, renderer: CarRenderer, modifier: Modifier = Modifier) {
    val chase = steadyChase(scene)
    CarStageView(scene.copy(chase = chase), renderer, modifier)
}

/**
 * Whether to show the chase view. GPS speed wanders at a standstill (a few km/h of noise, short
 * dropouts), so the view changes only on a clear, lasting reading: chase after 2 s above 8 km/h,
 * parked again after 8 s below 4 km/h (or with no speed at all).
 */
@Composable
private fun steadyChase(scene: CarScene): Boolean {
    val s by rememberUpdatedState(scene)
    var chase by remember { mutableStateOf(scene.speedKmh > 8f) }
    LaunchedEffect(Unit) {
        var held = 0f
        while (isActive) {
            kotlinx.coroutines.delay(250)
            val v = if (s.connected) s.speedKmh else 0f
            val toward = if (chase) v < 4f else v > 8f
            held = if (toward) held + 0.25f else 0f
            if (held >= (if (chase) 8f else 2f)) { chase = !chase; held = 0f }
        }
    }
    return chase
}

@Composable
private fun CarStageView(scene: CarScene, renderer: CarRenderer, modifier: Modifier) {
    val context = LocalContext.current
    val gles3 = remember { supportsLive3d(context) }
    var failed by remember { mutableStateOf(LiveHealth.failed) }
    val live = when (renderer) {
        CarRenderer.Turntable -> false
        CarRenderer.Live -> gles3
        CarRenderer.Auto -> gles3 && failed == null
    }
    LaunchedEffect(live, failed) {
        LiveHealth.active.value =
            if (live) "Live 3D (OpenGL ES 3)" else if (!gles3) "Turntable (this unit has OpenGL ES 2 only)" else failed?.let { "Turntable ($it)" } ?: "Turntable"
    }
    if (live) {
        LiveCar(scene, modifier, onFailure = { why -> LiveHealth.failed = why; failed = why })
    } else {
        TurntableCar(scene, modifier)
    }
}

@Composable
private fun LiveCar(scene: CarScene, modifier: Modifier, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val car = remember {
        runCatching { FilamentCar.get(context) }.onFailure { Log.e("CarStage", "live 3D unavailable", it) }.getOrNull()
    }
    if (car == null) { LaunchedEffect(Unit) { onFailure("3D engine did not start") }; return }
    val fail by rememberUpdatedState(onFailure)
    car.onTooSlow = { fail("too slow on this unit") }
    car.update(scene)

    // Stop rendering while the launcher is in the background (DESIGN.md 9.1)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) car.stop()
            if (e == Lifecycle.Event.ON_RESUME) car.start()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val cam: androidx.compose.ui.graphics.drawscope.DrawScope.() -> RoadCam = {
        val p = car.pose
        RoadCam(p[0], p[1], p[2], p[3], focalPx = size.height / 2f * FilamentCar.FOCAL_MM / 12f, cx = size.width / 2f, cy = size.height / 2f)
    }
    Box(modifier.fillMaxSize().background(com.gp5.launcher.core.design.Gp5Palette.Stage)) {
        // the road and the light on it under the car, then the car (a transparent TextureView), then
        // the beams in the air when they come towards the camera
        BackdropLayer(camera = cam)
        RoadLayer(scene, camera = cam)
        BeamLayer(scene, camera = cam, front = false)
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { car.attach(it) } },
            onRelease = { car.detach() },
            modifier = Modifier.fillMaxSize(),
        )
        BeamLayer(scene, camera = cam, front = true)
    }
}

// ---------------------------------------------------------------- turntable (OpenGL ES 2 units)

private object Frames {
    const val SRC_W = 800
    const val SRC_H = 500
    /** All 72 lamps-off frames at stage size plus the lamps-on frames near the current angle. */
    val cache = object : LruCache<String, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun decode(context: Context, set: String, i: Int, targetW: Int): Bitmap? {
        val key = "$set/$i@$targetW"
        cache.get(key)?.let { return it }
        val opts = BitmapFactory.Options().apply {
            if (targetW in 1 until SRC_W) { inScaled = true; inDensity = SRC_W; inTargetDensity = targetW }
            // hardware bitmaps live in graphics memory and draw without an upload (API 26+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) inPreferredConfig = Bitmap.Config.HARDWARE
        }
        val bmp = runCatching {
            context.assets.open("car/turntable/$set/%02d.webp".format(i)).use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }
}

@Composable
private fun TurntableCar(scene: CarScene, modifier: Modifier) {
    val context = LocalContext.current
    val s by rememberUpdatedState(scene)
    val yaw = remember { YawController(CarCamera.HERO_YAW) }
    var angle by remember { mutableFloatStateOf(yaw.angle) }
    var targetW by remember { mutableIntStateOf(0) }
    var lamps by remember { mutableFloatStateOf(0f) }

    // camera swing (parked / chase) and the lamp cross-fade
    LaunchedEffect(Unit) {
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                if (yaw.step(dt, s.chase, !s.ambient)) angle = yaw.angle
                val l = if (s.lightsOn) 1f else 0f
                if (lamps != l) lamps = (lamps + (l - lamps) * (1f - exp(-dt / 0.1f))).let { if (abs(it - l) < 0.01f) l else it }
            }
        }
    }

    val count = CarCamera.TURNTABLE_FRAMES
    val index = ((angle / (360f / count)).roundToInt()) % count
    var off by remember { mutableStateOf<Bitmap?>(null) }
    var on by remember { mutableStateOf<Bitmap?>(null) }
    val lit = lamps > 0f

    // the frame on screen first (cached after warm-up, so this is instant while dragging)
    LaunchedEffect(index, targetW, lit) {
        if (targetW == 0) return@LaunchedEffect
        withContext(Dispatchers.IO) { Frames.decode(context, "off", index, targetW) }?.let { off = it }
        on = if (lit) withContext(Dispatchers.IO) { Frames.decode(context, "on", index, targetW) } else null
    }
    // then warm every lamps-off frame, nearest angles first
    LaunchedEffect(targetW) {
        if (targetW == 0) return@LaunchedEffect
        val start = index
        withContext(Dispatchers.IO) {
            for (d in 0 until count) {
                val i = (start + if (d % 2 == 0) d / 2 else count - (d + 1) / 2) % count
                Frames.decode(context, "off", i, targetW)
            }
        }
    }

    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    val m = remember { Matrix() }
    // the road, in the perspective of the Blender camera (render_stage.py: 50 mm on a 36 mm-wide sensor,
    // 7.4 m out, 1.9 m up) at the frame on screen
    val cam: androidx.compose.ui.graphics.drawscope.DrawScope.() -> RoadCam = {
        val k = min(size.width / Frames.SRC_W, size.height / Frames.SRC_H)
        val ox = (size.width - Frames.SRC_W * k) / 2f
        val oy = (size.height - Frames.SRC_H * k) / 2f
        RoadCam(index * 360f / count, 7.4f, 1.9f, 0.62f, focalPx = Frames.SRC_W / 2f * 50f / 18f * k, cx = ox + Frames.SRC_W / 2f * k, cy = oy + Frames.SRC_H / 2f * k)
    }
    Box(modifier.fillMaxSize().background(com.gp5.launcher.core.design.Gp5Palette.Stage)) {
    BackdropLayer(camera = cam)
    RoadLayer(scene, camera = cam)
    BeamLayer(scene, camera = cam, front = false)
    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { targetW = min(it.width, Frames.SRC_W) },
    ) {
        // ContentScale.Fit of an 800 x 500 frame, centred
        val k = min(size.width / Frames.SRC_W, size.height / Frames.SRC_H)
        val ox = (size.width - Frames.SRC_W * k) / 2f
        val oy = (size.height - Frames.SRC_H * k) / 2f
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            fun frame(b: Bitmap?, alpha: Float) {
                b ?: return
                paint.alpha = (alpha * 255).toInt(); paint.colorFilter = null
                m.setScale(Frames.SRC_W * k / b.width, Frames.SRC_H * k / b.height); m.postTranslate(ox, oy)
                nc.drawBitmap(b, m, paint)
            }
            frame(off, 1f)
            if (lamps > 0.01f) frame(on, lamps)
        }
    }
    BeamLayer(scene, camera = cam, front = true)
    }
}
