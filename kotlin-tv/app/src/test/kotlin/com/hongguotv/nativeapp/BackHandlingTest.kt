package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** 系统接口只要一个方法，测试用自己的接口即可复现同样的调用顺序。 */
private interface FakeBackCallback { fun onBackInvoked() }

class BackHandlingTest {
    private fun callback(onBack: () -> Unit) =
        Proxy.newProxyInstance(FakeBackCallback::class.java.classLoader, arrayOf(FakeBackCallback::class.java), backCallbackHandler(onBack))

    /** 注册时系统会先把回调放进 HashMap 当键：hashCode 返回 null 会直接抛 NullPointerException，
     *  真机上表现为启动即闪退。这里把这条路径钉住。 */
    @Test fun callbackWorksAsAHashMapKey() {
        val callback = callback { }
        val registry = java.util.HashMap<Any, String>()
        registry.put(callback, "back")
        assertTrue(registry.containsKey(callback))
        assertEquals("back", registry[callback])
        assertEquals(callback.hashCode(), callback.hashCode())
    }

    @Test fun callbackAnswersEqualsAndToStringLikeAPlainObject() {
        val callback = callback { }
        assertEquals(callback, callback)
        assertNotEquals(callback, Any())
        assertNotEquals(callback, callback { })
        assertEquals("HongguoTVBackCallback", callback.toString())
    }

    @Test fun onlyOnBackInvokedReachesTheCaller() {
        var invoked = 0
        val callback = callback { invoked++ } as FakeBackCallback
        callback.onBackInvoked()
        callback.onBackInvoked()
        assertEquals(2, invoked)
    }
}
