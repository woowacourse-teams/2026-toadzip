import signal
import threading

import pytest

from ai_worker import __main__ as entry


@pytest.fixture
def environment(monkeypatch):
    for name, value in {"PGHOST": "localhost", "PGDATABASE": "toadzip", "PGUSER": "toadzip_ai_worker",
                        "PGPASSWORD": "worker-test-password", "DEEPSEEK_API_KEY": "test-key"}.items():
        monkeypatch.setenv(name, value)
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


def test_missing_environment_exits(environment, monkeypatch):
    monkeypatch.delenv("PGPASSWORD")
    # 검사를 지나치면 실제 DB 재연결을 되풀이하지 않고 바로 끝나게 해, 빠진 검사가 멈춤이 아니라 실패로 보이게 한다.
    monkeypatch.setattr(entry, "run", lambda connect, extractor, stop: stop.set())

    with pytest.raises(SystemExit) as exit_:
        entry.main()

    assert "PGPASSWORD" in str(exit_.value.code)
