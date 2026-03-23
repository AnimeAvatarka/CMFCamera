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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
                startCamera()
            }
            else -> {
                requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }
        
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    CameraScreen(
                        onCaptureClick = { capturePhoto() }
                    )
                }
            }
        }
    }

    @Composable
    fun CameraScreen(onCaptureClick: () -> Unit) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Превью камеры
            CameraPreview(
                modifier = Modifier.fillMaxSize()
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
                    // Кнопка настроек
                    IconButton(onClick = { /* TODO: Settings */ }) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White
                        )
                    }
                    
                    // Кнопка переключения камеры
                    IconButton(onClick = { /* TODO: Switch camera */ }) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Cached,
                            contentDescription = "Switch camera",
                            tint = Color.White
                        )
                    }
                }
                
                // Нижняя панель с кнопкой спуска
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Кнопка спуска
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
    fun CameraPreview(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            },
            modifier = modifier,
            update = { previewView ->
                bindCameraUseCases(previewView, lifecycleOwner)
            }
        )
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                // Камера готова
            } catch (e: Exception) {
                Log.e("CMFCamera", "Camera init failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        val cameraProvider = cameraProvider ?: return
        
        // Превью
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
        
        // Съёмка фото
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY) // Быстрая съёмка
            .setTargetRotation(previewView.display.rotation)
            .build()
        
        // Выбор камеры (задняя по умолчанию)
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        
        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
        } catch (e: Exception) {
            Log.e("CMFCamera", "Use case binding failed", e)
        }
    }

    private fun capturePhoto() {
        val imageCapture = imageCapture ?: return
        
        val photoFile = createImageFile()
        
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@MainActivity, "Photo saved", Toast.LENGTH_SHORT).show()
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e("CMFCamera", "Photo capture failed", exception)
                    Toast.makeText(this@MainActivity, "Capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun createImageFile(): java.io.File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = getExternalFilesDir(null)
        return java.io.File(storageDir, "JPEG_${timeStamp}.jpg")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
