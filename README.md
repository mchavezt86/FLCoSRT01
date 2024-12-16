
Sunbox: Map example
===========================

This branch of the **Sunbox** project can read a specific sequence of QR codes that builds
a map with markers and information about those markers.

Introduction
------------

The final goal of **Sunbox** is to transmit location information to a smartphone without using
any radio connection. By having a set of **Sunbox** devices distributed across a place, they can
jointly transmit a route information by indicating the location of the next **Sunbox**.

This code should run on Android devices, it follows the same logic as the master branch.

Requirements
------------

* The map should be in the phone's cache before trying this example in Airplane mode (without
any wireless connection).
* The data transmitted by **Sunbox** should be generated so it transmits the correct map data.
The code for generation is located in [here][https://github.com/mchavezt86/videoQR01],
 file _QR+RSGen.py_.