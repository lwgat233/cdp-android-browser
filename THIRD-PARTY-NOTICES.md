# 第三方组件与本源（引用）地址

本项目自身以 **Apache-2.0** 发布（见 `LICENSE`）。下面列出**随 APK 一起分发**或运行时使用的第三方组件，
包含各自的**许可**与**本源地址（引用地址）**——这些地址既是许可条款要求的出处声明，也是取源码的地方。

仓库里另外存了被引用最多的那份许可全文：`legal/LGPL-3.0.txt`。
APK 自身也带着许可证文本（`res/raw/license.txt`、`license_gnutls.txt`、`license_nettle.txt`、
`license_gmp.txt`、`license_libiconv.txt`、`license_cpu_features.txt`），装机后可在应用内查看。

## 一、随 APK 分发的原生库（LGPL 系列，动态加载）

| 组件 | 版本 | 许可 | 本源地址（引用地址） | 随包内容 |
|---|---|---|---|---|
| ffmpeg-kit（`com.arthenica:ffmpeg-kit-https`） | 6.0-2 | LGPL-3.0 | <https://github.com/arthenica/ffmpeg-kit> ／ 版本页 <https://github.com/arthenica/ffmpeg-kit/releases/tag/v6.0-2> | `libffmpegkit.so`、`libffmpegkit_abidetect.so` |
| FFmpeg | 6.0（ffmpeg-kit 的 `https` 变体） | LGPL-3.0 | <https://ffmpeg.org/> ／ 源码 <https://git.ffmpeg.org/ffmpeg.git> | `libavcodec.so` `libavdevice.so` `libavfilter.so` `libavformat.so` `libavutil.so` `libswresample.so` `libswscale.so` |
| GnuTLS（ffmpeg-kit 的 https 支持） | 随 6.0-2 | LGPL-2.1 或更新（库本体；附带程序为 GPL-3.0） | <https://www.gnutls.org/> ／ 源码 <https://gitlab.com/gnutls/gnutls> | 编入 `libavformat`/`libffmpegkit` 的网络栈 |
| nettle | 随 6.0-2 | LGPL-3.0 | <https://www.lysator.liu.se/~nisse/nettle/> | 同上（加密原语） |
| GMP | 随 6.0-2 | LGPL-3.0（另有 GPL-2.0 双许可） | <https://gmplib.org/> | 同上（大数运算） |
| libiconv | 随 6.0-2 | LGPL-2.1（`libiconv` 可执行程序为 GPL） | <https://www.gnu.org/software/libiconv/> | 同上（字符集转换） |
| cpu_features（Google） | 随 6.0-2 | Apache-2.0 | <https://github.com/google/cpu_features> | 运行时 CPU 特性探测 |

## 二、随 APK 分发的 Java/Kotlin 依赖

| 组件 | 版本 | 许可 | 本源地址（引用地址） |
|---|---|---|---|
| smart-exception-java（`com.arthenica`） | 0.2.1 | BSD-3-Clause | <https://github.com/arthenica/smart-exception> |
| AndroidX WebKit | 1.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/webkit> ／ 源码 <https://android.googlesource.com/platform/frameworks/support/> |
| AndroidX Core | 1.1.0 | Apache-2.0 | 同上（AndroidX 源码树） |
| AndroidX Lifecycle Runtime | 2.0.0 | Apache-2.0 | <https://android.googlesource.com/platform/frameworks/lifecycle/> |
| AndroidX VersionedParcelable | 1.1.0 | Apache-2.0 | 同上（AndroidX 源码树） |
| Kotlin 标准库 | 1.9.24 | Apache-2.0 | <https://github.com/JetBrains/kotlin> |

## 三、运行时使用、但**不随包分发**的系统组件

| 组件 | 许可 | 本源地址（引用地址） |
|---|---|---|
| Android System WebView / Chromium（页面渲染内核，设备自带） | BSD-3-Clause 及若干第三方许可 | <https://chromium.googlesource.com/chromium/src/> |

## LGPL 组件的分发方式（说清楚，免得被要求"给出源码"时说不出来）

- 上表第一组的库以**未修改的共享库（`.so`）**形式随 APK 分发，应用运行时**动态加载**；
  替换这些 `.so` 即可使用修改版库（本仓库为 debug 签名，重打包没有额外门槛）。
- 各组件的许可证全文随 APK 附带（`res/raw/*.txt`），本仓库另存一份 LGPL-3.0 全文于 `legal/LGPL-3.0.txt`。
- 源码获取地址即上表的"本源地址"列；如需对应版本的完整源码快照，按 ffmpeg-kit 的版本页取 `v6.0-2` 源码包。
