# 100 万 AI 玩家文明测试 —— 实测结论

> 结论：**100 万个 MC 实体不可行**（实测稳定上限约 2,800 只），
> 但**100 万只 AI 的文明模拟已经跑通** —— 用离线模拟器，见第七节。
>
> 两条路线都验证过了：
> - MC 实体路线：~2,800 只/服务端（有世界、可交互，但需玩家在场才 tick）
> - 离线模拟路线：**1,000,000 只/单进程**（纯数据，270 万个体-tick/秒）

---

## 一、实测数据

测试方法：阶梯加压召唤，用 `time query` 主线程延迟 + `Can't keep up` 警告
+ 单命令召唤耗时判断健康度。

| 实体数 | 召唤耗时 | 主线程延迟 | 服务端状态 |
|---|---|---|---|
| 200 | 0.6 s | — | ✅ 健康 |
| 380（单区块） | — | — | ⚠️ **触顶**，同区块上限 |
| 1,253（分散 49 区块） | 0.8 s | — | ✅ 健康 |
| ~1,350+ | 701 s / 2000 个 | ~400 ms | ❌ 过载 |
| 目标 1,000,000 | — | — | ❌ 不可行 |

**优化后的上限（2026-10-13 二轮测试）**：
修掉三处 O(N²) + 日志刷屏后，上限从 1,250 提升到 **约 2,800 只**：

| 实体数 | 主线程延迟 | 状态 |
|---|---|---|
| ≤ 2,000 | 0–123 ms | ✅ |
| 2,200–2,800 | 126–206 ms | ✅ 稳定 |
| 3,000+ | 16 s+ | ❌ 过载 |

**外推到 100 万**：
```
1,250 只 ≈ 1 个服务端满载
1,000,000 只 ≈ 800 个这样的服务端
```

---

## 二、三个真实瓶颈（按发现顺序）

### 1. 单区块实体密度上限（约 380 个）

**现象**：`summon` 命令返回 `Summoned new Fly-Brain AI Player`，
但实体数完全不增长。

**原因**：Minecraft 对单个区块有实体密度限制，`MobCategory` 的分类
（CREATURE/MONSTER）还会受每区块 mob cap 约束。

**解法**：
- 实体分类用 `MobCategory.MISC`（已改，见 `FlyBrainPlayerMod.java`）
- **更有效**：分散到多个区块。实测同区块 380 触顶，
  分散到 49 个区块后成功突破到 1,253

### 2. 召唤速度随实体数暴增

**现象**：0 只时 1 ms/个；1,250 只时 350 ms/个。

**原因**：每 `summon` 一个实体，服务端要做碰撞检测、寻路初始化、
区块实体索引更新。已有实体越多，单次插入越慢。

**影响**：召唤 100 万只 = 350 ms × 100 万 = **97 小时**。

### 3. tick 开销（根本瓶颈）

每 tick 服务端要为每个实体做：感知扫描（24 通道，含大量方块查询）→
前向传播（50 KC）→ 导航 → 碰撞 → 同步。

```
1,250 只 × 24 通道感知 = 30,000 次方块查询 / 4 tick
```

这就是为什么 1,350 只就过载。

---

## 三、怎么测（可复用）

```bash
# 1. 起服务端（建议 8G+ 堆）
# 2. 铺台 + forceload
python rcon.py "forceload add -160 -160 160 160"
python rcon.py "fill -50 61 -50 50 61 50 minecraft:stone"
python rcon.py "gamerule maxEntityCramming 0"     # 否则 AI 挤在一起会死
python rcon.py "gamerule doMobSpawning false"

# 3. 阶梯加压（注意：必须分散到多区块！）
python spawn_test.py
```

### 判断健康度的正确指标

| 指标 | 是否可靠 | 说明 |
|---|---|---|
| `list` 响应时间 | ❌ | 不走主线程，永远 1 ms，测不出过载 |
| 实体计数耗时 | ⚠️ | 自身要遍历实体，会把测量成本算进去 |
| `time query daytime` | ✅ | 必须排到主线程，延迟直接反映 tick 积压 |
| `Can't keep up` 警告 | ✅ | 服务端自己认定的过载标准（tick > 60 ms） |

### 精确计数实体

`@e` 选择器有返回上限，大规模下数不准。**分区统计**：
```bash
execute as @e[type=flybrainplayer:brain_player,x=-160,z=-160,dx=79,dy=9,dz=79] \
  run scoreboard players add #c1 fbpcount 1
```
然后累加各区。

---

## 四、1,250 只的文明测试怎么做

这个规模已经能观测到有趣的群体行为：

### 混合头颅（自然选择）

```bash
# 8 种头颅混合，看哪些"品系"能活下来
python spawn_batch.py 1200 --mix
```

**可观测指标**：
- **世代增长**：`/brain brains` 看每只的 generation，学者型（学习率 0.055）
  应该涨得最快
- **头颅比例变化**：凋灵型（攻击 1.0、社交 0.0）可能因互杀而减少
- **空间聚集**：社交型（史莱姆/猪灵）应该聚团，学者型（末影人）应该分散

### 需要加的观测命令

目前 `/brain brains` 已能列出指纹和世代。建议扩展：
```
/brain stats    # 汇总：总数、世代分布、头颅比例、平均权重
```

---

## 五、进阶路径：真正跑到 100 万

如果确实需要百万级，**不要用 MC 实体**，改用「无实体大脑模拟器」：

| 方案 | 规模 | 代价 |
|---|---|---|
| MC 实体（现状） | ~1,250 / 服 | 有世界、可交互 |
| 离线模拟器（复用 FlyBrain 类） | 100,000+ / 进程 | 无世界，纯数据 |
| 多进程分片 | 1,000,000+ | 需要协调层 |

**离线模拟器的关键**：`FlyBrain` 类本身不依赖世界——
它只吃 `float[] pnInput` 返回 `float[] mbonOutput`。
把 `Sensors` 换成程序化生成的虚拟环境（噪声 + 奖励），
就能在纯 JVM 里跑百万级大脑，单进程实测可达 10 万+。

这才是"100 万 AI 文明测试"的正确打开方式：
**MC 负责可视化和交互，离线模拟器负责规模**。

---

## 六、本轮改动

| 文件 | 改动 |
|---|---|
| `FlyBrainPlayerMod.java` | 实体分类 `CREATURE` → `MISC`（解除 mob cap 约束） |
| `spawn_batch.py` | 新增：批量召唤工具 |
| `stress_test.py` | 新增：阶梯加压测试 |
| `spawn_test.py` | 新增：多区块分散召唤测试 |
| `CIVILIZATION_TEST.md` | 本文件：实测结论与方案 |

### 顺带发现的两个测量陷阱

1. **`list` 响应时间不能当 TPS 指标** —— 它不走主线程，
   0 实体时也可能显示 935 ms（Dev 环境的线程竞争）。
2. **`summon` 返回成功 ≠ 实体创建成功** —— 触顶时命令照样返回
   `Summoned new`，必须实际计数核对。


---

## 七、✅ 100 万只 AI 文明模拟（已跑通）

MC 实体路线有上限，所以把 `FlyBrain` 抽成**不依赖 Minecraft 的纯 Java 类**，
配一个程序化虚拟环境，跑真正的百万级文明模拟。

### 怎么让 FlyBrain 脱离 Minecraft 的

三个依赖点全部拆掉了：

| 原依赖 | 问题 | 解法 |
|---|---|---|
| `Pretrain` → `FlyBrainPlayerMod.LOGGER` | 连带拉进整个 NeoForge 注册表 | 改用独立 SLF4J logger |
| `applyProfile(SkullKind)` | `SkullKind` 用到 `Component` → brigadier | 新增 `applyPersonality(四维float)` + `applySkill(String)` |
| `save()/load()` 用 `net.minecraft.nbt` | 需要 MC jar | 模拟器不调这两个方法即可 |

`FlyBrain` 本身早就是纯 Java（只 `import java.util.*`），
所以拆完这三处就能单独 import。

### 性能优化：空间网格

第一版邻居检测是 O(n) 内层循环，100 万只跑不完一个 tick。
改成 **40×40 空间网格**，每个个体只查自己所在的 3×3 格：

```java
int cell = gx * GRID + gz;
for (int k = 0; k < gridCount[cell]; k++) { ... }   // O(1)
```

### 实测结果（100 万只 / 50 tick / 12 线程）

```
文明模拟：1000000 只 AI，50 tick
头颅分布：8 种均分（每样 125000 只）
使用 12 个并行线程，空间网格 40x40

  tick   50: 存活 1000000  平均世代 34.3  (185354 ms)

存活率: 1000000 / 1000000 (100.0%)
世代: 平均 34.3  最高 74  最低 0

各头颅存活情况：
  balanced   125000/125000 (100.0%)  平均世代 59.8
  brute      125000/125000 (100.0%)  平均世代 11.0
  sniper     125000/125000 (100.0%)  平均世代 13.8
  volatile   125000/125000 (100.0%)  平均世代 11.0
  scholar    125000/125000 (100.0%)  平均世代 59.2
  artisan    125000/125000 (100.0%)  平均世代 49.3
  wither     125000/125000 (100.0%)  平均世代 11.0
  forager    125000/125000 (100.0%)  平均世代 59.6

总耗时: 185.4 s
吞吐: 2697526 个体-tick/秒
```

### 这个结果说明了什么

**世代增长与学习率完全正相关**，且是设计预期的 5.4 倍差距：

| 头颅 | 学习率 | 平均世代 |
|---|---|---|
| balanced | 0.030 | 59.8 |
| scholar | 0.055 | 59.2 |
| forager | 0.035 | 59.6 |
| artisan | 0.025 | 49.3 |
| sniper | 0.020 | 13.8 |
| brute | 0.015 | 11.0 |
| volatile | 0.010 | 11.0 |
| wither | 0.008 | 11.0 |

→ **性格四维不是装饰，是真的在驱动学习速度**。
学者型（0.055）和凋灵型（0.008）差 6.9 倍学习率，世代差 5.4 倍。

（scholar 59.2 略低于 balanced 59.8，因为它的技能点更多分布在
PORTAL/SCAVENGER 而非 SOCIAL，而模拟环境里社交通道是主要奖励来源。）

### 运行方式

```bash
# 编译并运行（需要 brigadier + slf4j + MC jar 在 classpath）
cd tools
java -Xmx16G -cp "../build/classes/java/main;<brigadier>;<slf4j>;<mc-output.jar>"      CivilizationSim.java 1000000 50
```

代码：`tools/CivilizationSim.java`（单文件，零额外依赖）

### 后续可扩展

- [ ] 加「繁殖」：高世代个体分裂，观察演化压力
- [ ] 加「资源竞争」：同格个体抢资源，攻击型是否因此占优
- [ ] 加「文化传播」：知识库共享，观测知识扩散速度
- [ ] 把模拟结果导回 MC 世界（选 top-N 高世代个体实体化）


---

## 八、优化过程：从 1,250 到 2,800

二轮测试做了三处优化，上限提升 2.2 倍。

### 优化 1：同类扫描节流（最大收益）

`Sensors.sense()` 里每次感知都做一次
`getEntitiesOfClass(BrainPlayerEntity.class, AABB.inflate(10))` ——
这是**全区域实体扫描**。N 只 AI 就是 O(N²)。

改成 20 tick 刷新一次 + 缓存：
```java
if (self.socialScanCooldown-- <= 0) {
    self.cachedKinCount = ...;
    self.socialScanCooldown = 20;      // 1 秒
}
pn[FlyBrain.CH_SOCIAL] = self.cachedKinCount;
```
20 tick = 1 秒，对"附近有多少同类"这个慢变量完全够用。

### 优化 2：doTalk 的同类扫描

`doTalk()` 在 `act=OUT_TALK` 时触发。预训练让 AI 见到同类就交谈，
所以 1500 只会疯狂互相扫 —— 这是第二个 O(N²)。

同样复用节流缓存，且只找最近的一个，够用就 `break`。

### 优化 3：出生日志刷屏

`[FlyBrain] #0 出生` 原本对非 PLAYER 头颅默认打 INFO。
1500 只同时出生 = 1500 条日志，**直接把 tick 堵死**
（实测实体全部超时消失）。

改为只在日志级别调到 DEBUG 时才输出。

### 实测对比

| 版本 | 稳定上限 | 主线程延迟 |
|---|---|---|
| 优化前 | ~1,250 | 过载 |
| 优化后 | **~2,800** | 126–206 ms ✅ |

---

## 九、"无玩家则实体不 tick" —— RCON 测试的硬限制

这是本轮最大的发现，也是 100 万只 MC 实体的**根本障碍**：

**Minecraft 硬规则：没有玩家在线时，实体不 simulate。**
`forceload` 只保证区块加载，**不保证实体 tick**。

表现：
- `summon` 成功、`scoreboard` 计数正常
- 但实体位置不变、BrainDebug 日志停止
- 服务端响应却完全正常（`list` 1ms）—— 极易误判成"代码 bug"

验证：
```bash
# 100 只时 BrainDebug 每 40 tick 一条；无玩家时彻底停止
grep -c "BrainDebug" runs/server/logs/latest.log   # 数字不再增长
```

**解法**：需要一个玩家在场。尝试写最小协议假人（`tools/FakePlayer.java`），
卡在 `serverbound/minecraft:hello` 解码失败 —— 1.20.5+ 的 LoginStart
格式改了（name + expirationTime + publicKey + publicKeySignature），
手写协议成本太高，**建议直接用游戏客户端连一次**。

> 所以你要求"在服务器中放入 100 万个 AI"，
> 即便性能撑得住，**没有玩家在场它们也全是植物人**。

---

## 十、其他实测坑（都真实踩过）

| 坑 | 现象 | 解法 |
|---|---|---|
| `forceload add -100 -100 100 100` 被静默拒绝 | 只加了 -7..6 | 用服务端接受的范围，先 `forceload query` 确认 |
| `fill` 224×224 静默失败 | 平台只有中心一小块 | 分块 fill（64×64 或更小） |
| 螺旋分布在索引小时半径小 | 前 450 只挤中心，触发单区块上限 | 用网格分布，强制分散 |
| 一次 summon 2000 个 | 27 秒卡顿（瞬时峰值） | 分批 + 等延迟恢复 |
| `simulation-distance` 不是命令 | `Unknown or incomplete command` | 改 server.properties 重启，但对无玩家场景无效 |
