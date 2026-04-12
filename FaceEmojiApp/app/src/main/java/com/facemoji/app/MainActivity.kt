package com.facemoji.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.facemoji.app.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceDetector: FaceDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ML Kit face detector — classify smiling + eye-open probabilities
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        faceDetector = FaceDetection.getClient(options)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.captureButton.setOnClickListener { takePhoto() }
        binding.retakeButton.setOnClickListener { showCameraScreen() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                // Fall back to back camera if front camera unavailable
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture
                    )
                } catch (ex: Exception) {
                    Toast.makeText(this, "Could not open camera", Toast.LENGTH_SHORT).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        binding.captureButton.isEnabled = false
        binding.captureHint.text = "Processing..."

        val photoFile = File(
            cacheDir,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    binding.captureButton.isEnabled = true
                    binding.captureHint.text = "Point camera at your face"
                    Toast.makeText(baseContext, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    processAndShowResult(photoFile)
                }
            }
        )
    }

    private fun processAndShowResult(file: File) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap != null) {
            binding.capturedImageView.setImageBitmap(bitmap)
        }

        showResultScreen()
        binding.tvEmoji.text = "🔍"
        binding.tvLabel.text = "Analyzing..."

        val image = InputImage.fromFilePath(this, Uri.fromFile(file))
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    showEmoji("🤔", "No face found!\nTry again closer to the camera")
                } else {
                    val result = classifyFace(faces[0])
                    showEmoji(result.emoji, result.label)
                }
            }
            .addOnFailureListener {
                showEmoji("😕", "Detection failed\nPlease try again")
            }
    }

    private fun showEmoji(emoji: String, label: String) {
        binding.tvEmoji.text = emoji
        binding.tvLabel.text = label
    }

    /**
     * Maps detected facial probabilities to the closest standard emoji.
     *
     * Uses ML Kit's smilingProbability (0–1) and eye-open probabilities (0–1).
     */
    private fun classifyFace(face: Face): EmojiResult {
        val smiling   = face.smilingProbability         ?: 0f
        val leftEye   = face.leftEyeOpenProbability     ?: 1f
        val rightEye  = face.rightEyeOpenProbability    ?: 1f

        val eyesClosed = leftEye < 0.3f && rightEye < 0.3f
        val winking    = (leftEye < 0.3f && rightEye > 0.7f) ||
                         (rightEye < 0.3f && leftEye > 0.7f)

        return when {
            eyesClosed && smiling < 0.3f   -> EmojiResult("😴", "Sleepy")
            eyesClosed && smiling >= 0.3f  -> EmojiResult("😂", "Laughing Hard")
            winking    && smiling >= 0.3f  -> EmojiResult("😉", "Winking")
            smiling >= 0.85f               -> EmojiResult("😂", "Laughing Out Loud")
            smiling >= 0.70f               -> EmojiResult("😁", "Big Smile")
            smiling >= 0.50f               -> EmojiResult("😊", "Happy")
            smiling >= 0.25f               -> EmojiResult("🙂", "Slight Smile")
            else                           -> EmojiResult("😐", "Neutral")
        }
    }

    data class EmojiResult(val emoji: String, val label: String)

    private fun showCameraScreen() {
        binding.cameraContainer.visibility = View.VISIBLE
        binding.resultContainer.visibility = View.GONE
        binding.captureButton.isEnabled = true
        binding.captureHint.text = "Point camera at your face"
    }

    private fun showResultScreen() {
        binding.cameraContainer.visibility = View.GONE
        binding.resultContainer.visibility = View.VISIBLE
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        faceDetector.close()
    }

    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
