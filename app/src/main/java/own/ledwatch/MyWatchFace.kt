package own.ledwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.palette.graphics.Palette
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.*

/**
 * Updates rate in milliseconds for interactive mode. We update once a second to advance the
 * second hand.
 */
private const val INTERACTIVE_UPDATE_RATE_MS = 1000

/**
 * Handler message id for updating the time periodically in interactive mode.
 */
private const val MSG_UPDATE_TIME = 0


/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn"t
 * shown. On devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient
 * mode. The watch face is drawn with less contrast in mute mode.
 *
 *
 * Important Note: Because watch face apps do not have a default Activity in
 * their project, you will need to set your Configurations to
 * "Do not launch Activity" for both the Wear and/or Application modules. If you
 * are unsure how to do this, please review the "Run Starter project" section
 * in the Google Watch Face Code Lab:
 * https://codelabs.developers.google.com/codelabs/watchface/index.html#0
 */
class MyWatchFace : CanvasWatchFaceService() {

    override fun onCreateEngine(): Engine {
        return Engine()
    }

    private class EngineHandler(reference: MyWatchFace.Engine) : Handler() {
        private val mWeakReference: WeakReference<MyWatchFace.Engine> = WeakReference(reference)

        override fun handleMessage(msg: Message) {
            val engine = mWeakReference.get()
            if (engine != null) {
                when (msg.what) {
                    MSG_UPDATE_TIME -> engine.handleUpdateTimeMessage()
                }
            }
        }
    }

    inner class Engine : CanvasWatchFaceService.Engine() {

        private lateinit var mCalendar: Calendar

        private var mRegisteredTimeZoneReceiver = false
        private var mMuteMode: Boolean = false
        private var mCenterX: Float = 0F
        private var mCenterY: Float = 0F




        private lateinit var mBackgroundPaint: Paint
        private lateinit var mBackgroundBitmap: Bitmap
        private lateinit var mGrayBackgroundBitmap: Bitmap

        private var mAmbient: Boolean = false
        private var mLowBitAmbient: Boolean = false
        private var mBurnInProtection: Boolean = false

        /* Handler to update the time once a second in interactive mode. */
        private val mUpdateTimeHandler = EngineHandler(this)

        private val mTimeZoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)

            setWatchFaceStyle(
                WatchFaceStyle.Builder(this@MyWatchFace)
                    .setAcceptsTapEvents(true)
                    .build()
            )

            mCalendar = Calendar.getInstance()

            initializeBackground()



        }

        private fun initializeBackground() {
            mBackgroundPaint = Paint().apply {
                color = Color.BLACK
            }
            mBackgroundBitmap =
                BitmapFactory.decodeResource(resources, R.drawable.back)

            /* Extracts colors from background image to improve watchface style. */
            Palette.from(mBackgroundBitmap).generate {
                it?.let {
                }
            }
        }



        override fun onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            super.onDestroy()
        }

        override fun onPropertiesChanged(properties: Bundle) {
            super.onPropertiesChanged(properties)
            mLowBitAmbient = properties.getBoolean(
                WatchFaceService.PROPERTY_LOW_BIT_AMBIENT, false
            )
            mBurnInProtection = properties.getBoolean(
                WatchFaceService.PROPERTY_BURN_IN_PROTECTION, false
            )
        }

        override fun onTimeTick() {
            super.onTimeTick()
            invalidate()
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            mAmbient = inAmbientMode


            // Check and trigger whether or not timer should be running (only
            // in active mode).
            updateTimer()
        }


        override fun onInterruptionFilterChanged(interruptionFilter: Int) {
            super.onInterruptionFilterChanged(interruptionFilter)
            val inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE

            /* Dim display in mute mode. */
            if (mMuteMode != inMuteMode) {
                mMuteMode = inMuteMode
                invalidate()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)

            /*
             * Find the coordinates of the center point on the screen, and ignore the window
             * insets, so that, on round watches with a "chin", the watch face is centered on the
             * entire screen, not just the usable portion.
             */
            mCenterX = width / 2f
            mCenterY = height / 2f





            /* Scale loaded background image (more efficient) if surface dimensions change. */
            val scale = width.toFloat() / mBackgroundBitmap.width.toFloat()

            mBackgroundBitmap = Bitmap.createScaledBitmap(
                mBackgroundBitmap,
                (mBackgroundBitmap.width * scale).toInt(),
                (mBackgroundBitmap.height * scale).toInt(), true
            )

            /*
             * Create a gray version of the image only if it will look nice on the device in
             * ambient mode. That means we don"t want devices that support burn-in
             * protection (slight movements in pixels, not great for images going all the way to
             * edges) and low ambient mode (degrades image quality).
             *
             * Also, if your watch face will know about all images ahead of time (users aren"t
             * selecting their own photos for the watch face), it will be more
             * efficient to create a black/white version (png, etc.) and load that when you need it.
             */
            if (!mBurnInProtection && !mLowBitAmbient) {
                initGrayBackgroundBitmap()
            }
        }

        private fun initGrayBackgroundBitmap() {
            mGrayBackgroundBitmap = Bitmap.createBitmap(
                mBackgroundBitmap.width,
                mBackgroundBitmap.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(mGrayBackgroundBitmap)
            val grayPaint = Paint()
            val colorMatrix = ColorMatrix()
            colorMatrix.setSaturation(0f)
            val filter = ColorMatrixColorFilter(colorMatrix)
            grayPaint.colorFilter = filter
            canvas.drawBitmap(mBackgroundBitmap, 0f, 0f, grayPaint)
        }

        /**
         * Captures tap event (and tap type). The [WatchFaceService.TAP_TYPE_TAP] case can be
         * used for implementing specific logic to handle the gesture.
         */
        override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
            when (tapType) {
                WatchFaceService.TAP_TYPE_TOUCH -> {
                    // The user has started touching the screen.
                }
                WatchFaceService.TAP_TYPE_TOUCH_CANCEL -> {
                    // The user has started a different gesture or otherwise cancelled the tap.
                }
                WatchFaceService.TAP_TYPE_TAP ->
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(applicationContext, R.string.message, Toast.LENGTH_SHORT)
                        .show()
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas, bounds: Rect) {
            val now = System.currentTimeMillis()
            mCalendar.timeInMillis = now



            drawBackground(canvas, bounds)

            /*
            val resources: Resources = getResources()
            val mBackgroundPaint: Paint? = null
            val mBackgroundBitmap = BitmapFactory.decodeResource(resources, R.drawable.back)
            var mBackgroundScaledBitmap: Bitmap? = null
            var width = 0
            var height = 0
            var centerX = 0f
            var centerY = 0f

            width = bounds.width()
            height = bounds.height()
            centerX = width / 2f
            centerY = height / 2f

            // Draw the background.
            if (isInAmbientMode())
            {
                // black background
                if (mBackgroundPaint != null) {
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mBackgroundPaint)
                }
            } else {
                if (mBackgroundScaledBitmap == null || mBackgroundScaledBitmap.getWidth() != width || mBackgroundScaledBitmap.getHeight() != height) {
                    mBackgroundScaledBitmap = Bitmap
                        .createScaledBitmap(mBackgroundBitmap, width, height, true)
                }
                // fancy image background
                if (mBackgroundScaledBitmap != null) {
                    canvas.drawBitmap(mBackgroundScaledBitmap, 0f, 0f, null)
                }
            }
            */



            //clockleds
            clockleds(canvas, bounds)

        }











        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                registerReceiver()
                /* Update time zone in case it changed while we weren"t visible. */
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            } else {
                unregisterReceiver()
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer()
        }

        private fun registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = true
            val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
            this@MyWatchFace.registerReceiver(mTimeZoneReceiver, filter)
        }

        private fun unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = false
            this@MyWatchFace.unregisterReceiver(mTimeZoneReceiver)
        }

        /**
         * Starts/stops the [.mUpdateTimeHandler] timer based on the state of the watch face.
         */
        private fun updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME)
            }
        }

        /**
         * Returns whether the [.mUpdateTimeHandler] timer should be running. The timer
         * should only run in active mode.
         */
        private fun shouldTimerBeRunning(): Boolean {
            return isVisible && !mAmbient
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        fun handleUpdateTimeMessage() {
            invalidate()
            if (shouldTimerBeRunning()) {
                val timeMs = System.currentTimeMillis()
                val delayMs = INTERACTIVE_UPDATE_RATE_MS - timeMs % INTERACTIVE_UPDATE_RATE_MS
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs)
            }
        }




        private fun drawBackground(canvas: Canvas, bounds: Rect) {

            val resources: Resources = getResources()
            val mBackgroundPaint: Paint? = null
            val mBackgroundBitmap = BitmapFactory.decodeResource(resources, R.drawable.back)
            var mBackgroundScaledBitmap: Bitmap? = null
            var width = bounds.width()
            var height = bounds.height()


            // Draw the background.
            if (isInAmbientMode())
            {
                // black background
                if (mBackgroundPaint != null) {
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mBackgroundPaint)
                }
            } else {
                if (mBackgroundScaledBitmap == null || mBackgroundScaledBitmap.getWidth() != width || mBackgroundScaledBitmap.getHeight() != height) {
                    mBackgroundScaledBitmap = Bitmap
                        .createScaledBitmap(mBackgroundBitmap, width, height, true)
                }
                // fancy image background
                if (mBackgroundScaledBitmap != null) {
                    canvas.drawBitmap(mBackgroundScaledBitmap, 0f, 0f, null)
                }
            }
        }




        //////clockleds/////////
        fun clockleds(canvas: Canvas, bounds: Rect) {
            val width = bounds.width()
            val height = bounds.height()
            val centerX = width.toFloat() / 2.0f
            val centerY = height.toFloat() / 2.0f
            var setclock = 3.0f
            var clockspace = 30
            var clockheight = 53
            var clockwidth = 43
            var colonwidth = 28
            var clocktop = 130.0f
            val resources: Resources = getResources()
            val textTime = SimpleDateFormat("hh:mm:ss").format(Date())
            val ampm = Calendar.getInstance()[Calendar.AM_PM]
            val logstring = "$textTime $ampm"
            Log.i("0wnleds","my screen size: width: $width height: $height"
            )
            Log.i("0wnleds", "My Clock Image built with $logstring")

            val clearBitmap = (resources.getDrawable(R.drawable.nc) as BitmapDrawable).bitmap
            val resizeclearBitmap = Bitmap.createScaledBitmap(clearBitmap, clockspace, clockheight, false)
            if (Character.getNumericValue(textTime[0]) == 1) {
                canvas.drawBitmap(resizeclearBitmap, setclock, clocktop, null)
            } else {
                setclock = (-(clearBitmap.width / 2)).toFloat()
            }
            Log.i("0wnleds", "bitmap width: " + clearBitmap.width)

            setclock = setclock + resizeclearBitmap.width

            Log.i("0wnleds","setclock: $setclock")

            val hour1Bitmap = (resources.getDrawable(
                CLOCKNUMBERS[Character.getNumericValue(
                    textTime[0]
                )]
            ) as BitmapDrawable).bitmap
            val resizehour1Bitmap = Bitmap.createScaledBitmap(hour1Bitmap, clockwidth, clockheight, false)
            if (Character.getNumericValue(textTime[0]) == 1) {
                canvas.drawBitmap(resizehour1Bitmap, setclock, clocktop, null)
            } else {
                setclock = (-(hour1Bitmap.width / 2)).toFloat()
            }
            Log.i("0wnleds", "bitmap width: " + hour1Bitmap.width)
            val resizehour2Bitmap = Bitmap.createScaledBitmap(
                (resources.getDrawable(
                    CLOCKNUMBERS[Character.getNumericValue(
                        textTime[1]
                    )]
                ) as BitmapDrawable).bitmap, clockwidth, clockheight, false
            )
            canvas.drawBitmap(
                resizehour2Bitmap,
                resizehour1Bitmap.width.toFloat() + setclock,
                clocktop,
                null
            )
            val resizecolonBitmap = Bitmap.createScaledBitmap(
                (resources.getDrawable(R.drawable.dot) as BitmapDrawable).bitmap,
                colonwidth,
                clockheight,
                false
            )
            canvas.drawBitmap(
                resizecolonBitmap,
                resizehour1Bitmap.width.toFloat() + setclock + resizehour2Bitmap.width
                    .toFloat(),
                clocktop,
                null
            )
            val resizeminute1Bitmap = Bitmap.createScaledBitmap(
                (resources.getDrawable(
                    CLOCKNUMBERS[Character.getNumericValue(
                        textTime[3]
                    )]
                ) as BitmapDrawable).bitmap, clockwidth, clockheight, false
            )
            canvas.drawBitmap(
                resizeminute1Bitmap,
                resizehour1Bitmap.width.toFloat() + setclock + resizehour2Bitmap.width
                    .toFloat() + resizecolonBitmap.width.toFloat(),
                clocktop,
                null
            )
            val resizeminute2Bitmap = Bitmap.createScaledBitmap(
                (resources.getDrawable(
                    CLOCKNUMBERS[Character.getNumericValue(
                        textTime[4]
                    )]
                ) as BitmapDrawable).bitmap, clockwidth, clockheight, false
            )
            canvas.drawBitmap(
                resizeminute2Bitmap,
                resizehour1Bitmap.width.toFloat() + setclock + resizehour2Bitmap.width
                    .toFloat() + resizecolonBitmap.width.toFloat() + resizeminute1Bitmap.width.toFloat(),
                clocktop,
                null
            )
            canvas.drawBitmap(
                resizecolonBitmap,
                resizehour1Bitmap.width.toFloat() + setclock + resizehour2Bitmap.width
                    .toFloat() + resizecolonBitmap.width.toFloat() + resizeminute1Bitmap.width.toFloat() + resizeminute2Bitmap.width
                    .toFloat(),
                clocktop,
                null
            )
            val resizeseconds1Bitmap = Bitmap.createScaledBitmap(
                (resources.getDrawable(
                    CLOCKNUMBERS[Character.getNumericValue(
                        textTime[6]
                    )]
                ) as BitmapDrawable).bitmap, clockwidth, clockheight, false
            )
            canvas.drawBitmap(
                resizeseconds1Bitmap,
                resizehour1Bitmap.width.toFloat() + setclock + resizehour2Bitmap.width
                    .toFloat() + resizecolonBitmap.width.toFloat() + resizeminute1Bitmap.width.toFloat() + resizeminute2Bitmap.width
                    .toFloat() + resizecolonBitmap.width.toFloat(),
                clocktop,
                null
            )
            val resizeseconds2Bitmap = Bitmap.createScaledBitmap(
                (resources.getDrawable(
                    CLOCKNUMBERS[Character.getNumericValue(
                        textTime[7]
                    )]
                ) as BitmapDrawable).bitmap, clockwidth, clockheight, false
            )
            canvas.drawBitmap(
                resizeseconds2Bitmap,
                resizehour1Bitmap.width.toFloat() + setclock + resizehour2Bitmap.width
                    .toFloat() + resizecolonBitmap.width.toFloat() + resizeminute1Bitmap.width.toFloat() + resizeminute2Bitmap.width
                    .toFloat() + resizecolonBitmap.width.toFloat() + resizeseconds1Bitmap.width.toFloat(),
                clocktop,
                null
            )
            canvas.drawBitmap(
                Bitmap.createScaledBitmap(
                    (resources.getDrawable(AMPM[ampm]) as BitmapDrawable).bitmap,
                    clockwidth,
                    clockheight,
                    false
                ), resizehour1Bitmap.width
                    .toFloat() + setclock + resizehour2Bitmap.width.toFloat() + resizecolonBitmap.width
                    .toFloat() + resizeminute1Bitmap.width.toFloat() + resizeminute2Bitmap.width
                    .toFloat() + resizecolonBitmap.width.toFloat() + resizeseconds1Bitmap.width.toFloat() + resizeseconds2Bitmap.width
                    .toFloat(), clocktop, null
            )
        }

        private val CLOCKNUMBERS = intArrayOf(
            R.drawable.n0,
            R.drawable.n1,
            R.drawable.n2,
            R.drawable.n3,
            R.drawable.n4,
            R.drawable.n5,
            R.drawable.n6,
            R.drawable.n7,
            R.drawable.n8,
            R.drawable.n9
        )

        private val AMPM = intArrayOf(
            R.drawable.am,
            R.drawable.pm
        )
//////////end of watch leds///////////////////




    }
}