package com.opendroid.ai.core.llm

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class OnDeviceMemoryCheckTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun model(id: String) = OnDeviceModelRegistry.allModels.first { it.id == id }

    private fun phone(totalMem: Long, availMem: Long = 4L shl 30) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(activityManager).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            this.totalMem = totalMem
            this.availMem = availMem
            threshold = 216L shl 20
        })
    }

    @Test
    fun `an 8 GB phone may run Gemma 4 E4B`() {
        phone(totalMem = 7_824_412L * 1024) // what a real 8 GB phone reports

        OnDeviceModelRegistry.checkDeviceMemoryCompatibility(context, model("gemma-4-e4b-it-litert"))
    }

    @Test
    fun `a 6 GB phone may run Gemma 4 E2B but not Gemma 4 E4B`() {
        phone(totalMem = 5_600L shl 20)

        OnDeviceModelRegistry.checkDeviceMemoryCompatibility(context, model("gemma-4-e2b-it-litert"))
        val error = assertThrows(IllegalStateException::class.java) {
            OnDeviceModelRegistry.checkDeviceMemoryCompatibility(context, model("gemma-4-e4b-it-litert"))
        }
        assertTrue(error.message, error.message!!.contains("8 GB"))
    }

    @Test
    fun `a 12 GB phone may run Gemma 3n E4B but an 8 GB phone may not`() {
        phone(totalMem = 11_200L shl 20)
        OnDeviceModelRegistry.checkDeviceMemoryCompatibility(context, model("gemma-3n-e4b-it-litert"))

        phone(totalMem = 7_824_412L * 1024)
        assertThrows(IllegalStateException::class.java) {
            OnDeviceModelRegistry.checkDeviceMemoryCompatibility(context, model("gemma-3n-e4b-it-litert"))
        }
    }

    @Test
    fun `a 4 GB phone may run Qwen but a 3 GB phone may not`() {
        phone(totalMem = 3_500L shl 20)
        OnDeviceModelRegistry.checkDeviceMemoryCompatibility(context, model("qwen-2.5-0.5b-it-litert"))

        phone(totalMem = 2_800L shl 20)
        assertThrows(IllegalStateException::class.java) {
            OnDeviceModelRegistry.checkDeviceMemoryCompatibility(context, model("qwen-2.5-0.5b-it-litert"))
        }
    }

    @Test
    fun `low free memory refuses a model that is not loaded yet`() {
        phone(totalMem = 7_824_412L * 1024, availMem = 1L shl 30)

        assertThrows(IllegalStateException::class.java) {
            OnDeviceModelRegistry.checkDeviceMemoryCompatibility(context, model("gemma-4-e4b-it-litert"))
        }
    }

    @Test
    fun `low free memory does not refuse the model the engine already holds`() {
        // The loaded model's own pages count against availMem, so they must not be asked for twice.
        phone(totalMem = 7_824_412L * 1024, availMem = 1L shl 30)

        OnDeviceModelRegistry.checkDeviceMemoryCompatibility(
            context,
            model("gemma-4-e4b-it-litert"),
            alreadyLoaded = true
        )
    }
}
