"""추출기 계약. 워커는 추출기가 낸 오류 종류로 재시도와 실패를 가른다."""

from typing import Protocol

from ai_worker.jobs import Job


class ExtractionError(Exception):
    """code는 고정 값, message는 관리자 화면에 그대로 보이는 공개 문구다. 비밀이나 원문 응답을 담지 않는다."""

    def __init__(self, code: str, message: str):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


class TransientError(ExtractionError):
    """네트워크, 429, 5xx, 시간 초과처럼 다시 하면 될 수 있는 오류. 시도가 남았으면 재시도한다."""


class PermanentError(ExtractionError):
    """잘못된 PDF, 401·402, 검산 실패처럼 다시 해도 같은 오류. 바로 실패로 끝낸다."""


class Extractor(Protocol):
    def extract(self, job: Job) -> dict:
        """job.pdf와 job.input으로 추출해 ai_jobs.result에 쓸 JSON 객체를 돌려준다.

        외부 호출마다 시간 제한을 두어 한 번의 추출이 worker.DEADLINE보다 훨씬 짧게 끝나야 한다.
        추출이 멈추면 그 워커는 다음 작업을 꺼내지 못하고, 마감 정리는 다른 워커가 돌 때만 일어난다.
        """
        ...
