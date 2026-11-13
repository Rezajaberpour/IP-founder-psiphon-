package com.example

import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.preferences.AppLanguage
import com.example.ui.IpFounderApp
import com.example.ui.ScannerViewModel
import com.example.ui.theme.MlmVpnTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        val app = application as MlmVpnApplication

        setContent {
            val viewModel: ScannerViewModel = viewModel(
                factory = ScannerViewModel.provideFactory(app, app.repository)
            )
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val baseContext = LocalContext.current
            val baseConfig = LocalConfiguration.current

            val effectiveLocale = remember(settings.appLanguage, baseConfig) {
                when (settings.appLanguage) {
                    AppLanguage.SYSTEM -> baseConfig.locales[0] ?: Locale.getDefault()
                    AppLanguage.ENGLISH -> Locale.ENGLISH
                    AppLanguage.PERSIAN -> Locale.forLanguageTag("fa")
                }
            }

            val localizedConfig = remember(baseConfig, effectiveLocale) {
                Configuration(baseConfig).apply {
                    setLocale(effectiveLocale)
                    setLayoutDirection(effectiveLocale)
                }
            }

            val localizedContext = remember(baseContext, localizedConfig, settings.appLanguage) {
                if (settings.appLanguage == AppLanguage.SYSTEM) {
                    baseContext
                } else {
                    val configContext = baseContext.createConfigurationContext(localizedConfig)
                    object : ContextWrapper(baseContext) {
                        override fun getResources(): Resources = configContext.resources
                        override fun getAssets(): AssetManager = configContext.assets
                        override fun getTheme(): Resources.Theme = baseContext.theme
                    }
                }
            }

            val isRtl = TextUtils.getLayoutDirectionFromLocale(effectiveLocale) == View.LAYOUT_DIRECTION_RTL
            val isPersian = effectiveLocale.language.equals("fa", ignoreCase = true)

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfig,
                LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalActivityResultRegistryOwner provides this@MainActivity
            ) {
                MlmVpnTheme(darkTheme = true, dynamicColor = false, isPersian = isPersian) {
                    IpFounderApp(viewModel = viewModel)
                }
            }
        }
    }
}
