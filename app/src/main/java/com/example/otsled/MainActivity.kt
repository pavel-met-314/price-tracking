package com.example.otsled

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.otsled.notification.PriceNotificationManager
import com.example.otsled.ui.AppViewModelFactory
import com.example.otsled.ui.navigation.OtsledNavGraph
import com.example.otsled.ui.theme.OtsledTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as OtsledApplication).container
        val viewModelFactory = AppViewModelFactory(container)
        val startProductId = intent.getLongExtra(PriceNotificationManager.EXTRA_PRODUCT_ID, -1L)
            .takeIf { it > 0L }

        setContent {
            OtsledTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val navController = rememberNavController()
                    OtsledNavGraph(
                        navController = navController,
                        viewModelFactory = viewModelFactory,
                        startProductId = startProductId,
                    )
                }
            }
        }
    }
}
