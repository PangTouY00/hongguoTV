package com.hongguotv.nativeapp

import java.lang.reflect.InvocationHandler

/** Android 13 起系统把返回派发成 OnBackInvokedCallback；注册时系统会把这个回调对象放进 HashMap 当键，
 *  于是 hashCode／equals／toString 必须给出真实值。动态代理对它们一律返回 null 时，拆箱 int 会抛
 *  NullPointerException（Android 13+ 真机上表现为启动即闪退）。这里补齐 Object 的三个方法，
 *  只把 onBackInvoked 接到调用方。低版本没有这套 API，调用方只在 API 33 以上才走到这里。 */
internal fun backCallbackHandler(onBack: () -> Unit): InvocationHandler = InvocationHandler { proxy, method, args ->
    when (method.name) {
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.getOrNull(0)
        "toString" -> "HongguoTVBackCallback"
        "onBackInvoked" -> { onBack(); null }
        else -> null
    }
}
