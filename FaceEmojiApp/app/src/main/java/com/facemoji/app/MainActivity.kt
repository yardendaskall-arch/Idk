package com.facemoji.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
    private val emojiGenerator = EmojiGenerator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enable landmarks + classifications so EmojiGenerator has full data
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        faceDetector = FaceDetection.getClient(options)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.captureButton.setOnClickListener { takePhoto() }
        binding.retakeButton.setOnClickListener  { showCameraScreen() }

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

            // Prefer front camera for selfies
            val selector = try {
                CameraSelector.DEFAULT_FRONT_CAMERA.also {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, it, preview, imageCapture!!)
                }
            } catch (e: Exception) {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture!!)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        binding.captureButton.isEnabled = false
        binding.captureHint.text = "Processing…"

        val photoFile = File(cacheDir,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + ".jpg")

        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(photoFile).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    binding.captureButton.isEnabled = true
                    binding.captureHint.text = "Point camera at your face"
                    Toast.makeText(baseContext, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    processImage(photoFile)
                }
            }
        )
    }

    private fun processImage(file: File) {
        // Decode the bitmap, honouring EXIF rotation so coords match ML Kit
        val raw = BitmapFactory.decodeFile(file.absolutePath) ?: run {
            Toast.makeText(this, "Could not read image", Toast.LENGTH_SHORT).show()
            showCameraScreen(); return
        }
        val exif = ExifInterface(file.absolutePath)
        val rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                                                   ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90  -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else                                 -> 0f
        }
        val bitmap = if (rotation != 0f) {
            val m = Matrix().apply { postRotate(rotation) }
            android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        } else raw

        // Show the reference photo and switch to result screen immediately
        binding.capturedImageView.setImageBitmap(bitmap)
        showResultScreen()
        binding.tvLabel.text = "Generating your emoji…"
        binding.generatedEmojiView.setImageDrawable(null)

        // Run face detection on the correctly-oriented bitmap
        val image = InputImage.fromBitmap(bitmap, 0)
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    binding.tvLabel.text = "No face detected\nTry getting closer"
                    binding.generatedEmojiView.setImageResource(android.R.drawable.ic_menu_report_image)
                } else {
                    val emojiBitmap = emojiGenerator.generate(faces[0], bitmap)
                    binding.generatedEmojiView.setImageBitmap(emojiBitmap)
                    binding.tvLabel.text = expressionLabel(faces[0])
                }
            }
            .addOnFailureListener {
                binding.tvLabel.text = "Detection failed — please try again"
            }
    }

    private fun expressionLabel(face: com.google.mlkit.vision.face.Face): String {
        val s = face.smilingProbability           ?: 0f
        val l = face.leftEyeOpenProbability       ?: 1f
        val r = face.rightEyeOpenProbability      ?: 1f
        return when {
            l < 0.3f && r < 0.3f && s < 0.3f -> "Looking sleepy!"
            l < 0.3f && r < 0.3f              -> "Laughing hard!"
            s > 0.75f -> "Huge smile!"
            s > 0.50f -> "Smiling :)"
            s > 0.25f -> "Slight smile"
            else       -> "Neutral face"
        }
    }

    private fun showCameraScreen() {
        binding.cameraContainer.visibility  = View.VISIBLE
        binding.resultContainer.visibility  = View.GONE
        binding.captureButton.isEnabled = true
        binding.captureHint.text = "Point camera at your face"
    }

    private fun showResultScreen() {
        binding.cameraContainer.visibility  = View.GONE
        binding.resultContainer.visibility  = View.VISIBLE
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) startCamera()
            else { Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show(); finish() }
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
