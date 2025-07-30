package com.example.avatar3d

import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.filament.*
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.*
import com.google.android.filament.gltfio.*
import java.nio.ByteBuffer
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        init {
            Utils.init() // Initialize Filament native libraries
        }
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var camera: Camera
    private lateinit var view: View
    private lateinit var uiHelper: UiHelper
    private var swapChain: SwapChain? = null
    private lateinit var skybox: Skybox

    private lateinit var assetLoader: AssetLoader
    private lateinit var materialProvider: MaterialProvider
    private lateinit var entityManager: EntityManager
    private var filamentAsset: FilamentAsset? = null
    private var animator: Animator? = null
    private var animationStartTime: Long = 0L

    // Camera control variables
    private var rotationX = 0f
    private var rotationY = 0f
    private var distance = 5f
    private var panX = 0f
    private var panY = 0f
    private var previousX = 0f
    private var previousY = 0f
    private var scaleDetector: ScaleGestureDetector? = null

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)
            swapChain?.let { swap ->
                if (renderer.beginFrame(swap, frameTimeNanos)) {
                    renderer.setClearOptions(Renderer.ClearOptions().apply {
                        clear = true
                        clearColor = floatArrayOf(0.2f, 0.2f, 0.3f, 1.0f)
                    })
                    // Update animation
                    filamentAsset?.let { asset ->
                        animator?.let { anim ->
                            if (anim.animationCount > 0) {
                                if (animationStartTime == 0L) {
                                    animationStartTime = frameTimeNanos
                                }
                                val elapsedTimeSeconds = (frameTimeNanos - animationStartTime) / 1_000_000_000.0f
                                try {
                                    anim.applyAnimation(0, elapsedTimeSeconds)
                                    anim.updateBoneMatrices()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error applying animation: ${e.message}", e)
                                }
                            } else {
                                Log.w(TAG, "No animations available to play")
                            }
                        } ?: Log.w(TAG, "Animator is null")
                    }
                    renderer.render(view)
                    renderer.endFrame()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Filament components
        surfaceView = findViewById(R.id.surface_view)
        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        entityManager = EntityManager.get()
        camera = engine.createCamera(entityManager.create())

        // Create and configure View
        view = engine.createView().apply {
            scene = this@MainActivity.scene
            camera = this@MainActivity.camera
            setMultiSampleAntiAliasingOptions(View.MultiSampleAntiAliasingOptions().apply {
                enabled = true
                sampleCount = 4
            })
        }

        // Add a simple skybox
        skybox = Skybox.Builder()
            .color(0.1f, 0.2f, 0.4f, 1.0f)
            .build(engine)
        scene.skybox = skybox

        // Setup UiHelper for Surface lifecycle
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            renderCallback = object : UiHelper.RendererCallback {
                override fun onNativeWindowChanged(surface: Surface) {
                    swapChain?.let { engine.destroySwapChain(it) }
                    swapChain = engine.createSwapChain(surface)
                    Log.d(TAG, "SwapChain created")
                }

                override fun onDetachedFromSurface() {
                    swapChain?.let {
                        engine.destroySwapChain(it)
                        swapChain = null
                        Log.d(TAG, "SwapChain destroyed")
                    }
                }

                override fun onResized(width: Int, height: Int) {
                    val aspect = if (height > 0) width.toDouble() / height else 1.0
                    camera.setProjection(
                        45.0,
                        aspect,
                        0.01,
                        50.0,
                        Camera.Fov.VERTICAL
                    )
                    view.setViewport(Viewport(0, 0, width, height))
                    Log.d(TAG, "Resized: $width x $height, aspect: $aspect")
                }
            }
            attachTo(surfaceView)
        }

        // Add a directional light
        val light = entityManager.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(200_000f)
            .direction(0.0f, -0.5f, -1.0f)
            .castShadows(true)
            .build(engine, light)
        scene.addEntity(light)

        // Initialize asset loader
        materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, entityManager)

        // Load GLB model
        loadGlbModel("talking.glb")

        // Set up gesture detectors
        setupGestures()

        // Initial camera update
        updateCamera()
    }

    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                distance /= detector.scaleFactor
                distance = distance.coerceIn(1f, 20f) // zoom limits
                updateCamera()
                return true
            }
        })

        surfaceView.setOnTouchListener { _, event ->
            scaleDetector?.onTouchEvent(event)
            if (event.pointerCount == 1 && !scaleDetector!!.isInProgress) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        previousX = event.x
                        previousY = event.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - previousX
                        val dy = event.y - previousY
                        if (event.pointerCount == 1) {
                            rotationX += dx * 0.5f
                            rotationY += dy * 0.5f
                            rotationY = rotationY.coerceIn(-90f, 90f)
                            updateCamera()
                        }
                        previousX = event.x
                        previousY = event.y
                    }
                }
            } else if (event.pointerCount == 2 && !scaleDetector!!.isInProgress) {
                // Pan with two fingers
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - previousX
                        val dy = event.y - previousY
                        panX -= dx * 0.01f
                        panY += dy * 0.01f
                        updateCamera()
                        previousX = event.x
                        previousY = event.y
                    }
                }
            }
            true
        }
    }

    private fun updateCamera() {
        val radX = Math.toRadians(rotationX.toDouble())
        val radY = Math.toRadians(rotationY.toDouble())

        val eyeX = (distance * cos(radY) * sin(radX)).toFloat() + panX
        val eyeY = (distance * sin(radY)).toFloat() + panY
        val eyeZ = (distance * cos(radY) * cos(radX)).toFloat()

        camera.lookAt(
            eyeX.toDouble(), eyeY.toDouble(), eyeZ.toDouble(),
            panX.toDouble(), panY.toDouble(), 0.0,
            0.0, 1.0, 0.0
        )
    }

    private fun loadGlbModel(fileName: String) {
        try {
            val buffer = assets.open(fileName).use { input ->
                val bytes = ByteArray(input.available())
                input.read(bytes)
                ByteBuffer.wrap(bytes)
            }

            filamentAsset = assetLoader.createAsset(buffer)
            filamentAsset?.let { asset ->
                ResourceLoader(engine).loadResources(asset)

                // Initialize animator from FilamentInstance
                val instance = asset.getInstance()
                animator = instance?.getAnimator()
                if (animator == null) {
                    Log.e(TAG, "Animator is null for $fileName")
                    return@let
                }

                if (animator?.animationCount ?: 0 > 0) {
                    Log.d(TAG, "Found ${animator?.animationCount} animations")
                    for (i in 0 until animator!!.animationCount) {
                        Log.d(TAG, "Animation $i: duration = ${animator!!.getAnimationDuration(i)} seconds")
                    }
                } else {
                    Log.w(TAG, "No animations found in $fileName")
                }

                val bounds = asset.boundingBox
                val center = bounds.center
                val halfExtent = bounds.halfExtent
                val min = floatArrayOf(
                    center[0] - halfExtent[0],
                    center[1] - halfExtent[1],
                    center[2] - halfExtent[2]
                )
                val max = floatArrayOf(
                    center[0] + halfExtent[0],
                    center[1] + halfExtent[1],
                    center[2] + halfExtent[2]
                )

                val size = floatArrayOf(
                    max[0] - min[0],
                    max[1] - min[1],
                    max[2] - min[2]
                )
                val maxSize = maxOf(size[0], size[1], size[2], 0.0001f)
                val scale = 2.0f / maxSize
                val translation = floatArrayOf(-center[0], -center[1], -center[2])

                val transform = FloatArray(16)
                android.opengl.Matrix.setIdentityM(transform, 0)
                android.opengl.Matrix.scaleM(transform, 0, scale, scale, scale)
                android.opengl.Matrix.translateM(transform, 0, translation[0], translation[1], translation[2])

                val transformManager = engine.transformManager
                val instanceTransform = transformManager.getInstance(asset.root)
                transformManager.setTransform(instanceTransform, transform)

                asset.releaseSourceData()
                scene.addEntities(asset.entities)

                Log.d(TAG, "GLB model loaded successfully: $fileName")
            } ?: run {
                Log.e(TAG, "Failed to create FilamentAsset for $fileName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading GLB model: $fileName", e)
        }
    }

    override fun onResume() {
        super.onResume()
        animationStartTime = 0L // Reset animation time
        choreographer.postFrameCallback(frameCallback)
        Log.d(TAG, "Rendering loop started")
    }

    override fun onPause() {
        super.onPause()
        choreographer.removeFrameCallback(frameCallback)
        Log.d(TAG, "Rendering loop stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            filamentAsset?.let { asset ->
                scene.removeEntities(asset.entities)
                assetLoader.destroyAsset(asset)
            }
            swapChain?.let { engine.destroySwapChain(it) }
            engine.destroySkybox(skybox)
            engine.destroyRenderer(renderer)
            engine.destroyView(view)
            engine.destroyCameraComponent(camera.entity)
            engine.destroyEntity(camera.entity)
            (materialProvider as? UbershaderProvider)?.destroyMaterials()
            assetLoader.destroy()
            engine.destroy()
            uiHelper.detach()
            Log.d(TAG, "Filament resources cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}