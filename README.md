# Audio Pool Guard

A client-only **Forge 1.20.1** mod that keeps exactly one behaviour of
[audio_engine_tweaks](https://github.com/mattymatty97/audio_priority): **ignore excess sounds once the client
sound pool is full**, and it additionally repairs a vanilla `SoundEngine` crash in the same code path.

**中文说明** · [English](#english)

---

## 中文说明

### 这个模组做什么

纯客户端，没有配置项、没有命令、没有界面，只做两件事：

1. **声音池满了就忽略多余声音** —— 不再往已经耗尽的 OpenAL 声道池里塞请求；
2. **修掉原版 `SoundEngine` 的一个 NPE** —— 同一段逻辑里那行的空指针不会再崩客户端。

保留 / 去除对照（相对上游 audio_engine_tweaks）：

| 上游功能 | 本模组 |
|---|---|
| 池满 → 忽略多余声音 | **保留**（改为不抛异常，见下） |
| 每类池占用比例限制 `maxPercentPerCategory` | 去除 |
| 分类优先级排序 `categoryClasses` / `sound_comparator` | 去除 |
| 重复声音限制 `maxDuplicatedSoundsByPos/ById` | 去除 |
| 分类音量倍率 `soundVolumes` | 去除 |
| `instantCategories` 白名单 | 去除 |
| 配置界面（多个 Screen / Widget） | 去除 |
| ModMenu 集成、配置文件 | 去除 |

### 为什么不像上游那样抛异常

上游在「池满」这个点上抛 `SoundPoolException`，靠异常让整批待播声音一起跳过。那个异常只在它自己重写的
tick 队列里被捕获，而 `SoundEngine.play` 还有另一条调用路径：

```
ClientLevel.playLocalSound → SoundManager.play → SoundEngine.play
```

这条路上没人接异常，它会直接冒到渲染线程把客户端崩掉。

本模组保留「忽略」的语义，但不抛异常：一旦观察到池满，后续声道申请会被**直接短路**成一个「已完成且结果为
`null`」的 future —— 这与原版「申请不到声道」完全等价，原版自己就会忽略该声音；池满期间每 100 ms 才真探
一次池，于是「池越满、每秒敲池子的次数越多」的雪崩被掐断。

### 第二道保护：修掉原版的一个 NPE

原版 `SoundEngine.tickNonPaused` 会对 `instanceToChannel` 里的每个声音执行：

```java
int life = this.soundDeleteTime.get(sound).intValue();   // 该声音在生命周期表里没有条目就 NPE
```

原版自己是成对写这两张表的（同一段代码里先 `soundDeleteTime.put` 再 `instanceToChannel.put`；回收时成对
remove），所以单线程不会出现这种状态；一旦出现（例如音效由别的线程开始播放，与 tick 循环的回收交错），
客户端就会在渲染线程崩掉。

本模组在 `tickNonPaused` 入口把缺失的条目按原版默认时长（20 tick）补回去，让原版走它自己的正常回收流程 ——
效果就是「把多余的声音丢掉」，而不是崩溃。

### 实现

两个 mixin 注入点，都在客户端：

1. `ChannelAccessMixin` —— 注入 `ChannelAccess.createHandle` 的 RETURN，把返回的 future 交给
   `SoundPoolGuard` 判定池状态并决定是否短路（对应上游 `SoundSystemMixin#onAcquireSourceManager`）。
2. `SoundEngineMixin` —— 注入 `SoundEngine.tickNonPaused` 的 HEAD，补齐缺失的生命周期条目。

日志前缀统一是 `[audio-pool-guard]`，节流 5 秒：池满 / 恢复 / 修复条目数。

### 安装

1. 把 `audio-pool-guard-<version>.jar` 放进客户端 `mods/` 目录；
2. 如果同时装着上游那个 Fabric 版（经 Sinytra Connector 等加载），**删掉它**，两者功能重叠；
3. 它是纯客户端模组，服务器不需要装（也不会因为服务器没装而报红叉）。

### 构建

需要 **JDK 17**：

```bash
./gradlew build          # Linux / macOS
gradlew.bat build        # Windows
```

产物在 `build/libs/audio-pool-guard-<version>.jar`（`reobfJar` 之后的成品）。

无外网环境下构建时，可先运行 `tools/offline-build-setup.ps1 -GradleHome <你的 Gradle 用户目录>`
把本机 Gradle 构件缓存摆成本地 Maven 仓库，然后用 `--offline -x downloadMcpConfig` 构建。该脚本与
`build.gradle` 里的 `file://` 仓库都是**可选**的：目录不存在时会被自动跳过，正常联网构建不受影响。

### 关于 mixin 不接 refmap

注入目标直接写成 **SRG 运行时成员名**（例如 `m_120128_`、`f_120230_`），不生成 refmap、也不接 Mixin 注解
处理器。1.20.1 的 Forge 运行时是「类名 = official、成员名 = SRG」，所以生产环境会正常命中。

代价：在 mojmap 的开发环境（`runClient`）里这两个注入不会命中。mixin 配置因此写成
`"required": false` + `"defaultRequire": 0` —— 开发环境只是功能不生效，不会崩；生产环境正常生效。

### 只要第一道保护？

删掉 `SoundEngineMixin.java`，并从 `audio_pool_guard.mixins.json` 的 `client` 列表里移除
`"SoundEngineMixin"`，重新构建即可。

### 许可与出处

- 上游：[audio_engine_tweaks](https://github.com/mattymatty97/audio_priority)（作者 mattymatty，**LGPL-3.0**）。
  本移植的池满逻辑直接取自它的 `SoundSystemMixin`。
- 本模组同样以 **LGPL-3.0** 发布，许可全文见 [`LICENSE`](LICENSE)。
- 作为 LGPL-3.0 作品的衍生，分发 jar 时需要能同时提供本仓库的源码。

---

## English

### What it does

Client-only, no config, no commands, no GUI. Two things:

1. **Ignores excess sounds once the client sound pool is full** — it stops hammering an exhausted OpenAL
   source pool with requests.
2. **Repairs a vanilla `SoundEngine` NPE** in the same code path, so that line can no longer crash the client.

Kept vs. removed, relative to upstream audio_engine_tweaks:

| Upstream feature | This mod |
|---|---|
| Full pool → ignore excess sounds | **kept** (without throwing, see below) |
| Per-category pool usage limit `maxPercentPerCategory` | removed |
| Category priority sorting `categoryClasses` / `sound_comparator` | removed |
| Duplicate limits `maxDuplicatedSoundsByPos/ById` | removed |
| Per-category volume multipliers `soundVolumes` | removed |
| `instantCategories` whitelist | removed |
| Configuration screens (all Screen/Widget classes) | removed |
| ModMenu integration and config file | removed |

### Why it does not throw like upstream

Upstream throws `SoundPoolException` at the "pool is full" point so its own rewritten tick queue can skip the
whole batch of pending sounds. That exception is only caught there, but `SoundEngine.play` has a second call
path:

```
ClientLevel.playLocalSound → SoundManager.play → SoundEngine.play
```

Nothing catches it there, so it reaches the render thread and crashes the client.

This mod keeps the "ignore" semantics without throwing. Once the pool has been observed full, further channel
requests are **short-circuited** into an already-completed future holding `null` — exactly equivalent to
vanilla failing to acquire a channel, which vanilla handles by ignoring that sound. While the pool is full the
real pool is only probed once every 100 ms, which breaks the feedback loop where a fuller pool means more
acquisition attempts per second.

### Second guard: a vanilla NPE

Vanilla `SoundEngine.tickNonPaused` runs this for every sound in `instanceToChannel`:

```java
int life = this.soundDeleteTime.get(sound).intValue();   // NPE if the sound has no lifetime entry
```

Vanilla always writes the two maps as a pair (in `play()`: `soundDeleteTime.put` immediately followed by
`instanceToChannel.put`; on expiry both entries are removed in the same iteration), so a single-threaded
client cannot reach that state. When it does happen (for example a sound started from another thread
interleaving with the tick loop's cleanup), the client crashes on the render thread.

The guard restores the missing entry with vanilla's default lifetime (20 ticks) at the start of
`tickNonPaused`, so vanilla runs its normal bookkeeping — the extra sound gets dropped instead of crashing.

### Implementation

Two mixin injections, both client-only:

1. `ChannelAccessMixin` — injects at the RETURN of `ChannelAccess.createHandle` and hands the returned future
   to `SoundPoolGuard`, which tracks the pool state and decides whether to short-circuit (the counterpart of
   upstream `SoundSystemMixin#onAcquireSourceManager`).
2. `SoundEngineMixin` — injects at the HEAD of `SoundEngine.tickNonPaused` and restores missing lifetime
   entries.

Log lines are prefixed with `[audio-pool-guard]` and throttled to one message per 5 s: pool full / recovered /
entries restored.

### Installation

1. Drop `audio-pool-guard-<version>.jar` into the client `mods/` folder.
2. If the upstream Fabric build is installed too (e.g. through Sinytra Connector), **remove it** — the two
   overlap.
3. Client-only: nothing needs to be installed on a server, and no red X is shown when the server lacks it.

### Building

Requires **JDK 17**:

```bash
./gradlew build          # Linux / macOS
gradlew.bat build        # Windows
```

The artifact is `build/libs/audio-pool-guard-<version>.jar` (the `reobfJar` output).

For builds without internet access, run `tools/offline-build-setup.ps1 -GradleHome <your gradle user home>`
first: it lays the local Gradle artifact cache out as a Maven repository, then build with
`--offline -x downloadMcpConfig`. Both it and the `file://` repositories in `build.gradle` are optional —
missing directories are skipped and a normal network build is unaffected.

### About the mixins (SRG names, no refmap)

Injection targets are written as **SRG runtime member names** (e.g. `m_120128_`, `f_120230_`) and no refmap or
Mixin annotation processor is used. The Forge 1.20.1 runtime uses official class names with SRG member names,
so the injections resolve in production.

The trade-off: in a mojmap development environment (`runClient`) these two injections do not resolve. The mixin
config therefore uses `"required": false` with `"defaultRequire": 0`: in development the feature is simply
inactive and nothing crashes, while production applies it normally.

### Want only the first guard?

Delete `SoundEngineMixin.java`, remove `"SoundEngineMixin"` from the `client` list in
`audio_pool_guard.mixins.json` and rebuild.

### License and credits

- Upstream: [audio_engine_tweaks](https://github.com/mattymatty97/audio_priority) by mattymatty, **LGPL-3.0**.
  The pool-full logic of this port is taken from its `SoundSystemMixin`.
- This port is released under the same **LGPL-3.0** license; the full text is in [`LICENSE`](LICENSE).
- As a derivative of an LGPL-3.0 work, anyone redistributing the jar must be able to obtain this repository's
  source as well.
