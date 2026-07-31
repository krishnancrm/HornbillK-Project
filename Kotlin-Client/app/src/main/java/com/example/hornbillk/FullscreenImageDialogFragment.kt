package com.example.hornbillk

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.DialogFragment
import kotlin.math.min
import kotlin.math.sqrt

class FullscreenImageDialogFragment : DialogFragment(), GestureDetector.OnDoubleTapListener {

    companion object {
        private const val ARG_IMAGE_BYTES = "image_bytes"
        private const val POPUP_DURATION_MS = 30000L // 30 seconds

        fun newInstance(imageBytes: ByteArray?): FullscreenImageDialogFragment {
            val fragment = FullscreenImageDialogFragment()
            val args = Bundle()
            args.putByteArray(ARG_IMAGE_BYTES, imageBytes)
            fragment.arguments = args
            return fragment
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { dismiss() }

    private lateinit var imageView: ImageView
    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private var matrix: Matrix = Matrix()
    private var savedMatrix: Matrix = Matrix()
    private var mode = Mode.NONE

    private var start = PointF()
    private var oldDist = 1f
    private var minScale = 1f
    private var maxScale = 5f

    private var imageWidth = 0
    private var imageHeight = 0

    private enum class Mode {
        NONE, DRAG, ZOOM
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Dialog)
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog
        if (dialog != null) {
            val displayMetrics = resources.displayMetrics

            // 1. Get the Screen Width (e.g., 1080 pixels)
            val screenWidth = displayMetrics.widthPixels

            // 2. FORCE SQUARE: Set Height = Width
            // This forces the window to be a perfect square box.
            dialog.window?.setLayout(screenWidth, screenWidth)

            dialog.window?.setGravity(android.view.Gravity.CENTER)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.dialog_fullscreen_image, container, false)
        imageView = view.findViewById(R.id.fullscreen_image_view)

        imageView.adjustViewBounds = false

        // --- FIX 2: ENABLE MANUAL SCALING ---
        imageView.scaleType = ImageView.ScaleType.MATRIX

        gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                handleDoubleTap()
                return true
            }
        })

        scaleGestureDetector = ScaleGestureDetector(requireContext(), ScaleListener())

        setupTouchListener()

        // Load Image
        val imageBytes = arguments?.getByteArray(ARG_IMAGE_BYTES)
        if (imageBytes != null) {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            }
        }

        // Apply Math after layout is ready
        imageView.post {
            centerImage()
        }

        handler.postDelayed(dismissRunnable, POPUP_DURATION_MS)
        return view
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        imageView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)

            val curr = PointF(event.x, event.y)

            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    savedMatrix.set(matrix)
                    start.set(curr)
                    mode = Mode.DRAG
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    oldDist = spacing(event)
                    if (oldDist > 10f) {
                        savedMatrix.set(matrix)
                        mode = Mode.ZOOM
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == Mode.DRAG) {
                        matrix.set(savedMatrix)
                        matrix.postTranslate(curr.x - start.x, curr.y - start.y)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    mode = Mode.NONE
                }
            }
            imageView.imageMatrix = matrix
            true
        }
    }

    private fun centerImage() {
        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()

        val drawable = imageView.drawable ?: return
        val contentWidth = drawable.intrinsicWidth.toFloat()
        val contentHeight = drawable.intrinsicHeight.toFloat()

        if (viewWidth == 0f || viewHeight == 0f || contentWidth == 0f || contentHeight == 0f) return

        // 1. Calculate the scale needed for Width and Height
        val scaleX = viewWidth / contentWidth
        val scaleY = viewHeight / contentHeight

        // 2. USE 'MIN' TO KEEP ORIGINAL SHAPE (No Squishing, No Trimming)
        // This ensures the WHOLE video fits inside the screen.
        val scale = min(scaleX, scaleY)

        // 3. Calculate centering positions
        val redundantYSpace = viewHeight - (scale * contentHeight)
        val redundantXSpace = viewWidth - (scale * contentWidth)

        // 4. Apply Scale and Center it
        matrix.setScale(scale, scale)
        matrix.postTranslate(redundantXSpace / 2, redundantYSpace / 2)

        imageView.imageMatrix = matrix
    }

    private fun handleDoubleTap() {
        // Safely call the function in MainActivity
        (activity as? MainActivity)?.stopMonitoringFromDoubleTap()
        dismiss()
    }

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt(x * x + y * y)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var scaleFactor = detector.scaleFactor
            val values = FloatArray(9)
            matrix.getValues(values)
            val currentScale = values[Matrix.MSCALE_X]

            if (currentScale * scaleFactor < minScale) {
                scaleFactor = minScale / currentScale
            } else if (currentScale * scaleFactor > maxScale) {
                scaleFactor = maxScale / currentScale
            }

            matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(dismissRunnable)
    }

    override fun onDoubleTap(e: MotionEvent): Boolean = true
    override fun onDoubleTapEvent(e: MotionEvent): Boolean = false
    override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false
}