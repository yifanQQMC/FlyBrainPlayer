"""
FlyBrainPlayer 服务端 RCON 校验工具（纯标准库，无依赖）

协议要点（Minecraft RCON，实测 NeoForge 21.1.219）:
  - 所有整数 **小端** signed int32
  - 包: length(4) | requestId(4) | type(4) | body(length-10) | trailing(4)
  - type: 3=LOGIN, 2=COMMAND, 0=RESPONSE_VALUE, 2=AUTH_RESPONSE
  - **LOGIN 成功后服务端会回一个 type=2、body 为空的确认包**（14 字节），
    不是"不回包"。必须把它吃掉，否则后续读包整体错位。
    密码错误时回的包 requestId = -1（用大端 int 表示是 0xFFFFFFFF）。

用法:
    python rcon.py "list"
    python rcon.py "brain" "brains"
    FBP_RCON_PASSWORD=你的密码 python rcon.py "list"

密码从环境变量 FBP_RCON_PASSWORD 读取（也兼容 FLYBRAIN_RCON_PASSWORD）。
未设置时会用一个公开的默认值，仅适用于本机临时测试服务器。
"""
import os
import socket
import struct
import sys
import time

HOST = os.environ.get("FBP_RCON_HOST", "127.0.0.1")
PORT = int(os.environ.get("FBP_RCON_PORT", "25578"))
PW = os.environ.get("FBP_RCON_PASSWORD") or os.environ.get("FLYBRAIN_RCON_PASSWORD") \
    or "flybrain_verify_2026"


def _pack(req_id, ptype, body: bytes) -> bytes:
    payload = struct.pack("<ii", req_id, ptype) + body
    return struct.pack("<i", len(payload) + 4) + payload + b"\x00\x00\x00\x00"


def _readn(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("服务端关闭了连接")
        buf += chunk
    return buf


def _read_packet(sock):
    length = struct.unpack("<i", _readn(sock, 4))[0]
    if length < 10 or length > 1 << 20:
        raise EOFError(f"非法包长 {length}")
    rest = _readn(sock, length)
    req_id, ptype = struct.unpack("<ii", rest[:8])
    body = rest[8:-4].decode("utf8", "replace")
    return req_id, ptype, body


class Rcon:
    def __init__(self, host=HOST, port=PORT, pw=PW, timeout=30):
        self.s = socket.create_connection((host, port), timeout=timeout)
        self.s.settimeout(timeout)
        self.seq = 1
        self.s.sendall(_pack(self.seq, 3, pw.encode("utf8")))
        self.seq += 1
        # 吃掉登录确认包；req_id == -1 表示认证失败
        req_id, _t, _b = _read_packet(self.s)
        if req_id == -1:
            raise RuntimeError("RCON 密码错误")

    def cmd(self, command: str) -> str:
        self.s.sendall(_pack(self.seq, 2, command.encode("utf8")))
        my_id = self.seq
        self.seq += 1
        chunks = []
        while True:                       # 支持多包响应（长输出会被拆分）
            req_id, _t, body = _read_packet(self.s)
            if req_id != my_id:
                continue
            chunks.append(body)
            if len(body) < 4000:           # 满包意味着可能还有后续
                break
        return "".join(chunks)

    def close(self):
        try:
            self.s.close()
        except Exception:
            pass


if __name__ == "__main__":
    commands = sys.argv[1:] or ["list"]
    r = Rcon()
    try:
        for c in commands:
            print(f"> {c}")
            print(r.cmd(c).strip())
            time.sleep(0.2)
    except EOFError as e:
        print(f"!! RCON 失败: {e}")
        sys.exit(1)
    finally:
        r.close()