package com.ruirui.android_location_spike

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                LocationSpikeScreen()
            }
        }
    }
}

@Composable
fun LocationSpikeScreen() {
    // STATE: save the log-entries
    val logMessages = remember {  mutableStateListOf<String>("System ready. Waiting for Start...") }
    val context = LocalContext.current

    // 1. Which permissions do we need for our device?
    val permissionsToRequest = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        // starting from Android 13, we need to request notifications permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    // 2. Launcher: What happens if the user clicks on "Allow" / "Don't allow"
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        // check whether at least one location permission has been granted
        val locationGranted = permissionsMap[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                              permissionsMap[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (locationGranted) {
            logMessages.add("Location permissions granted! Starting Service...")

            // create Intent to start Location Service
            val serviceIntent = Intent(context, LocationService::class.java)

            // ContextCompat automatically chooses the correct instruction for the android version
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            logMessages.add("Error: Location permissions not granted!")
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Text(
            text = "FindMe Background Spike",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.padding(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = {
                logMessages.add("Start clicked! (Service still missing)")
                permissionLauncher.launch(permissionsToRequest)
            }) {
                Text("Start Tracking")
            }

            Button(onClick = {
                logMessages.add("Stopping Service...")
                // send Intent to stop
                val stopIntent = Intent(context, LocationService::class.java)
                context.stopService(stopIntent)
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Stop Tracking")
            }
        }

        Spacer(modifier = Modifier.padding(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.padding(8.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logMessages.reversed()) { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}