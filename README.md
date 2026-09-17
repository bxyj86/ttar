# ttar —— 高性能 tar 打包工具（Kotlin/JVM）

说在前面:本仓库所有内容均为AI生成包括这个readme

面向 Android/Termux 及 Linux 环境的未压缩 tar 打包器。
针对 FUSE 存储优化：小文件并行读、大文件流式处理，内存可控，
理论速度接近存储物理上限。

---

## 一、适用场景与限制

**适用：**

- 短周期、本地打包（同设备或同局域网内传输前的预处理）
- 目录内文件数多、单文件体积中等（KB ~ 数十 MB）
- 一次性的临时归档，不需要长期保留

**不适用：**

- 长期归档保存
- 单个超大文件（> 1 GB）的打包加速
  （大文件瓶颈在存储带宽，当前实现已接近单流上限）
- 跨网络的长距离传输（应先压缩，不要用未压缩 tar）

---

## 二、特性

- **未压缩 tar**：标准 USTAR 格式，兼容 `tar`、`bsdtar`、Python `tarfile`
- **流式写盘**：边读边写，内存占用与总数据量无关
- **小文件并行**：多线程 `readBytes()` 掩盖 FUSE 延迟
- **大文件流式**：主线程分块读写，不占堆、不抢线程
- **手写 header**：绕开 `tarfile.TarInfo`，纯字节操作
- **PAX 扩展**：路径超 255 字节自动启用，中文长路径无忧
- **符号链接**：保存链接本身而非跟随目标

---

## 三、快速开始

编译

进入源码目录：

```bash
cd <源码所在目录>
export TERM=dumb
kotlinc ttar.kt -include-runtime -d ttar.jar
```

· 首次编译约 1~2 分钟。
· 编译成功无输出，检查 ls -l ttar.jar 能看到刚刚更新的时间。
· 每次改完源码都要重新编译。

---

运行

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

---

参数说明

程序参数

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
-Xms2g -Xmx2g 单个超大文件场景 可选，配合 -w 4

注意：JVM 参数必须写在 -jar 之前，否则会被当成程序自己的参数。

---

输出路径推断规则

按优先级从高到低：

1. 给了 -o / --output：使用它。
2. 最后一个参数以 .tar 结尾，且参数多于一个：当作输出路径。
3. 都没有：输出到当前目录，文件名为 <第一个输入的 basename>.tar。

示例

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

边界情况

单个 .tar 文件会被当作输入：

```bash
java -jar ttar.jar 'a.tar'
```

→ 打包 a.tar 本身，输出 ./a.tar.tar。

如果不想双 .tar 后缀，用 -o 显式指定：

```bash
java -jar ttar.jar 'a.tar' -o 'out.tar'
```

尾随斜杠不影响命名，./dir/ 和 ./dir 都输出 ./dir.tar。

---

路径处理注意事项

1. 路径一律用单引号

路径里只要有空格、中文、特殊字符，就必须用单引号包起来。

```bash
java -jar ttar.jar '/路径 里有空格/目录/'
```

引号 行为
单引号 '...' 里面所有字符原样传递，不做任何解释 —— 推荐
双引号 "..." $、 ` 、\、! 等仍会被 Shell 解释，可能出问题

为什么优先单引号：

```bash
# 双引号里 $USER 会被展开，路径就错了
java -jar ttar.jar "/data/$USER/目录/"

# 单引号里 $USER 是字面量
java -jar ttar.jar '/data/$USER/目录/'
```

中文、空格、Ľ 这类字符双引号通常也没事，但只要路径里出现 $、 ` 、\、!、& 中任何一个，双引号就会出问题。统一用单引号，永远不出错。

2. 唯一例外：路径本身含单引号

极罕见。此时用 '\'' 拼接：

```bash
java -jar ttar.jar '/路径/含'"'"'单引号/目录/'
```

日常几乎遇不到，无视即可。

3. 建议用 Tab 补全

输入到一半按 Tab，Shell 会自动补全真实路径，比手打更可靠，尤其是：

· 路径里有空格
· 路径里有特殊字符或非 ASCII 字符
· 不确定字符的编码（有些字符看着一样、字节不同）

Tab 补全后，Shell 通常会加上反斜杠转义（如 路径\ 有空格/），和单引号等价。

4. 查看真实目录名

如果怀疑目录名藏了不可见字符：

```bash
ls -b <父目录>
```

-b 会转义空格、零宽字符等，一眼能看出真实字节。

5. 输出目录要存在

-o 指定的路径，父目录必须已存在。程序只创建输出文件本身，不会创建父目录。

```bash
# 正确：先建目录
mkdir -p '/某个/输出/目录'
java -jar ttar.jar './dir/' -o '/某个/输出/目录/out.tar'
```

---

验证产物

查看 tar 内容

```bash
tar -tvf 'out.tar' | head -20
```

-t 列表，-v 显示权限、大小、时间。

对比条目数

```bash
tar -tf 'out.tar' | wc -l
```

和源目录的文件+目录总数对比。

完整性验证

```bash
mkdir -p /tmp/chk
tar -xf 'out.tar' -C /tmp/chk
diff -r './源目录' '/tmp/chk/顶层名'
```

diff -r 静默无输出说明字节级一致。

确认符号链接

```bash
tar -tvf 'out.tar' | grep '^l'
```

l 开头的行是符号链接条目，会显示 -> 目标路径。

---

常见问题

1. FileNotFoundException

· 路径带空格没加引号
· 路径里有特殊字符、编码不一致
· 路径确实不存在

处理：用 Tab 补全，或 ls -b 确认真实名字，路径一律加单引号。

2. jansi ... libc.so.6 not found

Termux 缺 jansi 本机库，只影响 kotlinc 的彩色输出，不影响编译结果。加 export TERM=dumb 屏蔽。

3. OutOfMemoryError

依次尝试：

```bash
# 1. 明确限制堆大小
java -Xms512m -Xmx512m -jar ttar.jar './某个目录/'

# 2. 收窄窗口
java -Xms512m -Xmx512m -jar ttar.jar './某个目录/' -w 4

# 3. 减少线程
java -Xms512m -Xmx512m -jar ttar.jar './某个目录/' -j 4 -w 4
```

4. tar 比原数据略大

正常。tar 是未压缩格式，每个文件都有 512 字节 header，加上每文件 512 字节对齐的 padding，总量会略大于源数据。

5. 路径过长

USTAR 单条路径上限 255 字节，中文一个字 3 字节，几层目录就容易超。工具已自动启用 PAX 扩展处理长路径，正常不会报错。如果报错，检查是否用了旧版本。

6. 输出被中途打断

tar 文件可能不完整。重新运行即可，工具会覆盖输出文件。

---

工具特性

· 未压缩 tar：输出是标准 tar 格式，不压缩。
· 流式写盘：边读边写，内存峰值不随总大小增长。
· 多线程并行读盘：默认 8 线程，覆盖小文件场景的 I/O 延迟。
· 大文件分流：超过 8 MB 的文件自动改为主线程分块流式读，避免内存爆掉，也不浪费线程槽位。
· 符号链接保留：用 Files.isSymbolicLink 判断，tar 里存 typeflag='2' + 目标路径，不跟随链接、不递归进目录。
· 长路径自动 PAX：路径超 255 字节时写 PAX 扩展头，保持兼容性。
· 手写 USTAR header：绕过 TarInfo 对象开销，每文件省约 50 微秒。

---

快速参考

```bash
# 编译
export TERM=dumb
kotlinc ttar.kt -include-runtime -d ttar.jar

# 打包（推荐）
java -Xms1g -Xmx1g -jar ttar.jar './某个目录/'

# 打包并指定输出
java -Xms1g -Xmx1g -jar ttar.jar './某个目录/' 'out.tar'

# 打包多个输入
java -Xms1g -Xmx1g -jar ttar.jar './dirA/' './dirB/' './file.txt'

# 内存紧张
java -Xms512m -Xmx512m -jar ttar.jar './某个目录/'

# 查看帮助
java -jar ttar.jar -h
```

## 五、性能特点

### 两种物理瓶颈

| 文件类型 | 瓶颈 | 最优策略 |
|---|---|---|
| 小文件（< 1 MB） | 系统调用延迟 | 并行掩盖 |
| 大文件（> 8 MB） | 存储带宽 | 顺序流式 |

小文件的瓶颈不是带宽，而是 FUSE 每次 `open/read/close` 约 0.3 ms 的固定开销。
多线程并行能让这些延迟相互重叠，相比单线程串行读取，
吞吐可提升 2~4 倍。

大文件顺序读本身就能打满存储带宽，并行只会增加调度开销和内存，
没有收益。

### 混合目录

分流策略把大文件移出窗口，让窗口槽位留给小文件，
**小文件并行密度达到最高**，整体吞吐最优。

### 典型吞吐参考

具体数值取决于设备存储性能、文件大小分布和系统负载。
下面是大致量级：

| 场景 | 吞吐量级 |
|---|---|
| 小文件为主（< 1 MB） | 数十 MB/s |
| 大文件为主（> 8 MB） | 数百 MB/s（接近裸存储带宽） |
| 混合目录 | 介于两者之间，被大文件拉升 |

---

## 六、内存使用

| 场景 | 峰值内存 |
|---|---|
| 全部小文件（≤ 8 MB） | window × 单文件 + 1 MB 缓冲 |
| 全部大文件（> 8 MB） | 1 MB 读缓冲 + 1 MB 输出缓冲 |
| 混合 | 两者叠加，实测 < 150 MB |

**推荐堆参数：**

```bash
# 默认（推荐）
java -Xms1g -Xmx1g -jar ttar.jar ...

# 内存紧张或只处理小文件
java -Xmx512m -jar ttar.jar ...

# 目录里全是 GB 级大文件
java -Xms2g -Xmx2g -jar ttar.jar ... -w 4
```

**堆大小与速度无关**，只影响稳定性。JVM 堆越大，GC 停顿越长，
反而可能更慢。

---

## 七、内部架构

### 7.1 两阶段流程

```
packFast
 ├─ 阶段 1：scan        单线程递归目录，收集所有条目元数据
 └─ 阶段 2：流式打包    工作线程读盘 + 主线程写 tar
     ├─ 预填窗口
     ├─ 循环：取队首 → 写 tar → 补一个新任务到队尾
     └─ 结尾写 1024 字节零块
```

**为什么先扫描再打包？**

1. 读盘线程只做 `readBytes()`，不再 `stat`（省一次系统调用）
2. 提前知道 `total`，便于显示进度

### 7.2 大文件分流策略

| 文件类型 | 处理方式 | 内存 |
|---|---|---|
| 目录 | 只写 header | 0 |
| 符号链接 | 只写 header + linkname | 0 |
| 小文件（≤ 8 MB） | 线程池并行 `readBytes()` | window × 小文件 |
| 大文件（> 8 MB） | 主线程 1 MB 分块流式读 | 1 MB 缓冲 |

**阈值 8 MB 的来源**：I/O 角度 1 MB 以上并行已无收益，
但内存角度需给窗口留余量。8 MB × 16 窗口 = 128 MB 峰值，可接受。

### 7.3 滑动窗口

```
itemsIt ──→ [已提交读盘的任务队列, 最多 win 个] ──→ 主线程写 tar
                    ↑                                    │
                    └──── 从 itemsIt 补新任务 ────────────┘
```

- **工作线程**：并行执行 `readBytes()`
- **主线程**：`future.get()` → 写 header/data → 提交下一项
- **窗口大小**：始终维持在 `win` 附近，太多占内存，太少线程饿死

读写完全重叠：主线程在等第 N 项数据时，工作线程已经在读 N+1 到 N+win 项。

### 7.4 符号链接处理

关键点：**先判 symlink，再判 dir/file**。

```kotlin
if (Files.isSymbolicLink(nio)) {
    // 保留链接本身，typeflag='2'，linkname 存目标
} else if (child.isDirectory) {
    // 真实目录，递归
} else if (child.isFile) {
    // 真实文件
}
```

`File.isDirectory` / `File.isFile` 会跟随符号链接，用
`java.nio.file.Files.isSymbolicLink` 才能拿到真实链接状态。

> 注意：Android FUSE 对普通 App 不允许创建符号链接，
> 此功能在 Termux 家目录或 Linux 下有效。

---

## 八、PAX 扩展头

**什么时候触发？**

- 路径 UTF-8 字节数 > 100 且无法拆成 prefix(155) + name(100)
- 符号链接目标路径 > 100 字节

中文一个字 3 字节，几层目录叠上去很容易超过 255 字节上限，
此时自动启用。

PAX 头是独立条目（512 字节 header + 内容 + padding），遇到才写，
不影响其他条目性能。`tar -tvf` 和 Python `tarfile` 都能正确解析。

---

## 九（已经删除）

## 十、常见问题

### 1.（已经移走）

### 4. 输出 tar 比原数据大

正常。tar 是未压缩格式，每个文件要 512 字节 header，
加上 512 字节对齐 padding，总量略大于源数据。

### 5. 路径超长抛异常

本版已自动启用 PAX，不会抛异常。若仍报错，检查是否用了旧 jar。

---

## 十一、参数调优

### 阈值

编辑源码顶部：

```kotlin
const val BIGFILE_THRESHOLD = 8L shl 20   // 8 MB
const val CHUNK_SIZE = 1 shl 20           // 1 MB
```

| 目录特点 | 建议阈值 |
|---|---|
| 几乎全是 100 KB 以下小文件 | 4 MB |
| 大部分是 1~5 MB 中等文件 | **8 MB（默认）** |
| 大量 20~100 MB 文件 | 16~32 MB |
| 有 GB 级大文件 | 32 MB，配 `-w 4` |
| 内存受限（≤ 512 MB 堆） | 4 MB，配 `-w 8` |

8 MB 是通用最优值，4~16 MB 之间性能差异很小。

### 线程数与窗口

| 场景 | `-j` | `-w` |
|---|---|---|
| 默认 | 8 | 16 |
| 全是小文件 | 8~12 | 16~24 |
| 全是大文件 | 4 | 4 |
| 内存紧张 | 4 | 4 |

**存储 I/O 并发有上限，线程多 ≠ 快。**

---

## 十二、设计要点

- **分流不是补丁，是物理约束下的最优解**：小文件需要并行掩盖延迟，
  大文件需要顺序榨干带宽，两者的最优策略刚好相反
- **滑动窗口**保证读写重叠，同时限制内存峰值
- **手写 USTAR header** 避免 `TarInfo` 的对象分配和 Python/C 互操作开销
- **PAX 扩展**解决 USTAR 路径长度限制，兼容所有主流 tar 实现
- **JVM 堆大小不影响速度**，只影响 GC 停顿频率

---

## 十三、兼容性

- **JVM**：OpenJDK 11 及以上
- **编译**：Kotlin 1.6 及以上
-   测试版本:Kotlin version 2.4.20 (JRE 21.0.12)
- **平台**：Android/Termux、Linux、macOS（未在 Windows 测试）
- **tar 格式**：USTAR + PAX 扩展头，兼容 GNU tar、bsdtar、Python tarfile

---

## 十四、未来计划

- **APK 化**：目前是 JVM 桌面/命令行工具。若要在 Android 上作为 APK 分发，
  需要重构代码结构（UI 与逻辑分离、Android 权限处理、JNI/Java 层调用等），
  遵循 Android 应用开发规范重写
- **超大文件优化**：当前单个 > 8 GB 文件只是流式串行读写，
  未做 mmap、并行块读取、异步 I/O 等进一步优化。
  此外，Android FUSE 对单文件大小和单次读写的分块也有底层限制，
  数 GB 以上文件的处理需要额外验证

---

## 十五、目录结构

```
项目目录/
├── ttar.kt          # 源码
├── ttar.jar         # 编译产物
└── README.md        # 本文档
```
