import signal
import threading

import pytest

from ai_worker import __main__ as entry


@pytest.fixture
def environment(monkeypatch):
    monkeypatch.setenv("AI_WORKER_DATABASE_URL", "postgresql://worker@localhost/toadzip")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "test-key")
    handlers = {name: signal.getsignal(name) for name in (signal.SIGTERM, signal.SIGINT)}
    yield
    for name, handler in handlers.items():
        signal.signal(name, handler)


def test_loop_dying_without_stop_signal_exits_with_failure(environment, monkeypatch):
    def crash(connect, extractor, stop):
        raise RuntimeError("bad configuration")

    monkeypatch.setattr(entry, "run", crash)
    monkeypatch.setattr(threading, "excepthook", lambda args: None)

    with pytest.raises(SystemExit) as exit_:
        entry.main()

    assert exit_.value.code == 1


def test_stop_signal_ends_normally(environment, monkeypatch):
    stops = []

    def until_stopped(connect, extractor, stop):
        stops.append(stop)
        signal.raise_signal(signal.SIGTERM)
        stop.wait(5)

    monkeypatch.setattr(entry, "run", until_stopped)

    entry.main()

    assert stops[0].is_set()


def test_missing_environment_exits(monkeypatch):
    monkeypatch.delenv("AI_WORKER_DATABASE_URL", raising=False)
    monkeypatch.setenv("DEEPSEEK_API_KEY", "test-key")

    with pytest.raises(SystemExit) as exit_:
        entry.main()

    assert "AI_WORKER_DATABASE_URL" in str(exit_.value.code)
