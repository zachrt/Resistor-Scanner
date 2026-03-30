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
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

enum class ResistorColor(val value: Int, val multiplier: Double, val tolerance: Double?) {
    BLACK(0, 1.0, null),
    BROWN(1, 10.0, 1.0),
    RED(2, 100.0, 2.0),
    ORANGE(3, 1000.0, null),
    YELLOW(4, 10000.0, null),
    GREEN(5, 100000.0, 0.5),
    BLUE(6, 1000000.0, 0.25),
    VIOLET(7, 10000000.0, 0.1),
    GREY(8, 100000000.0, 0.05),
    WHITE(9, 1000000000.0, null),
    GOLD(-1, 0.1, 5.0),
    SILVER(-1, 0.01, 10.0),
    BODY(-2, 0.0, null)
}

fun ImageProxy.toCorrectBitmap(): Bitmap {
    val rotationDegrees = this.imageInfo.rotationDegrees
    val bmp = this.toBitmap()

    return if (rotationDegrees != 0) {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        bmp.recycle()
        rotated
    } else {
        bmp
    }
}

class MainActivity : AppCompatActivity() {
    private val REQUIRED_PERMISSIONS = arrayOf(android.Manifest.permission.CAMERA)
    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.controlsContainer.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        if (allPermissionsGranted()) {
            binding.viewFinder.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
        }

        binding.captureButton.setOnClickListener {
            takePhoto()
        }
        binding.retryButton.setOnClickListener {
            resetCamera()
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
                .setTargetRotation(binding.viewFinder.display.rotation)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Log.e("ScannerApp", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = arrayOf(Manifest.permission.CAMERA).all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        if (rc == 10 && allPermissionsGranted()) {
            startCamera()
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val fullBitmap = image.toCorrectBitmap()
                    val croppedBitmap = cropToReticle(fullBitmap)
                    image.close()

                    // THE REAL SCAN HAPPENS HERE
                    // We update scanImageForColors to return the list AND the visual mask
                    val (detectedColors, maskedBitmap) = scanImageForColors(croppedBitmap)
                    val resultText = calculateResistance(detectedColors)

                    runOnUiThread {
                        binding.viewFinder.visibility = android.view.View.GONE
                        binding.resultImageView.visibility = android.view.View.VISIBLE

                        // NEW: Show the MASKED image (black background)
                        // This lets you see exactly what the computer analysed.
                        binding.resultImageView.setImageBitmap(maskedBitmap)

                        binding.resultText.text = resultText
                        binding.resultText.visibility = android.view.View.VISIBLE

                        binding.captureButton.visibility = android.view.View.GONE
                        binding.retryButton.visibility = android.view.View.VISIBLE
                        binding.colorPreviewContainer.visibility = android.view.View.VISIBLE

                        val bands = listOf(binding.band1, binding.band2, binding.band3, binding.band4)
                        bands.forEach { it.visibility = android.view.View.GONE }

                        detectedColors.forEachIndexed { index, color ->
                            if (index < bands.size) {
                                bands[index].visibility = android.view.View.VISIBLE
                                bands[index].setBackgroundColor(android.graphics.Color.rgb(
                                    getStandardRGB(color)[0],
                                    getStandardRGB(color)[1],
                                    getStandardRGB(color)[2]
                                ))
                                bands[index].setOnClickListener {
                                    Toast.makeText(this@MainActivity, "${color.name}: ${color.value}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.e("ScannerApp", "Photo capture failed: ${exception.message}", exception)
                }
            }
        )
    }

    private fun resetCamera() {
        binding.viewFinder.visibility = android.view.View.VISIBLE
        binding.resultImageView.visibility = android.view.View.GONE
        binding.resultText.visibility = android.view.View.GONE
        binding.colorPreviewContainer.visibility = android.view.View.GONE
        binding.captureButton.visibility = android.view.View.VISIBLE
        binding.retryButton.visibility = android.view.View.GONE
    }

    private fun cropToReticle(fullBitmap: Bitmap): Bitmap {
        val width = fullBitmap.width
        val height = fullBitmap.height

        // --- THE PANORAMIC CROP ---
        // 0.98f = Keep 98% of the image width (Left to Right)
        // 0.20f = Keep only the middle 20% of the image height (Top to Bottom)
        val boxWidthPercentage = 0.98f
        val boxHeightPercentage = 0.20f

        val cropWidth = (width * boxWidthPercentage).toInt()
        val cropHeight = (height * boxHeightPercentage).toInt()

        // Calculate the exact center starting coordinates
        val cropStartX = (width - cropWidth) / 2
        val cropStartY = (height - cropHeight) / 2

        // Carve out the horizontal strip and return it
        return Bitmap.createBitmap(
            fullBitmap,
            cropStartX,
            cropStartY,
            cropWidth,
            cropHeight
        )
    }

    private fun findClosestColor(r: Int, g: Int, b: Int): ResistorColor {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r, g, b, hsv)

        val hue = hsv[0]   // 0.0 to 360.0
        val sat = hsv[1]   // 0.0 to 1.0
        val value = hsv[2] // 0.0 to 1.0

        if (value < 0.25f) return ResistorColor.BLACK
        if (value > 0.8f && sat < 0.15f) return ResistorColor.WHITE

        // NEW: If it's dark but lacks color, it's a glossy Black band, not Grey.
        if (sat < 0.2f && value in 0.25f..0.5f) return ResistorColor.BLACK

        // NEW: Stricter separation between Brown and Orange
        // Brown must be noticeably dark. If it's bright, it's Orange.
        if (hue in 10f..35f && value < 0.45f) return ResistorColor.BROWN
        if (hue in 35f..55f && sat > 0.4f && value > 0.4f && value < 0.85f) return ResistorColor.GOLD

        return when (hue) {
            in 0f..10f, in 345f..360f -> ResistorColor.RED
            in 11f..38f -> ResistorColor.ORANGE
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
            else -> intArrayOf(200, 200, 200)
        }
    }

    private fun calculateResistance(colors: List<ResistorColor>): String {
        if (colors.size < 3) return "Scan Failed"

        val digit1 = colors[0].value
        val digit2 = colors[1].value
        val multiplier = colors[2].multiplier

        val resistance = (digit1 * 10 + digit2) * multiplier

        return when {
            resistance >= 1_000_000 -> "${resistance / 1_000_000} MΩ"
            resistance >= 1_000 -> "${resistance / 1_000} kΩ"
            else -> "$resistance Ω"
        }
    }

    private fun scanImageForColors(bitmap: Bitmap): Pair<List<ResistorColor>, Bitmap> {
        val detectedColors = mutableListOf<ResistorColor>()

        // --- STEP 1: STRICTER WHITE PAPER MASK ---
        val maskedBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)

        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(pixel, hsv)
                val sat = hsv[1]
                val value = hsv[2]

                // TIGHTENED: Must be extremely bright (>0.80) and almost zero color (<0.15)
                // This will stop the beige resistor body from turning magenta.
                if (sat < 0.15f && value > 0.80f) {
                    maskedBitmap.setPixel(x, y, android.graphics.Color.MAGENTA)
                } else {
                    maskedBitmap.setPixel(x, y, pixel)
                }
            }
        }

        // --- STEP 2: ANALYZE THE MASKED IMAGE (With Black Override) ---
        var lastSeenColor: ResistorColor? = null
        var consecutiveCount = 0
        val MINIMUM_WIDTH = 4

        val scanStartY = (bitmap.height * 0.15).toInt()
        val scanEndY = (bitmap.height * 0.85).toInt()

        for (x in 0 until bitmap.width step 3) {
            var highestSaturation = -1f
            var lowestValue = 1.0f // Track how dark the darkest pixel is
            var winningColorInColumn = ResistorColor.BODY
            var validPixelsInColumn = 0

            for (y in scanStartY..scanEndY step 3) {
                val pixel = maskedBitmap.getPixel(x, y)

                if (pixel == android.graphics.Color.MAGENTA) continue
                validPixelsInColumn++

                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)

                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(pixel, hsv)
                val sat = hsv[1]
                val value = hsv[2]

                // Track the darkest spot in this column
                if (value < lowestValue) {
                    lowestValue = value
                }

                // Skip pure glare
                if (sat < 0.2f && value > 0.7f) continue

                // Find the most vividly colored pixel
                if (sat > highestSaturation) {
                    highestSaturation = sat
                    winningColorInColumn = findClosestColor(r, g, b)
                }
            }

            // If it's mostly background noise, skip
            if (validPixelsInColumn < 3) {
                consecutiveCount = 0
                continue
            }

            // THE BLACK OVERRIDE:
            // If the column has a genuinely dark shadow/band, it's the Black band!
            // This bypasses the saturation check completely.
            if (lowestValue < 0.35f) {
                winningColorInColumn = ResistorColor.BLACK
            }

            if (winningColorInColumn == ResistorColor.BODY) {
                consecutiveCount = 0
                continue
            }

            // Minimum Width Filter
            if (winningColorInColumn == lastSeenColor) {
                consecutiveCount++
                if (consecutiveCount == MINIMUM_WIDTH) {
                    if (detectedColors.lastOrNull() != winningColorInColumn) {
                        detectedColors.add(winningColorInColumn)
                    }
                }
            } else {
                lastSeenColor = winningColorInColumn
                consecutiveCount = 1
            }
        }

        return Pair(detectedColors.take(4), maskedBitmap)
    }
}