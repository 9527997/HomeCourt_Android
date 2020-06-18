/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.posenet

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorInflater
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.*
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import kotlinx.android.synthetic.main.tfe_pn_activity_posenet.*
import org.tensorflow.lite.examples.posenet.lib.BodyPart
import org.tensorflow.lite.examples.posenet.lib.Person
import org.tensorflow.lite.examples.posenet.lib.Posenet
import java.lang.ref.WeakReference
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class PosenetActivity() :
    AppCompatActivity(),
    ActivityCompat.OnRequestPermissionsResultCallback {
    private val START_LEFT_ANIMATION = 0
    private val START_RIGHT_ANIMATION = 1

    /** List of body joints that should be connected.    */
//    private val bodyJoints = listOf(
//        Pair(BodyPart.LEFT_WRIST, BodyPart.LEFT_ELBOW),
//        Pair(BodyPart.RIGHT_ELBOW, BodyPart.RIGHT_WRIST)
//    Pair(BodyPart.LEFT_WRIST, BodyPart.LEFT_ELBOW),
//    Pair(BodyPart.LEFT_ELBOW, BodyPart.LEFT_SHOULDER),
//    Pair(BodyPart.LEFT_SHOULDER, BodyPart.RIGHT_SHOULDER),
//    Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_ELBOW),
//    Pair(BodyPart.RIGHT_ELBOW, BodyPart.RIGHT_WRIST),
//    Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_HIP),
//    Pair(BodyPart.LEFT_HIP, BodyPart.RIGHT_HIP),
//    Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_SHOULDER),
//    Pair(BodyPart.LEFT_HIP, BodyPart.LEFT_KNEE),
//    Pair(BodyPart.LEFT_KNEE, BodyPart.LEFT_ANKLE),
//    Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_KNEE),
//    Pair(BodyPart.RIGHT_KNEE, BodyPart.RIGHT_ANKLE)
//    )

    /** Threshold for confidence score. */
    private val minConfidence = 0.5

    /** Radius of circle used to draw keypoints.  */
    private val circleRadius = 20.0f

    /** Paint class holds the style and color information to draw geometries,text and bitmaps. */
    private var paint = Paint()

    /** A shape for extracting frame data.   */
    private var PREVIEW_WIDTH = 640
    private var PREVIEW_HEIGHT = 480

    /** An object for the Posenet library.    */
    private lateinit var posenet: Posenet

    /** ID of the current [CameraDevice].   */
    private var cameraId: String? = null

    /** A [SurfaceView] for camera preview.   */
    private var surfaceView: SurfaceView? = null

    /** A [CameraCaptureSession] for camera preview.   */
    private var captureSession: CameraCaptureSession? = null

    /** A reference to the opened [CameraDevice].    */
    private var cameraDevice: CameraDevice? = null

    /** The [android.util.Size] of camera preview.  */
    private var previewSize: Size? = null

    /** The [android.util.Size.getWidth] of camera preview. */
    private var previewWidth = 0

    /** The [android.util.Size.getHeight] of camera preview.  */
    private var previewHeight = 0

    /** A counter to keep count of total frames.  */
    private var frameCounter = 0

    /** An IntArray to save image data in ARGB8888 format  */
    private lateinit var rgbBytes: IntArray

    /** A ByteArray to save image data in YUV format  */
    private var yuvBytes = arrayOfNulls<ByteArray>(3)

    /** An additional thread for running tasks that shouldn't block the UI.   */
    private var backgroundThread: HandlerThread? = null

    /** A [Handler] for running tasks in the background.    */
    private var backgroundHandler: Handler? = null

    /** An [ImageReader] that handles preview frame capture.   */
    private var imageReader: ImageReader? = null

    /** [CaptureRequest.Builder] for the camera preview   */
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    /** [CaptureRequest] generated by [.previewRequestBuilder   */
    private var previewRequest: CaptureRequest? = null

    /** A [Semaphore] to prevent the app from exiting before closing the camera.    */
    private val cameraOpenCloseLock = Semaphore(1)

    /** Whether the current camera device supports Flash or not.    */
    private var flashSupported = false

    /** Orientation of the camera sensor.   */
    private var sensorOrientation: Int? = null

    /** Abstract interface to someone holding a display surface.    */
    private var surfaceHolder: SurfaceHolder? = null

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    private var leftBarricade: ImageView? = null
    private var rightBarricade: ImageView? = null
    private var leftRotateAnimSet: Animator? = null
    private var rightRotateAnimSet: Animator? = null
    private var leftTranslationYAnimSet: Animator? = null
    private var rightTranslationYAnimSet: Animator? = null

    private var leftOrRight = BodyPart.RIGHT_WRIST
    private var goLeft = true

    private var lastCurrentTime: Long = 0

    private class MyHandler(activity: PosenetActivity) : Handler() {
        private val mActivity: WeakReference<PosenetActivity> = WeakReference(activity)

        override fun handleMessage(msg: Message) {
            if (mActivity.get() == null) {
                return
            }
            val activity = mActivity.get()
            when (msg.what) {
                activity!!.START_LEFT_ANIMATION -> {
                    activity!!.tv_score.text = "left"
                    activity!!.startLeftRotateAnim()
                    activity!!.startRightTransYAnim()
                    activity!!.goLeft = !activity!!.goLeft
                }
                activity!!.START_RIGHT_ANIMATION -> {
                    activity!!.tv_score.text = "right"
                    activity!!.startRightRotateAnim()
                    activity!!.startLeftTransYAnim()
                    activity!!.goLeft = !activity!!.goLeft
                }
            }
        }
    }

    private val myHandler: MyHandler = MyHandler(this)

//    private val mHandler: Handler = Handler()
//    private var mCountTime = 64
//    private var countDownTv: TextView? = null
//
//    private var currentTime: Long = 0
//    private var scoreNum = 0

    //倒计时，并处理点击事件
//    fun startCountDown() {
//        mHandler.postDelayed(countDown, 0)
//    }

    /*
        倒计时
     */
//    private val countDown = object : Runnable {
//        override fun run() {
//            if (mCountTime > 60) {
//                countDownTv!!.text = (mCountTime - 61).toString()
//            } else if (mCountTime == 61) {
//                countDownTv!!.text = "Go"
//                currentTime = System.currentTimeMillis()
//            } else if (mCountTime >= 0) {
//                countDownTv!!.visibility = View.GONE
//                tv_time!!.text = mCountTime.toString()
////                mHandler.postDelayed(this, 1000)
//            }
//            mCountTime--
//
//            if (mCountTime >= 0) {
//                mHandler.postDelayed(this, 1000)
//            } else {
//                //todo 倒计时结束
////                resetCounter()
//            }
//        }
//    }

    /** [CameraDevice.StateCallback] is called when [CameraDevice] changes its state.   */
    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@PosenetActivity.cameraDevice = cameraDevice
            createCameraPreviewSession()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@PosenetActivity.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            onDisconnected(cameraDevice)
            this@PosenetActivity?.finish()
        }
    }

    /**
     * A [CameraCaptureSession.CaptureCallback] that handles events related to JPEG capture.
     */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
        }
    }

    /**
     * Shows a [Toast] on the UI thread.
     *
     * @param text The message to show
     */
    private fun showToast(text: String) {
        val activity = this
        activity?.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.tfe_pn_activity_posenet)

        surfaceView = findViewById(R.id.surfaceView)
        surfaceHolder = surfaceView!!.holder

        leftBarricade = findViewById(R.id.iv_left)
        rightBarricade = findViewById(R.id.iv_right)

//        countDownTv = findViewById(R.id.tv_start)

        startLeftTransYAnim()

        setLeftOrRightHand(intent.getBooleanExtra(IS_LEFT_HAND, false))
    }

    private fun startLeftRotateAnim() {
        Log.d(TAG, "startLeftRotateAnim")
        leftRotateAnimSet =
            AnimatorInflater.loadAnimator(this, R.animator.anim_left_rotate)
                .apply {
                    setTarget(leftBarricade)
                    start()
                }
    }

    private fun startRightRotateAnim() {
        Log.d(TAG, "startRightRotateAnim")
        rightRotateAnimSet =
            AnimatorInflater.loadAnimator(this, R.animator.anim_right_rotate)
                .apply {
                    setTarget(rightBarricade)
                    start()
                }
    }

    private fun startLeftTransYAnim() {
        leftTranslationYAnimSet =
            AnimatorInflater.loadAnimator(this, R.animator.anim_left_translation_y)
                .apply {
                    setTarget(leftBarricade)
                    start()
                }
    }

    private fun startRightTransYAnim() {
        rightTranslationYAnimSet =
            AnimatorInflater.loadAnimator(this, R.animator.anim_right_translation_y)
                .apply {
                    setTarget(rightBarricade)
                    start()
                }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
    }

    override fun onStart() {
        super.onStart()
        openCamera()
        posenet = Posenet(this!!)
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        posenet.close()
    }

    private fun requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            ConfirmationDialog().show(supportFragmentManager, FRAGMENT_DIALOG)
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (allPermissionsGranted(grantResults)) {
                ErrorDialog.newInstance(getString(R.string.tfe_pn_request_permission))
                    .show(supportFragmentManager, FRAGMENT_DIALOG)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun allPermissionsGranted(grantResults: IntArray) = grantResults.all {
        it == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Sets up member variables related to camera.
     */
    private fun setUpCameraOutputs() {

        val activity = this
        val manager = activity!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                // 这里改为使用前置摄像头
                val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (cameraDirection != null &&
                    cameraDirection == CameraCharacteristics.LENS_FACING_BACK
                ) {
                    continue
                }

                previewSize = Size(PREVIEW_WIDTH, PREVIEW_HEIGHT)

                imageReader = ImageReader.newInstance(
                    PREVIEW_WIDTH, PREVIEW_HEIGHT,
                    ImageFormat.YUV_420_888, /*maxImages*/ 2
                )

                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!

                previewHeight = previewSize!!.height
                previewWidth = previewSize!!.width

                // Initialize the storage bitmaps once when the resolution is known.
                rgbBytes = IntArray(previewWidth * previewHeight)

                // Check if the flash is supported.
                flashSupported =
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

                this.cameraId = cameraId

                // We've found a viable camera and finished setting up member variables,
                // so we don't need to iterate through other available cameras.
                return
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.tfe_pn_camera_error))
                .show(supportFragmentManager, FRAGMENT_DIALOG)
        }
    }

    /**
     * Opens the camera specified by [PosenetActivity.cameraId].
     */
    private fun openCamera() {
        val permissionCamera =
            ContextCompat.checkSelfPermission(this!!, Manifest.permission.CAMERA)
        if (permissionCamera != PackageManager.PERMISSION_GRANTED) {
            ErrorDialog.newInstance(getString(R.string.tfe_pn_request_permission))
                .show(supportFragmentManager, FRAGMENT_DIALOG)
        }
        setUpCameraOutputs()
        val manager = this!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            // Wait for camera to open - 2.5 seconds is sufficient
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(cameraId!!, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    /**
     * Closes the current [CameraDevice].
     */
    private fun closeCamera() {
        if (captureSession == null) {
            return
        }

        try {
            cameraOpenCloseLock.acquire()
            captureSession!!.close()
            captureSession = null
            cameraDevice!!.close()
            cameraDevice = null
            imageReader!!.close()
            imageReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("imageAvailableListener").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }
    }

    /** Fill the yuvBytes with data from image planes.   */
    private fun fillBytes(planes: Array<Image.Plane>, yuvBytes: Array<ByteArray?>) {
        // Row stride is the total number of bytes occupied in memory by a row of an image.
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer.get(yuvBytes[i]!!)
        }
    }

    /** A [OnImageAvailableListener] to receive frames as they are available.  */
    private var imageAvailableListener = object : OnImageAvailableListener {
        override fun onImageAvailable(imageReader: ImageReader) {
            // We need wait until we have some size from onPreviewSizeChosen
            if (previewWidth == 0 || previewHeight == 0) {
                return
            }
            Log.d(TIME_TAG + 1,  (SystemClock.currentThreadTimeMillis() - lastCurrentTime).toString())
            lastCurrentTime = SystemClock.currentThreadTimeMillis()

            val image = imageReader.acquireLatestImage() ?: return
            fillBytes(image.planes, yuvBytes)

            ImageUtils.convertYUV420ToARGB8888(
                yuvBytes[0]!!,
                yuvBytes[1]!!,
                yuvBytes[2]!!,
                previewWidth,
                previewHeight,
                /*yRowStride=*/ image.planes[0].rowStride,
                /*uvRowStride=*/ image.planes[1].rowStride,
                /*uvPixelStride=*/ image.planes[1].pixelStride,
                rgbBytes
            )

            // Create bitmap from int array
            val imageBitmap = Bitmap.createBitmap(
                rgbBytes, previewWidth, previewHeight,
                Bitmap.Config.ARGB_8888
            )
            Log.d(TAG, "width" + previewHeight + ",height:" + previewHeight)

            // Create rotated version for portrait display
            val rotateMatrix = Matrix()
            //前置摄像头需要镜像翻转
            rotateMatrix.postScale(-1.0f, 1.0f)
//            rotateMatrix.postRotate(90.0f)

            val rotatedBitmap = Bitmap.createBitmap(
                imageBitmap, 0, 0, previewWidth, previewHeight,
                rotateMatrix, true
            )
            image.close()

            // Process an image for analysis in every 1 frames.
//            frameCounter = (frameCounter + 1) % 3
//            if (frameCounter == 0) {
            Log.d(
                TIME_TAG + 2,
                (SystemClock.currentThreadTimeMillis() - lastCurrentTime).toString()
            )
            lastCurrentTime = SystemClock.currentThreadTimeMillis()
            processImage(rotatedBitmap)
//            }
        }
    }

    /** Process image using Posenet library.   */
    private fun processImage(bitmap: Bitmap) {
        // Crop bitmap.
        val croppedBitmap = extensionBitmap(bitmap)

        // Created scaled version of bitmap for model input.
        val scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, MODEL_WIDTH, MODEL_HEIGHT, true)

        // Perform inference.
        val person = posenet.estimateSinglePose(scaledBitmap)
        for (keyPoint in person.keyPoints) {
            if (keyPoint.score > minConfidence &&
                (keyPoint.bodyPart.equals(leftOrRight))
            ) {
                val position = keyPoint.position

                Log.d(TAG, position.x.toString() + "  " + position.y)

                val b1 = (position.x < (0.2 * MODEL_WIDTH))
                val b2 = position.y > (0.5 * MODEL_HEIGHT)
                Log.d(TAG, b1.toString() + "  " + b2)

                //todo 增加得分
                if (goLeft && position.x < (0.2 * MODEL_WIDTH)) {
                    startLeftRotateAnim()
                    startRightTransYAnim()
                    goLeft = !goLeft
                } else if (!goLeft && position.x > (0.8 * MODEL_WIDTH)) {
                    startRightRotateAnim()
                    startLeftTransYAnim()
                    goLeft = !goLeft
                }
            }
        }

//        val canvas: Canvas = surfaceHolder!!.lockCanvas()
//        draw(canvas, person, scaledBitmap)

        Log.d(TIME_TAG + 3, (SystemClock.currentThreadTimeMillis() - lastCurrentTime).toString())
        lastCurrentTime = SystemClock.currentThreadTimeMillis()
    }

    /** Extense Bitmap to maintain aspect ratio of model input.   */
    private fun extensionBitmap(bitmap: Bitmap): Bitmap {
        val bitmapRatio = bitmap.height.toFloat() / bitmap.width
        val modelInputRatio = MODEL_HEIGHT.toFloat() / MODEL_WIDTH
        var croppedBitmap = bitmap

        // Acceptable difference between the modelInputRatio and bitmapRatio to skip cropping.
        val maxDifference = 1e-5

        // Checks if the bitmap has similar aspect ratio as the required model input.
        when {
            abs(modelInputRatio - bitmapRatio) < maxDifference -> return croppedBitmap
            modelInputRatio < bitmapRatio -> {
                // New image is taller so we are height constrained.
                croppedBitmap = Bitmap.createBitmap(
                    bitmap.height,
                    bitmap.height,
                    Bitmap.Config.ARGB_8888
                )
                val paint1 = Paint()

                val canvas = Canvas(croppedBitmap);
                canvas.drawBitmap(bitmap, 0.0f, 0.0f, paint1);
                canvas.save();
                canvas.restore()
            }
            else -> {
                croppedBitmap = Bitmap.createBitmap(
                    bitmap.width,
                    bitmap.width,
                    Bitmap.Config.ARGB_8888
                )
                val paint1 = Paint()

                val canvas = Canvas(croppedBitmap);
                canvas.drawBitmap(bitmap, 0.0f, 0.0f, paint1);
                canvas.save();
                canvas.restore()
            }
        }
        return croppedBitmap
    }

    /** Crop Bitmap to maintain aspect ratio of model input.   */
    private fun cropBitmap(bitmap: Bitmap): Bitmap {
        val bitmapRatio = bitmap.height.toFloat() / bitmap.width
        val modelInputRatio = MODEL_HEIGHT.toFloat() / MODEL_WIDTH
        var croppedBitmap = bitmap

        // Acceptable difference between the modelInputRatio and bitmapRatio to skip cropping.
        val maxDifference = 1e-5

        // Checks if the bitmap has similar aspect ratio as the required model input.
        when {
            abs(modelInputRatio - bitmapRatio) < maxDifference -> return croppedBitmap
            modelInputRatio < bitmapRatio -> {
                // New image is taller so we are height constrained.
                val cropHeight = bitmap.height - (bitmap.width.toFloat() / modelInputRatio)
                croppedBitmap = Bitmap.createBitmap(
                    bitmap,
                    0,
                    (cropHeight / 2).toInt(),
                    bitmap.width,
                    (bitmap.height - cropHeight).toInt()
                )
            }
            else -> {
                val cropWidth = bitmap.width - (bitmap.height.toFloat() * modelInputRatio)
                croppedBitmap = Bitmap.createBitmap(
                    bitmap,
                    (cropWidth / 2).toInt(),
                    0,
                    (bitmap.width - cropWidth).toInt(),
                    bitmap.height
                )
            }
        }
        return croppedBitmap
    }

    /** Set the paint color and size.    */
    private fun setPaint() {
        paint.color = Color.GREEN
        paint.textSize = 80.0f
        paint.strokeWidth = 8.0f
    }

    /** Draw bitmap on Canvas.   */
    private fun draw(canvas: Canvas, person: Person, bitmap: Bitmap) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        // Draw `bitmap` and `person` in square canvas.
        val screenWidth: Int
        val screenHeight: Int
        val left: Int
        val right: Int
        val top: Int
        val bottom: Int

        //改为全屏显示
        screenWidth = canvas.width
        screenHeight = canvas.height
        left = 0
        top = 0
        right = left + screenWidth
        bottom = top + screenHeight

        var previewBitmap = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            (bitmap.height * screenHeight / screenWidth)
        )

        setPaint()
        canvas.drawBitmap(
            previewBitmap,
            Rect(0, 0, previewBitmap.width, previewBitmap.height),
            Rect(left, top, right, bottom),
            paint
        )

        val widthRatio = screenWidth.toFloat() / MODEL_WIDTH
        val heightRatio = screenHeight.toFloat() * screenWidth / (MODEL_HEIGHT * screenHeight)

        // Draw key points over the image.
        for (keyPoint in person.keyPoints) {
            if (keyPoint.score > minConfidence &&
                (keyPoint.bodyPart.equals(leftOrRight))
            ) {
                val position = keyPoint.position
                val adjustedX: Float = position.x.toFloat() * widthRatio + left
                val adjustedY: Float = position.y.toFloat() * heightRatio + top
                canvas.drawCircle(adjustedX, adjustedY, circleRadius, paint)

                Log.d(
                    TAG,
                    adjustedX.toString() + "  " + adjustedY + "  " + (0.2 * screenWidth) + "    " + (0.8 * screenHeight)
                )

                //todo 增加得分
                if (goLeft && adjustedX < (0.2 * screenWidth) && adjustedY > (0.5 * screenHeight)) {
//                    startLeftRotateAnim()
//                    startRightTransYAnim()
//                    goLeft = !goLeft
                    val message = Message()
                    message.what = START_LEFT_ANIMATION
                    myHandler.sendMessage(message)
                } else if (!goLeft && adjustedX > (0.8 * screenWidth) && adjustedY > (0.5 * screenHeight)) {
//                    startRightRotateAnim()
//                    startLeftTransYAnim()
//                    goLeft = !goLeft
                    val message = Message()
                    message.what = START_RIGHT_ANIMATION
                    myHandler.sendMessage(message)
                }
            }
        }

//        for (line in bodyJoints) {
//            if (
//                (person.keyPoints[line.first.ordinal].score > minConfidence) and
//                (person.keyPoints[line.second.ordinal].score > minConfidence)
//            ) {
//                canvas.drawLine(
//                    person.keyPoints[line.first.ordinal].position.x.toFloat() * widthRatio + left,
//                    person.keyPoints[line.first.ordinal].position.y.toFloat() * heightRatio + top,
//                    person.keyPoints[line.second.ordinal].position.x.toFloat() * widthRatio + left,
//                    person.keyPoints[line.second.ordinal].position.y.toFloat() * heightRatio + top,
//                    paint
//                )
//            }
//        }

//        canvas.drawText(
//            "Score: %.2f".format(person.score),
//            (15.0f * widthRatio),
//            (30.0f * heightRatio + bottom),
//            paint
//        )
//        canvas.drawText(
//            "Device: %s".format(posenet.device),
//            (15.0f * widthRatio),
//            (50.0f * heightRatio + bottom),
//            paint
//        )
//        canvas.drawText(
//            "Time: %.2f ms".format(posenet.lastInferenceTimeNanos * 1.0f / 1_000_000),
//            (15.0f * widthRatio),
//            (70.0f * heightRatio + bottom),
//            paint
//        )

        // Draw!
        surfaceHolder!!.unlockCanvasAndPost(canvas)
    }

    /**
     * Creates a new [CameraCaptureSession] for camera preview.
     */
    private fun createCameraPreviewSession() {
        try {

            // We capture images from preview in YUV format.
            imageReader = ImageReader.newInstance(
                previewSize!!.width, previewSize!!.height, ImageFormat.YUV_420_888, 2
            )
            imageReader!!.setOnImageAvailableListener(imageAvailableListener, backgroundHandler)

            // This is the surface we need to record images for processing.
            val recordingSurface = imageReader!!.surface
            val targets = listOf(surfaceView!!.holder.surface, imageReader!!.surface)

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ).apply { addTarget(surfaceView!!.holder.surface) }
            previewRequestBuilder!!.addTarget(recordingSurface)

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice!!.createCaptureSession(
//                listOf(recordingSurface),
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        // The camera is already closed
                        if (cameraDevice == null) return

                        // When the session is ready, we start displaying the preview.
                        captureSession = cameraCaptureSession
                        try {
                            // Auto focus should be continuous for camera preview.
                            previewRequestBuilder!!.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            // Flash is automatically enabled when necessary.
                            setAutoFlash(previewRequestBuilder!!)

                            // Finally, we start displaying the camera preview.
                            previewRequest = previewRequestBuilder!!.build()
                            captureSession!!.setRepeatingRequest(
                                previewRequest!!,
                                captureCallback, backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, e.toString())
                        }
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        showToast("Failed")
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun setAutoFlash(requestBuilder: CaptureRequest.Builder) {
        if (flashSupported) {
            requestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            )
        }
    }

    /**
     * Shows an error message dialog.
     */
    class ErrorDialog : DialogFragment() {

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
            AlertDialog.Builder(activity)
                .setMessage(arguments!!.getString(ARG_MESSAGE))
                .setPositiveButton(android.R.string.ok) { _, _ -> activity!!.finish() }
                .create()

        companion object {

            @JvmStatic
            private val ARG_MESSAGE = "message"

            @JvmStatic
            fun newInstance(message: String): ErrorDialog = ErrorDialog().apply {
                arguments = Bundle().apply { putString(ARG_MESSAGE, message) }
            }
        }
    }

    companion object {
        /**
         * Conversion from screen rotation to JPEG orientation.
         */
        private val ORIENTATIONS = SparseIntArray()
        private val FRAGMENT_DIALOG = "dialog"

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        /**
         * Tag for the [Log].
         */
        private const val TAG = "PosenetActivity"
        private const val TIME_TAG = "SystemCurrentTime"
    }

    //设置左右手
    private fun setLeftOrRightHand(isLeftHand: Boolean) {
        leftOrRight = if (isLeftHand) {
            BodyPart.LEFT_WRIST
        } else {
            BodyPart.RIGHT_WRIST
        }
        //todo 开始倒计时
//        startCountDown()
    }
}
