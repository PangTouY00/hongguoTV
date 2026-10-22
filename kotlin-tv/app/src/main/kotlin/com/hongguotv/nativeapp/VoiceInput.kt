// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent

/** The installed recognition activity owns microphone permission and capture.
 * We only receive text; no app-owned recording or continuous listening.
 */
internal class VoiceInput(private val activity: Activity,private val text: (String)->Unit,private val notice: (String)->Unit) {
    companion object {
        const val REQUEST=7311
        fun result(data: Intent?): String? = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull { it.isNotBlank() && it.none(Char::isISOControl) }?.trim()?.take(80)
    }
    private var pending=false
    fun start() {
        if(pending) return
        val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT,"请说出剧名或关键词")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3)
        // A package-manager query can miss an activity that Android can still launch.
        // Attempt the action itself and use ActivityNotFoundException as the result.
        try {
            pending=true
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent,REQUEST)
        } catch(_: ActivityNotFoundException) { pending=false; notice("系统没有可启动的语音识别页面。麦克风只能录音，还需要语音转文字服务；请使用手机输入，或支持语音的系统键盘。") }
        catch(_: SecurityException) { pending=false; notice("系统不允许启动语音识别，请检查系统语音服务及麦克风权限。") }
    }
    fun handle(request: Int,code: Int,data: Intent?): Boolean {
        if(request!=REQUEST) return false
        pending=false
        if(code==Activity.RESULT_OK) {
            val value=runCatching { result(data) }.getOrNull()
            if(value==null) notice("没有识别到文字，请重新尝试。") else text(value)
        }
        return true
    }
}
