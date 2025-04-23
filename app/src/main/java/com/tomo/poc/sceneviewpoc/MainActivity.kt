package com.tomo.poc.sceneviewpoc

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.isValid
import io.github.sceneview.ar.getDescription
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.node.CubeNode
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnAttach
import androidx.fragment.app.Fragment
import io.github.sceneview.ar.scene.PlaneRenderer
import io.github.sceneview.node.Node

class MainActivity : AppCompatActivity(R.layout.activity_main) {

    private lateinit var sceneView: ARSceneView
    private lateinit var loadingView: View
    private lateinit var instructionText: TextView
    private lateinit var measurementText: TextView

    private var isLoading = false
        set(value) {
            field = value
            loadingView.isGone = !value
        }

    private var baseAnchorNode: AnchorNode? = null
    private var measurementAnchorNode: AnchorNode? = null
    private var trackingFailureReason: TrackingFailureReason? = null
    private var errorMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setFullScreen(
            findViewById(R.id.rootView),
            fullScreen = true,
            hideSystemBars = false,
            fitsSystemWindows = false
        )

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar)?.apply {
            doOnApplyWindowInsets { systemBarsInsets ->
                (layoutParams as ViewGroup.MarginLayoutParams).topMargin = systemBarsInsets.top
            }
            title = ""
        })

        instructionText = findViewById(R.id.instructionText)
        measurementText = findViewById(R.id.measurementText)
        loadingView = findViewById(R.id.loadingView)

        sceneView = findViewById<ARSceneView>(R.id.sceneView).apply {
            lifecycle = this@MainActivity.lifecycle

            // Required for the vertical plane to show
            planeRenderer.isEnabled = true
            planeRenderer.isVisible = true
            planeRenderer.planeRendererMode = PlaneRenderer.PlaneRendererMode.RENDER_ALL

            configureSession { session, config ->
                // Enable both horizontal and vertical plane detection
                config.apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                }

                config.depthMode = when (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    true -> Config.DepthMode.AUTOMATIC
                    else -> Config.DepthMode.DISABLED
                }
                config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
            }

            onTrackingFailureChanged = { reason ->
                this@MainActivity.trackingFailureReason = reason
                updateInstructions()
            }

            setOnGestureListener(onDown = { motionEvent, node ->
                if (node == null) {
                    sceneView.session?.let { session ->
                        val frame = session.update()
                        val hitResult = frame.hitTest(motionEvent)
                            .firstOrNull { it.isValid(depthPoint = false, point = false) }

                        hitResult?.let { hit ->
                            val trackable = hit.trackable
                            if (trackable is Plane) {
                                if (baseAnchorNode == null) {
                                    // First tap must be on horizontal plane
                                    if (trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING) {
                                        addBaseAnchorNode(hit.createAnchor())
                                        errorMessage = null
                                    } else {
                                        errorMessage =
                                            "Please select a horizontal surface (floor) first"
                                    }

                                    sceneView.session?.let { session ->
                                        session.configure(session.config.apply {
                                            planeFindingMode =
                                                Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                                        })
                                    }

                                } else if (measurementAnchorNode == null) {
                                    addMeasurementAnchorNode(
                                        hit.createAnchor(),
                                        trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING ||
                                                trackable.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING
                                    )
                                } else {
                                    // Reset on third tap
                                    resetMeasurement()
                                }
                                updateInstructions()
                            }
                        }
                    }
                }
            })
        }
    }

    private fun addBaseAnchorNode(anchor: Anchor) {
        sceneView.addChildNode(
            AnchorNode(sceneView.engine, anchor).apply {
                lifecycleScope.launch {
                    val node = createMarkerNode(Color.Green, true)

                    isLoading = true
                    addChildNode(node)
                    isLoading = false
                    baseAnchorNode = this@apply
                    updateInstructions()
                }
            }
        )
    }

    private fun addMeasurementAnchorNode(anchor: Anchor, isHorizontal: Boolean) {
        sceneView.addChildNode(
            AnchorNode(sceneView.engine, anchor).apply {
                lifecycleScope.launch {
                    isLoading = true
                    addChildNode(createMarkerNode(Color.Red, isHorizontal))
                    isLoading = false
                    measurementAnchorNode = this@apply
                    calculateHeight()
                    updateInstructions()
                }
            }
        )
    }

    private fun createMarkerNode(color: Color, isHorizontal: Boolean): CubeNode {
        val size = if (isHorizontal) {
            Position(0.1f, 0.01f, 0.1f) // Flat marker for floor
        } else {
            Position(0.05f, 0.1f, 0.01f) // Tall marker for wall
        }

        return CubeNode(
            sceneView.engine,
            size = size,
            materialInstance = sceneView.materialLoader.createColorInstance(color.copy(alpha = 0.7f))
        )
    }

    private fun calculateHeight() {
        val basePose = baseAnchorNode?.anchor?.pose
        val measurementPose = measurementAnchorNode?.anchor?.pose

        if (basePose != null && measurementPose != null) {
            val height = measurementPose.translation[1] - basePose.translation[1]
            measurementText.text = "Height: %.2f meters".format(height)
            measurementText.visibility = View.VISIBLE
        }
    }

    private fun resetMeasurement() {
        baseAnchorNode?.anchor?.detach()
        measurementAnchorNode?.anchor?.detach()
        sceneView.removeChildNode(baseAnchorNode as Node)
        sceneView.removeChildNode(measurementAnchorNode as Node)
        baseAnchorNode = null
        measurementAnchorNode = null
        measurementText.visibility = View.GONE
        sceneView.planeRenderer.isEnabled = true
        sceneView.session?.let { session ->
            session.configure(session.config.apply {
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            })
        }
    }

    private fun updateInstructions() {
        instructionText.text = trackingFailureReason?.getDescription(this) ?: errorMessage
                ?: if (baseAnchorNode == null) {
            "Tap on the floor to set base point"
        } else if (measurementAnchorNode == null) {
            "Now tap on a wall to measure height"
        } else {
            null
        }
    }

    override fun onPause() {
        super.onPause()
        sceneView.onSessionPaused
    }

    override fun onResume() {
        super.onResume()
        sceneView.onSessionResumed
    }
}

fun Activity.setKeepScreenOn(keepScreenOn: Boolean = true) {
    if (keepScreenOn) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

fun Activity.setFullScreen(
    rootView: View,
    fullScreen: Boolean = true,
    hideSystemBars: Boolean = true,
    fitsSystemWindows: Boolean = true
) {
    rootView.viewTreeObserver?.addOnWindowFocusChangeListener { hasFocus ->
        if (hasFocus) {
            WindowCompat.setDecorFitsSystemWindows(window, fitsSystemWindows)
            WindowInsetsControllerCompat(window, rootView).apply {
                if (hideSystemBars) {
                    if (fullScreen) {
                        hide(
                            WindowInsetsCompat.Type.statusBars() or
                                    WindowInsetsCompat.Type.navigationBars()
                        )
                    } else {
                        show(
                            WindowInsetsCompat.Type.statusBars() or
                                    WindowInsetsCompat.Type.navigationBars()
                        )
                    }
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
    }
}

fun View.doOnApplyWindowInsets(action: (systemBarsInsets: Insets) -> Unit) {
    doOnAttach {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            action(insets.getInsets(WindowInsetsCompat.Type.systemBars()))
            WindowInsetsCompat.CONSUMED
        }
    }
}
