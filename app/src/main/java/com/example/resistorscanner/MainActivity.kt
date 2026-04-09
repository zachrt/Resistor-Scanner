package com.example.resistorscanner

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.app.ActivityCompat
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.example.resistorscanner.databinding.ActivityMainBinding
import android.util.Log
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.material.snackbar.Snackbar

// --- DATA MODEL ---
enum class ResistorColor(val value: Int, val multiplier: Double, val tolerance: String?) {
    BLACK(0, 1.0, null),
    BROWN(1, 10.0, "±1%"),
    RED(2, 100.0, "±2%"),
    ORANGE(3, 1000.0, null),
    YELLOW(4, 10000.0, null),
    GREEN(5, 100000.0, "±0.5%"),
    BLUE(6, 1000000.0, "±0.25%"),
    VIOLET(7, 10000000.0, "±0.1%"),
    GREY(8, 100000000.0, "±0.05%"),
    WHITE(9, 1000000000.0, null),
    GOLD(-1, 0.1, "±5%"),
    SILVER(-1, 0.01, "±10%"),
    BODY(-2, 0.0, null)
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null

    // State Management
    private var originalBitmap: Bitmap? = null
    private var baseBitmap: Bitmap? = null
    private var currentPickingIndex = 0
    private val pickedColors = mutableListOf(ResistorColor.BODY, ResistorColor.BODY, ResistorColor.BODY, ResistorColor.BODY)
    private val markerPoints = mutableListOf<PointF>()

    // Zooming State
    private var matrix = Matrix()
    private var savedMatrix = Matrix()
    private var mode = 0
    private val startPoint = PointF()
    private val midPoint = PointF()
    private var oldDist = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "Resistor Scanner"

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.controlsContainer.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        if (allPermissionsGranted()) {
            binding.viewFinder.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }

        binding.captureButton.setOnClickListener { takePhoto() }
        binding.retryButton.setOnClickListener { resetCamera() }
        binding.undoButton?.setOnClickListener { undoLastPick() }

        binding.chartButton.setOnClickListener {
            val intent = android.content.Intent(this, ChartActivity::class.java)
            intent.putExtra("BAND1", pickedColors[0].name)
            intent.putExtra("BAND2", pickedColors[1].name)
            intent.putExtra("BAND3", pickedColors[2].name)
            intent.putExtra("BAND4", pickedColors[3].name)
            startActivity(intent)
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val fullBitmap = image.toCorrectBitmap()
                    val cropped = cropToReticle(fullBitmap)

                    // FIXED: Fallback config for safety
                    val config = cropped.config ?: Bitmap.Config.ARGB_8888
                    originalBitmap = cropped.copy(config, true)
                    baseBitmap = cropped

                    image.close()
                    runOnUiThread { showSelectionUI() }
                }
                override fun onError(exc: ImageCaptureException) { Log.e("ScannerApp", "Error", exc) }
            }
        )
    }

    private fun showSelectionUI() {
        supportActionBar?.title = "Identify Bands"
        supportActionBar?.subtitle = "Pinch to zoom, tap to select"

        binding.viewFinder.visibility = View.GONE
        binding.resultImageView.visibility = View.VISIBLE
        binding.resultImageView.setImageBitmap(baseBitmap)
        binding.resultImageView.scaleType = ImageView.ScaleType.MATRIX

        binding.captureButton.visibility = View.GONE
        binding.retryButton.visibility = View.VISIBLE
        binding.colorPreviewContainer.visibility = View.VISIBLE
        binding.undoButton?.visibility = View.VISIBLE

        currentPickingIndex = 0
        pickedColors.fill(ResistorColor.BODY)
        markerPoints.clear()
        updateColorBandsUI()
        setupZoomableImageView()
    }

    private fun setupZoomableImageView() {
        binding.resultImageView.setOnTouchListener { v, event ->
            val view = v as ImageView
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    savedMatrix.set(matrix)
                    startPoint.set(event.x, event.y)
                    mode = 1
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    oldDist = spacing(event)
                    if (oldDist > 10f) {
                        savedMatrix.set(matrix)
                        midPoint(midPoint, event)
                        mode = 2
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    if (mode == 1 && distance(event.x, event.y, startPoint.x, startPoint.y) < 10) {
                        if (currentPickingIndex < 4) handleZoomedTap(event.x, event.y)
                    }
                    mode = 0
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 1) {
                        matrix.set(savedMatrix)
                        matrix.postTranslate(event.x - startPoint.x, event.y - startPoint.y)
                    } else if (mode == 2) {
                        val newDist = spacing(event)
                        if (newDist > 10f) {
                            matrix.set(savedMatrix)
                            val scale = newDist / oldDist
                            matrix.postScale(scale, scale, midPoint.x, midPoint.y)
                        }
                    }
                }
            }
            view.imageMatrix = matrix
            true
        }
    }

    private fun handleZoomedTap(touchX: Float, touchY: Float) {
        val bitmap = originalBitmap ?: return
        val inverse = Matrix()
        binding.resultImageView.imageMatrix.invert(inverse)
        val touchPoint = floatArrayOf(touchX, touchY)
        inverse.mapPoints(touchPoint)

        val bitmapX = touchPoint[0].toInt().coerceIn(0, bitmap.width - 1)
        val bitmapY = touchPoint[1].toInt().coerceIn(0, bitmap.height - 1)

        val pixel = bitmap.getPixel(bitmapX, bitmapY)
        val color = findClosestColor(Color.red(pixel), Color.green(pixel), Color.blue(pixel))

        pickedColors[currentPickingIndex] = color
        markerPoints.add(PointF(bitmapX.toFloat(), bitmapY.toFloat()))

        drawMarkersOnImage()
        updateColorBandsUI()

        // PROMPT ADDED: Ask the user to confirm the match
        val prompt = "Band ${currentPickingIndex + 1}: ${color.name}. Do the colors match?"
        Snackbar.make(binding.root, prompt, Snackbar.LENGTH_LONG)
            .setAction("UNDO") { undoLastPick() }
            .show()

        binding.resultText.text = calculateResistance(pickedColors)
        binding.resultText.visibility = View.VISIBLE

        currentPickingIndex++
    }

    private fun drawMarkersOnImage() {
        val config = originalBitmap?.config ?: Bitmap.Config.ARGB_8888
        val cleanCopy = originalBitmap?.copy(config, true) ?: return
        val canvas = Canvas(cleanCopy)
        val paint = Paint().apply {
            color = Color.CYAN
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        for (point in markerPoints) {
            canvas.drawCircle(point.x, point.y, 12f, paint)
        }

        baseBitmap = cleanCopy
        binding.resultImageView.setImageBitmap(baseBitmap)
    }

    private fun undoLastPick() {
        if (currentPickingIndex > 0) {
            currentPickingIndex--
            pickedColors[currentPickingIndex] = ResistorColor.BODY

            if (markerPoints.isNotEmpty()) {
                markerPoints.removeAt(markerPoints.size - 1)
            }

            drawMarkersOnImage()
            updateColorBandsUI()
            binding.resultText.text = calculateResistance(pickedColors)
        }
    }

    private fun resetCamera() {
        binding.viewFinder.visibility = View.VISIBLE
        binding.resultImageView.visibility = View.GONE
        binding.resultText.visibility = View.GONE
        binding.colorPreviewContainer.visibility = View.GONE
        binding.captureButton.visibility = View.VISIBLE
        binding.retryButton.visibility = View.GONE
        binding.undoButton?.visibility = View.GONE

        currentPickingIndex = 0
        markerPoints.clear()
        pickedColors.fill(ResistorColor.BODY)

        supportActionBar?.title = "Resistor Scanner"
        supportActionBar?.subtitle = null
        matrix.reset()
    }

    private fun calculateResistance(colors: List<ResistorColor>): String {
        val active = colors.filter { it != ResistorColor.BODY }
        if (active.size < 3) return "Select 3+ bands"
        val d1 = active[0].value
        val d2 = active[1].value
        val mult = active[2].multiplier
        val res = (d1 * 10 + d2) * mult
        val resStr = when {
            res >= 1_000_000 -> "${res / 1_000_000} MΩ"
            res >= 1_000 -> "${res / 1_000} kΩ"
            else -> "$res Ω"
        }
        val tolerance = if (active.size == 4) " ${active[3].tolerance ?: ""}" else ""
        return "$resStr$tolerance"
    }

    private fun updateColorBandsUI() {
        val bands = listOf(binding.band1, binding.band2, binding.band3, binding.band4)
        pickedColors.forEachIndexed { index, color ->
            bands[index].visibility = if (color == ResistorColor.BODY) View.INVISIBLE else View.VISIBLE
            val rgb = getStandardRGB(color)
            bands[index].setBackgroundColor(Color.rgb(rgb[0], rgb[1], rgb[2]))

            // Allow manual correction by clicking the band preview
            bands[index].setOnClickListener { showColorPicker(index) }
        }
    }

    private fun showColorPicker(index: Int) {
        val options = ResistorColor.entries.filter { it != ResistorColor.BODY }
        val names = options.map { it.name }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Does the color match? Correct it below:")
            .setItems(names) { _, which ->
                pickedColors[index] = options[which]
                updateColorBandsUI()
                binding.resultText.text = calculateResistance(pickedColors)
            }.show()
    }

    private fun findClosestColor(r: Int, g: Int, b: Int): ResistorColor {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]; val sat = hsv[1]; val value = hsv[2]

        if (value < 0.25f) return ResistorColor.BLACK
        if (sat < 0.15f) return if (value > 0.85f) ResistorColor.WHITE else ResistorColor.GREY
        if (sat < 0.25f && value > 0.5f && value < 0.85f) return ResistorColor.SILVER
        if (hue in 10f..35f && value < 0.45f) return ResistorColor.BROWN
        if (hue in 35f..55f && sat > 0.4f && value > 0.4f && value < 0.85f) return ResistorColor.GOLD

        return when (hue) {
            in 0f..12f, in 345f..360f -> ResistorColor.RED
            in 13f..38f -> ResistorColor.ORANGE
            in 39f..65f -> ResistorColor.YELLOW
            in 66f..160f -> ResistorColor.GREEN
            in 161f..260f -> ResistorColor.BLUE
            in 261f..344f -> ResistorColor.VIOLET
            else -> ResistorColor.BODY
        }
    }

    private fun getStandardRGB(color: ResistorColor): IntArray {
        return when (color) {
            ResistorColor.BLACK -> intArrayOf(0, 0, 0)
            ResistorColor.BROWN -> intArrayOf(139, 69, 19)
            ResistorColor.RED -> intArrayOf(255, 0, 0)
            ResistorColor.ORANGE -> intArrayOf(255, 165, 0)
            ResistorColor.YELLOW -> intArrayOf(255, 255, 0)
            ResistorColor.GREEN -> intArrayOf(0, 255, 0)
            ResistorColor.BLUE -> intArrayOf(0, 0, 255)
            ResistorColor.VIOLET -> intArrayOf(238, 130, 238)
            ResistorColor.GREY -> intArrayOf(128, 128, 128)
            ResistorColor.WHITE -> intArrayOf(255, 255, 255)
            ResistorColor.GOLD -> intArrayOf(212, 175, 55)
            ResistorColor.SILVER -> intArrayOf(192, 192, 192)
            else -> intArrayOf(60, 60, 60)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (exc: Exception) { Log.e("ScannerApp", "Failed", exc) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun cropToReticle(fullBitmap: Bitmap): Bitmap {
        val cropWidth = (fullBitmap.width * 0.98f).toInt()
        val cropHeight = (fullBitmap.height * 0.20f).toInt()
        return Bitmap.createBitmap(fullBitmap, (fullBitmap.width - cropWidth) / 2, (fullBitmap.height - cropHeight) / 2, cropWidth, cropHeight)
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return Math.sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point.set(x / 2, y / 2)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return Math.sqrt(((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)).toDouble()).toFloat()
    }
}
fun ImageProxy.toCorrectBitmap(): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val rotation = imageInfo.rotationDegrees
    return if (rotation != 0) {
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    } else bmp
}