package ched.red.parallax

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import kotlin.math.exp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.setpaper)
        enableEdgeToEdge()
        Log.d("Wallpaper", "init! Success")

        val setWallpaperButton: Button = findViewById(R.id.setWallpaperButton)
        setWallpaperButton.setOnClickListener {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(applicationContext, ParallaxWallpaperService::class.java))
            }
            startActivity(intent)
        }
    }

}

class ParallaxWallpaperPhysics : Runnable {

    private var testEventSum = 0f
    private var warnTimer = 0f
    private var visible = true
    private var camX = 0f
    private var camY = 0f
    private var eventX = 0f
    private var eventY = 0f
    @Volatile
    private var loop = true
    private val lock = Any()
    private var starTime = System.nanoTime()
    private var endTime = System.nanoTime()
    private var deltaMS = 0f

    fun getCam(): Pair<Float, Float> {
        synchronized(lock) {
            return Pair(camX, camY)
        }
    }

    fun setEvent(x: Float, y: Float) {
        synchronized(lock) {
            eventX = x
            eventY = y
        }
    }

    fun setVisibility(value: Boolean){
        synchronized(lock) {
            visible = value
        }
    }

    override fun run(){
        while (loop){
            starTime = System.nanoTime()

            if (visible){
                camX *= 0.97f
                camY *= 0.97f

                camX += eventX
                camY += eventY

                if (testEventSum == eventX + eventY && testEventSum != 0f){
                    warnTimer += 0.005f
                    if (warnTimer > 1){
                        warnTimer = 0f
                        Log.w("Physics", "Gyroscope has not changed, but updates are still happening!")
                    }
                }
                else {
                    testEventSum = eventX + eventY
                    warnTimer = 0f
                }
            }

            endTime = System.nanoTime()

            val deltaNS = endTime - starTime
            var sleepNS = (5000000) - deltaNS
            deltaMS = deltaNS / 1000000f
            val sleepMS = (sleepNS / 1000000f).toInt()
            sleepNS -= sleepMS * 1000000

            if (sleepMS <= 0 && sleepNS <= 0){
                Log.e("Physics", "Sleep time is below 0! Took $deltaNS ns, slept for $sleepMS ms, $sleepNS ns")
            } else {
                Thread.sleep(sleepMS.toLong(), sleepNS.toInt())
            }
        }
    }

    fun stop(){
        loop = false
    }
}
class ParallaxWallpaperService : WallpaperService() {

    private inner class ParallaxEngine : Engine(), SensorEventListener {
        private val sensorManager: SensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        private var canvasRect = Rect(-1, -1, 0, 0)

        private val backgroundLayer: Bitmap
        private val foregroundLayer: Bitmap
        private val backgroundRect: Rect
        private val foregroundRect: Rect
        private var physicsRunnable = ParallaxWallpaperPhysics()
        private var physicsThread = Thread(physicsRunnable)

        private var camX = 0f
        private var camY = 0f

        private var eventX = 0f
        private var eventY = 0f

        private val chore = Choreographer.getInstance()
        private val frameCallback = object: Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val newCam = physicsRunnable.getCam()

                camX = newCam.first
                camY = newCam.second

                physicsRunnable.setEvent(eventX, eventY)

                drawFrame()
                chore.postFrameCallback(this)
            }
        }


        private var visible = true
        private val handler = Handler()
        private val drawRunner = object: Runnable {
            override fun run() {
                if (!physicsThread.isAlive)
                    physicsThread.start()
                chore.postFrameCallback(frameCallback)
            }
        }


        init {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            handler.post(drawRunner)

            // Load bitmap layers - replace with your images in drawable
            backgroundLayer = BitmapFactory.decodeResource(resources, R.drawable.background_layer)
            backgroundRect = Rect(0, 0, backgroundLayer.width, backgroundLayer.height)
            foregroundLayer = BitmapFactory.decodeResource(resources, R.drawable.foreground_layer)
            foregroundRect = Rect(0, 0, foregroundLayer.width, foregroundLayer.height)
        }

        override fun onSensorChanged(event: SensorEvent) {
            eventX = (event.values[1])
            eventY = (event.values[0])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // not used here
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            Log.d("Surface", "Created")
            super.onSurfaceCreated(holder)
            canvasRect = Rect(0, 0, holder.surfaceFrame.width(), holder.surfaceFrame.height())
            drawFrame()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.d("Surface", "Check!")
            super.onSurfaceChanged(holder, format, width, height)

            canvasRect = Rect(0, 0, width, height)
            Log.d("Surface", "Set to $width, $height")
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            handler.removeCallbacks(drawRunner)
            sensorManager.unregisterListener(this)
        }


        fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null

            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    // Clear canvas
                    canvas.drawColor(-0x1000000) // black background

                    if (canvasRect.left == -1){
                        Log.d("Surface", "Check failed! canvasRect was never set")
                    }
                    val ratioX = backgroundRect.right/canvasRect.right
                    val ratioY = backgroundRect.bottom/canvasRect.bottom

                    val scalar = 1.4f
                    val motionScalar = -1.4f
                    val resultX = (camX * motionScalar).toInt()
                    val resultY = (camY * motionScalar).toInt()

                    val ratioValue = if (ratioX > ratioY) ratioY else ratioX
                    val resizedRect = Rect(0, 0, ((backgroundRect.right/ratioValue)*scalar).toInt(), ((backgroundRect.bottom/ratioValue)*scalar).toInt())
                    val shiftedRect = Rect(0, 0, (canvasRect.right-resizedRect.right)/2, (canvasRect.bottom-resizedRect.bottom)/2)
                    val foregroundDst = Rect(shiftedRect.right+resultX,shiftedRect.bottom+resultY,resizedRect.right+shiftedRect.right+resultX,resizedRect.bottom+shiftedRect.bottom+resultY)
                    val backgroundDst = Rect(shiftedRect.right+resultX,shiftedRect.bottom+resultY,resizedRect.right+shiftedRect.right+resultX,resizedRect.bottom+shiftedRect.bottom+resultY)

//                    Log.d("Frame", "Using screen size: ${canvasRect.right}, ${canvasRect.bottom}")
//                    Log.d("Frame", "Using foreground src: ${foregroundRect.left}, ${foregroundRect.top}, ${foregroundRect.width()}, ${foregroundRect.height()}")
//                    Log.d("Frame", "Set foreground rect: ${foregroundDst.left}, ${foregroundDst.top}, ${foregroundDst.width()}, ${foregroundDst.height()}")
                    // Draw each layer with offsets for parallax effect
                    canvas.drawBitmap(backgroundLayer, backgroundRect, backgroundDst, null)
                    canvas.drawBitmap(foregroundLayer, foregroundRect, foregroundDst, null)
                }
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas)
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            physicsRunnable.setVisibility(visible)
            Log.d("Wallpaper", "Visibility changed to $visible")
            if (visible) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
                handler.post(drawRunner)
                chore.postFrameCallback(frameCallback)
            } else {
                sensorManager.unregisterListener(this)
                handler.removeCallbacks(drawRunner)
                chore.removeFrameCallback(frameCallback)
            }
        }

        override fun onDestroy() {
            physicsRunnable.stop()
            physicsThread.join()
            super.onDestroy()
            sensorManager.unregisterListener(this)
            handler.removeCallbacks(drawRunner)
        }

    }

    override fun onCreateEngine(): Engine {
        Log.d("Wallpaper", "Creating ParallaxEngine")
        return ParallaxEngine()
    }
}

