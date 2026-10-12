"""
独立验证头颅的本能权重差异（纯 Python 复刻 FlyBrain.applyProfile 的逻辑）。

目的：在不起游戏的情况下证明"不同头颅 → 不同行为倾向"真的成立。
跑法：python test_profile.py
"""
# 复制 FlyBrain 的出厂本能权重（pnToMbonInnate 的非零项）
CH_THREAT, CH_FOOD, CH_GAP, CH_PAIN, CH_MASTER = 2, 1, 7, 9, 3
CH_NOISE, CH_TOUCH, CH_NOVELTY, CH_CURIOSITY = 13, 19, 21, 20
CH_SOCIAL_TALK, CH_BOREDOM, CH_ITEM, CH_HEIGHT = 22, 23, 8, 17
CH_FALLING, CH_REWARD, CH_SOCIAL, CH_FOLLOW = 12, 10, 15, 14

OUT_ATTACK, OUT_FORWARD = 6, 0
OUT_BACK, OUT_JUMP, OUT_STOP = 3, 4, 5
OUT_GREET, OUT_DIG, OUT_WANDER, OUT_DANCE = 8, 9, 10, 11
OUT_STUDY, OUT_TALK = 12, 13

OUT_COUNT = 14

BASE = {
    (CH_THREAT, OUT_ATTACK): 1.2, (CH_THREAT, OUT_BACK): 0.6,
    (CH_FOOD, OUT_FORWARD): 1.5,
    (CH_HUNGER := 0, OUT_FORWARD): 0.5,
    (CH_GAP, OUT_JUMP): 0.8,
    (CH_PAIN, OUT_BACK): 0.7,
    (CH_MASTER, OUT_GREET): 0.5,
    (CH_TOUCH, OUT_JUMP): 1.2, (CH_TOUCH, OUT_FORWARD): 0.8,
    (CH_REWARD, OUT_DANCE): 0.4,
    (CH_NOISE, OUT_WANDER): 0.5, (CH_NOISE, OUT_FORWARD): 0.7,
    (CH_NOVELTY, OUT_STUDY): 1.3,
    (CH_CURIOSITY, OUT_STUDY): 0.9,
    (CH_SOCIAL_TALK, OUT_TALK): 1.2, (CH_SOCIAL_TALK, OUT_GREET): 0.8,
}

# 头颅定义：(学习率, 好奇, 攻击, 社交, 技能列表)
SKULLS = {
    "balanced": (0.030, 0.6, 0.3, 0.7, ["CURIOUS", "SOCIAL"]),
    "brute":   (0.015, 0.15, 0.9, 0.2, ["BRAWL", "MINING"]),
    "sniper":  (0.020, 0.3, 0.6, 0.1, ["MARKSMAN", "FLEE"]),
    "volatile": (0.010, 0.1, 1.0, 0.1, ["ASSAULT", "EXPLOSIVE"]),
    "scholar": (0.055, 1.0, 0.2, 0.3, ["CURIOUS", "PORTAL", "SCAVENGER"]),
    "artisan": (0.025, 0.5, 0.4, 0.9, ["MINING", "BUILDER", "SOCIAL"]),
    "wither":  (0.008, 0.05, 1.0, 0.0, ["ASSAULT", "DESTRUCTION"]),
    "forager": (0.035, 0.7, 0.1, 1.0, ["SCAVENGER", "CURIOUS", "SOCIAL"]),
}

SKILL_EFFECTS = {
    "BRAWL":      [(CH_THREAT, OUT_ATTACK, 1.6), (CH_PAIN, OUT_ATTACK, 0.8)],
    "MARKSMAN":   [(CH_THREAT, OUT_ATTACK, 0.9), (CH_PAIN, OUT_BACK, 1.4)],
    "FLEE":       [(CH_THREAT, OUT_BACK, 2.0), (CH_TOUCH, OUT_BACK, 1.2)],
    "MINING":     [(CH_ITEM, OUT_DIG, 1.8), (CH_HEIGHT, OUT_DIG, 0.8)],
    "BUILDER":    [(CH_TOUCH, OUT_DIG, 1.4)],
    "SCAVENGER":  [(CH_FOOD, OUT_FORWARD, 2.0)],
    "CURIOUS":    [(CH_CURIOSITY, OUT_STUDY, 1.5)],
    "SOCIAL":     [(CH_MASTER, OUT_GREET, 1.6), (CH_SOCIAL, OUT_TALK, 1.2)],
    "ASSAULT":    [(CH_THREAT, OUT_ATTACK, 2.5)],
    "EXPLOSIVE":  [(CH_THREAT, OUT_ATTACK, 3.0), (CH_REWARD, OUT_DANCE, 1.0)],
    "DESTRUCTION": [(CH_THREAT, OUT_ATTACK, 3.5), (CH_TOUCH, OUT_DIG, 1.6)],
    "PORTAL":     [(CH_GAP, OUT_JUMP, 2.0)],
}


def apply(tag):
    lr, cur, aggr, soc, skills = SKULLS[tag]
    w = {k: v for k, v in BASE.items()}

    def boost(pn, mb, f):
        w[(pn, mb)] = w.get((pn, mb), 0.0) * max(0.05, f)

    boost(CH_THREAT, OUT_ATTACK, 0.3 + aggr * 2.2)
    boost(CH_THREAT, OUT_BACK, 0.3 + (1 - aggr) * 1.0)
    boost(CH_NOVELTY, OUT_STUDY, 0.2 + cur * 1.8)
    boost(CH_CURIOSITY, OUT_STUDY, 0.2 + cur * 1.2)
    boost(CH_SOCIAL_TALK, OUT_TALK, 0.2 + soc * 1.6)
    boost(CH_SOCIAL_TALK, OUT_GREET, 0.2 + soc * 1.0)
    for s in skills:
        for pn, mb, f in SKILL_EFFECTS[s]:
            boost(pn, mb, f)
    return w, lr


# MBON 通道名
MB_NAME = {0: "前进", 1: "左转", 2: "右转", 3: "后退", 4: "跳跃", 5: "停止",
           6: "攻击", 7: "疾跑", 8: "招呼", 9: "挖掘", 10: "游走", 11: "旋转",
           12: "学习", 13: "交谈"}

print("=== 不同头颅 → 不同行为倾向 ===\n")
hdr = f"{'头颅':<10}{'学习率':>7}{'攻击性':>8}{'好奇性':>8}{'社交性':>8}  →  最强倾向"
print(hdr)
print("-" * 76)

rows = []
for tag, (lr, cur, aggr, soc, skills) in SKULLS.items():
    w, lrr = apply(tag)
    # 模拟"场上同时有威胁/食物/同类"的情形，看谁赢得最多
    scen = {(CH_THREAT, OUT_ATTACK): 1.0, (CH_FOOD, OUT_FORWARD): 1.0,
            (CH_SOCIAL_TALK, OUT_TALK): 1.0, (CH_NOVELTY, OUT_STUDY): 0.8,
            (CH_PAIN, OUT_ATTACK): 0.5}
    scores = [0.0] * OUT_COUNT
    for (pn, mb), base in w.items():
        for (spn, smb), act in scen.items():
            if spn == pn:
                scores[mb] += base * act
    top = max(range(OUT_COUNT), key=lambda i: scores[i])
    rows.append((tag, lrr, aggr, cur, soc, MB_NAME[top], scores[top], scores[6], scores[12], scores[13]))

for tag, lrr, aggr, cur, soc, top, s, sa, ss, st in rows:
    print(f"{tag:<10}{lrr:>7.3f}{aggr:>8.2f}{cur:>8.2f}{soc:>8.2f}  →  {top}({max(s,sa,ss,st):.2f})")

print("\n=== 关键对比 ===")
d = {r[0]: r for r in rows}
print(f"凋灵   攻击性 {d['wither'][2]:.2f} → 攻击得分 {d['wither'][7]:.2f}（全场最高）")
print(f"末影人 学习率 {d['scholar'][1]:.3f}（全场最高）→ 学习倾向 {d['scholar'][8]:.2f}")
print(f"史莱姆 社交性 {d['forager'][3]:.2f} → 交谈得分 {d['forager'][9]:.2f}（全场最高）")
print(f"僵尸   学习率 {d['brute'][1]:.3f} vs 凋灵 {d['wither'][1]:.3f} → 学习率差异生效")

# rows 结构: (tag, lr, aggr, cur, soc, topName, sAll, sAttack, sStudy, sTalk)
checks = [
    ("凋灵型攻击性最高", d['wither'][7] == max(r[7] for r in rows)),
    ("末影人型学习率最高", d['scholar'][1] == max(r[1] for r in rows)),
    ("史莱姆型社交最高", d['forager'][9] == max(r[9] for r in rows)),
    ("凋灵型社交几乎为零", d['wither'][9] < 0.5),
    ("8 种头颅行为不完全相同", len({r[5] for r in rows}) >= 2),
]
print()
for name, ok in checks:
    print(f"{'✓' if ok else '✗'} {name}")
print(f"\n共 {sum(1 for _,ok in checks if ok)}/{len(checks)} 项通过")