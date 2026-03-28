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
import android.graphics.BitmapFactory
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
    BODY(-2, 0.0, null) // Placeholder for the background
}

fun ImageProxy.toBitmap(): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    // 1. Decode the original "sideways" image
    val originalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    // 2. Prepare a matrix to rotate it 90 degrees clockwise
    val matrix = android.graphics.Matrix()
    matrix.postRotate(90f)

    // 3. Create a NEW bitmap that is physically upright
    return Bitmap.createBitmap(
        originalBitmap,
        0, 0,
        originalBitmap.width,
        originalBitmap.height,
        matrix,
        true
    )
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
            Log.d("ScannerApp", "Button was physically pressed!")
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

            // 1. Setup Preview
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            // 2. Setup ImageCapture (Crucial!)
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                // 3. YOU MUST INCLUDE BOTH 'preview' AND 'imageCapture' HERE
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture // <--- If this is missing, the button won't work!
                )

                Log.d("ScannerApp", "Camera and Capture bound successfully!")

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

    private var lastCapturedImage: android.graphics.Bitmap? = null

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val fullBitmap = image.toBitmap()
                    val croppedBitmap = cropToReticle(fullBitmap)
                    image.close()

                    // THE REAL SCAN HAPPENS HERE
                    val detectedColors = scanImageForColors(croppedBitmap)
                    val resultText = calculateResistance(detectedColors)

                    runOnUiThread {
                        binding.viewFinder.visibility = android.view.View.GONE
                        binding.resultImageView.visibility = android.view.View.VISIBLE
                        binding.resultImageView.setImageBitmap(croppedBitmap)

                        binding.resultText.text = resultText
                        binding.resultText.visibility = android.view.View.VISIBLE

                        binding.captureButton.visibility = android.view.View.GONE
                        binding.retryButton.visibility = android.view.View.VISIBLE
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
        binding.resultText.visibility = android.view.View.GONE // Hide the numbers
        binding.captureButton.visibility = android.view.View.VISIBLE
        binding.retryButton.visibility = android.view.View.GONE
    }
    private fun cropToReticle(bitmap: Bitmap): Bitmap {
        val viewFinder = binding.viewFinder
        val reticle = binding.reticle

        // 1. Get the scaling factor between the Bitmap and the ViewFinder
        val scaleX = bitmap.width.toFloat() / viewFinder.width
        val scaleY = bitmap.height.toFloat() / viewFinder.height

        // 2. Calculate the Reticle's position relative to the ViewFinder
        val cropWidth = (reticle.width * scaleX).toInt()
        val cropHeight = (reticle.height * scaleY).toInt()

        // Center the crop based on the reticle's location
        val cropLeft = ((reticle.left + (reticle.width / 2)) * scaleX - (cropWidth / 2)).toInt()
        val cropTop = ((reticle.top + (reticle.height / 2)) * scaleY - (cropHeight / 2)).toInt()

        // 3. Safety check: Ensure crop boundaries are within the bitmap
        val left = cropLeft.coerceIn(0, bitmap.width - cropWidth)
        val top = cropTop.coerceIn(0, bitmap.height - cropHeight)

        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }
    private fun findClosestColor(r: Int, g: Int, b: Int): ResistorColor {
        var closestColor = ResistorColor.BODY
        var minDistance = Double.MAX_VALUE

        // Loop through all colors in your Enum (except the BODY placeholder)
        for (color in ResistorColor.entries) {
            if (color == ResistorColor.BODY) continue

            // Simple Euclidean distance math to find which standard color
            // the pixel is "closest" to in 3D RGB space.
            val dR = r - getStandardRGB(color)[0]
            val dG = g - getStandardRGB(color)[1]
            val dB = b - getStandardRGB(color)[2]
            val distance = Math.sqrt((dR * dR + dG * dG + dB * dB).toDouble())

            if (distance < minDistance) {
                minDistance = distance
                closestColor = color
            }
        }
        return closestColor
    }

    // Helper to define what "True" Red, Brown, etc., look like in RGB
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
            else -> intArrayOf(200, 200, 200) // Default body color
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
    private fun scanImageForColors(bitmap: Bitmap): List<ResistorColor> {
        val detectedColors = mutableListOf<ResistorColor>()
        val midY = bitmap.height / 2
        var lastColor: ResistorColor? = null

        // We scan every 5th pixel to save processing power and avoid "noise"
        for (x in 0 until bitmap.width step 5) {
            val pixel = bitmap.getPixel(x, midY)

            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)

            val currentColor = findClosestColor(r, g, b)

            // HCI Logic: We only care about CHANGE.
            // If we see "Brown, Brown, Brown", we only record one "Brown".
            // We also ignore the "BODY" color of the resistor.
            if (currentColor != ResistorColor.BODY && currentColor != lastColor) {
                detectedColors.add(currentColor)
                lastColor = currentColor
            }
        }

        // Most resistors have 3 or 4 bands. If we find more than 4,
        // the image might be too noisy, so we just take the first 4.
        return detectedColors.take(4)
    }
}


