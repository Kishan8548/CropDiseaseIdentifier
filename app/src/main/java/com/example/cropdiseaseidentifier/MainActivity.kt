package com.example.cropdiseaseidentifier

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.cropdiseaseidentifier.databinding.ActivityMainBinding
import com.example.cropdiseaseidentifier.ml.BestModel
import androidx.core.graphics.scale
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            binding.imageView.setImageURI(uri)

            val originalBitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                // Use the new ImageDecoder.decodeBitmap API to request a software-backed bitmap
                ImageDecoder.decodeBitmap(source) { decoder, info, source ->
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }

            // --- Crucial Fix: Ensure the bitmap is mutable and not HARDWARE backed ---
            val safeBitmap = if (originalBitmap.config == Bitmap.Config.HARDWARE) {
                // If it's a hardware bitmap (for older API path or if ImageDecoder failed),
                // create a safe copy for pixel manipulation.
                originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                originalBitmap
            }

            val result = predictDisease(safeBitmap)
            binding.resultTextView.text = result
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonPickImage.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun predictDisease(bitmap: Bitmap): String {
        // Load model
        val model = BestModel.newInstance(this)

        // Resize bitmap to model input size
        val inputSize = 250
        // Bitmap.createScaledBitmap implicitly handles a copy, but we must ensure
        // the *source* bitmap passed to it is safe, which we did in pickImage.
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // Convert bitmap to TensorBuffer
        val inputFeature = TensorBuffer.createFixedSize(intArrayOf(1, inputSize, inputSize, 3), org.tensorflow.lite.DataType.FLOAT32)
        // Adjust the size of ByteBuffer allocation to match the model input shape (1, 224, 224, 3) * 4 bytes/float
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)

        // This line *was* the source of the crash if 'resized' came from a hardware bitmap.
        // It is now safe because 'safeBitmap' passed into this function is guaranteed
        // to be a software-backed copy.
        resized.getPixels(intValues, 0, resized.width, 0, 0, resized.width, resized.height)

        var pixel = 0
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val value = intValues[pixel++]
                // Normalization: converting 0-255 to 0.0-1.0 float values
                byteBuffer.putFloat(((value shr 16 and 0xFF) / 255.0f)) // Red
                byteBuffer.putFloat(((value shr 8 and 0xFF) / 255.0f))  // Green
                byteBuffer.putFloat(((value and 0xFF) / 255.0f))       // Blue
            }
        }
        inputFeature.loadBuffer(byteBuffer)

        // Run inference
        val outputs = model.process(inputFeature)
        val outputBuffer = outputs.outputFeature0AsTensorBuffer
        val predictions = outputBuffer.floatArray

        // Load labels
        val labels = assets.open("labels.txt").bufferedReader().readLines()

        // Get highest probability
        val maxIndex = predictions.indices.maxByOrNull { predictions[it] } ?: -1
        val confidence = predictions[maxIndex] * 100

        // Close model
        model.close()

        return "${labels[maxIndex]} (${String.format("%.2f", confidence)}%)"
    }
}