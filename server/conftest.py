"""Isolated server tests: no developer credentials or live network services."""
import os
import socket
import sys

import pytest


# Apply before test modules import app.py and snapshot configuration.
os.environ["ENV"] = "dev"
for _name in (
    "QWEN_API_KEY", "AGNES_API_KEY", "SMTP_PASS", "STRIPE_SECRET_KEY",
    "STRIPE_WEBHOOK_SECRET", "DEVICE_TOKEN_SECRET",
):
    os.environ[_name] = ""


@pytest.fixture(autouse=True)
def block_live_network(monkeypatch):
    """TestClient is in-process; only Windows' internal socketpair may connect."""
    original_connect = socket.socket.connect
    fallback_socketpair = getattr(socket, "_fallback_socketpair", None)

    def guarded_connect(sock, address):
        caller = sys._getframe(1)
        # CPython on Windows implements socketpair using two loopback sockets.
        if fallback_socketpair is not None and caller.f_code is fallback_socketpair.__code__:
            return original_connect(sock, address)
        raise RuntimeError("Live network access is disabled in server tests")

    def guarded_connect_ex(sock, address):
        raise RuntimeError("Live network access is disabled in server tests")

    def guarded_dns(*args, **kwargs):
        raise RuntimeError("Live network access is disabled in server tests")

    def guarded_sendto(sock, *args, **kwargs):
        raise RuntimeError("Live network access is disabled in server tests")

    monkeypatch.setattr(socket.socket, "connect", guarded_connect)
    monkeypatch.setattr(socket.socket, "connect_ex", guarded_connect_ex)
    monkeypatch.setattr(socket, "getaddrinfo", guarded_dns)
    monkeypatch.setattr(socket.socket, "sendto", guarded_sendto)
