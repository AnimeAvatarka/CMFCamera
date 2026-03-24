package com.cmf.camera

import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
    
    private var isFrontCamera by mutableStateOf(false)
    private var refreshTrigger by mutableStateOf(0)
    private var lastPhotoBitmap by mutableStateOf<Bitmap?>(null)
    
    // Состояния UI
    private var selectedMode by mutableStateOf("Photo")
    private var zoomScale by mutableStateOf(1f)
    private var exposureCompensation by mutableStateOf(0)
    
    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[android.Manifest.permission.CAMERA] == true) {
            startCamera()
        } else {
            Toast.makeText(this, "Нужно разрешение камеры", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setTheme(com.google.android.material.R.style.Theme_Material3_DynamicColors_DayNight)
        }
        
        val permissions = arrayOf(android.Manifest.permission.CAMERA)
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        
        if (allGranted) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
        
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    PixelCameraUI(
                        onCaptureClick = { capturePhoto() },
                        onSwitchCameraClick = { switchCamera() },
                        onModeSelected = { selectedMode = it },
                        onZoomSelected = { zoomScale = it },
                        selectedMode = selectedMode,
                        zoomScale = zoomScale,
                        lastPhotoBitmap = lastPhotoBitmap
                    )
                }
            }
        }
    }

    @Composable
    fun PixelCameraUI(
        onCaptureClick: () -> Unit,
        onSwitchCameraClick: () -> Unit,
        onModeSelected: (String) -> Unit,
        onZoomSelected: (Float) -> Unit,
        selectedMode: String,
        zoomScale: Float,
        lastPhotoBitmap: Bitmap?
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Превью камеры
            CameraPreview(modifier = Modifier.fillMaxSize())
            
            // Верхняя панель (Настройки, Вспышка)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { /* Settings */ }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
                IconButton(onClick = { /* Flash */ }) {
                    Icon(Icons.Default.FlashOn, contentDescription = "Flash", tint = Color.White)
                }
            }
            
            // Левая панель (Экспозиция)
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = 8.dp)
                    .align(Alignment.CenterStart),
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.BrightnessHigh, contentDescription = "Exposure", tint = Color.White, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(150.dp)
                        .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .align(Alignment.Center)
                            .background(Color.White, RoundedCornerShape(2.dp))
                    )
                }
            }
            
            // Правая панель (Галерея)
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .background(Color.White.copy(alpha = 0.3f))
                    .clickable { /* Open Gallery */ }
            ) {
                if (lastPhotoBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = lastPhotoBitmap!!.asImageBitmap(),
                        contentDescription = "Last photo",
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Gray))
                }
            }
            
            // Нижняя панель управления
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Зум
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    listOf(0.5f, 1f, 2f, 5f).forEach { zoom ->
                        Text(
                            text = "${zoom}x",
                            color = if (zoomScale == zoom) Color.White else Color.White.copy(alpha = 0.5f),
                            fontWeight = if (zoomScale == zoom) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable { onZoomSelected(zoom) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Кнопка спуска и переключения
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Переключение камеры
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .border(2.dp, Color.White, CircleShape)
                            .clip(CircleShape)
                            .clickable { onSwitchCameraClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Switch", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    
                    // Кнопка спуска
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(4.dp)
                            .background(Color.White, CircleShape)
                            .clickable { onCaptureClick() }
                    )
                    
                    // Пустое место для симметрии
                    Spacer(modifier = Modifier.size(48.dp))
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Режимы съёмки
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(horizontal = 32.dp)
                ) {
                    items(listOf("Portrait", "Photo", "Night Sight", "Panorama")) { mode ->
                        Text(
                            text = mode,
                            color = if (selectedMode == mode) Color.White else Color.White.copy(alpha = 0.5f),
                            fontSize = 14.sp,
                            fontWeight = if (selectedMode == mode) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable { onModeSelected(mode) }
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
        val provider = cameraProvider
        val currentIsFront = isFrontCamera
        val currentRefresh = refreshTrigger
        
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = modifier,
            update = { previewView ->
                if (provider != null) {
                    bindCameraUseCases(previewView, lifecycleOwner, provider, currentIsFront)
                }
            }
        )
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                runOnUiThread { refreshTrigger++ }
            } catch (e: Exception) {
                Log.e(TAG, "Camera init FAILED", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(
        previewView: PreviewView, 
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        cameraProvider: ProcessCameraProvider,
        isFront: Boolean
    ) {
        try {
            preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(previewView.display?.rotation ?: android.view.Surface.ROTATION_0)
                .setJpegQuality(100)
                .build()
            
            val cameraSelector = if (isFront) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            
            cameraProvider.unbindAll()
            
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            
            Log.d(TAG, "Camera BOUND SUCCESSFULLY")
            
        } catch (e: Exception) {
            Log.e(TAG, "bindCameraUseCases FAILED", e)
        }
    }

    private fun switchCamera() {
        isFrontCamera = !isFrontCamera
        refreshTrigger++
    }

    private fun capturePhoto() {
        val imageCapture = imageCapture ?: return
        
        // СОХРАНЕНИЕ В ГАЛЕРЕЮ (MediaStore)
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CMF Camera")
            }
        }
        
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()
        
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo saved to Gallery: ${output.savedUri}")
                    
                    // Загружаем превью
                    output.savedUri?.let { uri ->
                        try {
                            val inputStream = contentResolver.openInputStream(uri)
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            inputStream?.close()
                            lastPhotoBitmap = bitmap
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load preview", e)
                        }
                    }
                    
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Фото сохранено в галерею!", Toast.LENGTH_SHORT).show()
                    }
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture FAILED", exception)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Ошибка: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
