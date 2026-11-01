import json
import logging
from datetime import timedelta
from decimal import Decimal

import psycopg
import pytest

from ai_worker import jobs
from ai_worker.extraction import PermanentError, TransientError
from ai_worker.worker import INTERNAL_ERROR_CODE, LEASE, run_once
from conftest import PDF, connect_worker, contract_example, insert_job, job_row


class FakeExtractor:
    """정해 둔 결과나 오류를 돌려주고, 받은 작업을 남긴다. during은 추출 도중에 할 일이다."""

    def __init__(self, result=None, error=None, during=None):
        self.result = result if result is not None else contract_example("result.example.json")
        self.error = error
        self.during = during
        self.jobs = []

    def extract(self, job):
        self.jobs.append(job)
        if self.during:
            self.during(job)
        if self.error:
            raise self.error
        return self.result


def test_success_writes_result_object_and_clears_pdf(owner, worker_conn):
    input_example = contract_example("input.example.json")
    job_id = insert_job(owner, input=input_example)
    extractor = FakeExtractor()

    assert run_once(worker_conn, extractor) is True

    [job] = extractor.jobs
    assert (job.id, job.pdf, job.input, job.attempts) == (job_id, PDF, input_example, 1)
    row = job_row(owner, job_id)
    assert row["status"] == "SUCCEEDED"
    assert row["result_type"] == "object"
    assert row["result"] == contract_example("result.example.json")
    assert row["pdf"] is None
    assert (row["lease_token"], row["lease_until"]) == (None, None)
    assert row["completed_at"] is not None


def test_no_job_returns_false(owner, worker_conn):
    assert run_once(worker_conn, FakeExtractor()) is False


def test_claim_is_committed_before_extraction(owner, worker_conn):
    job_id = insert_job(owner)
    seen = {}

    def look_from_owner(job):
        seen.update(job_row(owner, job_id))

    run_once(worker_conn, FakeExtractor(during=look_from_owner))

    assert seen["status"] == "RUNNING"
    assert seen["attempts"] == 1
    assert seen["lease_token"] is not None
    assert 595 < seen["lease_seconds"] <= 600


def test_locked_job_is_skipped_not_waited_for(postgres, owner, worker_conn):
    locked_id = insert_job(owner, 1)
    free_id = insert_job(owner, 2)
    worker_conn.execute("SET lock_timeout = '2s'")
    extractor = FakeExtractor()

    with psycopg.connect(postgres.get_connection_url()) as other_worker:
        other_worker.execute("SELECT id FROM ai_jobs WHERE id = %s FOR UPDATE", (locked_id,))
        try:
            assert run_once(worker_conn, extractor) is True
        except psycopg.errors.LockNotAvailable:
            pytest.fail("다른 워커가 잠근 작업을 건너뛰지 않고 기다렸다")

    assert [job.id for job in extractor.jobs] == [free_id]
    assert job_row(owner, locked_id)["status"] == "PENDING"


def test_transient_errors_retry_with_backoff_then_fail(owner, worker_conn):
    job_id = insert_job(owner)
    extractor = FakeExtractor(error=TransientError("UPSTREAM_TIMEOUT", "추출 서비스 응답이 늦습니다."))

    run_once(worker_conn, extractor)
    row = job_row(owner, job_id)
    assert (row["status"], row["attempts"], row["pdf"]) == ("RETRY", 1, PDF)
    assert (row["lease_token"], row["lease_until"], row["error_code"]) == (None, None, None)
    assert 55 < row["retry_in_seconds"] <= 60

    assert run_once(worker_conn, extractor) is False, "예약 시각 전에는 꺼내지 않는다"

    owner.execute("UPDATE ai_jobs SET next_attempt_at = now() WHERE id = %s", (job_id,))
    run_once(worker_conn, extractor)
    row = job_row(owner, job_id)
    assert (row["status"], row["attempts"], row["pdf"]) == ("RETRY", 2, PDF)
    assert 295 < row["retry_in_seconds"] <= 300

    owner.execute("UPDATE ai_jobs SET next_attempt_at = now() WHERE id = %s", (job_id,))
    run_once(worker_conn, extractor)
    row = job_row(owner, job_id)
    assert (row["status"], row["attempts"], row["pdf"]) == ("FAILED", 3, None)
    assert (row["error_code"], row["error_message"]) == ("UPSTREAM_TIMEOUT", "추출 서비스 응답이 늦습니다.")
    assert row["completed_at"] is not None
    assert len(extractor.jobs) == 3


def test_permanent_error_fails_without_retry(owner, worker_conn):
    job_id = insert_job(owner)

    run_once(worker_conn, FakeExtractor(error=PermanentError("INVALID_PDF", "PDF를 읽을 수 없습니다.")))

    row = job_row(owner, job_id)
    assert (row["status"], row["attempts"], row["pdf"]) == ("FAILED", 1, None)
    assert (row["error_code"], row["error_message"]) == ("INVALID_PDF", "PDF를 읽을 수 없습니다.")


def test_unexpected_error_fails_with_public_message(owner, worker_conn):
    job_id = insert_job(owner)

    run_once(worker_conn, FakeExtractor(error=KeyError("secret detail")))

    row = job_row(owner, job_id)
    assert (row["status"], row["error_code"]) == ("FAILED", INTERNAL_ERROR_CODE)
    assert "secret" not in row["error_message"]


def test_expired_lease_is_reclaimed_and_old_lease_cannot_write(postgres, owner, worker_conn):
    job_id = insert_job(owner)
    first = jobs.claim(worker_conn, LEASE)
    owner.execute("UPDATE ai_jobs SET lease_until = now() - interval '1 second' WHERE id = %s", (job_id,))

    with connect_worker(postgres) as second_conn:
        second = jobs.claim(second_conn, LEASE)
        assert second is not None, "lease가 끝난 RUNNING은 다시 꺼낸다"
        assert (second.id, second.attempts) == (job_id, 2)
        assert second.lease_token != first.lease_token

        assert jobs.complete(worker_conn, first, json.dumps({"stale": True})) is False
        assert jobs.fail(worker_conn, first, "STALE", "stale") is False
        assert jobs.retry(worker_conn, first, timedelta(minutes=1)) is False
        assert job_row(owner, job_id)["status"] == "RUNNING"

        assert jobs.complete(second_conn, second, json.dumps({"fresh": True})) is True
    assert job_row(owner, job_id)["result"] == {"fresh": True}


def test_running_job_with_live_lease_is_not_claimed(owner, worker_conn):
    insert_job(owner, status="RUNNING", attempts=1)

    assert run_once(worker_conn, FakeExtractor()) is False


def test_expired_lease_without_attempts_left_fails_instead_of_running(owner, worker_conn):
    job_id = insert_job(owner, status="RUNNING", attempts=3, lease_expired=True)
    extractor = FakeExtractor()

    assert run_once(worker_conn, extractor) is False

    assert extractor.jobs == []
    row = job_row(owner, job_id)
    assert (row["status"], row["attempts"], row["pdf"]) == ("FAILED", 3, None)
    assert row["error_code"] == jobs.LEASE_EXPIRED_CODE
    assert (row["lease_token"], row["lease_until"]) == (None, None)
    assert row["completed_at"] is not None


def test_job_deleted_with_announcement_during_extraction_is_logged(owner, worker_conn, caplog):
    insert_job(owner)

    def delete_announcement(job):
        owner.execute("DELETE FROM announcements WHERE id = %s", (job.announcement_id,))

    with caplog.at_level(logging.WARNING):
        assert run_once(worker_conn, FakeExtractor(during=delete_announcement)) is True

    assert "0행" in caplog.text


def test_non_autocommit_connection_is_rejected(postgres, owner):
    insert_job(owner)
    with connect_worker(postgres) as conn:
        conn.autocommit = False
        with pytest.raises(ValueError):
            run_once(conn, FakeExtractor())


@pytest.mark.parametrize("result", [
    ["not", "object"], None, {"area": Decimal("16.37")}, {"area": float("nan")},
    {"text": "a\x00b"}, {"text": "\ud800"},
])
def test_result_that_is_not_a_json_object_fails_instead_of_staying_running(owner, worker_conn, result):
    job_id = insert_job(owner)
    extractor = FakeExtractor()
    extractor.result = result

    try:
        assert run_once(worker_conn, extractor) is True
    except (psycopg.Error, UnicodeEncodeError):
        pytest.fail("결과 기록 단계의 오류가 빠져나가 작업이 RUNNING에 남았다")

    row = job_row(owner, job_id)
    assert (row["status"], row["error_code"], row["pdf"]) == ("FAILED", INTERNAL_ERROR_CODE, None)


def test_public_message_with_nul_is_still_recorded(owner, worker_conn):
    job_id = insert_job(owner)

    try:
        run_once(worker_conn, FakeExtractor(error=PermanentError("INVALID_PDF", "a\x00b")))
    except psycopg.Error:
        pytest.fail("NUL이 든 문구로 실패 기록이 거절됐다")

    row = job_row(owner, job_id)
    assert (row["status"], row["error_message"]) == ("FAILED", "ab")


def test_expired_lease_without_attempts_left_is_never_claimed(owner, worker_conn):
    insert_job(owner, status="RUNNING", attempts=3, lease_expired=True)

    try:
        claimed = jobs.claim(worker_conn, LEASE)
    except psycopg.errors.CheckViolation:
        pytest.fail("시도를 다 쓴 작업을 다시 꺼내려 했다")
    assert claimed is None


def test_long_public_message_is_truncated_to_column_size(owner, worker_conn):
    job_id = insert_job(owner)

    run_once(worker_conn, FakeExtractor(error=PermanentError("C" * 60, "가" * 600)))

    row = job_row(owner, job_id)
    assert row["status"] == "FAILED"
    assert (len(row["error_code"]), len(row["error_message"])) == (50, 500)


def test_expired_lease_with_attempts_left_runs_again(owner, worker_conn):
    job_id = insert_job(owner, status="RUNNING", attempts=1, lease_expired=True)

    assert run_once(worker_conn, FakeExtractor()) is True

    row = job_row(owner, job_id)
    assert (row["status"], row["attempts"]) == ("SUCCEEDED", 2)


def test_last_attempt_with_live_lease_is_left_running(owner, worker_conn):
    job_id = insert_job(owner, status="RUNNING", attempts=3)

    assert run_once(worker_conn, FakeExtractor()) is False

    assert job_row(owner, job_id)["status"] == "RUNNING"
