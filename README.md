# Fly Brain Player — 果蝇脑 AI 玩家

用 **红石块（果蝇大脑）+ 雕刻南瓜（视觉扫描仪）** 组装出一个由果蝇神经环路驱动的 AI 玩家。
Minecraft 1.21.1 · NeoForge 21.1.219

<p align="center">
  <img src="skull_compare.png" alt="8 种头颅" width="100%">
  <br>
  <em>8 种头颅 = 8 套技能树 + 8 种性格 + 8 张独立皮肤</em>
</p>

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen.svg)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.219-orange.svg)](https://neoforged.net/)
[![Java](https://img.shields.io/badge/Java-21-blue.svg)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Build](https://img.shields.io/badge/build-passing-success.svg)](build.gradle)

---

## 目录

- [一、这是什么](#一这是什么)
- [二、玩法](#二玩法)
- [三、大脑独立与基础预训练](#三每只-ai-的大脑是独立的而且都经过预训练)
- [四、代码结构](#四代码结构)
- [五、构建](#五构建)
- [六、技术要点](#六技术要点)
- [七、自动化验证](#七自动化验证)

---

## 一、这是什么

灵感来自 Janelia Research Campus 2024 年发布的**成年果蝇全脑连接组**
（FlyWire / Dorkenwald et al., *Nature* 2024，约 14 万神经元、5×10^7 突触）。

本模组把它的结构骨架搬进 Minecraft：

| 果蝇神经结构 | 本模组对应 |
|---|---|
| 嗅觉受体 ORN → 投影神经元 PN | `Sensors` 的 24 个输入通道 |
| 嗅小球 → Kenyon 细胞（蘑菇体） | 50 个 KC，k-WTA(3) 稀疏编码 |
| MBON（蘑菇体输出神经元） | 14 个动作输出通道 |
| 侧角 Lateral Horn（先天通路） | 威胁→攻击、障碍→转向，不可学习 |
| PAM/PPL1 多巴胺神经元 | 奖励信号 + 资格迹 Hebbian 学习 |
| 夜间睡眠记忆巩固 | 每 400 tick「睡一次」重放经验 |

**没有一行硬编码的行为树**——所有动作都是 MBON 通道竞争的结果。

---

## 亮点

| | |
|---|---|
| 🧠 **会学习的大脑** | 多巴胺 Hebbian 学习，突触权重写进 NBT，关服重开还记得 |
| 🎓 **出生即会做事** | 基础预训练把侧角先天通路当教师，见食物走、见怪打、遇坑跳 |
| 🌐 **自己查资料** | 上 minecraft.wiki 学词条，学到「钻石在 y=-59..-16」后真的会往下挖 |
| 🎭 **8 种头颅** | 玩家/僵尸/骷髅/苦力怕/末影人/猪灵/凋灵/史莱姆，各有技能树 + 性格 + 皮肤 |
| 💬 **能聊天** | 问它「AI 钻石在哪挖」，它会回答；不同头颅语气还不一样 |
| 🫂 **个体独立** | 每只 AI 的大脑权重互不相同（`brainFingerprint()` 可验证） |
| 🔬 **百万级模拟** | 离线跑 100 万只 AI，世代分布验证性格真实生效 |
| 🔬 **百万级模拟** | 离线跑 100 万只 AI，世代分布验证性格真实生效 |

---

## 二、玩法

### 组装 AI 玩家

```
最简：   红石块(大脑)  +  雕刻南瓜(扫描仪)
          ↓ 自下而上叠放（南瓜在上）
          ↓ 右键南瓜 → 生成均衡型 AI

带头颅： 红石块(大脑)  →  头颅  →  雕刻南瓜(扫描仪)
          ↓ 右键南瓜 → 生成对应头颅类型的 AI
```

头颅也可以单独给已存在的 AI 换装：**右键 AI 头顶上的头颅方块**即可。

### 头颅 = 技能 + 性格 + 皮肤

8 种头颅对应不同的果蝇品系，各有不同的先天倾向：

| 头颅 | 性格 | 技能 | 学习率 | 特点 |
|---|---|---|---|---|
| 玩家头颅 | 均衡 | 好奇·社交 | 0.030 | 什么都会一点，最全面 |
| 僵尸头颅 | 暴力 | 近战·挖矿 | 0.015 | 见怪就打，但不爱学 |
| 骷髅头颅 | 谨慎 | 远程·逃跑 | 0.020 | 保持距离，一受惊就跑 |
| 苦力怕头颅 | 暴躁 | 强攻·自爆 | 0.010 | 见怪就炸，不长记性 |
| **末影人头颅** | 学者 | 好奇·传送·拾荒 | **0.055** | 学得最快，什么都想知道 |
| 猪灵头颅 | 工匠 | 挖矿·建造·社交 | 0.025 | 挖矿盖房一把好手 |
| 凋灵头颅 | 毁灭 | 强攻·破坏 | 0.008 | 纯破坏机器，几乎不学 |
| 史莱姆头颅 | 友善 | 拾荒·好奇·社交 | 0.035 | 见什么捡什么，人缘最好 |

性格四维（学习率/好奇/攻击/社交）会**乘性调制本能通路权重**，
所以不是"换了个名字"，而是真的换了一套行为倾向。实测数据见 `test_profile.py`：

```
凋灵   攻击倾向 26.25（全场最高）    末影人 学习率 0.055（全场最高）
僵尸   攻击倾向  4.38                史莱姆 社交倾向 2.16（全场最高）
```

### 联网学习

AI 会主动上 **minecraft.wiki** 查资料，两段式抓取：
先取导语（回答"是什么"），再补正文（**高度区间在这段**，决定它挖哪）。

```
/brain ask Diamond_Ore     # 同步查一个词条
/brain list                # 看知识库
AI Diamond_Ore 在哪挖       # 在聊天栏直接问它
```

知识库**全服共享**并写进存档（`world/flybrain_knowledge.dat`），
关服重开还在，新生成的 AI 也继承。查到的高度会直接改变挖掘决策：

```
[Wiki] 学到新知识: Diamond Ore → diamondore:drops        ← 导语
[Wiki] 补充正文后 fact 升级: Diamond Ore → diamondore:y=-63..16   ← 正文
```

### 交流

#### 指挥 AI（聊天框直接喊名字）

每只 AI 都有一个**随机英文名**（全服不重名），头顶显示 `§bALEX §r末影人#12`。
在聊天框直接喊名字下指令：

```
ALEX，去探索一下前面      → 朝你面朝的方向探路
STEVE，过来              → 走到你身边
NICK，停下               → 站住不动
KAI，攻击                → 找附近怪物练练
ZURI，跳一下             → 跳
MAYA，逛逛               → 自由游走 10 秒
LIAM，打招呼             → 按头颅性格向你问好
```

支持中英文、中英文逗号、空格分隔。指令执行时 AI 会回一句
`[ALEX] 收到：EXPLORE，我去前面看看！` 并转头看你。

> 指令**优先于大脑自主决策** —— 被叫到就听话，做完回到自主行为。

#### 实时监控文明

```
/brain watch
```

```
════ 文明实时监控 ════
人口 1194 | 平均世代 106.6 | 最高 112
世代分布 g0:0 1-20:0 21-40:0 41-60:0 61-80:0 80+:1194
社会分工 balanced:160 brute:167 sniper:150 volatile:157
        scholar:134 artisan:155 wither:136 forager:135
知识库 7 条 | 个体掌握 219 条次 | 有知识者 33/1194
传授 1 次 (教学相长) 被教 219 次
活跃度 79/622 (13%)
```

**世代分布**看文明进程，**社会分工**看 8 种头颅比例，
**知识库/个体掌握**看知识积累，**传授/被教**看文化传播。

#### 让文明滚起来

知识不会凭空出现 —— 玩家是火种：

```
/brain ask Diamond        # 你查 wiki，同时教给附近 16 格内所有 AI
```

之后 AI 之间会**互相传授**（6 格内，知识多的教少的），
知识就在种群里扩散。学者型（末影人）学得最快，
凋灵型几乎不学 —— 性格差异直接体现在文明贡献上。


| 方式 | 效果 |
|---|---|
| 聊天栏 `AI <问题>` | 它转身看你并回答 |
| 潜行 + 右键 AI | 打开对话面板（看世代/知识/在学什么） |
| 右键 AI | 认主 |
| `/brain say <话>` | 对所有 AI 广播一句话 |
| `/brain set <头颅>` | 换头颅（`balanced`/`wither`/`scholar`…） |
| `/brain skulls` | 列出全部头颅 |
| `/brain on` · `/brain off` | 开关联网学习 |

不同头颅**说话风格也不同**——凋灵型回"不听。"，末影人型会去查 wiki 再回答。

### AI 玩家会做什么（以及怎么学）

| 行为 | 触发 | 学习 |
|---|---|---|
| 捡掉落物 | 8 格内有掉落物 | 捡到 +0.5 多巴胺 → 强化 |
| 攻击怪物 | 6 格内有敌对生物 | 攻击成功 +奖励 |
| 跟着你跑 | 距离 > 10 格 | 靠近 +奖励 |
| 挥手打招呼 | 靠近主人 | — |
| 挖方块 | 手上有东西 + 面对障碍 | 挖成功 +0.3 |
| **按知识挖矿** | 当前 Y 在已学到的矿物区间 | 优先找对应矿石 |
| **自主学习** | 无聊 / 看到没学过的方块 | 去 wiki 查 → 存知识库 |
| 跳跃躲坑 | 前方检测到 2 格深空洞 | 不可塑（先天） |
| 被你打就跑 | 受伤 | **负奖励** → 下次会躲你 |

**世代（generation）会随奖励次数增长**，权重写进 NBT —— 世界存档里这只 AI 玩家会越玩越聪明。

---

## 三、每只 AI 的大脑是独立的，而且都经过预训练

### 三个层次，共享性不同

| 层次 | 内容 | 共享性 | 类比 |
|---|---|---|---|
| **神经解剖** | `pnToKc` 接线矩阵 | **物种共享**（固定种子） | 基因决定的连接组 = 硬件 |
| **突触强度** | `kcToMbon` 权重 | **个体独立**（模板 + ±6% 扰动） | 个体经历 = 软件 |
| **知识库** | wiki 词条 | 全服共享 | 百科，可种群传递 |

接线必须共享，预训练权重才有意义；权重必须独立，才叫"大脑独立"。

### 预训练是真的在训练

**把侧角先天通路当教师信号** —— 它先天、正确、不可塑（威胁→攻击、食物→前进…），
用它当标准答案去训练可塑的 KC→MBON 权重，让蘑菇体学会**重建**侧角的行为映射。
这对应果蝇幼虫蛹期的预训练。

```
前向: out[mb]    = Σ_kc kcActivity[kc] · w[kc][mb]
教师: target[mb] = Σ_pn pn[p] · innate[p][mb]      ← 侧角先天通路
更新: w[kc][mb]  -= lr · (out[mb] - target[mb]) · kcActivity[kc]
```

320 情景 × 400 轮感知机 + SGD，**服务端实测 410ms，loss 0.1034 → 0.0271（降 74%）**。
静态缓存，全服只算一次。之后每只 AI 复制模板 + 微小扰动。

**所以刚放出来的 AI 就会做事**，不用先"学几十次"：
见食物→走、见怪物→打、遇障碍→转、遇坑→跳、被打→退、有同类→搭话。

验证（`test_pretrain.py`）：8/8 基础行为断言通过 + 24 个 PN 产生 24 种可分激活模式。

### 查看大脑

```
/brain brains
```
```
预训练 loss 0.0271 (耗时 410ms)
神经解剖 物种共享 | 突触强度 个体独立
#2  玩家头颅 | 指纹 86B15EA3 | 世代 2 | 权重 67.03
#2  玩家头颅 | 指纹 AC6DAFC7 | 世代 2 | 权重 67.17
#2  玩家头颅 | 指纹 C309F348 | 世代 2 | 权重 66.88

共 3 只大脑，指纹 3 个唯一 ✓ 全部互不相同
```

`brainFingerprint()` 是突触权重的 FNV-1a 哈希 —— 用来**证明个体独立**：
指纹必然不同，但权重总量都在预训练基线附近（说明都在能力范围内）。

### 一个踩过的严重坑：稀疏编码不可分

最初 24 个 PN 随机共享 50 个 KC，配合 k=3 的 WTA 后，
**不同 PN 激活时会撞到同一组 KC**（活动值都是 1.0 无法区分），
多个 MBON 输出完全相同 —— 表现为"AI 会打招呼但不会逃跑"。

改成 **one-hot 骨架 + 2~3 个随机共享**（每个 PN 有专属 KC）后，
24 个 PN 产生 24 种不同模式，基础能力从 5/6 变成 8/8。

---

## 四、代码结构

```
src/main/java/com/flybrain/
├── FlyBrainPlayerMod.java          主类：注册方块/物品/实体/创造栏/属性
├── BrainPlayerSummonerItem.java    召唤器物品
├── SkullSelectorItem.java          头颅选择器（循环切换 8 种头颅）
├── KnowledgePersistence.java       知识库落盘/读盘
├── brain/
│   ├── FlyBrain.java               ★ 连接组模拟器（PN→KC→MBON + 学习 + 头颅调制）
│   ├── Pretrain.java               ★ 基础预训练（侧角当教师，感知机 + SGD）
│   ├── Sensors.java                24 通道感受器（含好奇心/无聊/社交）
│   └── BrainPlayerEntity.java      AI 玩家实体（每 4 tick 决策一次）
├── learn/
│   ├── KnowledgeBase.java          知识库 + 事实抽取（extractFact）
│   └── WikiClient.java             minecraft.wiki 异步客户端（限流/缓存/超时）
├── profile/
│   └── SkullKind.java              ★ 8 种头颅：技能 + 性格 + 皮肤
├── comm/
│   ├── Dialogue.java               对话引擎（问答/闲聊/AI 互聊）
│   └── ChatHandler.java            聊天监听 + /brain 命令
├── block/
│   ├── RedstoneBrainBlock.java     果蝇脑
│   ├── CarvedPumpkinHeadBlock.java 南瓜头扫描仪
│   └── ProfileSkullBlock.java      头颅方块（组装 + 换装）
└── client/
    ├── BrainPlayerRenderer.java    按头颅切换贴图
    └── ClientSetup.java            客户端注册

gen_skulls.py       8 张头颅皮肤生成器（纯 Python，零依赖）
tools/CivilizationSim.java  百万级 AI 文明模拟器（纯 Java）
tools/CivilizationSim.java  百万级 AI 文明模拟器（纯 Java）
gen_tex.py          召唤器贴图生成器
test_profile.py     头颅行为差异验证（纯 Python，5 项断言）
test_pretrain.py    预训练收敛与基础能力验证（纯 Python，8 项断言）
rcon.py             RCON 客户端（纯标准库）
```

---

## 五、构建

```bash
cd /d/Projects/FlyBrainPlayer && "D:/java21/bin/java" \
  -classpath "D:\\Projects\\FlyBrainPlayer\\gradle\\wrapper\\gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain build --no-daemon
```

产物：`build/libs/flybrainplayer-1.0.0.jar`
丢进 1.21.1 NeoForge 的 `mods/` 即可。

> ⚠️ 本机必须用 `D:/java21`（PATH 上的 java 25 会让 Gradle 8.10 报 class file major version 69）。
> Git Bash 下不能直接 `./gradlew`。

---

## 六、技术要点

- **决策节流**：果蝇神经反应延迟约 20ms，游戏内对应每 4 tick 决策一次。
- **学习规则**：`Δw = lr · dopamine · eligibility · (匹配 +1 / 不匹配 −0.05)`，带突触饱和裁剪。
- **渲染**：复用原版 `ModelLayers.PLAYER` + 自绘 64×64 机器人皮肤，戴南瓜帽、红脑复眼。
- **贴图**：仓库内 `gen_tex.py` 手写 zlib+CRC 生成 PNG，无外部依赖。
- **脱困反射**：实体若被埋在方块里，会向上找最近空位弹出；全堵死则拆掉头顶方块。

### 排障：神经链路诊断日志

AI 玩家"站着不动"这类问题几乎都出在 PN 输入或 MBON 输出上。把日志级别调到
`DEBUG`（日志配置里设 `logger.com.flybrain.level=debug`）后，每 50 次决策会打一行：

```
[BrainDebug] act=10 navDone=false onGnd=true vXZ=0.005 | PN[4=1.00 13=0.55 15=1.00] | OUT[0.34 ... 0.55 ...]
```

- `PN[..]` = 24 个感受器通道的激活值（编号见 `FlyBrain.java` 常量）
- `OUT[..]` = 14 个 MBON 动作通道的输出
- `act=` = 当前胜出的动作编号（0 前进 / 4 跳跃 / 5 停止 / 6 攻击 / 10 游走）
- `vXZ=` = 实际水平速度，恒为 0 说明导航没在驱动

**典型故障对照**：

| 现象 | 根因 |
|---|---|
| `vXZ` 恒为 0 且 `act` 是 4/5 | 某个 PN（如 CH_GAP）恒为 1，压过了前进通道 |
| `navDone=true` 但没走动 | `moveTo` 寻路失败（目标悬空/不可达），返回 false 被忽略 |
| `act` 每次数值完全一样 | PN 输入没变化，或 k-WTA 把所有存活者赋成同一个值 |

---

## 七、自动化验证

项目内置服务端 + RCON 验证流程（比反复开客户端快得多）：

```bash
# 1. 起服务端（runServer，runs/server 已配好 RCON 25578）
# 2. 校验方块/实体注册与 AI 行为
python rcon.py "forceload add -2 -2 2 2"      # ⚠️ 必须先做这步，见下
python rcon.py "function flytest:setup"        # 铺测试场景
python rcon.py "summon flybrainplayer:brain_player 0 60 0"
python rcon.py "execute as @e[type=flybrainplayer:brain_player] run data get entity @s Pos"
sleep 30                                              # 等它自己走
python rcon.py "execute as @e[type=flybrainplayer:brain_player] run data get entity @s Pos"
```

> ⚠️ **`forceload` 必做**：没有玩家在线时，服务器不会主动加载区块，
> 召唤出的实体不会 tick —— 表现是"实体存在但完全不动，日志一行不打"。
> 这是 RCON 自动化验证最容易误判成"代码有 bug"的地方。
> `forceload add -2 -2 2 2` 把出生点附近的区块钉住即可。
>
> ⚠️ **数据包必须带 `pack.mcmeta`**，否则 MC 报
> `Found non-pack entry, ignoring` 直接忽略，铺场函数永远不执行。
>
> ⚠️ `@e` 选择器**只能搜到已加载区块里的实体**，远距离召唤的 bot 要先 `tp` 回来。

### 验证大脑独立性

```bash
python rcon.py "execute positioned 0 61 0 run brain brains"
```

会列出附近每只 AI 的大脑指纹，并自检"指纹是否全部唯一"：

```
预训练 loss 0.0271 (耗时 421ms)
神经解剖 物种共享 | 突触强度 个体独立
#0   玩家头颅 | 指纹 8CC9226E | 世代 0 | 权重 66.92
#0   玩家头颅 | 指纹 79971954 | 世代 0 | 权重 66.80
#0   玩家头颅 | 指纹 CE548EED | 世代 0 | 权重 66.97

共 3 只大脑，指纹 3 个唯一 ✓ 全部互不相同
```
---

## 许可

MIT License，详见 [LICENSE](LICENSE)。

皮肤 `textures/entity/player.png`（以及由它派生的 `brain_player.png`）来自
[SkinsMC](https://skinsmc.org/skin/676300) 的 "Cyborg Steve Half Face"，
属于第三方创作，已在 LICENSE 中单独标注。其余 7 张头颅皮肤由
`gen_skulls.py` 程序化生成。

Minecraft 相关名称与素材版权归 Mojang Studios 所有。本项目为非官方模组，
与 Mojang Studios 无关。

更新记录见 [CHANGELOG.md](CHANGELOG.md)，参与开发见 [CONTRIBUTING.md](CONTRIBUTING.md)。
