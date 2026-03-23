package com.cmf.camera

import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CMFCamera"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    
    // State для переключения камеры (вызывает рекомпозицию)
    private var isFrontCamera by mutableStateOf(false)
    
    // State для принудительного обновления превью
    private var refreshTrigger by mutableStateOf(0)
    
    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "Permission result: $isGranted")
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "=== onCreate START ===")
        
        // Dynamic Colors для Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setTheme(com.google.android.material.R.style.Theme_Material3_DynamicColors_DayNight)
        }
        
        // Запрашиваем разрешение
        when {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "Permission already granted")
                startCamera()
            }
            else -> {
                Log.d(TAG, "Requesting permission")
                requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }
        
        setContent {
            Log.d(TAG, "setContent called")
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    CameraScreen(
                        onCaptureClick = { capturePhoto() },
                        onSwitchCameraClick = { switchCamera() }
                    )
                }
            }
        }
        
        Log.d(TAG, "=== onCreate END ===")
    }

    @Composable
    fun CameraScreen(
        onCaptureClick: () -> Unit,
        onSwitchCameraClick: () -> Unit
    ) {
        Log.d(TAG, "CameraScreen composable called")
        
        Box(modifier = Modifier.fillMaxSize()) {
            // Превью камеры
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onSwitchCameraClick = onSwitchCameraClick
            )
            
            // UI элементы поверх превью
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Верхняя панель
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { 
                        Log.d(TAG, "Settings clicked")
                        Toast.makeText(this@MainActivity, "Settings", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White
                        )
                    }
                    
                    // Кнопка переключения камеры
                    IconButton(onClick = { 
                        Log.d(TAG, "Switch camera clicked")
                        onSwitchCameraClick()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Switch camera",
                            tint = Color.White
                        )
                    }
                }
                
                // Кнопка спуска
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(80.dp)
                            .background(Color.White, CircleShape)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color.Black, CircleShape)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun CameraPreview(
        modifier: Modifier = Modifier,
        onSwitchCameraClick: () -> Unit
    ) {
        Log.d(TAG, "CameraPreview composable called")
        
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val provider = cameraProvider
        val currentIsFront = isFrontCamera
        val currentRefresh = refreshTrigger
        
        AndroidView(
            factory = { ctx ->
                Log.d(TAG, "PreviewView FACTORY called")
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    Log.d(TAG, "PreviewView created")
                }
            },
            modifier = modifier,
            update = { previewView ->
                Log.d(TAG, "PreviewView UPDATE called, provider=${provider != null}, refresh=$currentRefresh")
                if (provider != null) {
                    Log.d(TAG, "Binding use cases, isFront=$currentIsFront")
                    bindCameraUseCases(previewView, lifecycleOwner, provider, currentIsFront)
                } else {
                    Log.d(TAG, "Provider not ready yet")
                }
            }
        )
    }

    private fun startCamera() {
        Log.d(TAG, "startCamera() called")
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                Log.d(TAG, "CameraProvider READY: $cameraProvider")
                
                // Обновляем UI
                runOnUiThread {
                    Log.d(TAG, "Refreshing UI after provider ready")
                    refreshTrigger++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera init FAILED", e)
                runOnUiThread {
                    Toast.makeText(this, "Camera init failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(
        previewView: PreviewView, 
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        cameraProvider: ProcessCameraProvider,
        isFront: Boolean
    ) {
        Log.d(TAG, "bindCameraUseCases() START, isFront=$isFront")
        
        try {
            // Превью
            preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
                Log.d(TAG, "Preview surface provider set")
            }
            
            // Съёмка
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(previewView.display?.rotation ?: android.view.Surface.ROTATION_0)
                .build()
            
            // Выбор камеры
            val cameraSelector = if (isFront) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            
            // Отвязываем старое
            cameraProvider.unbindAll()
            Log.d(TAG, "Previous use cases unbound")
            
            // Привязываем новое
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            
            Log.d(TAG, "=== Camera BOUND SUCCESSFULLY ===")
            
        } catch (e: Exception) {
            Log.e(TAG, "bindCameraUseCases FAILED", e)
            runOnUiThread {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun switchCamera() {
        Log.d(TAG, "switchCamera() called")
        
        // Меняем состояние (это вызовет рекомпозицию CameraPreview)
        isFrontCamera = !isFrontCamera
        refreshTrigger++
        
        Log.d(TAG, "isFrontCamera: $isFrontCamera, refreshTrigger: $refreshTrigger")
        
        Toast.makeText(
            this, 
            if (isFrontCamera) "Front camera" else "Back camera", 
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun capturePhoto() {
        Log.d(TAG, "capturePhoto() called")
        
        val imageCapture = imageCapture ?: run {
            Log.e(TAG, "imageCapture is null")
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Создаём файл для сохранения
        val photoFile = createImageFile()
        Log.d(TAG, "Photo file: ${photoFile.absolutePath}")
        
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        
        Log.d(TAG, "Taking picture...")
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo saved: ${output.savedUri}")
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity, 
                            "Photo saved to ${photoFile.name}", 
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture FAILED", exception)
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity, 
                            "Capture failed: ${exception.message}", 
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = getExternalFilesDir(null)
        return File(storageDir, "JPEG_${timeStamp}.jpg")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy() called")
        cameraExecutor.shutdown()
    }
}
