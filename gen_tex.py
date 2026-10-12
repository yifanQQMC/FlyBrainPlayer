import struct, zlib, math

def write_png(path, pixels, w, h):
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xffffffff)
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    raw = b"".join(b"\x00" + b"".join(bytes(p) for p in row) for row in pixels)
    assert len(raw) == h * (1 + w * 4), f"{len(raw)} vs {h*(1+w*4)}"
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
    open(path, "wb").write(png)

W = H = 64
px = [[(0,0,0,0) for _ in range(W)] for _ in range(H)]
RED=(200,40,40,255); DARK=(120,20,20,255); WHITE=(230,230,230,255)
SKIN=(255,180,80,255); EYE=(30,30,60,255); BLUE=(70,90,180,255)
PUMP=(255,140,0,255); STEM=(90,60,20,255)

def rect(x0,y0,x1,y1,c):
    for y in range(y0,y1):
        for x in range(x0,x1):
            if 0<=x<W and 0<=y<H: px[y][x]=c

rect(8,8,24,24,RED)
rect(8,8,24,12,PUMP)
rect(14,7,18,9,STEM)
rect(9,15,13,19,SKIN); rect(9,16,13,18,EYE)
rect(19,15,23,19,SKIN); rect(19,16,23,18,EYE)
rect(8,22,24,24,DARK)
rect(20,24,36,40,WHITE)
rect(26,24,30,40,BLUE)
rect(24,34,32,36,DARK)
rect(36,24,40,40,WHITE)
rect(16,24,20,40,WHITE)
rect(36,38,40,40,SKIN); rect(16,38,20,40,SKIN)
rect(0,16,16,32,BLUE)
rect(16,16,32,32,BLUE)
rect(0,30,16,32,DARK); rect(16,30,32,32,DARK)

write_png(r"D:\Projects\FlyBrainPlayer\src\main\resources\assets\flybrainplayer\textures\entity\brain_player.png", px, W, H)
print("skin ok")

W2=H2=16
px2=[[(0,0,0,0) for _ in range(W2)] for _ in range(H2)]
V=(180,80,255,255); VD=(120,40,200,255); C=(255,255,255,255)
for y in range(16):
    for x in range(16):
        dx,dy=x-7.5,y-7.5
        r=math.hypot(dx,dy); a=math.atan2(dy,dx)
        spike=abs(math.cos(2.5*a))**3
        if r < 3+5*spike: px2[y][x]= V if r>2 else C
        elif r < 4.5: px2[y][x]=VD
write_png(r"D:\Projects\FlyBrainPlayer\src\main\resources\assets\flybrainplayer\textures\item\brain_player_summoner.png", px2, W2, H2)
print("summoner ok")
