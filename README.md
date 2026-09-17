ttar —— 高性能 tar 打包工具

说在前面：本仓库所有内容均为 AI 生成，包括这个 readme。

面向 Android / Termux / Linux 的未压缩 tar 打包器。
针对 FUSE 存储优化：小文件并行读、大文件流式处理，内存可控。

提供两个版本：

版本 运行环境 入口
命令行版 Termux / Linux / macOS java -jar ttar.jar ...
APK 版 Android 8.0+ 图形界面

两版共享同一份打包引擎（USTAR 手写、PAX 扩展、大文件分流、自动调参）。

---

一、适用场景与限制

适用：

· 短周期、本地打包（同设备或同局域网内传输前的预处理）
· 目录内文件数多、单文件体积中等（KB ~ 数十 MB）
· 一次性的临时归档，不需要长期保留

不适用：

· 长期归档保存
· 单个超大文件（> 1 GB）的极限加速（大文件瓶颈在存储带宽，当前已接近单流上限）
· 跨网络的长距离传输

---

二、特性

· 未压缩 tar：标准 USTAR 格式，兼容 tar、bsdtar、Python tarfile
· 流式写盘：边读边写，内存占用与总数据量无关
· 小文件并行：多线程 readBytes() 掩盖 FUSE 延迟
· 大文件流式：主线程分块读写，不占堆、不抢线程
· 手写 header：绕开 TarInfo，纯字节操作，每文件省 ~50 μs
· PAX 扩展：路径超 255 字节自动启用，中文长路径无忧
· 符号链接：保存链接本身而非跟随目标（typeflag='2'）
· 自动调参（APK 版）：根据目录文件分布和可用堆自动选择参数

---

第一部分：命令行版（Termux / Linux）

三、编译

```bash
cd <源码所在目录>
export TERM=dumb
kotlinc ttar.kt -include-runtime -d ttar.jar
```

· 首次编译约 1~2 分钟。
· 编译成功无输出，ls -l ttar.jar 能看到刚刚更新的时间。
· 每次改完源码都要重新编译。

四、运行

最简形式

```bash
java -jar ttar.jar './某个目录/'
```

会在当前目录生成 某个目录.tar。

推荐形式（固定堆大小）

```bash
java -Xms1g -Xmx1g -jar ttar.jar './某个目录/'
```

完整形式

```bash
java -Xms1g -Xmx1g -jar ttar.jar '<输入...>' ['输出.tar'] [-o '输出.tar'] [-j 线程数] [-w 窗口]
```

参数

参数 说明 默认
<输入...> 要打包的文件或目录，可多个 必填
-o, --output 输出 tar 路径；省略则自动推断 自动推断
-j, --workers 读盘线程数 8
-w, --window 预取窗口大小 workers × 2
-h, --help 显示帮助 —

JVM 参数（放在 -jar 之前）

参数 说明 建议
-Xms1g -Xmx1g 固定堆大小为 1 GB 推荐
-Xms512m -Xmx512m 内存紧张时使用 可选
-Xms2g -Xmx2g 单个超大文件场景 可选，配 -w 4

注意：JVM 参数必须写在 -jar 之前，否则会被当成程序自己的参数。

五、输出路径推断

按优先级从高到低：

1. 给了 -o / --output：使用它。
2. 最后一个参数以 .tar 结尾，且参数多于一个：当作输出路径。
3. 都没有：输出到当前目录，文件名为 <第一个输入的 basename>.tar。

```bash
# 输出 ./dir.tar
java -jar ttar.jar './dir/'

# 输出 ./dir.tar（取第一个输入的名字）
java -jar ttar.jar './dirA/' './dirB/' './file.txt'

# 输出 out.tar
java -jar ttar.jar './dir/' 'out.tar'

# 输出 out.tar（兼容旧写法）
java -jar ttar.jar './dir/' -o 'out.tar'
```

边界情况：单个 .tar 文件会被当作输入。

```bash
java -jar ttar.jar 'a.tar'
```

→ 打包 a.tar 本身，输出 ./a.tar.tar。

想避免双 .tar 后缀，用 -o 显式指定：

```bash
java -jar ttar.jar 'a.tar' -o 'out.tar'
```

六、路径处理注意事项

1. 路径一律用单引号

路径里只要有空格、中文、特殊字符，就必须用单引号包起来。

```bash
java -jar ttar.jar '/路径 里有空格/目录/'
```

引号 行为
单引号 '...' 里面所有字符原样传递，不做任何解释 —— 推荐
双引号 "..." $、 ` 、\、! 等仍会被 Shell 解释

```bash
# 双引号里 $USER 会被展开，路径就错了
java -jar ttar.jar "/data/$USER/目录/"

# 单引号里 $USER 是字面量
java -jar ttar.jar '/data/$USER/目录/'
```

2. 路径本身含单引号

极罕见，用 '\'' 拼接：

```bash
java -jar ttar.jar '/路径/含'"'"'单引号/目录/'
```

3. 用 Tab 补全

输入到一半按 Tab，Shell 会自动补全真实路径。尤其适用于：

· 路径里有空格
· 路径里有特殊字符或非 ASCII 字符
· 不确定字符的编码

4. 查看真实目录名

```bash
ls -b <父目录>
```

-b 会转义空格、零宽字符等。

5. 输出目录要存在

-o 指定的路径，父目录必须已存在。

```bash
mkdir -p '/某个/输出/目录'
java -jar ttar.jar './dir/' -o '/某个/输出/目录/out.tar'
```

七、验证产物

```bash
# 查看 tar 内容
tar -tvf 'out.tar' | head -20

# 对比条目数
tar -tf 'out.tar' | wc -l

# 完整性验证
mkdir -p /tmp/chk
tar -xf 'out.tar' -C /tmp/chk
diff -r './源目录' '/tmp/chk/顶层名'

# 确认符号链接
tar -tvf 'out.tar' | grep '^l'
```

diff -r 静默无输出说明字节级一致。

八、命令行版常见问题

现象 原因 处理
FileNotFoundException 路径没加引号或编码不一致 Tab 补全或 ls -b 确认
jansi ... libc.so.6 not found Termux 缺 jansi 本机库 export TERM=dumb
OutOfMemoryError 堆或窗口过大 降 -Xmx 或 -w
tar 比原数据略大 未压缩格式，有 header 和 padding 正常现象
路径过长 USTAR 上限 255 字节 已自动 PAX

---

第二部分：APK 版（Android）

九、APK 版编译

依赖：

· Termux 中已装 openjdk-17、gradle
· Android SDK 命令行工具（sdkmanager）
· 已装 platforms;android-36、build-tools;36.0.0

编译：

```bash
cd <APK 源码目录>
export TERM=dumb
gradle assembleDebug
```

首次编译 5~15 分钟（下载 AGP、依赖），之后增量编译 30 秒 ~ 1 分钟。

产物：

```
app/build/outputs/apk/debug/app-debug.apk
```

安装：

```bash
termux-open app/build/outputs/apk/debug/app-debug.apk
```

十、APK 版使用

首次使用

1. 打开 App，看到「权限：未授予」
2. 点「授予所有文件访问权限」→ 跳到系统设置
3. 打开「管理所有文件」开关
4. 返回 App，权限状态变为「已授予 ✓」

打包

1. 源目录：点「选择…」用系统文件选择器，或直接在输入框手动输入
2. 输出 tar：点「选择…」指定保存位置和文件名，或手动输入
3. 点「开始打包」
4. 观察进度：
   ```
   扫描中  1200 项    0.3s
   打包中  1523 / 2688  (56%)    1.2s
   ```
5. 完成后显示：
   ```
   完成  4981 项  5.77 GB → 5.77 GB  22.03s
   线程 16  阈值 16MB  块 32MB  窗口 4
   内存：峰值 212 MB / 上限 512 MB
   自动调参：文件 4981  中位 3.3 KB  最大 825.8 MB  大文件 81 (1.6%)  可用堆 491 MB  预算 245 MB
   /storage/emulated/0/.../output.tar
   ```

设置页

点主界面右上角「设置」：

```
返回   设置
─────────────────────────────
线程数                          16
─────────────────────────────
大文件阈值 (MB)                 16
─────────────────────────────
流式块大小 (MB)                 16
─────────────────────────────
窗口倍数                         8
─────────────────────────────
自动调参（忽略上方参数）         [○]
─────────────────────────────
保留符号链接                     [●]
─────────────────────────────
跳过隐藏文件                     [○]
─────────────────────────────
默认源目录
/storage/emulated/0/
─────────────────────────────
默认输出文件名
output.tar
─────────────────────────────
保存                        恢复默认

内存峰值估算  ≈ 2048 MB

关于
版本                          1.0 (1)
堆上限                        512 MB
当前已用                      6 MB
可用                          506 MB
窗口预算                      256 MB (已达上限)
配置文件：
/storage/emulated/0/Android/data/com.example.ttar/ttar.conf
```

配置文件

所有设置存在：

```
/storage/emulated/0/Android/data/com.example.ttar/ttar.conf
```

格式（Properties 文本，可手动编辑，重启 App 生效）：

```properties
workers=16
bigfile_mb=16
chunk_mb=16
window_factor=8
keep_symlinks=true
skip_hidden=false
default_source=/storage/emulated/0/
default_output=output.tar
auto_tune=false
```

注意：Android 11+ 的文件管理器看不到 Android/data/，需要用 MT 管理器或 Termux 读取。

十一、APK 版技术要点

权限

只申请 MANAGE_EXTERNAL_STORAGE（所有文件访问权限）。

· 通过 Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION 引导用户开启
· 用户随时可撤销，App 每次启动检查
· 不上架 Google Play：此权限仅文件管理器类应用可获批

存储访问

· SAF 选择器：用 OpenDocumentTree / CreateDocument 让用户选路径
· 真实路径：把 SAF 返回的 URI 解析为 /storage/... 路径填入输入框
· 实际 I/O：用 File API（因为有全文件权限）

两种机制配合：SAF 负责「选」，File 负责「读写」。

Edge-to-edge

Android 15（API 35）强制 edge-to-edge，用 BaseActivity 统一处理：

· setDecorFitsSystemWindows(window, false)
· setOnApplyWindowInsetsListener 给根布局加 padding
· 状态栏图标颜色跟随深浅色主题

大堆

AndroidManifest.xml 中 android:largeHeap="true"：

· 普通应用堆上限：256 MB
· 开启后：512 MB（大多数设备）

内存监控

打包完成信息里显示：

```
内存：峰值 212 MB / 上限 512 MB
```

· 峰值：打包期间最高已用堆（每 100ms 采样一次）
· 上限：JVM 堆的硬天花板

独立的计时协程

UI 显示与实际打包完全解耦：

· 打包线程只更新 @Volatile 变量
· 一个独立协程每 100ms 读变量、读系统时钟、刷新界面
· 使用 SystemClock.elapsedRealtime()（单调时钟，用户调时间不影响）

即使 UI 卡顿，计时也不会暂停。

---

第三部分：核心引擎（两版共享）

十二、性能特点

两种物理瓶颈

文件类型 瓶颈 最优策略
小文件（< 1 MB） 系统调用延迟 并行掩盖
大文件（> 8 MB） 存储带宽 顺序流式

小文件的瓶颈不是带宽，而是 FUSE 每次 open/read/close 约 0.3 ms 的固定开销。多线程并行能让这些延迟相互重叠，相比单线程串行，吞吐可提升 2~4 倍。

大文件顺序读本身就能打满存储带宽，并行只会增加调度开销和内存，没有收益。

实测吞吐

场景 数据量 耗时 吞吐 峰值内存
小文件为主 85 MB / 2564 项 1.99 s 43 MB/s 23 MB
混合 5.77 GB / 4981 项 22.03 s 262 MB/s 212 MB
大文件为主 6.6 GB / 4594 项 23.89 s 294 MB/s <150 MB

具体数值取决于设备存储性能、文件大小分布和系统负载。

十三、内存使用

场景 峰值内存
全部小文件（≤ 8 MB） window × 单文件 + 1 MB 缓冲
全部大文件（> 8 MB） 1 MB ~ 32 MB 读缓冲 + 1 MB 输出缓冲
混合 两者叠加，实测 < 250 MB（512 MB 上限内）

内存分配模型（以 512 MB 堆为例）：

用途 占用
ART 运行时 + 类加载 ~60 MB
线程栈 ~16 MB
输出缓冲 1 MB
大文件 chunkBuf 设置值（最多 32 MB）
窗口预读 剩余 ~400 MB

三层预算保护：

层 预算
设置页显示 min(可用堆 × 60%, 256 MB)
AutoTuner 决策 同上
TarPacker 运行时 min(可用堆 × 70%, 320 MB)（硬边界）

十四、内部架构

14.1 两阶段流程

```
packFast
 ├─ 阶段 1：scan        单线程递归目录，收集所有条目元数据
 └─ 阶段 2：流式打包    工作线程读盘 + 主线程写 tar
     ├─ 预填窗口
     ├─ 循环：取队首 → 写 tar → 补一个新任务到队尾
     └─ 结尾写 1024 字节零块
```

为什么先扫描再打包？

1. 读盘线程只做 readBytes()，不再 stat（省一次系统调用）
2. 提前知道 total，便于显示进度
3. APK 版可以据此做自动调参

14.2 大文件分流

文件类型 处理方式 内存
目录 只写 header 0
符号链接 只写 header + linkname 0
小文件（≤ 阈值） 线程池并行 readBytes() window × 小文件
大文件（> 阈值） 主线程分块流式读 chunkSize

阈值来源：I/O 角度 1 MB 以上并行已无收益，但内存角度需给窗口留余量。8~16 MB 是通用甜点。

14.3 滑动窗口

```
itemsIt ──→ [已提交读盘的任务队列, 最多 win 个] ──→ 主线程写 tar
                    ↑                                    │
                    └──── 从 itemsIt 补新任务 ────────────┘
```

· 工作线程：并行执行 readBytes()
· 主线程：future.get() → 写 header/data → 提交下一项
· 窗口大小：始终维持在 win 附近

读写完全重叠。

14.4 符号链接

先判 symlink，再判 dir/file：

```kotlin
if (Files.isSymbolicLink(nio)) {
    // 保留链接本身，typeflag='2'，linkname 存目标
} else if (child.isDirectory) {
    // 真实目录，递归
} else if (child.isFile) {
    // 真实文件
}
```

File.isDirectory / File.isFile 会跟随符号链接，必须用 java.nio.file.Files.isSymbolicLink。

注意：Android 普通 App 的 FUSE 权限不允许创建符号链接，此功能在 Termux 家目录或 Linux 下有效。

十五、PAX 扩展头

触发条件：

· 路径 UTF-8 字节数 > 100 且无法拆成 prefix(155) + name(100)
· 符号链接目标路径 > 100 字节

中文一个字 3 字节，几层目录叠上去很容易超过 255 字节上限，此时自动启用。

PAX 头是独立条目（512 字节 header + 内容 + padding），遇到才写，不影响其他条目。

tar -tvf 和 Python tarfile 都能正确解析。

---

第四部分：参数调优

十六、APK 版自动调参

打开「自动调参」开关后，扫描完成时根据目录特点自动选择：

决策项 依据
线程数 小文件占比（多 → 高并发）
大文件阈值 堆大小 + 大文件占比
流式块大小 最大文件大小
窗口倍数 可用堆预算 ÷ 阈值

决策表：

目录特点 自动选出
全是小文件（中位 30 KB，最大 5 MB） 16 线程 / 阈值 4 MB / 块 4 MB
混合（中位 500 KB，最大 50 MB） 16 线程 / 阈值 16 MB / 块 16 MB
大文件为主（中位 20 MB，最大 800 MB） 8 线程 / 阈值 16 MB / 块 32 MB
超大文件（最大 3 GB） 8 线程 / 阈值 16 MB / 块 32 MB

完成信息里会显示实际使用的参数和调参依据。

十七、命令行版手动调优

编辑源码顶部：

```kotlin
const val BIGFILE_THRESHOLD = 8L shl 20   // 8 MB
const val CHUNK_SIZE = 1 shl 20           // 1 MB
```

目录特点 建议阈值
几乎全是 100 KB 以下小文件 4 MB
大部分是 1~5 MB 中等文件 8 MB（默认）
大量 20~100 MB 文件 16~32 MB
有 GB 级大文件 32 MB，配 -w 4
内存受限（≤ 512 MB 堆） 4 MB，配 -w 8

线程数与窗口

场景 -j -w
默认 8 16
全是小文件 8~12 16~24
全是大文件 4 4
内存紧张 4 4

存储 I/O 并发有上限，线程多 ≠ 快。

---

第五部分：设计要点与兼容性

十八、设计要点

· 分流不是补丁，是物理约束下的最优解：小文件需要并行掩盖延迟，大文件需要顺序榨干带宽，两者最优策略刚好相反
· 滑动窗口保证读写重叠，同时限制内存峰值
· 手写 USTAR header 避免 TarInfo 的对象分配开销
· PAX 扩展解决路径长度限制，兼容所有主流 tar 实现
· JVM 堆大小不影响速度，只影响 GC 停顿频率
· UI 与引擎解耦：TarPacker 不依赖 Android，可复用到任何 JVM 环境

十九、兼容性

· JVM：OpenJDK 11 及以上
· 编译：Kotlin 1.6 及以上
  · 测试版本：Kotlin 2.4.20 (JRE 21.0.12)
· Android：8.0（API 26）及以上，编译目标 API 36
· 平台：Android / Termux、Linux、macOS（未在 Windows 测试）
· tar 格式：USTAR + PAX 扩展头，兼容 GNU tar、bsdtar、Python tarfile

二十、目录结构

命令行版：

```
项目目录/
├── ttar.kt          # 源码
├── ttar.jar         # 编译产物
└── README.md        # 本文档
```

APK 版：

```
项目目录/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/values/strings.xml
        └── java/com/example/ttar/
            ├── BaseActivity.kt      edge-to-edge 适配
            ├── MainActivity.kt      主界面
            ├── SettingsActivity.kt  设置页
            ├── Options.kt           参数与配置
            ├── AutoTuner.kt         自动调参
            └── TarPacker.kt         打包引擎（核心）
```

二十一、未来计划

· 超大文件优化：当前单个 > 8 GB 文件走流式串行，未做 mmap、并行块读取
· 分卷打包：超过存储容量的输出
· 进度通知：后台打包 + 系统通知

---

二十二、一句话总结

一个面向 FUSE 存储优化的未压缩 tar 打包器，小文件并行 + 大文件流式，混合目录自动分流。命令行版核心 400 行、零第三方依赖；APK 版 9 个文件、512 MB 堆内稳跑，实测 262 MB/s。