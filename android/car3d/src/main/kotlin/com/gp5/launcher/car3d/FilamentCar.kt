package com.gp5.launcher.car3d

import android.content.Context
import android.opengl.Matrix
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.HDRLoader
import com.google.android.filament.utils.IBLPrefilterContext
import com.google.android.filament.utils.Utils
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Live 3D car (Filament, OpenGL ES 3.0). One instance per process: the engine, IBL and the 4 MB model
 * stay loaded while the launcher runs, so returning to Home is instant; only the TextureView attaches
 * and detaches. All Filament calls happen on the main thread (Choreographer).
 *
 * Asset contract (blender/build_android.py): metres, front = +Z, nodes GP5_Wheel_{FL,FR,RL,RR} spin
 * about X, materials GP5_Head_* / GP5_Tail_* light up.
 */
class FilamentCar private constructor(private val app: Context) : Choreographer.FrameCallback {

    companion object {
        private const val TAG = "FilamentCar"
        /** Body panel materials (GP5_Paint, plus panels the source model left in its own dark metal). */
        private val PAINT = setOf("GP5_Paint", "Color_001_1", "Material22", "material_36")
        private const val TARGET_Y = 0.62f
        /** Lens focal length (mm, 24 mm-high film), shared with the road drawn behind the car. */
        internal const val FOCAL_MM = 50f
        private const val WHEEL_RADIUS = 0.296f
        private const val FRAME_NS = 30_000_000L                     // 30 fps cap (DESIGN.md 9.1)
        private val CLEAR = doubleArrayOf(0.0, 0.0, 0.0, 0.0)           // transparent: the stage and road are drawn behind

        @Volatile private var instance: FilamentCar? = null
        fun get(context: Context): FilamentCar =
            instance ?: synchronized(this) { instance ?: FilamentCar(context.applicationContext).also { instance = it } }
    }

    init { Utils.init() }

    private val engine: Engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity: Int = EntityManager.get().create()
    // the camera's model matrix lives in its entity's transform component: create it before lookAt()
    private val camera: Camera = engine.transformManager.create(cameraEntity).let { engine.createCamera(cameraEntity) }
    private val assetLoader = AssetLoader(engine, UbershaderProvider(engine), EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)
    private var asset: FilamentAsset? = null
    private var resourcesReady = false
    private var ibl: IndirectLight? = null
    private var iblTools: Any? = null

    private var uiHelper: UiHelper? = null
    private var displayHelper: DisplayHelper? = null
    private var swapChain: SwapChain? = null
    private var running = false

    // scene state, animated each frame
    private var target = CarScene()
    private val yaw = YawController(CarCamera.HERO_YAW)
    private var drive = 0f                 // 0 parked camera .. 1 chase camera
    /** Camera now: yaw, distance, height, target height (for the road layer). */
    /** Snapshot state (written on the main thread), so the road and beam layers redraw as the camera moves. */
    var pose by androidx.compose.runtime.mutableStateOf(floatArrayOf(CarCamera.HERO_YAW, 6.4f, 1.75f, TARGET_Y)); private set
    private var lamps = 0f                 // 0 off .. 1 on
    private var wheelAngle = 0f
    private var lastFrameNs = 0L
    private var lastRenderNs = 0L
    private var dirty = true

    private val wheels = mutableListOf<Pair<Int, FloatArray>>()   // entity, original local transform
    private val headLamps = mutableListOf<MaterialInstance>()
    private val tailLamps = mutableListOf<MaterialInstance>()
    private var floor: MaterialInstance? = null
    private var floorDay = false

    /** Called after the frame budget has been missed for 3 s while animating. */
    var onTooSlow: (() -> Unit)? = null
    private var slowSince = 0L

    init {
        view.scene = scene
        view.blendMode = View.BlendMode.TRANSLUCENT
        view.camera = camera
        view.antiAliasing = View.AntiAliasing.NONE          // MSAA below is enough; FXAA costs a full pass
        view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply { enabled = true; sampleCount = 4 }
        view.bloomOptions = view.bloomOptions.apply { enabled = false }   // no halo: the beams are drawn as light in the scene
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply { enabled = false }
        view.colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.Agx()).build(engine)
        camera.setExposure(1f)
        renderer.clearOptions = renderer.clearOptions.apply { clear = true; clearColor = CLEAR }
        loadIbl()
        loadCar()
    }

    // ---------------------------------------------------------------- loading

    private fun asset(name: String): ByteBuffer {
        val bytes = app.assets.open(name).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }
    }

    /** Studio reflections: equirect HDR (rendered in Blender) -> cubemap -> prefiltered specular mips. */
    private fun loadIbl() {
        val equirect = HDRLoader.createTexture(engine, asset("car/studio.hdr")) ?: return
        val ctx = IBLPrefilterContext(engine)
        val toCube = IBLPrefilterContext.EquirectangularToCubemap(ctx)
        val specular = IBLPrefilterContext.SpecularFilter(ctx)
        val cube = toCube.run(equirect)
        val reflections = specular.run(cube)
        engine.destroyTexture(equirect); engine.destroyTexture(cube)
        // The prefilter context stays alive: destroying it also tore down our camera's transform
        // (SIGSEGV in Camera.lookAt on the first frame, Filament 1.77.3). It is small and made once.
        iblTools = Triple(ctx, toCube, specular)
        ibl = IndirectLight.Builder().reflections(reflections).intensity(2.8f).build(engine)
        scene.indirectLight = ibl
    }

    private fun loadCar() {
        val a = assetLoader.createAsset(asset("car/fit-gp5.glb")) ?: run { Log.e(TAG, "car asset failed"); return }
        asset = a
        resourceLoader.asyncBeginLoad(a)
        scene.addEntities(a.entities)
        val tm = engine.transformManager
        for (name in listOf("GP5_Wheel_FL", "GP5_Wheel_FR", "GP5_Wheel_RL", "GP5_Wheel_RR")) {
            val e = a.getFirstEntityByName(name)
            if (e == 0) continue
            val m = FloatArray(16); tm.getTransform(tm.getInstance(e), m)
            wheels += e to m
        }
        for (mi in a.instance.materialInstances) {
            when {
                mi.name.startsWith("GP5_Head_") -> headLamps += mi
                mi.name.startsWith("GP5_Tail_") -> tailLamps += mi
                mi.name == "GP5_Floor" -> floor = mi
                mi.name in PAINT -> tunePaint(mi)
                mi.name == "Grey_Plastic1" -> mi.setParameter("roughnessFactor", 0.35f)   // mirror-sharp trim in the export
            }
        }
        applyLamps(0f)
        Log.i(TAG, "car: ${a.entities.size} entities, ${wheels.size} wheels, ${headLamps.size} head / ${tailLamps.size} tail lamp materials")
    }

    // ---------------------------------------------------------------- view attach

    fun attach(textureView: TextureView) {
        detach()
        displayHelper = DisplayHelper(textureView.context)
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            isOpaque = false
            renderCallback = object : UiHelper.RendererCallback {
                override fun onNativeWindowChanged(surface: Surface) {
                    swapChain?.let { engine.destroySwapChain(it) }
                    swapChain = engine.createSwapChain(surface, uiHelper?.swapChainFlags ?: 0L)
                    displayHelper?.attach(renderer, textureView.display)
                    dirty = true
                }
                override fun onDetachedFromSurface() {
                    displayHelper?.detach()
                    swapChain?.let { engine.destroySwapChain(it); engine.flushAndWait() }
                    swapChain = null
                }
                override fun onResized(width: Int, height: Int) {
                    view.viewport = Viewport(0, 0, width, height)
                    camera.setLensProjection(FOCAL_MM.toDouble(), width.toDouble() / height, 0.1, 100.0)
                    dirty = true
                }
            }
            attachTo(textureView)
        }
        start()
    }

    fun detach() {
        stop()
        uiHelper?.detach(); uiHelper = null
        displayHelper?.detach(); displayHelper = null
    }

    fun start() { if (!running) { running = true; lastFrameNs = 0; Choreographer.getInstance().postFrameCallback(this) } }
    fun stop() { running = false; Choreographer.getInstance().removeFrameCallback(this) }

    // ---------------------------------------------------------------- inputs

    fun update(s: CarScene) {
        if (s != target) dirty = true
        target = s
    }


    // ---------------------------------------------------------------- frame

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        val dt = if (lastFrameNs == 0L) 0f else ((frameTimeNanos - lastFrameNs) / 1e9f).coerceAtMost(0.1f)
        lastFrameNs = frameTimeNanos
        val a = asset ?: return
        if (swapChain == null) return

        if (!resourcesReady) {
            resourceLoader.asyncUpdateLoad()
            if (resourceLoader.asyncGetLoadProgress() >= 1f) {
                resourcesReady = true; a.releaseSourceData(); dirty = true
            }
        }

        val animating = step(dt, frameTimeNanos)
        if (!(animating || dirty)) return
        if (frameTimeNanos - lastRenderNs < FRAME_NS && !dirty) return
        watchBudget(frameTimeNanos, animating)
        lastRenderNs = frameTimeNanos
        dirty = false

        val sc = swapChain ?: return
        if (renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    /** Advances the animation. Returns true while anything is still moving. */
    private fun step(dt: Float, now: Long): Boolean {
        val s = target
        var moving = false
        // camera: front three-quarter parked -> chase view from behind while driving
        val d0 = drive
        drive += ((if (s.chase) 1f else 0f) - drive) * (1f - exp(-dt / if (s.chase) 0.9f else 2.2f))
        if (abs(drive - d0) > 1e-4f) moving = true
        if (yaw.step(dt, s.chase, !s.ambient)) moving = true
        // wheels roll with the speed
        if (s.speedKmh > 0.1f) {
            wheelAngle = (wheelAngle + s.speedKmh / 3.6f / WHEEL_RADIUS * dt) % (2f * Math.PI.toFloat())
            moving = true
        }
        // lamps fade in and out (250 ms)
        val l0 = lamps
        lamps += ((if (s.lightsOn) 1f else 0f) - lamps) * (1f - exp(-dt / 0.12f))
        if (abs(lamps - l0) > 1e-3f) { applyLamps(lamps); moving = true }
        if (s.day != floorDay) applyFloor(s.day)

        placeCamera()
        spinWheels()
        return moving
    }

    private fun placeCamera() {
        val r = Math.toRadians(yaw.angle.toDouble())
        val dist = lerp(6.4f, 7.4f, drive)
        val h = lerp(1.75f, 2.0f, drive)
        val ex = (sin(r) * dist).toFloat(); val ez = (cos(r) * dist).toFloat()
        val p = pose
        if (p[0] != yaw.angle || p[1] != dist || p[2] != h) pose = floatArrayOf(yaw.angle, dist, h, TARGET_Y)
        camera.lookAt(ex.toDouble(), h.toDouble(), ez.toDouble(), 0.0, TARGET_Y.toDouble(), 0.0, 0.0, 1.0, 0.0)
    }

    private val rot = FloatArray(16); private val out = FloatArray(16)
    private fun spinWheels() {
        val tm = engine.transformManager
        val deg = Math.toDegrees(wheelAngle.toDouble()).toFloat()
        Matrix.setRotateM(rot, 0, deg, 1f, 0f, 0f)
        for ((e, base) in wheels) {
            Matrix.multiplyMM(out, 0, base, 0, rot, 0)
            tm.setTransform(tm.getInstance(e), out)
        }
    }

    /**
     * The baked floor is a dark grey glow with a black shadow, made for the dark stage. By day the glow
     * is brightened to the light stage colour so only the soft shadow remains.
     */
    private fun applyFloor(day: Boolean) {
        floorDay = day
        val k = if (day) 8f else 1f
        floor?.setParameter("baseColorFactor", com.google.android.filament.Colors.RgbaType.LINEAR, k, k, k, 1f)
    }

    /**
     * Black paint that reads as a car, not a mirror: the export has a perfectly sharp clearcoat (and a few
     * panels in a milky grey metal), which turn the studio's area lights into hard white blotches. A
     * slightly softer clearcoat and one paint for every body panel give smooth, even highlights.
     */
    private fun tunePaint(mi: MaterialInstance) {
        // gltfio ubershader parameters: every lit material has the base / metallic / roughness factors,
        // only clearcoat variants (GP5_Paint) have the clearcoat ones
        mi.setParameter("baseColorFactor", com.google.android.filament.Colors.RgbaType.LINEAR, 0.010f, 0.010f, 0.012f, 1f)
        mi.setParameter("metallicFactor", 0.15f)
        mi.setParameter("roughnessFactor", 0.42f)
        if (mi.name == "GP5_Paint") {
            mi.setParameter("clearCoatFactor", 0.55f)
            mi.setParameter("clearCoatRoughnessFactor", 0.32f)
        }
    }

    private fun applyLamps(level: Float) {
        for (mi in headLamps) mi.setParameter("emissiveFactor", 1.0f * level * 2f, 0.95f * level * 2f, 0.85f * level * 2f)
        for (mi in tailLamps) mi.setParameter("emissiveFactor", 0.9f * level, 0.012f * level, 0.01f * level)
    }

    private fun watchBudget(now: Long, animating: Boolean) {
        if (!animating || lastRenderNs == 0L) { slowSince = 0; return }
        val interval = now - lastRenderNs
        if (interval > 70_000_000L) {                 // under ~14 fps
            if (slowSince == 0L) slowSince = now
            else if (now - slowSince > 3_000_000_000L) { slowSince = 0; onTooSlow?.invoke() }
        } else slowSince = 0
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
