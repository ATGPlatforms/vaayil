package dev.vaayil.feature.profiles

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The paste field shows a whole config, private key included: the window must be secure (S5). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ImportScreenSecureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the import screen marks the window secure while composed`() {
        // Built outside the composable: lint's ViewModelConstructorInComposable is error-severity here.
        val vm = ImportViewModel { _, _ -> ImportOutcome.Failure(null) }
        compose.setContent {
            MaterialTheme {
                ImportScreen(
                    vm = vm,
                    onImported = {},
                    onOpenManual = {},
                )
            }
        }
        compose.runOnIdle {
            assertTrue((compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0)
        }
    }
}
