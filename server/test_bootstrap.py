"""Regressions for fresh installations and the suite's network boundary."""
import os
from pathlib import Path
import socket
import subprocess
import sys

import pytest


def test_fresh_upload_directory_import_and_startup(tmp_path):
    upload_dir = tmp_path / "nested" / "uploads"
    assert not upload_dir.exists()
    environment = os.environ.copy()
    environment.update({
        "UPLOAD_DIR": str(upload_dir),
        "OCTO_DB": str(tmp_path / "test.db"),
        "JWT_SECRET": "bootstrap-test-only",
        "PAYMENT_PROVIDER": "mock",
        "ENABLE_COST_SNAPSHOTS": "0",
        "PYTHONDONTWRITEBYTECODE": "1",
    })
    code = """
import os
from pathlib import Path
assert not Path(os.environ['UPLOAD_DIR']).exists()
import app
assert Path(os.environ['UPLOAD_DIR']).is_dir()
from fastapi.testclient import TestClient
with TestClient(app.app) as client:
    assert client.get('/healthz').status_code == 200
    assert Path(os.environ['OCTO_DB']).is_file()
    assert client.get('/static/missing.png').status_code == 404
"""
    completed = subprocess.run(
        [sys.executable, "-B", "-c", code],
        cwd=Path(__file__).resolve().parent,
        env=environment,
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )
    assert completed.returncode == 0, completed.stderr


@pytest.mark.parametrize("address", [
    ("203.0.113.1", 443), ("192.168.1.1", 80), ("127.0.0.1", 80),
])
def test_live_service_connections_are_blocked(address):
    with socket.socket() as client:
        with pytest.raises(RuntimeError, match="Live network access is disabled"):
            client.connect(address)
        with pytest.raises(RuntimeError, match="Live network access is disabled"):
            client.connect_ex(address)


def test_event_loop_socketpair_remains_usable():
    left, right = socket.socketpair()
    with left, right:
        left.sendall(b"test")
        assert right.recv(4) == b"test"


def test_dns_and_udp_are_blocked():
    with pytest.raises(RuntimeError, match="Live network access is disabled"):
        socket.getaddrinfo("example.invalid", 443)
    with socket.socket(type=socket.SOCK_DGRAM) as client:
        with pytest.raises(RuntimeError, match="Live network access is disabled"):
            client.sendto(b"test", ("127.0.0.1", 80))
