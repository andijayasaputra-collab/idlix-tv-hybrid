package com.idlix.tv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.idlix.data.api.ApiClient
import com.idlix.data.config.RemoteConfigManager
import kotlinx.coroutines.launch
import com.idlix.data.local.PlayerAppearanceStore
import com.idlix.data.update.AppUpdateManager
import com.idlix.tv.ui.navigation.AppNavigation
import com.idlix.tv.ui.theme.IdlixTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerAppearanceStore.init(applicationContext)
        // Keep screen on while TV app is running
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Keep the existing launch window while fetching; no home ViewModel or API
        // request is created until remote -> cache -> bundled fallback is resolved.
        lifecycleScope.launch {
            ApiClient.applyRemoteConfig(RemoteConfigManager.initialize(applicationContext))
            setContent {
                IdlixTvTheme {
                    AppNavigation()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppUpdateManager.resumePendingInstallIfAllowed(this)
    }
}
