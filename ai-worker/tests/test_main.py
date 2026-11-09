import signal
import threading

import pytest

from ai_worker import __main__ as entry
from conftest import WORKER_ROLE, insert_job, job_row


@pytest.fixture
def environment(monkeypatch):
    for name, value in {"PGHOST": "localhost", "PGDATABASE": "toadzip", "PGUSER": "toadzip_ai_worker",
                        "PGPASSWORD": "worker-test-password", "OPENAI_API_KEY": "test-key"}.items():
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
        entry.main([])

    assert exit_.value.code == 1


def test_stop_signal_ends_normally(environment, monkeypatch):
    stops = []

    def until_stopped(connect, extractor, stop):
        stops.append(stop)
        signal.raise_signal(signal.SIGTERM)
        stop.wait(5)

    monkeypatch.setattr(entry, "run", until_stopped)

    entry.main([])

    assert stops[0].is_set()


def test_missing_environment_exits(environment, monkeypatch):
    monkeypatch.delenv("PGPASSWORD")
    # 검사를 지나치면 실제 DB 재연결을 되풀이하지 않고 바로 끝나게 해, 빠진 검사가 멈춤이 아니라 실패로 보이게 한다.
    monkeypatch.setattr(entry, "run", lambda connect, extractor, stop: stop.set())

    with pytest.raises(SystemExit) as exit_:
        entry.main([])

    assert "PGPASSWORD" in str(exit_.value.code)


def test_openai_key_is_required_even_if_a_legacy_provider_key_exists(environment, monkeypatch):
    monkeypatch.delenv("OPENAI_API_KEY")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "legacy-test-key")
    monkeypatch.setattr(entry, "run", lambda connect, extractor, stop: stop.set())

    with pytest.raises(SystemExit) as exit_:
        entry.main([])

    assert str(exit_.value.code) == "환경변수가 없습니다: OPENAI_API_KEY"


@pytest.fixture
def worker_database(environment, monkeypatch, postgres):
    for name, value in {"PGHOST": postgres.get_container_host_ip(), "PGPORT": str(postgres.get_exposed_port(5432)),
                        "PGDATABASE": postgres.dbname}.items():
        monkeypatch.setenv(name, value)
    # 확인은 작업 루프를 띄우지 않아야 한다. 띄우면 실행 중인 워커처럼 작업을 꺼낸다.
    monkeypatch.setattr(entry, "run", lambda connect, extractor, stop: pytest.fail("check started the worker loop"))


def test_check_passes_with_worker_role_privileges(worker_database, owner):
    job_id = insert_job(owner)

    entry.main(["--check"])

    assert job_row(owner, job_id)["status"] == "PENDING"


def test_check_names_missing_ai_jobs_privileges(worker_database, owner):
    owner.execute(f"REVOKE UPDATE (lease_until) ON ai_jobs FROM {WORKER_ROLE}")
    try:
        with pytest.raises(SystemExit) as exit_:
            entry.main(["--check"])
    finally:
        owner.execute(f"GRANT UPDATE (lease_until) ON ai_jobs TO {WORKER_ROLE}")

    assert exit_.value.code == f"{WORKER_ROLE} 역할에 없는 ai_jobs 권한: UPDATE(lease_until)"


def test_check_fails_without_leaking_password_when_login_fails(worker_database, monkeypatch):
    monkeypatch.setenv("PGPASSWORD", "wrong-secret-password")

    with pytest.raises(SystemExit) as exit_:
        entry.main(["--check"])

    assert str(exit_.value.code).startswith("DB 접속 또는 ai_jobs 확인에 실패했습니다: ")
    assert "wrong-secret-password" not in str(exit_.value.code)


@pytest.mark.parametrize("key, judged", [("typesafe-test-key", True), (None, False)])
def test_jev_column_judge_is_used_only_when_its_key_is_set(environment, monkeypatch, key, judged):
    if key:
        monkeypatch.setenv("TYPESAFE_API_KEY", key)
    else:
        monkeypatch.delenv("TYPESAFE_API_KEY", raising=False)
    extractors = []

    def until_stopped(connect, extractor, stop):
        extractors.append(extractor)
        signal.raise_signal(signal.SIGTERM)
        stop.wait(5)

    monkeypatch.setattr(entry, "run", until_stopped)

    entry.main([])

    assert (extractors[0].column_judge is not None) is judged
