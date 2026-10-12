# 参与贡献

感谢你对这个项目感兴趣！这个模组的核心是一个果蝇神经环路模拟器，
欢迎各种形式的改进。

## 环境要求

| 项 | 版本 |
|---|---|
| JDK | **21**（NeoForge 21.1 要求） |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.219 |
| Python（可选） | 3.11+，仅用于生成贴图和跑验证脚本 |

## 构建

```bash
./gradlew build
# 产物：build/libs/flybrainplayer-1.0.0.jar
```

Windows 下如果 `gradlew` 失败，用 JDK 21 显式调 wrapper：

```bash
"C:/path/to/jdk21/bin/java" -classpath "gradle\wrapper\gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain build
```

## 代码结构

```
brain/      大脑核心
  FlyBrain.java    ★ 连接组：PN → KC → MBON + Hebbian 学习 + 头颅调制
  Pretrain.java    ★ 基础预训练（侧角当教师，感知机 + SGD）
  Sensors.java     24 路感知通道
  BrainPlayerEntity.java   AI 实体（每 4 tick 决策一次）

learn/      联网学习
  KnowledgeBase.java   知识库 + 事实抽取
  WikiClient.java      minecraft.wiki 异步客户端

profile/    头颅
  SkullKind.java      ★ 8 种头颅：技能 + 性格 + 皮肤

comm/       交流
  Dialogue.java       对话引擎
  ChatHandler.java    聊天监听 + /brain 命令
```

## 改代码前请先读这些"坑"

这几条是开发过程中真实踩过的，改相关代码时特别注意：

### 1. 换头颅前必须 `resetInnate()`
性格是**乘性调制**本能通路权重。不重置就反复换头颅会把权重一路乘下去，
所有通路趋近 0，表现为"AI 什么都干不了"。

### 2. 稀疏编码必须保证可分
`PN →(稀疏) KC →(k-WTA)→ MBON` 的网络，每个 PN 至少要有一个**专属 KC**。
纯随机接线时不同 PN 激活会撞到同一组 KC（活动值都是 1.0 无法区分），
导致通道混淆（"AI 会打招呼但不会逃跑"）。
改 `FlyBrain` 的接线逻辑后，请跑 `test_pretrain.py` 确认可分性仍是 24/24。

### 3. HTTP 绝不能阻塞主线程
`WikiClient` 全部走独立线程池 + 异步回调，回调里记得 `server.execute(...)` 切回主线程。

### 4. 事件监听器静默失效
`@SubscribeEvent` 放在 `@Mod` 主类里可能从不触发且无任何报错。
业务事件放独立类（`@EventBusSubscriber`），并在处理方法里无条件打一条日志确认。

### 5. 客户端可见的字段要走 synced entity data
头颅类型要传给渲染器，必须用 `EntityDataAccessor`，
否则客户端永远读到默认值。

## 验证脚本（纯 Python，零依赖）

```bash
python test_pretrain.py    # 预训练收敛 + 8 项基础行为断言
python test_profile.py     # 8 种头颅的行为差异（5 项断言）
```

> 注意：纯 Python 跑 400 轮 SGD 要 20 分钟，Java 只要 0.4 秒。
> 要快速验证算法收敛性请直接跑 `runServer`，看日志里的 `[Pretrain]`。

## 改头颅要同步改这几处

新增一种头颅需要动：

1. `SkullKind.java` 加枚举项（技能、性格四维、贴图名）
2. `gen_skulls.py` 加对应的换色函数，然后重新生成贴图
3. `assets/.../blockstates/profile_skull.json` 扩展 `type` 值
4. `assets/.../models/block/` 加方块模型
5. `assets/.../textures/block/` + `textures/entity/` 加贴图
6. `assets/.../lang/` 加翻译

## 提交规范

- 一个改动一个提交，提交信息用中文写清**为什么改**而不只是改了什么
- 涉及行为的改动，请附上验证脚本的输出或服务端日志片段
- 不要提交 `build/` `runs/` `.gradle/`（已在 `.gitignore`）

## RCON 调试

```bash
# 注意：无玩家时区块不加载，实体不会 tick，必须先 forceload
python rcon.py "forceload add -2 -2 2 2"
python rcon.py "summon flybrainplayer:brain_player 0 61 0"
python rcon.py "execute positioned 0 61 0 run brain brains"

# 密码从环境变量读
FBP_RCON_PASSWORD=你的密码 python rcon.py "brain brains"
```

`/brain brains` 会列出附近所有大脑的指纹并自检唯一性，
`/brain set <头颅>` 可以换头颅。
