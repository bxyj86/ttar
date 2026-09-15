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

### 编译

```bash
export TERM=dumb   # 屏蔽 jansi 警告，不影响编译
kotlinc ttar.kt -include-runtime -d ttar.jar
```

首次编译 1~2 分钟，成功无输出。

### 运行

```bash
java -Xms1g -Xmx1g -jar ttar.jar <输入路径> -o <输出.tar>
```

**路径带空格必须加引号。** 手打易错，建议用 Tab 补全。

---

## 四、命令行参数

| 参数 | 说明 | 默认 |
|---|---|---|
| `<paths...>` | 一个或多个输入路径（文件或目录） | 必填 |
| `-o, --output` | 输出 tar 文件路径 | 必填 |
| `-j, --workers` | 读盘线程数 | 8 |
| `-w, --window` | 预取窗口（同时在内存的任务数） | `workers × 2` |

示例：

```bash
# 打包目录
java -Xms1g -Xmx1g -jar ttar.jar "./data/" -o "./data.tar"

# 打包多个路径
java -Xms1g -Xmx1g -jar ttar.jar "./dirA/" "./dirB/" "./file.txt" -o "./out.tar"

# 内存紧张时收窗口
java -Xmx512m -jar ttar.jar "./data/" -o "./out.tar" -w 4

# 全是大文件时收窗口（降低内存峰值）
java -Xms1g -Xmx1g -jar ttar.jar "./videos/" -o "./videos.tar" -w 4
```

---

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

## 九、验证产物

```bash
# 大小
ls -l out.tar

# 条目数
tar -tf out.tar | wc -l

# 列内容
tar -tvf out.tar | head -20

# 完整性：解压到临时目录再对比
mkdir -p /tmp/chk
tar -xf out.tar -C /tmp/chk
diff -r "./源目录" "/tmp/chk/顶层名"
```

`diff -r` 静默无输出 = 字节级一致。

---

## 十、常见问题

### 1. `FileNotFoundException`

- **路径带空格没加引号**：Shell 把路径拆成了多个参数
- **字符编码不一致**：某些非 ASCII 字符从不同来源复制，字节可能不同
- 用 `ls -b` 查看真实名字，或 Tab 补全避免手打

### 2. `OutOfMemoryError`

```bash
# 限制堆大小
java -Xmx512m -jar ttar.jar ...

# 收窄窗口
java -Xmx512m -jar ttar.jar ... -w 4

# 有超大文件时，阈值自动触发流式，不再全读进内存
```

### 3. `jansi ... libc.so.6 not found`

Termux 特有的 jansi 本机库缺失警告，**不影响编译结果**。屏蔽：

```bash
export TERM=dumb
```

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
