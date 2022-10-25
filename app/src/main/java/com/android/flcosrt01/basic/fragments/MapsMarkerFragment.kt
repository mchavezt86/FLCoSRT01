package com.android.flcosrt01.basic.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.findFragment
import androidx.navigation.fragment.navArgs
import com.android.flcosrt01.basic.R
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.android.synthetic.main.activity_camera.*
import java.io.File
import java.io.FileReader

class MapsMarkerFragment : Fragment(), OnMapReadyCallback {

    private lateinit var map : MapView

    /** AndroidX navigation arguments */
    private val args: MapsMarkerFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.activity_maps,container,false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        map = view.findViewById(R.id.mapView)
        map.onCreate(savedInstanceState)
        map.onResume()
        map.getMapAsync(this)
        //Log.d("Map","Loading map...")
    }

    override fun onMapReady(googleMap: GoogleMap) {
        val mapFile = File(args.filename)
        val mapReader = FileReader(mapFile)
        val lines = mapReader.readLines()
        val ewi = LatLng(51.99910, 4.37834)
        //Log.d("Map","Still loading...")
        /*googleMap.addMarker(
            MarkerOptions()
                .position(ewi)
                .title("EWI building")
        )
        val stop1 = LatLng(51.99994,4.37805)
        val stop2 = LatLng(52.00031,4.37770)
        googleMap.addMarker(
            MarkerOptions()
                .position(stop1)
                .title("Stop 1")
        )
        googleMap.addMarker(
            MarkerOptions()
                .position(stop2)
                .title("Stop 2")
        )*/
        lines.forEach {
            val data = it.split(";")
            if (data.size >= 3){
                val markerLatLong = LatLng(data[0].toDouble(),data[1].toDouble())
                googleMap.addMarker(
                    MarkerOptions()
                        .position(markerLatLong)
                        .title(data[2])
                )
            }
        }
        // [START_EXCLUDE silent]
        googleMap.moveCamera(CameraUpdateFactory.newLatLng(ewi))
        googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(ewi,12f))
        // [END_EXCLUDE]
    }
}