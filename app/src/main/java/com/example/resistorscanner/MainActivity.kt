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
                    // This is where the function is "used"
                    val bitmap = image.toBitmap()

                    lastCapturedImage = bitmap
                    image.close()

                    runOnUiThread {
                        binding.viewFinder.visibility = android.view.View.GONE
                        binding.resultImageView.visibility = android.view.View.VISIBLE
                        binding.resultImageView.setImageBitmap(bitmap)

                        Toast.makeText(baseContext, "Captured and Straightened!", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraScanner", "Capture failed", exception)
                }
            }
        )
    }
}


