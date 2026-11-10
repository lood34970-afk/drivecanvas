package com.example.drivecanvas

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.example.drivecanvas.ui.DriveCanvasApp
import com.example.drivecanvas.ui.DriveCanvasTheme

class MainActivity : ComponentActivity() {
    private val viewModel: DriveCanvasViewModel by viewModels()

    /** Starts speed monitoring whether or not the permission was granted; without it the app stays parked-only. */
    private val carSpeedPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.startVehicleMotion() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(CAR_SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startVehicleMotion()
        } else {
            carSpeedPermissionLauncher.launch(CAR_SPEED_PERMISSION)
        }
        setContent {
            DriveCanvasTheme {
                DriveCanvasApp(viewModel)
            }
        }
    }

    private companion object {
        const val CAR_SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
    }
}
