package com.locationtrigger.clean

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LocationTriggerScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationTriggerScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as LocationTriggerApp
    val scope = rememberCoroutineScope()
    val state by app.store.stateFlow.collectAsState(initial = TriggerState())
    val snackbarHostState = remember { SnackbarHostState() }

    var pinInput by remember { mutableStateOf("") }
    var testPreview by remember { mutableStateOf<String?>(null) }
    var testingGps by remember { mutableStateOf(false) }

    LaunchedEffect(state.pinCode) {
        if (pinInput.isEmpty() && state.pinCode.isNotEmpty()) {
            pinInput = state.pinCode
        }
    }

    val basePermissions = remember {
        buildList {
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var permVersion by remember { mutableIntStateOf(0) }
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permVersion++
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    val missingBase = remember(permVersion) {
        basePermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
    val hasForegroundLoc = remember(permVersion) { OfflineLocationFetcher.hasLocationPermission(context) }
    val hasBgLoc = remember(permVersion) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val hasPostNoti = remember(permVersion) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val deviceLocationOn = remember(permVersion) { OfflineLocationFetcher.isDeviceLocationEnabled(context) }

    val basePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permVersion++
        if (state.enabled && hasPostNoti) {
            LocationTriggerService.start(context)
        }
    }

    val bgLocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        permVersion++
    }

    val powerManager = context.getSystemService(PowerManager::class.java)
    val batteryIgnored = remember(permVersion) {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    LaunchedEffect(state.enabled, hasPostNoti, hasForegroundLoc) {
        if (state.enabled && hasPostNoti) {
            LocationTriggerService.start(context)
        } else if (!state.enabled) {
            LocationTriggerService.stop(context)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Location Trigger") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (missingBase.isNotEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "SMS & Location Permissions Required",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(6.dp))
                            Button(onClick = { basePermLauncher.launch(basePermissions.toTypedArray()) }) {
                                Text("Grant Permissions")
                            }
                        }
                    }
                }
            } else if (!hasBgLoc && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "Allow Background Location",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Select 'Allow all the time' so GPS replies work when app is closed.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(6.dp))
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                } else {
                                    bgLocLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                }
                            }) {
                                Text("Open Location Settings")
                            }
                        }
                    }
                }
            }

            if (!batteryIgnored) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Allow Background Running", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Disable battery restriction so triggers work after closing app",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }) {
                                Text("Allow")
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (deviceLocationOn) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Text(
                                text = if (deviceLocationOn) "Phone Location (GPS): ON" else "Phone Location (GPS) is OFF",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (deviceLocationOn) {
                                    "High-accuracy GPS ready for SMS triggers."
                                } else {
                                    "Android blocks apps from silently toggling GPS — turn ON in Settings."
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (deviceLocationOn) {
                            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                                Text("Settings")
                            }
                        } else {
                            Button(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                                Text("Turn ON")
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (state.enabled) "Trigger Active: ON" else "Trigger Paused: OFF",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            val previewPin = pinInput.trim().ifEmpty { state.pinCode }.ifEmpty { "4829" }
                            Text(
                                "Send SMS 'LOC $previewPin' or '$previewPin' from any phone",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.enabled,
                            onCheckedChange = { turnOn ->
                                scope.launch {
                                    val candidate = pinInput.trim().ifEmpty { state.pinCode }
                                    if (turnOn && !TriggerLogic.isValidPin(candidate)) {
                                        snackbarHostState.showSnackbar("Enter at least a 4-character PIN first")
                                        return@launch
                                    }
                                    if (turnOn && pinInput.isNotBlank()) {
                                        app.store.setPinCode(pinInput)
                                    }
                                    app.store.setEnabled(turnOn)
                                    if (turnOn && hasPostNoti) {
                                        LocationTriggerService.start(context)
                                    } else if (!turnOn) {
                                        LocationTriggerService.stop(context)
                                    }
                                }
                            }
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Trigger PIN Code",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { pinInput = it },
                            label = { Text("Secret PIN (min 4 digits/chars, e.g. 4829)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val cleaned = pinInput.trim()
                                        if (!TriggerLogic.isValidPin(cleaned)) {
                                            snackbarHostState.showSnackbar("PIN must be at least 4 characters")
                                            return@launch
                                        }
                                        app.store.setPinCode(cleaned)
                                        app.store.setEnabled(true)
                                        if (hasPostNoti) LocationTriggerService.start(context)
                                        snackbarHostState.showSnackbar("Saved! SMS 'LOC $cleaned' to trigger")
                                    }
                                }
                            ) {
                                Text("Save & Start")
                            }
                            OutlinedButton(
                                enabled = !testingGps,
                                onClick = {
                                    scope.launch {
                                        testingGps = true
                                        val preview = withContext(Dispatchers.IO) {
                                            val locResult = OfflineLocationFetcher.fetchBestLocation(context, 12_000L)
                                            val bat = OfflineLocationFetcher.readBatteryPercent(context)
                                            TriggerLogic.formatLocationReply(locResult, bat)
                                        }
                                        testPreview = preview
                                        testingGps = false
                                    }
                                }
                            ) {
                                Text(if (testingGps) "Getting GPS..." else "Test GPS")
                            }
                        }
                        testPreview?.let { preview ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = preview,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (state.recentLogs.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Recent Trigger History", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { scope.launch { app.store.clearLogs() } }) {
                            Text("Clear")
                        }
                    }
                }
                items(state.recentLogs) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = entry,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
