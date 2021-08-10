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
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.android.camera.utils.GenericListAdapter
import com.android.flcosrt01.basic.R

class SelectorFragment : Fragment() {

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? = RecyclerView(requireContext())

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view as RecyclerView
        view.apply {
            layoutManager = LinearLayoutManager(requireContext())

            val cameraManager =
                    requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val cameraList = enumerateCameras(cameraManager)

            val layoutId = android.R.layout.simple_list_item_1
            adapter = GenericListAdapter(cameraList, itemLayoutId = layoutId) { view, item, _ ->
                view.findViewById<TextView>(android.R.id.text1).text = item.title
                view.setOnClickListener {
                    Navigation.findNavController(requireActivity(), R.id.fragment_container)
                            .navigate(
                                SelectorFragmentDirections.actionSelectorToCamera(
                                    item.cameraId, item.format, item.size.width, item.size.height,
                                    item.zoom, item.aeLow
                                )
                            )
                }
            }
        }

    }

    companion object {

        /** Helper class used as a data holder for each selectable camera format item */
        private data class FormatItem(
            val title: String,
            val cameraId: String,
            val format: Int,
            val size: Size,
            val zoom: Rect, // Added by Miguel
            val aeLow: Int) // Added by Miguel

        /** Helper function used to convert a lens orientation enum into a human-readable string */
        private fun lensOrientationString(value: Int) = when(value) {
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Unknown"
        }

        /** Helper function used to list all compatible cameras and supported pixel formats */
        @SuppressLint("InlinedApi")
        private fun enumerateCameras(cameraManager: CameraManager): List<FormatItem> {
            val availableCameras: MutableList<FormatItem> = mutableListOf()

            // Get list of all compatible cameras
            val cameraIds = cameraManager.cameraIdList.filter {
                val characteristics = cameraManager.getCameraCharacteristics(it)
                val capabilities = characteristics.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                capabilities?.contains(
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: false
            }

            // Iterate over the list of cameras and return all the compatible ones
            cameraIds.forEach { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val orientation = lensOrientationString(
                        characteristics.get(CameraCharacteristics.LENS_FACING)!!)

                // Query the available capabilities and output formats
                val capabilities = characteristics.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!
                val outputFormats = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.outputFormats
                /* Log.d("FPS ranges",
                    characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).contentToString()
                ) */ // Print the available FPS for the camera.
                // Try to get the output sizes - mact
                val outputSizes = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.YUV_420_888)
                // Sensor array size for calculating zoom
                val w = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!.width()
                val h = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!.height()
                Log.d("Zoom", "Max zoom: ${characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)}")
                //val zoom = calcZoom(w,h,4.0F)
                // AE range
                val aeRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)!!

                outputSizes.forEach { size ->
                    // All cameras *must* support JPEG output so we don't need to check characteristics
                    // Replaced the JPEG output to YUV_420_288 - mact
                    /* Include zoom for each size to be scaled */
                    val zoom = scaleZoom(w,h,6.0F,size)
                    availableCameras.add(
                        FormatItem(
                            "$orientation JPEG ($id) $size", id, ImageFormat.YUV_420_888, size, zoom, aeRange.lower)
                    )
                    /* Log.d("Min Frame duration","$size: " +
                            "${characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                                .getOutputMinFrameDuration(ImageFormat.YUV_420_888,size)}") */

                    // Return cameras that support RAW capability
                    if (capabilities.contains(
                            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) &&
                        outputFormats.contains(ImageFormat.RAW_SENSOR)) {
                        availableCameras.add(
                            FormatItem(
                                "$orientation RAW ($id) $size", id, ImageFormat.RAW_SENSOR, size, zoom, aeRange.lower)
                        )
                    }

                    // Return cameras that support JPEG DEPTH capability
                    if (capabilities.contains(
                            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) &&
                        outputFormats.contains(ImageFormat.DEPTH_JPEG)) {
                        availableCameras.add(
                            FormatItem(
                                "$orientation DEPTH ($id) $size", id, ImageFormat.DEPTH_JPEG, size, zoom, aeRange.lower)
                        )
                    }
                }
            }
            return availableCameras
        }

        private fun calcZoom(w: Int, h: Int, zoom: Float) : Rect{
            val newZoom = zoom.coerceIn(1.0F,zoom)

            val centerX = w/2
            val centerY = h/2
            val deltaX = ((0.5F * w) / newZoom ).toInt()
            val deltaY = ((0.5F * h) / newZoom ).toInt()

            return Rect(centerX - deltaX,centerY - deltaY,centerX + deltaX,centerY + deltaY)
        }

        private fun scaleZoom(w: Int, h: Int, zoom: Float, size: Size) : Rect{
            val newZoom = zoom.coerceIn(1.0F,zoom)

            val centerX = w/2
            val centerY = h/2
            val deltaX = ((0.5F * w) / newZoom ).toInt()
            val deltaY = ((0.5F * w * size.height / size.width) / newZoom ).toInt()

            return Rect(centerX - deltaX,centerY - deltaY,centerX + deltaX,centerY + deltaY)
        }
    }
}
