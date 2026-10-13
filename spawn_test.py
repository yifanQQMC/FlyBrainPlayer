"""阶梯召唤到 2000，验证 MISC 分类是否解除了 mob-cap 限制"""
import sys
import time

sys.path.insert(0, ".")
from rcon import Rcon


def make_counter(r):
    r.cmd("scoreboard objectives add fbpcount dummy")

    def cnt():
        r.cmd("scoreboard players reset #t fbpcount")
        r.cmd("execute as @e[type=flybrainplayer:brain_player] "
              "run scoreboard players add #t fbpcount 1")
        out = r.cmd("scoreboard players get #t fbpcount")
        # "none is set" → 0；否则 "#t has N [fbpcount]"
        if "none is set" in out or "Unknown" in out:
            return 0
        for tok in out.replace(",", "").split():
            if tok.isdigit():
                return int(tok)
        return 0
    return cnt


def main():
    r = Rcon(timeout=600)
    cnt = make_counter(r)

    r.cmd("forceload add -16 -16 16 16")
    r.cmd("fill -50 61 -50 50 61 50 minecraft:stone")
    r.cmd("fill -50 62 -50 50 70 50 minecraft:air")
    r.cmd("gamerule doMobSpawning false")
    r.cmd("gamerule maxEntityCramming 0")
    r.cmd("difficulty peaceful")
    print("OK  stage ready")

    for batch in range(10):
        t0 = time.time()
        base = cnt()
        for i in range(200):
            idx = base + i
            x = -48 + (idx % 14) * 7
            z = -48 + (idx // 14) * 7
            r.cmd(f"summon flybrainplayer:brain_player {x} 61 {z}")
        dt = time.time() - t0
        time.sleep(2)
        n = cnt()
        print(f"  +200 -> total {n:>5}   ({dt:>6.2f}s)")
        if n < base + 150:
            print(f"  !! 只增加 {n - base}，仍在触顶")
            break

    r.close()


if __name__ == "__main__":
    main()
