-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**
# 随包的软解后端（ijkplayer）：它的 java 层被 libijkplayer.so 按名字回调——postEventFromNative、
# @CalledByNative 的那些方法、IMediaDataSource 的 readAt 都由 native 侧按名字和签名去找。
# 改名或删掉任何一个，表现都是「装载成功、一播放就崩」，所以整包原样保留。
-keep class tv.danmaku.ijk.media.player.** { *; }
# android.media.MediaDataSource 是 API 23 的东西，这条分支的 minSdk 是 19：那个重载不会用到，
# 但压缩器会因为找不到它而报错。
-dontwarn tv.danmaku.ijk.media.player.**
-dontwarn android.media.MediaDataSource
