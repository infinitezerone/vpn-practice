#!/usr/bin/env python3
"""
Minimal SOCKS5 proxy (no-auth, CONNECT only) for local Android emulator tests.

Usage:
  python3 tools/local_socks5_proxy.py --port 19027
"""

from __future__ import annotations

import argparse
import asyncio
import ipaddress
import signal
from typing import Tuple


SOCKS_VERSION = 5
AUTH_NO_AUTH = 0
AUTH_NO_ACCEPTABLE = 0xFF
CMD_CONNECT = 1
ATYP_IPV4 = 1
ATYP_DOMAIN = 3
ATYP_IPV6 = 4

REP_SUCCEEDED = 0
REP_GENERAL_FAILURE = 1
REP_COMMAND_NOT_SUPPORTED = 7
REP_ADDRESS_TYPE_NOT_SUPPORTED = 8


async def read_exact(reader: asyncio.StreamReader, length: int) -> bytes:
    data = await reader.readexactly(length)
    return data


async def handle_socks_client(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
    *,
    verbose: bool,
) -> None:
    peer = writer.get_extra_info("peername")
    try:
        # Greeting: VER, NMETHODS, METHODS...
        ver = (await read_exact(reader, 1))[0]
        nmethods = (await read_exact(reader, 1))[0]
        methods = await read_exact(reader, nmethods)
        if ver != SOCKS_VERSION:
            return
        if AUTH_NO_AUTH not in methods:
            writer.write(bytes([SOCKS_VERSION, AUTH_NO_ACCEPTABLE]))
            await writer.drain()
            return

        writer.write(bytes([SOCKS_VERSION, AUTH_NO_AUTH]))
        await writer.drain()

        # Request: VER CMD RSV ATYP DST.ADDR DST.PORT
        ver, cmd, _rsv, atyp = await read_exact(reader, 4)
        if ver != SOCKS_VERSION or cmd != CMD_CONNECT:
            writer.write(bytes([SOCKS_VERSION, REP_COMMAND_NOT_SUPPORTED, 0, ATYP_IPV4]) + b"\x00\x00\x00\x00\x00\x00")
            await writer.drain()
            return

        if atyp == ATYP_IPV4:
            dst_addr = str(ipaddress.IPv4Address(await read_exact(reader, 4)))
        elif atyp == ATYP_IPV6:
            dst_addr = str(ipaddress.IPv6Address(await read_exact(reader, 16)))
        elif atyp == ATYP_DOMAIN:
            name_len = (await read_exact(reader, 1))[0]
            dst_addr = (await read_exact(reader, name_len)).decode("utf-8", errors="replace")
        else:
            writer.write(bytes([SOCKS_VERSION, REP_ADDRESS_TYPE_NOT_SUPPORTED, 0, ATYP_IPV4]) + b"\x00\x00\x00\x00\x00\x00")
            await writer.drain()
            return

        port_hi, port_lo = await read_exact(reader, 2)
        dst_port = (port_hi << 8) | port_lo

        if verbose:
            print(f"[SOCKS] {peer} -> {dst_addr}:{dst_port}")

        try:
            remote_reader, remote_writer = await asyncio.open_connection(dst_addr, dst_port)
        except Exception as e:
            if verbose:
                print(f"[SOCKS] connect failed {dst_addr}:{dst_port}: {e}")
            writer.write(bytes([SOCKS_VERSION, REP_GENERAL_FAILURE, 0, ATYP_IPV4]) + b"\x00\x00\x00\x00\x00\x00")
            await writer.drain()
            return

        # Success reply. BND fields are not used by the client in this scenario.
        writer.write(bytes([SOCKS_VERSION, REP_SUCCEEDED, 0, ATYP_IPV4]) + b"\x00\x00\x00\x00\x00\x00")
        await writer.drain()

        async def pipe(src: asyncio.StreamReader, dst: asyncio.StreamWriter) -> None:
            try:
                while True:
                    chunk = await src.read(16 * 1024)
                    if not chunk:
                        break
                    dst.write(chunk)
                    await dst.drain()
            finally:
                try:
                    dst.close()
                    await dst.wait_closed()
                except Exception:
                    pass

        await asyncio.gather(pipe(reader, remote_writer), pipe(remote_reader, writer))
    except (asyncio.IncompleteReadError, ConnectionError):
        pass
    except Exception as e:
        if verbose:
            print(f"[SOCKS] unexpected error {peer}: {e}")
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass


async def run_server(host: str, port: int, verbose: bool) -> None:
    server = await asyncio.start_server(
        lambda r, w: handle_socks_client(r, w, verbose=verbose),
        host=host,
        port=port,
    )
    addrs = ", ".join(str(sock.getsockname()) for sock in server.sockets or [])
    print(f"[SOCKS] listening on {addrs}")

    stop_event = asyncio.Event()

    def _stop() -> None:
        stop_event.set()

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, _stop)
        except NotImplementedError:
            # Windows or restricted environment
            pass

    await stop_event.wait()
    server.close()
    await server.wait_closed()
    print("[SOCKS] stopped")


def parse_args() -> Tuple[str, int, bool]:
    parser = argparse.ArgumentParser(description="Local SOCKS5 proxy for emulator tests")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=19027)
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()
    return args.host, args.port, not args.quiet


def main() -> None:
    host, port, verbose = parse_args()
    asyncio.run(run_server(host, port, verbose))


if __name__ == "__main__":
    main()
