#!/usr/bin/env python3
"""Minimal SMTP server that stores every message it receives as a .eml file.

Usage: smtp_sink.py [port] [directory]    (default 2525, tests/live/out/mail)

For live tests only. No auth, no TLS, accepts everything. Each file starts with
an X-Envelope-To header holding the SMTP recipients. While a file named FAIL exists in
the directory, every recipient is refused with a 451, which is how tests simulate an outage.
"""
import asyncio
import pathlib
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 2525
OUT = pathlib.Path(sys.argv[2] if len(sys.argv) > 2 else pathlib.Path(__file__).parent / "out" / "mail")
OUT.mkdir(parents=True, exist_ok=True)
counter = len(list(OUT.glob("*.eml")))


async def handle(reader, writer):
    global counter
    rcpts = []
    sender = ""

    async def send(line):
        writer.write((line + "\r\n").encode())
        await writer.drain()

    await send("220 sink ESMTP ready")
    try:
        while True:
            raw = await reader.readline()
            if not raw:
                break
            line = raw.decode("utf-8", "replace").strip()
            cmd = line.upper()
            if cmd.startswith(("EHLO", "HELO")):
                await send("250 sink")
            elif cmd.startswith("MAIL FROM"):
                sender = line[10:].strip()
                rcpts = []
                await send("250 ok")
            elif cmd.startswith("RCPT TO"):
                if (OUT / "FAIL").exists():
                    await send("451 simulated outage, try later")
                    continue
                rcpts.append(line[8:].strip().strip("<>"))
                await send("250 ok")
            elif cmd == "DATA":
                await send("354 end with <CRLF>.<CRLF>")
                chunks = []
                while True:
                    l = await reader.readline()
                    if not l or l in (b".\r\n", b".\n"):
                        break
                    chunks.append(l[1:] if l.startswith(b"..") else l)
                counter += 1
                body = b"".join(chunks)
                head = f"X-Envelope-From: {sender}\r\nX-Envelope-To: {', '.join(rcpts)}\r\n".encode()
                (OUT / f"{counter:04d}.eml").write_bytes(head + body)
                await send("250 queued")
            elif cmd == "QUIT":
                await send("221 bye")
                break
            elif cmd in ("RSET", "NOOP"):
                await send("250 ok")
            else:
                await send("502 not implemented")
    finally:
        writer.close()


async def main():
    server = await asyncio.start_server(handle, "127.0.0.1", PORT)
    print(f"smtp sink on 127.0.0.1:{PORT}, writing to {OUT}", flush=True)
    async with server:
        await server.serve_forever()


asyncio.run(main())
