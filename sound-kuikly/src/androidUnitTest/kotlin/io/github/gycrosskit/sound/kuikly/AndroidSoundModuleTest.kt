package io.github.gycrosskit.sound.kuikly

import android.os.Looper
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderModuleExport
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidSoundModuleTest {
    @Test fun actualSdkFactoryAndDestroySuppressQueuedRendererWork() {
        lateinit var factory: () -> IKuiklyRenderModuleExport
        val export = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IKuiklyRenderExport::class.java)) { _, method, args ->
            if (method.name == "moduleExport") {
                assertEquals(SoundModule.NAME, args[0])
                @Suppress("UNCHECKED_CAST")
                factory = args[1] as () -> IKuiklyRenderModuleExport
            }
            null
        } as IKuiklyRenderExport
        export.registerGycSoundModule(1)
        val first = factory() as AndroidSoundModule
        val second = factory() as AndroidSoundModule
        assertNotSame(first, second)
        val replies = mutableListOf<Any?>()
        Thread { first.call("prepare", "bad json", replies::add) }.also { it.start(); it.join() }
        first.onDestroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(replies.isEmpty())
        second.call("prepare", "bad json", replies::add)
        assertEquals(listOf<Any?>("{\"status\":\"failed\"}"), replies)
        second.onDestroy()
    }
}
