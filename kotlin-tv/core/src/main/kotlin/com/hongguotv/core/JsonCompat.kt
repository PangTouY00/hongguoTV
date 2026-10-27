// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

/**
 * Android 自带的 org.json 把 keys() 声明成裸类型 Iterator（没有元素类型），
 * 直接 .asSequence() 会让后面每个参数都退化成 Any?。这里把元素类型固定成 String。
 */
internal fun jsonKeys(value: JSONObject): Iterator<String> = object : Iterator<String> {
    private val source = value.keys()
    override fun hasNext(): Boolean = source.hasNext()
    override fun next(): String = source.next() as String
}
