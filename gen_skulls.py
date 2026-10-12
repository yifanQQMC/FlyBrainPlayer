"""
生成 8 张头颅皮肤（64x64 Classic/Steve 模型）。

基准是 SkinsMC 下载的真实 "Cyborg Steve Half Face" 皮肤，
其余 7 种在其基础上做**程序化换色 + 局部重绘**，保证风格一致、辨识度清晰。
所有像素严格 RGBA 四通道，写前断言长度正确（MC 会对通道数错误报 missing texture）。
"""
import struct, zlib, math, os

OUT = r"D:\Projects\FlyBrainPlayer\src\main\resources\assets\flybrainplayer\textures\entity"
BASE = os.path.join(OUT, "brain_player.png")


def read_png(path):
    d = open(path, 'rb').read()
    i = 8; idat = b''; w = h = 0
    while i < len(d):
        ln = struct.unpack(">I", d[i:i + 4])[0]
        tag = d[i + 4:i + 8]
        body = d[i + 8:i + 8 + ln]
        if tag == b'IHDR':
            w, h, bd, ct = struct.unpack(">IIBB", body[:10])
            assert bd == 8 and ct == 6, f"期望 8bit RGBA，实际 {bd}/{ct}"
        elif tag == b'IDAT':
            idat += body
        i += 12 + ln
    raw = zlib.decompress(idat)
    stride = w * 4
    rows, prev = [], bytearray(stride)
    p = 0
    for _ in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p + stride]); p += stride
        for x in range(stride):
            a = line[x - 4] if x >= 4 else 0
            b = prev[x]
            c = prev[x - 4] if x >= 4 else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        rows.append([tuple(line[x * 4:x * 4 + 4]) for x in range(w)])
        prev = line
    return w, h, rows


def write_png(path, rows, w, h):
    def ch(t, dd):
        c = t + dd
        return struct.pack(">I", len(dd)) + c + struct.pack(">I", zlib.crc32(c) & 0xffffffff)
    raw = b""
    for y in range(h):
        r = b""
        for px in rows[y]:
            assert len(px) == 4, f"像素不是 RGBA: {px}"
            assert all(0 <= v <= 255 for v in px), f"像素值越界: {px}"
            r += bytes(px)
        raw += b"\x00" + r
    assert len(raw) == h * (1 + w * 4), f"{len(raw)} != {h*(1+w*4)}"
    open(path, 'wb').write(b"\x89PNG\r\n\x1a\n"
                          + ch(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                          + ch(b"IDAT", zlib.compress(raw))
                          + ch(b"IEND", b""))


def recolor(rows, w, h, fn):
    """对整张图按 fn(x,y,rgba)->rgba 重绘"""
    out = [[(0, 0, 0, 0)] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            out[y][x] = fn(x, y, rows[y][x])
    return out


def in_head(x, y):
    """头部区域（含帽子层）：经典 Steve 皮肤布局"""
    return 8 <= x < 16 and 8 <= y < 16


def in_head_overlay(x, y):
    return 40 <= x < 48 and 8 <= y < 16


def tint(c, factor_r, factor_g, factor_b, keep_a=True):
    r, g, b, a = c
    return (min(255, int(r * factor_r)),
            min(255, int(g * factor_g)),
            min(255, int(b * factor_b)), a if keep_a else 0)


def clamp255(v):
    """所有颜色分量统一走这里，避免 int(x * k) + n 冲出 255"""
    return max(0, min(255, int(v)))


def make_variant(name, head_fn, body_fn):
    """基于基准皮肤，替换头部与身体的颜色"""
    w, h, rows = read_png(BASE)

    def fn(x, y, px):
        if in_head(x, y) or in_head_overlay(x, y):
            return head_fn(x, y, px)
        # 身体/手臂/腿
        if y >= 16:
            return body_fn(x, y, px)
        return px

    out = recolor(rows, w, h, fn)
    write_png(os.path.join(OUT, name + ".png"), out, w, h)
    return name


# ===== 各头颅的外观定义 =====
# player 型用原图（真实下载的半机器人皮肤），不重绘


def zombie_head(x, y, px):
    # 僵尸：青灰皮肤 + 深色斑块 + 发光暗红眼
    if px[3] == 0:
        return px
    r, g, b, a = px
    base = (clamp255(r * 0.55 + 30), clamp255(g * 0.85 + 25), clamp255(b * 0.55 + 30), a)
    # 随机斑块（用坐标做伪随机，保证可复现）
    n = (x * 7 + y * 13) % 11
    if n < 3:
        return (clamp255(base[0] * 0.65), clamp255(base[1] * 0.65), clamp255(base[2] * 0.65), a)
    # 眼部区域做暗红发光
    if 10 <= x < 14 and 12 <= y < 14:
        return (140, 20, 20, a)
    return base


def zombie_body(x, y, px):
    if px[3] == 0:
        return px
    return (clamp255(px[0] * 0.5 + 20), clamp255(px[1] * 0.6 + 35), clamp255(px[2] * 0.5 + 25), px[3])


def skeleton_head(x, y, px):
    # 骷髅：纯白骨 + 黑洞眼 + 黑色裂痕
    if px[3] == 0:
        return px
    if (in_head(x, y) and 10 <= x < 14 and 12 <= y < 14) or \
       (in_head(x, y) and 12 <= x < 16 and 12 <= y < 14):
        return (20, 20, 20, 255)
    n = (x * 11 + y * 5) % 17
    v = 215 - n * 6
    return (v, v, max(180, v - 15), 255)


def skeleton_body(x, y, px):
    if px[3] == 0:
        return px
    v = 205
    return (v, v, v - 10, px[3])


def creeper_head(x, y, px):
    # 苦力怕：绿色底 + 黑色像素斑块（标志性）
    if px[3] == 0:
        return px
    n = (x * 3 + y * 17) % 13
    if n < 4:
        return (18, 18, 18, 255)
    # 注意所有分量都要 clamp：直接乘再加常数很容易冲出 255
    r = min(255, clamp255(px[0] * 0.35 + 15))
    g = min(255, clamp255(px[1] * 0.90 + 40))
    b = min(255, clamp255(px[2] * 0.35 + 20))
    return (r, g, b, px[3])


def creeper_body(x, y, px):
    if px[3] == 0:
        return px
    n = (x * 5 + y * 3) % 13
    if n < 4:
        return (18, 18, 18, 255)
    r = min(255, clamp255(px[0] * 0.35 + 15))
    g = min(255, clamp255(px[1] * 0.90 + 40))
    b = min(255, clamp255(px[2] * 0.35 + 20))
    return (r, g, b, px[3])


def enderman_head(x, y, px):
    # 末影人：纯黑 + 紫色眼睛 + 头部高光
    if px[3] == 0:
        return px
    if in_head(x, y) and 10 <= x < 14 and 12 <= y < 14:
        return (190, 40, 240, 255)
    if in_head(x, y) and 12 <= x < 16 and 12 <= y < 14:
        return (190, 40, 240, 255)
    v = 25 + ((x + y) % 3) * 6
    return (v, v, v + 8, 255)


def enderman_body(x, y, px):
    if px[3] == 0:
        return px
    v = 22 + ((x * 2 + y) % 3) * 5
    return (v, v, v + 10, px[3])


def piglin_head(x, y, px):
    # 猪灵：粉褐皮肤 + 金色耳环 + 獠牙区
    if px[3] == 0:
        return px
    r, g, b, a = px
    if in_head(x, y) and (x == 9 or x == 15) and 11 <= y < 14:
        return (235, 195, 60, a)          # 金耳环
    base = (clamp255(r * 0.9 + 40), clamp255(g * 0.6 + 30), clamp255(b * 0.6 + 40), a)
    if in_head(x, y) and 11 <= x < 14 and 14 <= y < 15:
        return (245, 240, 225, a)         # 獠牙
    return base


def piglin_body(x, y, px):
    if px[3] == 0:
        return px
    return (clamp255(px[0] * 0.85 + 35), clamp255(px[1] * 0.55 + 25), clamp255(px[2] * 0.55 + 35), px[3])


def wither_head(x, y, px):
    # 凋灵：近黑 + 骨白肋骨纹
    if px[3] == 0:
        return px
    if in_head(x, y) and (y == 10 or y == 14) and 9 <= x < 15:
        return (200, 200, 195, 255)
    v = 28 + ((x + y) % 2) * 5
    return (v, v, v, 255)


def wither_body(x, y, px):
    if px[3] == 0:
        return px
    if y % 6 == 0:
        return (185, 185, 180, px[3])
    v = 25
    return (v, v, v, px[3])


def slime_head(x, y, px):
    # 史莱姆：半透明青绿 + 内部高光核
    if px[3] == 0:
        return px
    cx, cy = 12, 12
    d = math.hypot(x - cx, y - cy)
    if d < 2.2:
        return (200, 255, 220, 200)       # 核心
    r, g, b, a = px
    return (min(255, int(r * 0.4) + 40), min(255, int(g * 1.0) + 60),
            min(255, int(b * 0.5) + 50), min(a, 200))


def slime_body(x, y, px):
    if px[3] == 0:
        return px
    r, g, b, a = px
    return (min(255, int(r * 0.4) + 35), min(255, int(g * 1.0) + 55),
            min(255, int(b * 0.5) + 45), min(a, 210))


VARIANTS = {
    "zombie": (zombie_head, zombie_body),
    "skeleton": (skeleton_head, skeleton_body),
    "creeper": (creeper_head, creeper_body),
    "enderman": (enderman_head, enderman_body),
    "piglin": (piglin_head, piglin_body),
    "wither": (wither_head, wither_body),
    "slime": (slime_head, slime_body),
}

if __name__ == "__main__":
    # player 型：直接复制基准（真实下载的半机器人皮肤）
    w, h, rows = read_png(BASE)
    write_png(os.path.join(OUT, "player.png"), rows, w, h)
    print("OK player.png  (真实 Cyborg Steve 皮肤)")

    for name, (hf, bf) in VARIANTS.items():
        make_variant(name, hf, bf)
        print(f"OK {name}.png")
    print("\n共生成 8 张头颅皮肤 →", OUT)