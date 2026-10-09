package info.dourok.voicebot

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import info.dourok.voicebot.ui.ActivationScreen
import info.dourok.voicebot.ui.ChatScreen
import info.dourok.voicebot.ui.ServerFormScreen
import info.dourok.voicebot.ui.theme.VoicebotclientandroidTheme


@AndroidEntryPoint
class MainActivity : ComponentActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        (getSystemService(AUDIO_SERVICE) as AudioManager).mode = AudioManager.MODE_IN_COMMUNICATION
        Log.d("MainActivity", "onCreate")
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                0
            )
        } else {
            Log.d("MainActivity", "Permission granted")
        }
        enableEdgeToEdge()
        setContent {
            VoicebotclientandroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column {
                        Spacer(modifier = Modifier.height(innerPadding.calculateTopPadding()))
                        AppNavigation()
                    }
                }
            }
        }
    }

    // R1 button (KEYCODE_PHICOMM_OK = 275) -> emit an event for the ViewModel (wake / interrupt / stop).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == 275 && event.action == KeyEvent.ACTION_DOWN) {
            ButtonEvents.flow.tryEmit(Unit)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/** Event bus from the R1 hardware button to the ViewModel. */
object ButtonEvents {
    val flow = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 8)
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val activity = LocalContext.current as Activity
    val entryPoint = EntryPointAccessors.fromActivity(activity, NavigationEntryPoint::class.java)
    val navigationEvents = entryPoint.getNavigationEvents()


    Log.d("AppNavigation", "navigationEvents: $navigationEvents")

    LaunchedEffect(navController) {
        navigationEvents.collect { route ->
            navController.navigate(route)
        }
    }

    NavHost(navController = navController, startDestination = "chat") {  // start straight at chat (skip the setup form)
        composable("form") { ServerFormScreen() }
        composable("activation") { ActivationScreen() }
        composable("chat") { ChatScreen() }
    }
}

