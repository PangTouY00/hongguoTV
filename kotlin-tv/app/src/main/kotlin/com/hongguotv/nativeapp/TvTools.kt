// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hongguotv.core.EpisodeTarget
import com.hongguotv.core.PhoneSearchServer
import com.hongguotv.core.LibraryTransferServer
import com.hongguotv.core.BackupData
import com.hongguotv.core.VideoZoom
import java.net.Inet4Address
import java.util.concurrent.Executors

/** Native modal controls kept separate from catalog/player navigation. */
class TvTools(private val activity: Activity) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var dialog: AlertDialog?=null
    val showing: Boolean get()=dialog?.isShowing==true
    private var phone: java.io.Closeable?=null
    private fun dp(value: Int)=(value*activity.resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float=17f)=TextView(activity).apply { text=value; textSize=size; setTextColor(TvStyle.text); setPadding(dp(8),dp(4),dp(8),dp(4)) }
    private fun note(value: String,size: Float=13f)=label(value,size).apply { setTextColor(TvStyle.muted) }
    private fun column()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(4),dp(16),dp(8)) }
    /** onDismissed 在窗口关闭后跑一次，用来收尾那些「边看边试、不确认就撤销」的面板。
     *  它排在 anchor 重新拿回焦点之前：撤销会改设置，界面按钮的文字要在那之后重取。 */
    private fun open(next: AlertDialog,anchor: View?,onDismissed: ()->Unit={}) {
        val previous=dialog; dialog=null; phone?.close(); phone=null; previous?.dismiss(); dialog=next
        next.setOnDismissListener { if(dialog===next) { phone?.close(); phone=null; dialog=null; onDismissed(); if(anchor?.isAttachedToWindow==true) anchor.requestFocus() } }
        next.show()
        TvStyle.fitDialog(next)
    }
    fun close() { dialog?.dismiss(); phone?.close(); phone=null }
    fun destroy() { close(); worker.shutdownNow() }
    fun info(title: String,message: String,anchor: View?) = open(AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("知道了",null).create(),anchor)
    fun confirm(title: String,message: String,anchor: View?,action: ()->Unit) {
        val next=AlertDialog.Builder(activity).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton("确认") { _,_-> action() }.create()
        open(next,anchor); next.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }
    /** emphasis 是这一批行里要重点标出的那些下标：强调色加粗。系统给列表项的焦点态会换底色，
     *  颜色在那一刻不一定还看得出来，所以调用方要在文字上同时区分开，不能只把颜色交给我。
     *  它排在 onExtra 之前：现有调用点用尾随 lambda 传 onExtra，而尾随 lambda 只绑最后一个参数。 */
    fun choose(title: String,labels: List<String>,anchor: View?,onChoice: (Int)->Unit,extra: String?=null,compact: Boolean=false,emphasis: Set<Int> = emptySet(),onExtra: ()->Unit={}) {
        val items=Array<CharSequence>(labels.size) { index ->
            val text=labels.getOrElse(index){""}
            if(index !in emphasis) text
            else SpannableString(text).apply {
                setSpan(StyleSpan(Typeface.BOLD),0,text.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(TvStyle.accent),0,text.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val builder=AlertDialog.Builder(activity).setTitle(title).setItems(items) { _,index-> onChoice(index) }.setNegativeButton("取消",null)
        if(extra!=null) builder.setNeutralButton(extra) { _,_-> onExtra() }
        val next=builder.create(); open(next,anchor)
        // 只改宽度，高度交给 fitDialog：窗口给 WRAP_CONTENT 系统不认（上一版装机实测），留着只会盖掉收缩结果。
        if(compact) next.window?.let { it.setLayout(minOf(dp(620),(activity.resources.displayMetrics.widthPixels*.78f).toInt()),it.attributes.height) }
        next.listView.requestFocus()
    }
    /** 画面模式。焦点落到哪一行，哪一行就立刻作用到画面上；带滑杆的那一行（zoomRow）下面紧跟一条
     *  1.0—2.0 的滑杆：遥控器落在滑杆上时左右键一次正好走一档（max 是 10 格、keyProgressIncrement 设成 1），
     *  落在模式行上时左右键同样调档，触屏设备可以直接拖。调的是此刻正在显示的画面，不是先记一个数字再去验证。
     *  确认保留当前这一项；返回（含 BACK 键）退回打开这个窗口之前的模式与倍数。
     *  上下键显式串好：滑杆只在经过那一行时才被走到，从别的行往下仍然直接到下一条模式。 */
    fun frameModePicker(title: String,labels: List<String>,zoomRow: Int,selected: Int,zoom: Float,anchor: View?,
                       onPreview: (Int,Float)->Unit,onCommit: (Int,Float)->Unit,onRevert: (Int,Float)->Unit) {
        val startTenths=VideoZoom.tenths(zoom); val startMode=selected.coerceIn(0,(labels.size-1).coerceAtLeast(0))
        var tenths=startTenths; var mode=startMode; var committed=false
        lateinit var next: AlertDialog; lateinit var seek: SeekBar
        val rows=mutableListOf<android.widget.TextView>()
        val content=column()
        content.addView(note("上下选模式 · 左右调倍数 · 确认保留 · 返回恢复之前的设置"))
        val value=label(VideoZoom.label(VideoZoom.scale(tenths)),20f).apply { setTextColor(TvStyle.accent); maxLines=1 }
        fun setTenths(target: Int) {
            val clamped=VideoZoom.stepTenths(target,0)
            if(clamped==tenths) return
            tenths=clamped
            value.text=VideoZoom.label(VideoZoom.scale(clamped))
            seek.progress=clamped-VideoZoom.MIN_TENTHS
            rows.getOrNull(zoomRow)?.contentDescription="${labels.getOrElse(zoomRow){""}} · ${VideoZoom.text(VideoZoom.scale(clamped))} 倍"
            onPreview(mode,VideoZoom.scale(clamped))
        }
        labels.forEachIndexed { index,caption ->
            val row=android.widget.TextView(activity).apply {
                id=View.generateViewId(); text=caption; textSize=17f
                isFocusable=true; isClickable=true; maxLines=2
                setPadding(dp(12),dp(10),dp(12),dp(10)); contentDescription=caption
                fun paint(focused: Boolean) {
                    setTextColor(if(index==startMode) TvStyle.accent else TvStyle.text)
                    background=TvStyle.shape(activity,if(index==startMode||focused) TvStyle.raised else TvStyle.surface,if(focused) TvStyle.accent else android.graphics.Color.TRANSPARENT)
                }
                paint(false)
                setOnFocusChangeListener { _,focused-> paint(focused); if(focused) { mode=index; onPreview(index,VideoZoom.scale(tenths)) } }
                setOnClickListener { committed=true; onCommit(index,VideoZoom.scale(tenths)); next.dismiss() }
                setOnKeyListener { _,key,event ->
                    if(index!=zoomRow || event.action!=KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when(key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { setTenths(tenths-VideoZoom.STEP_TENTHS); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { setTenths(tenths+VideoZoom.STEP_TENTHS); true }
                        else -> false
                    }
                }
            }
            content.addView(row,LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,dp(3),0,dp(3)) }); rows+=row
            if(index==zoomRow) {
                seek=SeekBar(activity).apply {
                    id=View.generateViewId(); max=VideoZoom.MAX_TENTHS-VideoZoom.MIN_TENTHS
                    progress=tenths-VideoZoom.MIN_TENTHS; keyProgressIncrement=VideoZoom.STEP_TENTHS
                    isFocusable=true; contentDescription="画面放大倍数"
                    progressTintList=android.content.res.ColorStateList.valueOf(TvStyle.accent)
                    progressBackgroundTintList=android.content.res.ColorStateList.valueOf(TvStyle.raised)
                    thumbTintList=android.content.res.ColorStateList.valueOf(TvStyle.text)
                    setOnFocusChangeListener { _,focused-> thumbTintList=android.content.res.ColorStateList.valueOf(if(focused) TvStyle.accent else TvStyle.text) }
                    setOnKeyListener { _,key,event ->
                        if(event.action!=KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        // 系统把左右键交给我们时自己走一档；系统自己处理时按 keyProgressIncrement 也是恰好一档。
                        // 两条路最后都落到 setTenths，值不会分叉。
                        when(key) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> { setTenths(tenths-VideoZoom.STEP_TENTHS); true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { setTenths(tenths+VideoZoom.STEP_TENTHS); true }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { committed=true; onCommit(zoomRow,VideoZoom.scale(tenths)); next.dismiss(); true }
                            else -> false
                        }
                    }
                    setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar,progress: Int,fromUser: Boolean) { if(fromUser) setTenths(VideoZoom.MIN_TENTHS+progress) }
                        override fun onStartTrackingTouch(bar: SeekBar) {}
                        override fun onStopTrackingTouch(bar: SeekBar) {}
                    })
                }
                // 数值单独占一行，两端刻度贴在滑杆两侧：固定宽度的数值块在 720p 与 1080p 的电视上差一倍的
                // density，挤在滑杆旁边会被截断，让它自己一行就不受屏幕密度影响。
                content.addView(value,LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(12),dp(2),dp(12),0) })
                val bar=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
                bar.addView(note("1.0"),LinearLayout.LayoutParams(-2,-2))
                bar.addView(seek,LinearLayout.LayoutParams(0,dp(46),1f))
                bar.addView(note("2.0"),LinearLayout.LayoutParams(-2,-2))
                content.addView(bar,LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(12),0,dp(12),0) })
                content.addView(note("每档 0.1 倍 · 只作用到「自定义放大」",12f),LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(12),0,dp(12),dp(2)) })
            }
        }
        next=AlertDialog.Builder(activity).setTitle(title).setView(ScrollView(activity).apply { addView(content) }).setNegativeButton("返回并恢复",null).create()
        open(next,anchor,{ if(!committed) onRevert(startMode,VideoZoom.scale(startTenths)) })
        // 只改宽度，高度交给 fitDialog，跟 choose 那几处一样；这里宽度要留够，模式行的说明比单纯的选项长。
        next.window?.let { it.setLayout(minOf(dp(760),(activity.resources.displayMetrics.widthPixels*.88f).toInt()),it.attributes.height) }
        val cancel=next.getButton(AlertDialog.BUTTON_NEGATIVE)
        rows.forEachIndexed { index,row ->
            row.nextFocusUpId=rows.getOrNull(index-1)?.id ?: row.id
            row.nextFocusDownId=if(index==zoomRow) seek.id else rows.getOrNull(index+1)?.id ?: cancel.id
        }
        if(zoomRow in rows.indices) {
            seek.nextFocusUpId=rows[zoomRow].id
            seek.nextFocusDownId=rows.getOrNull(zoomRow+1)?.id ?: cancel.id
        }
        cancel.nextFocusUpId=rows.last().id
        rows.getOrNull(startMode)?.requestFocus()
    }
    fun chooseGrid(title: String,labels: List<String>,selected: Int,anchor: View?,onChoice: (Int)->Unit) {
        val content=column()
        val scroll=android.widget.ScrollView(activity)
        val list=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(list);content.addView(scroll)
        val choices=mutableListOf<android.widget.TextView>()
        lateinit var next: AlertDialog
        labels.chunked(4).forEachIndexed { rowIndex,values ->
            val row=LinearLayout(activity)
            values.forEachIndexed { col,value ->
                val index=rowIndex*4+col
                val choice=android.widget.TextView(activity).apply {
                    id=View.generateViewId();text=value;textSize=15f;gravity=android.view.Gravity.CENTER
                    isFocusable=true;isClickable=true;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                    setPadding(dp(10),dp(10),dp(10),dp(10));contentDescription=value
                    fun paint(focused: Boolean) {
                        setTextColor(if(index==selected) TvStyle.accent else TvStyle.text)
                        background=TvStyle.shape(activity,if(index==selected) TvStyle.raised else TvStyle.surface,if(focused) TvStyle.accent else android.graphics.Color.TRANSPARENT)
                    }
                    paint(false);setOnFocusChangeListener { _,focused->paint(focused) }
                    setOnClickListener { next.dismiss();onChoice(index) }
                }
                row.addView(choice,LinearLayout.LayoutParams(0,dp(46),1f).apply { setMargins(dp(4),dp(4),dp(4),dp(4)) });choices+=choice
            }
            repeat(4-values.size) { row.addView(View(activity),LinearLayout.LayoutParams(0,dp(46),1f)) }
            list.addView(row)
        }
        choices.forEachIndexed { i,v ->
            v.nextFocusLeftId=if(i%4==0) v.id else choices[i-1].id
            v.nextFocusRightId=if(i%4==3 || i==choices.lastIndex) v.id else choices[i+1].id
            v.nextFocusUpId=choices.getOrNull(i-4)?.id ?: v.id
            v.nextFocusDownId=choices.getOrNull(i+4)?.id ?: if(i/4<choices.lastIndex/4) choices.last().id else v.id
        }
        next=AlertDialog.Builder(activity).setTitle(title).setView(content).setNegativeButton("返回",null).create()
        open(next,anchor)
        next.window?.let { it.setLayout((activity.resources.displayMetrics.widthPixels*.85f).toInt(),it.attributes.height) }
        choices.getOrNull(selected.coerceAtLeast(0))?.requestFocus()
    }
    fun episodePicker(count: Int,current: Int,anchor: View?,select: (Int)->Unit) {
        val content=column(); var digits=(current+1).toString(); var replace=true
        val value=label(digits,28f).apply { gravity=Gravity.CENTER }; content.addView(value)
        val error=label("定位到该集，再按确认播放",14f); content.addView(error)
        fun digit(key: String) { digits=if(replace) key else (digits+key).take(6); replace=false; value.text=digits; error.text="定位到该集，再按确认播放" }
        fun erase() { digits=if(replace) "" else digits.dropLast(1); replace=false; value.text=digits.ifEmpty { "—" } }
        val buttons=mutableListOf<Button>()
        listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("清空","0","退格")).forEach { keys ->
            val row=LinearLayout(activity)
            keys.forEach { key ->
                val button=Button(activity).apply {
                    id=View.generateViewId(); text=key; minHeight=dp(42)
                    backgroundTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf()),intArrayOf(TvStyle.accent,TvStyle.raised))
                    setOnClickListener { when(key) { "清空"->{ digits="";replace=false;value.text="—" }; "退格"->erase(); else->digit(key) } }
                }
                buttons+=button
                row.addView(button,LinearLayout.LayoutParams(0,-2,1f))
            }
            content.addView(row)
        }
        val next=AlertDialog.Builder(activity).setTitle("跳转集数 · 1—$count").setView(ScrollView(activity).apply { addView(content) }).setNegativeButton("取消",null).setPositiveButton("定位",null).create()
        next.setOnKeyListener { _,key,event ->
            when {
                key in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { if(event.action==KeyEvent.ACTION_DOWN) digit((key-KeyEvent.KEYCODE_0).toString()); true }
                key==KeyEvent.KEYCODE_DEL -> { if(event.action==KeyEvent.ACTION_DOWN) erase(); true }
                else -> false
            }
        }
        open(next,anchor)
        val locate=next.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancel=next.getButton(AlertDialog.BUTTON_NEGATIVE)
        buttons.forEachIndexed { i,button ->
            button.nextFocusLeftId=buttons[if(i%3==0) i else i-1].id
            button.nextFocusRightId=buttons[if(i%3==2) i else i+1].id
            button.nextFocusUpId=buttons[if(i<3) i else i-3].id
            button.nextFocusDownId=if(i<9) buttons[i+3].id else locate.id
        }
        locate.nextFocusUpId=buttons[10].id; cancel.nextFocusUpId=buttons[9].id
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val target=EpisodeTarget.parse(digits,count)
            if(target==null) error.text="请输入 1—$count 之间的集数"
            else { next.dismiss(); select(target) }
        }
        buttons.first().requestFocus()
    }
    private fun qr(url: String)=ImageView(activity).apply {
        val bits=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,360,360)
        val pixels=IntArray(360*360) { i->if(bits[i%360,i/360]) Color.BLACK else Color.WHITE }
        setImageBitmap(Bitmap.createBitmap(pixels,360,360,Bitmap.Config.ARGB_8888))
        contentDescription="二维码"
    }
    fun phoneInput(anchor: View?,submit: (String)->Unit) {
        val addresses=runCatching { LanInputAddresses.find(activity.getSystemService(ConnectivityManager::class.java)) }.getOrDefault(emptyList())
        if(addresses.isEmpty()) {
            info("手机输入","未找到可用的 Wi-Fi 或网线局域网地址。请检查电视网络；手机和电视需要在可互通的局域网内。",anchor); return
        }
        showPhoneInput(anchor,submit,addresses,0)
    }
    private fun showPhoneInput(anchor: View?,submit: (String)->Unit,addresses: List<LanInputAddress>,index: Int) {
        val selected=addresses[index]
        val content=column(); val status=label("正在开启手机输入…"); content.addView(status)
        val builder=AlertDialog.Builder(activity).setTitle("手机输入剧名").setView(content).setNegativeButton("关闭",null)
            .setPositiveButton("连接帮助",null)
        if(addresses.size>1) builder.setNeutralButton("切换地址",null)
        val next=builder.create(); open(next,anchor)
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val message="请依次检查：\n1. 用手机浏览器打开二维码地址；扫码软件内打不开时，选择在浏览器打开。\n2. 手机不要只用移动数据；手机和电视需处于可互通的局域网。\n3. 访客 Wi-Fi、路由器设备隔离、VPN 可能阻断连接。\n4. 多个网络可尝试切换地址；电脑模拟器的内部地址通常无法被手机访问。\n\n返回时会重新生成二维码，旧地址随窗口关闭失效。"
            val help=AlertDialog.Builder(activity).setTitle("手机连接不上").setMessage(message)
                .setPositiveButton("重新生成二维码") { _,_-> showPhoneInput(anchor,submit,addresses,index) }.setNegativeButton("关闭",null).create()
            open(help,anchor)
        }
        if(addresses.size>1) next.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            choose("选择电视网络地址",addresses.map { "${it.label} · ${it.address}" },anchor,{ chosen -> showPhoneInput(anchor,submit,addresses,chosen) })
        }
        worker.execute {
            try {
                val session=PhoneSearchServer(selected.address,{ query -> main.post { if(dialog===next) { next.dismiss(); submit(query) } } },
                    { main.post { if(dialog===next) status.text="二维码已过期，请关闭后重新打开。" } },
                    onConnected={ main.post { if(dialog===next) status.text="手机已连接，请在手机上输入剧名并发送。" } })
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
                    row.addView(qr(session.url),LinearLayout.LayoutParams(dp(185),dp(185)))
                    val instructions=column()
                    instructions.addView(label("${selected.label} · ${selected.address}\n手机扫码后，用浏览器打开并发送剧名。\n需要同一局域网；二维码有效 5 分钟。",16f))
                    instructions.addView(note(session.url))
                    row.addView(instructions,LinearLayout.LayoutParams(0,-2,1f)); content.addView(row)
                    status.text="等待手机连接…"; content.addView(status)
                }
            } catch(_: Exception) { main.post { if(dialog===next) status.text="此地址无法开启手机输入，请关闭重试，或切换其他网络地址。" } }
        }
    }
    fun libraryTransfer(backup: String,anchor: View?,restore: (BackupData,Boolean)->Unit) {
        val connectivity=activity.getSystemService(ConnectivityManager::class.java)
        val network=connectivity.activeNetwork; val capabilities=connectivity.getNetworkCapabilities(network)
        val address=connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        if(address==null || capabilities==null || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
            info("备份与恢复","请让电视和手机连接同一局域网后重试。",anchor); return
        }
        val content=column(); val status=label("正在生成二维码…"); content.addView(status)
        val next=AlertDialog.Builder(activity).setTitle("手机备份与恢复").setView(ScrollView(activity).apply { addView(content) }).setNegativeButton("关闭",null).create()
        open(next,anchor)
        worker.execute {
            try {
                val session=LibraryTransferServer(address,backup,{ data -> main.post {
                    if(dialog!==next) return@post
                    next.dismiss()
                    val preview=column()
                    preview.addView(label("备份含 ${data.favorites.size} 部收藏、${data.history.size} 条观看记录、${data.searches.size} 条搜索、${data.later.size} 部稍后看、${data.hidden.size} 部已隐藏推荐。\n\n收藏合并，观看位置以较新的记录为准；已有收藏优先保留（最多 500 部），历史保留最近 200 条。",16f))
                    val settings=CheckBox(activity).apply { text="同时恢复播放设置和内容分类"; isChecked=false }; preview.addView(settings)
                    val confirm=AlertDialog.Builder(activity).setTitle("确认恢复本机记录")
                        .setView(ScrollView(activity).apply { addView(preview) }).setNegativeButton("取消",null)
                        .setPositiveButton("合并恢复") { _,_-> restore(data,settings.isChecked) }.create()
                    open(confirm,anchor); confirm.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
                } },{ main.post { if(dialog===next) { content.removeAllViews(); content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
                    row.addView(qr(session.url),LinearLayout.LayoutParams(dp(185),dp(185)))
                    val instructions=column()
                    instructions.addView(label("手机扫码，用浏览器下载备份或选择备份文件恢复。\n仅限同一局域网；关闭即失效，最长 5 分钟。恢复需在电视确认。",16f))
                    instructions.addView(note(session.url)); row.addView(instructions,LinearLayout.LayoutParams(0,-2,1f)); content.addView(row)
                }
            } catch(_: Exception) { main.post { if(dialog===next) status.text="无法开启备份传输，请检查网络后重试。" } }
        }
    }
}
