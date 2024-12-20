/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.flcosrt01.basic.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.android.camera.utils.getPreviewOutputSize
import com.example.android.camera.utils.AutoFitSurfaceView
import com.example.android.camera.utils.OrientationLiveData
import com.android.flcosrt01.basic.CameraActivity
import com.android.flcosrt01.basic.ProcessingClass
import com.android.flcosrt01.basic.R
import kotlinx.android.synthetic.main.fragment_camera.*
import kotlinx.coroutines.*
import org.bytedeco.opencv.opencv_core.*
import org.opencv.core.CvType
import org.bytedeco.opencv.global.opencv_imgcodecs.imwrite
import java.io.Closeable
import java.io.File
import java.lang.Runnable
import java.text.SimpleDateFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.LinkedBlockingQueue
import kotlin.RuntimeException
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class CameraFragment : Fragment() {

    /** Counter of ImageReader readings */
    private var imgCounter = 0

    /** Variables of the output of an ImageReader readings: length of the Y buffer */
    private var yBufferLength = 0

    /** ROI */
    //private var roi : Rect? = null

    /** Queue for each Mat after applying the ROI mask.*/
    //private val roiMatQueue = ConcurrentLinkedDeque<Mat>()

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
    }

    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
            overlay.background = Color.argb(150, 255, 255, 255).toDrawable()
            // Wait for ANIMATION_FAST_MILLIS
            overlay.postDelayed({
                // Remove white flash animation
                overlay.background = null
                //overlay.postDelayed(animationTask, CameraActivity.ANIMATION_FAST_MILLIS)
            }, CameraActivity.ANIMATION_FAST_MILLIS)
        }
    }

    /** [HandlerThread] where all buffer reading operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** Where the camera preview is displayed */
    private lateinit var viewFinder: AutoFitSurfaceView

    /** Overlay on top of the camera preview */
    private lateinit var overlay: View

    /** ROI rectangle on top of the camera preview */
    private lateinit var roiRectView : View

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private lateinit var session: CameraCaptureSession

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    /** Job variable to control multi-thread job */
    //private lateinit var savingMatJob : Job

    /** Queue for storing QR data */
    private val rxData = ConcurrentLinkedDeque<String>()

    /** Number of ROIs */
    private val numberOfROIs = CameraActivity.numberOfTx

    /** ROI arrays*/
    private val roiViewArray = ArrayList<View>(numberOfROIs)
    private val roiArray = ArrayList<Rect>(numberOfROIs)

    /** Number or RS Data size*/
    private val rsDataSize = CameraActivity.rsDataSize

    /** New capture request to be global */
    private lateinit var newCaptureRequest : CaptureRequest.Builder

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_camera, container, false)

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        overlay = view.findViewById(R.id.overlay)
        viewFinder = view.findViewById(R.id.view_finder)
        //roiRectView = view.findViewById(R.id.roirect)
        val frameLayout = view.findViewById<FrameLayout>(R.id.frame_layout)

        /*roiArray.forEach {
            frameLayout.addView(it)
            it.apply {
                id = View.generateViewId()
                layoutParams.width = 0
                layoutParams.height = 0
                background = ContextCompat.getDrawable(context,R.drawable.emptyrect)
                visibility = View.INVISIBLE
            }
        }*/

        for (i in 0 until numberOfROIs){
            val roiView = View(context)
            frameLayout.addView(roiView)
            roiView.apply {
                id = View.generateViewId()
                layoutParams.width = 0
                layoutParams.height = 0
                background = ContextCompat.getDrawable(context,R.drawable.emptyrect)
                visibility = View.INVISIBLE
            }
            roiViewArray.add(roiView)
        }

        /*roiRectView = View(context)
        frameLayout.addView(roiRectView)
        roiRectView.apply {
            id = View.generateViewId()
            layoutParams.width = 0
            layoutParams.height = 0
            background = ContextCompat.getDrawable(context,R.drawable.emptyrect)
            visibility = View.INVISIBLE
        }*/

        capture_button.setOnApplyWindowInsetsListener { v, insets ->
            v.translationX = (-insets.systemWindowInsetRight).toFloat()
            v.translationY = (-insets.systemWindowInsetBottom).toFloat()
            insets.consumeSystemWindowInsets()
        }
        progressBar.max = rsDataSize

        viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {

                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                        viewFinder.display, characteristics, SurfaceHolder::class.java)
                Log.d(TAG, "View finder size: ${viewFinder.width} x ${viewFinder.height}")
                Log.d(TAG, "Selected preview size: $previewSize")
                //viewFinder.setAspectRatio(previewSize.width, previewSize.height)
                viewFinder.setAspectRatio(args.width, args.height)

                // Resize the ROI rectangle to one third of the height
                /*roiRect.layoutParams.width = previewSize.height / 4
                roiRect.layoutParams.height = previewSize.height / 4*/

                // Depending on orientation the ROI view must be located accordingly
                /*when(activity?.resources?.configuration?.orientation){
                    1 -> {
                        roiRect.x = (previewSize.height - roiRect.width).toFloat() / 2f
                        roiRect.y = (previewSize.width - roiRect.width).toFloat() / 2f
                    }
                    2 -> {
                        roiRect.y = (previewSize.height - roiRect.width).toFloat() / 2f
                        roiRect.x = (previewSize.width - roiRect.width).toFloat() / 2f
                    }
                }*/
                //Log.d(TAG,"Orientation: ${activity?.resources?.configuration?.orientation}")
                //Log.d(TAG,"ROI: width = ${overlay.width / 3}, height = ${overlay.height / 3}")

                // To ensure that size is set, initialize camera in the view's thread
                view.post { initializeCamera() }
            }
        })

        /* Crate an ArrayBlockingQueue of ByteBuffers of size TOTAL_IMAGES. Check this constant
        * because it determines the amount of images the bufferQueue can store, while another
        * thread tries to read it */
        //val bufferQueue = ArrayBlockingQueue<ByteArray>(TOTAL_IMAGES,true)
        //val roiMatQueue = ArrayBlockingQueue<Mat>(TOTAL_IMAGES,true)

        // Listen to the capture button
        capture_button.setOnClickListener {

            // Disable click listener to prevent multiple requests simultaneously in flight
            it.isEnabled = false
            it.isClickable = false

            // Flash animation.
            overlay.post(animationTask)

            // Initialise the controlling job
            //savingMatJob = Job()

            var readCounter = 0

            // Start the Progress Bar to 0
            progressBar.progress = 0

            var startTime = 0L

            /* Crate an ArrayBlockingQueue of ByteBuffers of size TOTAL_IMAGES. Check this constant
            * because it determines the amount of images the bufferQueue can store, while another
            * thread tries to read it */
            val bufferQueue = ArrayBlockingQueue<ByteArray>(TOTAL_IMAGES,true)
            val roiMatQueue = ArrayBlockingQueue<Mat>(TOTAL_IMAGES,true)

            val size = Size(args.width,args.height)

            // Perform I/O heavy operations in a different scope
            //lifecycleScope.launch(Dispatchers.IO) {
            // Flush any images left in the image reader
            imageReader.acquireLatestImage()?.close()

            // Save the start time.
            startTime = System.currentTimeMillis()
            /* Set the ImageReader listener to just get the Y-plane of the YUV image. This plane
            * contains just the grayscale version of the image captured.
            * This reads, gets the plane and stores the byteArray variable in the
            * ArrayBlockingQueue bufferQueue*/
            imageReader.setOnImageAvailableListener({ reader ->
                /*reader.acquireNextImage()?.let { image ->
                    val byteArray = ByteArray(yBufferLength)
                    image.planes[0].buffer.get(byteArray,0,yBufferLength)
                    bufferQueue.add(byteArray)
                    image.close()
                    Log.d(TAG, "Image counter: $imgCounter")
                    imgCounter += 1
                }*/
                val image = reader.acquireNextImage() /** Non-null assessment? Reduces capture rate...*/
                val byteArray = ByteArray(yBufferLength)
                image?.planes?.get(0)?.buffer?.get(byteArray,0,yBufferLength)
                bufferQueue.add(byteArray)
                image?.close()
                Log.d(TAG, "Image counter: $imgCounter")
                imgCounter += 1
            }, imageReaderHandler)
            //}
            /* Launch another thread to take the byteArrays from the queue and stores them as Mat
            * objects in the ConcurrentLinkedDeque roiMatQueue*/
            val savingMatJob = lifecycleScope.launch(Dispatchers.Default) {
                //var readCounter = 0
                while (isActive){ //readCounter < 100) {
                    //val imgBytes = withContext(Dispatchers.IO) { bufferQueue.take() }
                    val matImg = Mat(size.height, size.width, CvType.CV_8UC1)
                    matImg.data().put(withContext(Dispatchers.IO) { bufferQueue.take() }, 0, yBufferLength)
                    /*synchronized(roiMatQueue) {
                        roiMatQueue.add(Mat(matImg, roi)) //Add Mat using the ROI or LinkedBlockingQueue
                        //roiMatQueue.add(matImg)
                    }*/
                    //roiMatQueue.add(Mat(matImg,roi))
                    roiArray.forEach{ roi ->
                        roiMatQueue.add(Mat(matImg,roi))
                    }
                    /*for (i in 0 until numberOfROIs){
                        roiMatQueue.add(Mat(matImg,roiArray[i]))
                    }*/
                    Log.d(TAG, "Taking image $readCounter")
                    readCounter += 1
                }
            }

            lifecycleScope.launch(Dispatchers.Default) {
                /* When finished clean some variables, remove the ImageReader listener, print the
                * calculated FPS and re-enable the capture button */
                //var preMat = Mat(roiArray[0].height(), roiArray[0].width(), CvType.CV_8UC1, Scalar(0.0))

                while (rxData.size < rsDataSize) {
                    //val mat : Mat?
                    /*synchronized(roiMatQueue){ //Recheck: idea -> block decodeQR, remove delay
                        mat = roiMatQueue.pollFirst()
                        ProcessingClass.decodeQR(mat, rxData)
                    }*/
                    val mat = withContext(Dispatchers.IO){ roiMatQueue.take() }
                    //val nextMat = roiMatQueue.peek()
                    //ProcessingClass.decodeQR(mat, rxData)
                    if (!ProcessingClass.decodeQR(mat, /*preMat,*/ rxData)) {
                        progressBar.progress = rxData.size
                    }
                    //preMat = mat.clone()

                    //delay(15)
                    //Log.d(TAG, "Currently ${rxData.size} QRs")
                    //progressBar.progress = rxData.size
                }

                // Remove ImageReader listener and clean variables
                //lifecycleScope.launch(Dispatchers.IO) {
                imageReader.setOnImageAvailableListener({ reader ->
                    reader.acquireLatestImage()?.close()
                } , imageReaderHandler)
                //}
                // Stop job
                savingMatJob.cancel("Sufficient data for RS-FEC")

                bufferQueue.clear()
                roiMatQueue.clear()

                val rsStartTime = System.currentTimeMillis()

                session.stopRepeating()

                /* Call the Reed Solomon Forward Error Correction (RS-FEC) function */
                /*var result = ""
                thread(start=true,name="ReedSolomon",priority = Thread.MAX_PRIORITY){
                    result = ProcessingClass.reedSolomonFEC(rxData)
                        }.join()*/
                val result = ProcessingClass.reedSolomonFEC(rxData)//,6)
                //Log.d(TAG, "Result: $result")

                session.setRepeatingRequest(newCaptureRequest.build(), null, cameraHandler)

                val endTime = System.currentTimeMillis()

                val totalTime = endTime - startTime // Time duration
                //Log.d(TAG,"FPS: ${100000.0/totalTime}")
                //Log.d(TAG,"Total time : $totalTime")
                overlay.post(animationTask)

                // Add an Alert Dialog to show results + execution time
                val resultDialog = ResultDialogFragment()
                resultDialog.changeText(
                        getString(R.string.capt_dec_time) + "${rsStartTime-startTime} ms" + System.lineSeparator()
                                + getString(R.string.rs_time) + "${endTime - rsStartTime} ms" + System.lineSeparator()
                                + System.lineSeparator() + result
                        , "$readCounter frames, $totalTime ms")
                //resultDialog.show(requireParentFragment().parentFragmentManager,"result")
                resultDialog.show(activity?.supportFragmentManager!!,"result")

                // Clear some variables
                imgCounter = 0
                //bufferQueue.clear()
                //roiMatQueue.clear()
                rxData.clear()
                it.post {
                    it.isEnabled = true
                    it.isClickable = true
                }
                // Loop the roiMatQueue to save the files
                /*Log.d(TAG,"Saving files...")
                while (roiMatQueue.peekFirst() != null){
                    saveImage(roiMatQueue.pollFirst()!!)
                }*/
            }
        }

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner, Observer {
                orientation -> Log.d(TAG, "Orientation changed: $orientation")
            })
        }
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating capture request
     * - Sets up the still image capture listeners
     */
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        //var startTime = 0L

        // Initialize an image reader which will be used to capture continuously
        /* We need to change this to use ImageFormat.YUV_420_888 and use the size selected in the
        * Selector Fragment. mact */
        val size = Size(args.width,args.height)
        imageReader = ImageReader.newInstance(
                size.width, size.height, ImageFormat.YUV_420_888, IMAGE_BUFFER_SIZE
        )

        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(viewFinder.holder.surface, imageReader.surface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        val captureRequest = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(viewFinder.holder.surface)
                    addTarget(imageReader.surface)
                //Set Zoom
                set(CaptureRequest.SCALER_CROP_REGION,args.zoom)
                // Set Continuous Picture mode
                //set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                /* Control Mode to auto + AF to Continuous + AE to Auto */
                set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON)
                // AE to lowest value
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,args.aeLow)
                // Set AE and AF regions
                set(CaptureRequest.CONTROL_AE_REGIONS,arrayOf(MeteringRectangle(args.zoom,
                        MeteringRectangle.METERING_WEIGHT_MAX-1)))
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(args.zoom,
                        MeteringRectangle.METERING_WEIGHT_MAX-1)))
                // AE FPS to highest
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,android.util.Range(args.fps,args.fps))
                }

        // This will keep sending the capture request as frequently as possible until the
        // session is torn down or session.stopRepeating() is called
        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)

        // Try to get a first image from the ImageReader to get its buffer length
        lifecycleScope.launch(Dispatchers.IO) {
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                yBufferLength = image.planes[0].buffer.remaining() //Buffer length
                image.close()
            }, imageReaderHandler)
        }

        /* Wait for the imageReader to get the buffer length */
        @Suppress("ControlFlowWithEmptyBody")
        while (yBufferLength == 0){
            delay(10)
            //Log.d(TAG,"You're in the loop.")
        }

        /* Try to find the ROI */
        //lifecycleScope.launch(Dispatchers.IO) {
        imageReader.setOnImageAvailableListener({ reader ->
            /*val image = */reader.acquireLatestImage()?.let {
                val byteArray = ByteArray(yBufferLength)
                it.planes[0].buffer.get(byteArray,0,yBufferLength)
                it.close()
                val matImg = Mat(size.height, size.width, CvType.CV_8UC1)
                matImg.data().put(byteArray, 0, byteArray.size)
                if (roiArray.size < numberOfROIs) {
                    ProcessingClass.detectROI(matImg,roiArray)
                }
            }
            /*val byteArray = ByteArray(yBufferLength)
            image.planes[0].buffer.get(byteArray,0,yBufferLength)
            image.close()
            val matImg = Mat(size.height, size.width, CvType.CV_8UC1)
            matImg.data().put(byteArray, 0, byteArray.size)
            if (roi == null) {
                roi = ProcessingClass.detectROI(matImg)
            }*/
        }, imageReaderHandler)
        //}
        while(roiArray.size < numberOfROIs){
            delay(10)
        }

        /* The setting of (null, null) for the imageReader listener throws an error on some phone
        * models,thus we set an image listener which just closes the latest image */
        imageReader.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.close()
        },imageReaderHandler)

        for (i in 0 until numberOfROIs){
            Log.d(TAG,"ImageReader -> width: ${size.width}, height: ${size.height}, Y-Buffer size: $yBufferLength ")
            Log.d(TAG,"ROI -> width: ${roiArray[i].width()}, height: ${roiArray[i].height()}")

            /*Set the ROI View parameters to be displayed in the camera surface*/
            roiViewArray[i].y = (roiArray[i].x() * viewFinder.height / size.width).toFloat()
            roiViewArray[i].x = (viewFinder.width- (roiArray[i].y() + roiArray[i].height()) * viewFinder.width / size.height).toFloat()
            roiViewArray[i].layoutParams = FrameLayout.LayoutParams(
                roiArray[i].height() * viewFinder.width / size.height,
                roiArray[i].width() * viewFinder.height / size.width
            )

            roiViewArray[i].visibility = View.VISIBLE
        }

        /*Log.d(TAG,"ImageReader -> width: ${size.width}, height: ${size.height}, Y-Buffer size: $yBufferLength ")
        Log.d(TAG,"ROI -> width: ${roi!!.width()}, height: ${roi!!.height()}")

        /*Set the ROI View parameters to be displayed in the camera surface*/
        roiViewArray[0].y = (roi!!.x() * viewFinder.height / size.width).toFloat()
        roiViewArray[0].x = (viewFinder.width- (roi!!.y() + roi!!.height()) * viewFinder.width / size.height).toFloat()
        roiViewArray[0].layoutParams = FrameLayout.LayoutParams(
            roi!!.height() * viewFinder.width / size.height,
            roi!!.width() * viewFinder.height / size.width
        )*/
        /* Show the ROI and the capture button */
        //roiViewArray[0].visibility = View.VISIBLE
        capture_button.visibility = View.VISIBLE

        /* Calculate AE/AF rectangle */
        /*val regionAEAF = ProcessingClass.scaleRect(args.zoom, size, roi!!)*/

        /** NOTE: Here should go the code to implement a new capture request based on the AE/AF
         * regions. This needs to be implemented accordingly to get the lowest AE value. Interesting
         * if all the phone cameras work the same. */
        newCaptureRequest = camera.createCaptureRequest(
            CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(viewFinder.holder.surface)
            addTarget(imageReader.surface)
            //Set Zoom
            set(CaptureRequest.SCALER_CROP_REGION,args.zoom)
            // Set Continuous Picture mode
            //set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            /* Control Mode to auto + AF to Continuous + AE to Auto */
            set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_LOCK,true)
            // AE to lowest value
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,args.aeLow)
            // Set AE and AF regions
            set(CaptureRequest.CONTROL_AE_REGIONS,arrayOf(MeteringRectangle(args.zoom,
                    MeteringRectangle.METERING_WEIGHT_MAX-1)))
            set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(args.zoom,
                    MeteringRectangle.METERING_WEIGHT_MAX-1)))
            // AE FPS to highest
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,Range(args.fps,args.fps))
        }

        // Stop and restart the camera session with new capture requests
        session.stopRepeating()
        session.setRepeatingRequest(newCaptureRequest.build(), null, cameraHandler)

        /* The ROI is defined by the resolution. x,y = screen half - half of roi.
        *  The actual values of the ROI rectangle needed for OpenCV Mat requires width and height
        * to be swap, Mat_x coordinate is y  and Mat_y is measured from the other end so its
        * width - x - w */
        /*roi = Rect(size.width/2 - size.height/8,
            size.height - size.height/2 - size.height/8, //(size.height/2 + size.height/16),// - size.height/4),
            size.height/4,size.height/4)*/

        /* Crate an ArrayBlockingQueue of ByteBuffers of size TOTAL_IMAGES. Check this constant
        * because it determines the amount of images the bufferQueue can store, while another
        * thread tries to read it */
        /*val bufferQueue = ArrayBlockingQueue<ByteArray>(TOTAL_IMAGES,true)
        val roiMatQueue = ArrayBlockingQueue<Mat>(TOTAL_IMAGES,true)

        // Listen to the capture button
        capture_button.setOnClickListener {

            // Disable click listener to prevent multiple requests simultaneously in flight
            it.isEnabled = false
            it.isClickable = false

            // Flash animation.
            overlay.post(animationTask)

            // Initialise the controlling job
            //savingMatJob = Job()

            var readCounter = 0

            // Start the Progress Bar to 0
            progressBar

            // Perform I/O heavy operations in a different scope
            lifecycleScope.launch(Dispatchers.IO) {
                // Flush any images left in the image reader
                imageReader.acquireLatestImage()?.close()

                // Save the start time.
                startTime = System.currentTimeMillis()
                /* Set the ImageReader listener to just get the Y-plane of the YUV image. This plane
                * contains just the grayscale version of the image captured.
                * This reads, gets the plane and stores the byteArray variable in the
                * ArrayBlockingQueue bufferQueue*/
                imageReader.setOnImageAvailableListener({ reader ->
                    /*reader.acquireNextImage()?.let { image ->
                        val byteArray = ByteArray(yBufferLength)
                        image.planes[0].buffer.get(byteArray,0,yBufferLength)
                        bufferQueue.add(byteArray)
                        image.close()
                        Log.d(TAG, "Image counter: $imgCounter")
                        imgCounter += 1
                    }*/
                    val image = reader.acquireNextImage() /** Non-null assessment? Reduces capture rate...*/
                    val byteArray = ByteArray(yBufferLength)
                    image?.planes?.get(0)?.buffer?.get(byteArray,0,yBufferLength)
                    bufferQueue.add(byteArray)
                    image?.close()
                    Log.d(TAG, "Image counter: $imgCounter")
                    imgCounter += 1
                }, imageReaderHandler)
            }
            /* Launch another thread to take the byteArrays from the queue and stores them as Mat
            * objects in the ConcurrentLinkedDeque roiMatQueue*/
            val savingMatJob = lifecycleScope.launch(Dispatchers.Default) {
                //var readCounter = 0
                while (isActive){ //readCounter < 100) {
                    //val imgBytes = withContext(Dispatchers.IO) { bufferQueue.take() }
                    val matImg = Mat(size.height, size.width, CvType.CV_8UC1)
                    matImg.data().put(withContext(Dispatchers.IO) { bufferQueue.take() }, 0, yBufferLength)
                    /*synchronized(roiMatQueue) {
                        roiMatQueue.add(Mat(matImg, roi)) //Add Mat using the ROI or LinkedBlockingQueue
                        //roiMatQueue.add(matImg)
                    }*/
                    //roiMatQueue.add(Mat(matImg,roi))
                    roiArray.forEach{ roi ->
                        roiMatQueue.add(Mat(matImg,roi))
                    }
                    /*for (i in 0 until numberOfROIs){
                        roiMatQueue.add(Mat(matImg,roiArray[i]))
                    }*/
                    Log.d(TAG, "Taking image $readCounter")
                    readCounter += 1
                }
            }

            lifecycleScope.launch(Dispatchers.Default) {
                /* When finished clean some variables, remove the ImageReader listener, print the
                * calculated FPS and re-enable the capture button */
                //var preMat = Mat(roiArray[0].height(), roiArray[0].width(), CvType.CV_8UC1, Scalar(0.0))

                while (rxData.size < rsDataSize) {
                    //val mat : Mat?
                    /*synchronized(roiMatQueue){ //Recheck: idea -> block decodeQR, remove delay
                        mat = roiMatQueue.pollFirst()
                        ProcessingClass.decodeQR(mat, rxData)
                    }*/
                    val mat = withContext(Dispatchers.IO){ roiMatQueue.take() }
                    //val nextMat = roiMatQueue.peek()
                    //ProcessingClass.decodeQR(mat, rxData)
                    if (!ProcessingClass.decodeQR(mat, /*preMat,*/ rxData)) {
                        progressBar.progress = rxData.size
                    }
                    //preMat = mat.clone()

                    //delay(15)
                    //Log.d(TAG, "Currently ${rxData.size} QRs")
                    //progressBar.progress = rxData.size
                }

                // Remove ImageReader listener and clean variables
                lifecycleScope.launch(Dispatchers.IO) {
                    imageReader.setOnImageAvailableListener({ reader ->
                        reader.acquireLatestImage()?.close()
                    } , imageReaderHandler)
                }
                // Stop job
                savingMatJob.cancel("Sufficient data for RS-FEC")

                val rsStartTime = System.currentTimeMillis()

                session.stopRepeating()

                /* Call the Reed Solomon Forward Error Correction (RS-FEC) function */
                val result = ProcessingClass.reedSolomonFEC(rxData)
                //Log.d(TAG, "Result: $result")

                session.setRepeatingRequest(newCaptureRequest.build(), null, cameraHandler)

                val endTime = System.currentTimeMillis()

                val totalTime = endTime - startTime // Time duration
                //Log.d(TAG,"FPS: ${100000.0/totalTime}")
                //Log.d(TAG,"Total time : $totalTime")
                overlay.post(animationTask)

                // Add an Alert Dialog to show results + execution time
                val resultDialog = ResultDialogFragment()
                resultDialog.changeText(
                        getString(R.string.capt_dec_time) + "${rsStartTime-startTime} ms" + System.lineSeparator()
                        + getString(R.string.rs_time) + "${endTime - rsStartTime} ms" + System.lineSeparator()
                                + System.lineSeparator() + result
                        , "$readCounter frames, $totalTime ms")
                //resultDialog.show(requireParentFragment().parentFragmentManager,"result")
                resultDialog.show(activity?.supportFragmentManager!!,"result")

                // Clear some variables
                imgCounter = 0
                bufferQueue.clear()
                roiMatQueue.clear()
                rxData.clear()
                it.post {
                    it.isEnabled = true
                    it.isClickable = true
                }
                // Loop the roiMatQueue to save the files
                /*Log.d(TAG,"Saving files...")
                while (roiMatQueue.peekFirst() != null){
                    saveImage(roiMatQueue.pollFirst()!!)
                }*/
            }
        }*/
    }

    /** Function to save a [Mat] object as a JPG image */
    private fun saveImage(mat : Mat) : Boolean {
        val file = createFile(requireContext(), "jpg")
        return imwrite(file.absolutePath,mat)
    }

    /** Creates a [File] named with the current date and time */
    private fun createFile(context: Context, extension: String): File {
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
        return File(context.filesDir, "IMG_${sdf.format(Date())}.$extension")
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
            manager: CameraManager,
            cameraId: String,
            handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when(error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private suspend fun createCaptureSession(
            device: CameraDevice,
            targets: List<Surface>,
            handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        device.createCaptureSession(targets, object: CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    override fun onStop() {
        super.onStop()
        try {
            camera.close()
            imageReader.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
    }

    /** Dialog fragment to display the results FPS and decoded text */
    /* Then this should be extended to include a scheme of restaurants and detailed information and
    * the Intent call to Google Maps */
    class ResultDialogFragment : DialogFragment() {
        private var result = "*"
        private var execTime = "*"
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            return activity?.let{
                // Use the Builder class for convenient dialog construction
                val builder = AlertDialog.Builder(it)
                builder.setMessage(result)
                builder.setTitle(execTime)
                builder.create()
            } ?: throw IllegalStateException("Activity cannot be null")
        }
        fun changeText(text : String, time : String) {
            /*val builder = AlertDialog.Builder(activity)
            builder.setMessage(text)
            builder.setTitle(time.toString())
            return  builder.create()*/
            result = text
            execTime = time
        }
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        /** Maximum number of images that will be held in the reader's buffer */
        private const val IMAGE_BUFFER_SIZE: Int = 3

        /** Maximum number of images that will be held in the reader's buffer */
        private const val TOTAL_IMAGES: Int = 25

        /** Maximum time allowed to wait for the result of an image capture */
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        /** Number of ROIs */
        //private val NUMBER_OF_ROIs = CameraActivity.numberOfTx

        /** Helper data class used to hold capture metadata with their associated image */
        data class CombinedCaptureResult(
                val image: Image,
                val metadata: CaptureResult,
                val orientation: Int,
                val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }
    }
}
