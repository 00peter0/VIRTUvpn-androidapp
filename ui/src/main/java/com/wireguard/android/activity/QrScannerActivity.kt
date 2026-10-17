package com.wireguard.android.activity

import android.content.Intent
import android.graphics.PointF
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import com.wireguard.android.R
import com.wireguard.android.databinding.QrScannerActivityBinding
import com.wireguard.android.util.AppPermissionAccess
import com.wireguard.android.util.NativeQrScanContract
import com.wireguard.android.util.QrFrameDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One foreground, local camera for native imports and Router pairing. */
class QrScannerActivity : AppCompatActivity() {
    private lateinit var binding: QrScannerActivityBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val decoder = QrFrameDecoder()
    private val completed = AtomicBoolean(false)
    private val resultPending = AtomicBoolean(false)
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var generation = 0
    @Volatile private var failed = false
    @Volatile private var windowFocused = false
    private var touchFocusPoint: PointF? = null
    private var lastDecodeAt = 0L // Analysis worker only; at most eight attempts per second.
    private var focusedGeneration = -1
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (AppPermissionAccess.cameraAllowed(this)) startCamera() else showPermissionNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark("#071018".toColorInt()), navigationBarStyle = SystemBarStyle.dark("#071018".toColorInt()))
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding = QrScannerActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        binding.qrToolbar.setNavigationOnClickListener { finish() }
        binding.qrPrompt.text = intent.getStringExtra(NativeQrScanContract.EXTRA_PROMPT) ?: getString(R.string.vcs_qr_title)
        binding.qrPreview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        binding.qrPreview.scaleType = PreviewView.ScaleType.FIT_CENTER
        binding.qrPreview.setOnClickListener {
            val point = touchFocusPoint
            touchFocusPoint = null
            focus(point?.x ?: (binding.qrPreview.width / 2f), point?.y ?: (binding.qrPreview.height / 2f))
        }
        binding.qrPreview.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP -> { touchFocusPoint = PointF(event.x, event.y); view.performClick(); true }
                else -> false
            }
        }
        binding.qrPreview.previewStreamState.observe(this) { state ->
            if (state == PreviewView.StreamState.STREAMING && !failed) {
                binding.qrStatus.setText(R.string.vcs_qr_hint)
                if (focusedGeneration != generation) {
                    focusedGeneration = generation
                    focus(binding.qrPreview.width / 2f, binding.qrPreview.height / 2f)
                }
            }
        }
        binding.qrRetry.setOnClickListener {
            if (AppPermissionAccess.cameraAllowed(this)) startCamera()
            else permission.launch(PermissionsActivity.intent(this, PermissionsActivity.CAMERA, requestOnOpen = true))
        }
        binding.qrLight.setOnClickListener {
            camera?.let { it.cameraControl.enableTorch(it.cameraInfo.torchState.value != TorchState.ON) }
        }
    }

    override fun onStart() {
        super.onStart()
        if (AppPermissionAccess.cameraAllowed(this)) startCamera() else showPermissionNeeded()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused = hasFocus // Help and system dialogs pause recognition without consuming a result.
    }

    override fun onStop() {
        generation++ // Invalidate pending frames/futures; CameraX stops the camera with this lifecycle.
        super.onStop()
    }

    override fun onDestroy() {
        generation++
        releaseOwnedCases()
        executor.shutdown()
        super.onDestroy()
    }

    private fun showPermissionNeeded() {
        generation++
        failed = true
        releaseOwnedCases()
        binding.qrStatus.setText(R.string.vcs_qr_permission_needed)
        binding.qrRetry.isVisible = true
    }

    private fun startCamera() {
        if (isFinishing || isDestroyed || completed.get()) return
        val current = ++generation
        failed = false
        binding.qrStatus.setText(R.string.vcs_qr_opening)
        binding.qrRetry.isVisible = false
        releaseOwnedCases()
        val future = try { ProcessCameraProvider.getInstance(this) }
        catch (_: Exception) { showCameraFailure(current); return }
        future.addListener({
            if (current != generation || isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@addListener
            if (!AppPermissionAccess.cameraAllowed(this)) { showPermissionNeeded(); return@addListener }
            try {
                val cameraProvider = future.get().also { provider = it }
                val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                val cameraPreview = Preview.Builder().build().also { preview = it; it.surfaceProvider = binding.qrPreview.surfaceProvider }
                val cameraAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(
                        Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                    .build().also { analysis = it }
                cameraAnalysis.setAnalyzer(executor) { image ->
                    try {
                        val now = SystemClock.elapsedRealtime()
                        if (current != generation || failed || !windowFocused || completed.get() || now - lastDecodeAt < 125) return@setAnalyzer
                        lastDecodeAt = now
                        val plane = image.planes[0]
                        val text = decoder.decode(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
                        if (text != null && resultPending.compareAndSet(false, true)) runOnUiThread {
                            try {
                                if (current == generation && windowFocused && !isFinishing && !isDestroyed &&
                                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && completed.compareAndSet(false, true)) {
                                    setResult(RESULT_OK, Intent().putExtra(NativeQrScanContract.EXTRA_RESULT, text))
                                    finish()
                                }
                            } finally { resultPending.set(false) }
                        }
                    } catch (error: RuntimeException) {
                        Log.w("VirtuVPN/QrScanner", "Camera analysis failed: ${error.javaClass.simpleName}")
                        runOnUiThread { showCameraFailure(current) }
                    } finally { image.close() }
                }
                val active = cameraProvider.bindToLifecycle(this, selector, cameraPreview, cameraAnalysis)
                camera = active
                binding.qrLight.isVisible = active.cameraInfo.hasFlashUnit()
                active.cameraInfo.torchState.observe(this) { binding.qrLight.setText(if (it == TorchState.ON) R.string.vcs_qr_light_off else R.string.vcs_qr_light_on) }
                active.cameraInfo.cameraState.observe(this) { if (it.error != null) showCameraFailure(current) }
            } catch (error: Exception) {
                Log.w("VirtuVPN/QrScanner", "Camera unavailable: ${error.javaClass.simpleName}")
                showCameraFailure(current)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun focus(x: Float, y: Float) {
        val active = camera ?: return
        if (failed || binding.qrPreview.width == 0 || binding.qrPreview.height == 0) return
        val point = binding.qrPreview.meteringPointFactory.createPoint(x, y)
        active.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).setAutoCancelDuration(3, TimeUnit.SECONDS).build())
    }

    private fun showCameraFailure(current: Int) {
        if (current != generation || isFinishing || isDestroyed || failed) return
        failed = true
        releaseOwnedCases()
        binding.qrStatus.setText(R.string.vcs_qr_camera_error)
        binding.qrRetry.isVisible = true
    }

    private fun releaseOwnedCases() {
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        camera?.cameraInfo?.torchState?.removeObservers(this)
        analysis?.clearAnalyzer()
        listOfNotNull(preview, analysis).takeIf { it.isNotEmpty() }?.let { provider?.unbind(*it.toTypedArray()) }
        camera = null
        preview = null
        analysis = null
        if (::binding.isInitialized) binding.qrLight.isVisible = false
    }
}
