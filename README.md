
Sunbox (FLCoSRT01)
===========================
Using [Camera2 API][1] the code FLCoSRT01 (FLCoS Real Time 01) is the main code for using the
**Sunbox** system.

Description
------------

The code follows the following logic:
1. Select the appropriate resolution, the type of code (QR or DM) and the rate of Reed-Solomon code
2. Once the camera starts, it will try to detect the screen (FLCoS reflecting sunlight).
3. The detection succeed when a rectangle is drawn surrounding the screen.
4. By pressing the circular button, the camera starts capturing the QR or DM codes transmitted and
based on the information from step 1, the programme decodes the message.

Requirements
------------
* The code requires the FLCoS from **Sunbox** specified in the [Sunbox paper](https://dl.acm.org/doi/10.1145/3534602)
* The information displayed by **Sunbox** should be created following [this code](https://github.com/mchavezt86/videoQR),
file _QR+RSGen.py_.

[1]: https://developer.android.com/reference/android/hardware/camera2/package-summary.html
