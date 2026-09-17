本仓库仅自用，不保证可靠性和稳定性，仅作为技术参考
```markdown
# ttar

面向 Android / Termux / Linux 的未压缩 tar 打包器。小文件并行读，大文件流式写，内存可控。

> 本仓库所有内容均由 AI 生成。

## 特性

- 未压缩 tar（标准 USTAR + PAX，兼容主流 tar）
- 流式写盘，内存占用与总数据量无关
- 小文件多线程并行，大文件主线程流式
- 支持符号链接、超长路径（PAX）、中文文件名
- 核心引擎零第三方依赖

## 命令行版

```bash
# 编译
TERM=dumb kotlinc ttar.kt -include-runtime -d ttar.jar

# 打包
java -Xms1g -Xmx1g -jar ttar.jar './某个目录/'
```

输出到当前目录的 某个目录.tar。

更多参数：

```bash
java -Xms1g -Xmx1g -jar ttar.jar '<输入...>' [-o <输出.tar>] [-j 线程数] [-w 窗口]
```

参数 说明 默认
<输入...> 文件或目录，可多个 必填
-o 输出路径；省略则自动推断 自动
-j 读盘线程数 8
-w 预取窗口 j×2

路径带空格或中文必须用单引号：

```bash
java -jar ttar.jar '/路径 有空格/目录/'
```

APK 版

```bash
cd ttar
TERM=dumb gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

首次使用需授予「所有文件访问权限」。支持文件选择器、参数设置、自动调参。

性能参考

场景 吞吐
小文件为主 ~70 MB/s
大文件为主 ~290 MB/s

具体取决于设备存储和文件分布。

环境要求

· JVM 11+
· Kotlin 1.6+
· APK 版：Android 8.0+，编译需 Android SDK 36

说明

· 大文件（> 8 MB）自动走流式，不占堆
· 小文件（≤ 8 MB）走多线程并行
· 内存不足时自动降级，不会 OOM
· 未压缩格式，适合短期归档和本地传输；长期保存或跨网络请自行压缩

目录

```
.
├── ttar.kt          # 命令行版源码
├── readme.md        # 本文档
└── ttar/            # APK 工程
```
