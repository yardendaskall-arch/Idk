package com.facemoji.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import java.io.FileOutputStream
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
    private var currentEmojiBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        faceDetector = FaceDetection.getClient(options)

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        binding.captureButton.setOnClickListener { takePhoto() }
        binding.retakeButton.setOnClickListener  { showCameraScreen() }
        binding.downloadButton.setOnClickListener { downloadGif() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()
            val preview  = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture!!)
            } catch (e: Exception) {
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture!!)
                } catch (ex: Exception) {
                    Toast.makeText(this, "Could not open camera", Toast.LENGTH_SHORT).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val cap = imageCapture ?: return
        binding.captureButton.isEnabled = false
        binding.captureHint.text = "Processing…"

        val file = File(cacheDir,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + ".jpg")

        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    binding.captureButton.isEnabled = true
                    binding.captureHint.text = "Point camera at your face"
                    Toast.makeText(baseContext, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = processImage(file)
            }
        )
    }

    private fun processImage(file: File) {
        val raw = BitmapFactory.decodeFile(file.absolutePath) ?: run {
            showCameraScreen(); return
        }
        val exif = ExifInterface(file.absolutePath)
        val rot  = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90  -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else                                 -> 0f
        }
        val bitmap = if (rot != 0f) {
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot) }, true)
        } else raw

        binding.capturedImageView.setImageBitmap(bitmap)
        showResultScreen()
        binding.tvLabel.text        = "Generating your emoji…"
        binding.downloadButton.isEnabled = false
        currentEmojiBitmap          = null
        binding.generatedEmojiView.setImageDrawable(null)

        faceDetector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    binding.tvLabel.text = "No face detected\nGet closer and try again"
                } else {
                    val emoji = emojiGenerator.generate(faces[0], bitmap)
                    currentEmojiBitmap = emoji
                    binding.generatedEmojiView.setImageBitmap(emoji)
                    EmojiStore.save(this, emoji)
                    binding.tvLabel.text             = expressionLabel(faces[0])
                    binding.downloadButton.isEnabled = true
                }
            }
            .addOnFailureListener {
                binding.tvLabel.text = "Detection failed — please try again"
            }
    }

    private fun downloadGif() {
        val bmp = currentEmojiBitmap ?: return
        binding.downloadButton.isEnabled = false
        binding.downloadButton.text      = "Saving…"

        // Run encoding off the main thread
        Thread {
            try {
                val name = "face_emoji_${System.currentTimeMillis()}.gif"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+: use MediaStore so the file appears in Downloads
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "image/gif")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                    contentResolver.openOutputStream(uri)!!.use { GifEncoder().encode(bmp, it) }
                } else {
                    // Android 9 and below: write directly to Downloads
                    val dir  = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    FileOutputStream(File(dir, name)).use { GifEncoder().encode(bmp, it) }
                }

                runOnUiThread {
                    Toast.makeText(this, "Saved to Downloads as $name", Toast.LENGTH_LONG).show()
                    binding.downloadButton.isEnabled = true
                    binding.downloadButton.text      = "Download as GIF"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.downloadButton.isEnabled = true
                    binding.downloadButton.text      = "Download as GIF"
                }
            }
        }.start()
    }

    private fun expressionLabel(face: com.google.mlkit.vision.face.Face): String {
        // If ML Kit didn't return a probability (null), fall back to geometry
        val s = face.smilingProbability     ?: estimateSmileGeometrically(face)
        val l = face.leftEyeOpenProbability  ?: 1f
        val r = face.rightEyeOpenProbability ?: 1f
        val src = if (face.smilingProbability != null) "ML" else "geo"
        return when {
            l < 0.3f && r < 0.3f -> "Sleepy \uD83D\uDE34  ($src ${(s*100).toInt()}%)"
            s > 0.60f -> "Big smile! \uD83D\uDE01  ($src ${(s*100).toInt()}%)"
            s > 0.38f -> "Smiling \uD83D\uDE0A  ($src ${(s*100).toInt()}%)"
            s > 0.20f -> "Slight smile \uD83D\uDE42  ($src ${(s*100).toInt()}%)"
            s > 0.10f -> "Neutral \uD83D\uDE10  ($src ${(s*100).toInt()}%)"
            else      -> "Sad \uD83D\uDE22  ($src ${(s*100).toInt()}%)"
        }
    }

    /**
     * Estimates smile from mouth landmark geometry — identical logic to
     * EmojiGenerator.estimateSmileGeometrically() so the label and drawing agree.
     */
    private fun estimateSmileGeometrically(face: com.google.mlkit.vision.face.Face): Float {
        val ml = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT)?.position  ?: return 0.25f
        val mr = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT)?.position ?: return 0.25f
        val mb = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_BOTTOM)?.position ?: return 0.25f
        val faceH = face.boundingBox.height().toFloat().coerceAtLeast(1f)
        val cornerMidY = (ml.y + mr.y) / 2f
        val drop = (mb.y - cornerMidY) / faceH
        return ((drop - 0.03f) / 0.12f).coerceIn(0f, 1f)
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
