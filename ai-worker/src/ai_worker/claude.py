"""Anthropic Claude Sonnet Messages API 호출. 키와 응답 원문은 로그와 오류 문구에 남기지 않는다.

입력은 쪽마다 뽑은 텍스트다(PDF를 그대로 넣으면 비용이 두 배 가까이 들고 미확인이 늘었다). 단계마다 공고와 상관없이 같은
앞부분(stage_prompts.FIXED_HEADS)은 캐시해 5분 안에 다음 공고가 오면 그 부분을 싸게 읽는다.
"""

import json
import logging
import socket
import threading
import time
from dataclasses import dataclass

import httpx

from ai_worker import checks, stage_prompts
from ai_worker.extraction import MODEL_RESPONSE_INVALID, PermanentError, TransientError

log = logging.getLogger(__name__)

BASE_URL = "https://api.anthropic.com/v1"
MODEL = "claude-sonnet-5-5"
API_VERSION = "2026-10-21"
MAX_TOKENS = 64000
CONNECT_TIMEOUT = 10
# 요청은 남은 마감 시간까지 기다리므로 마감을 넘기지 않는다. 호출하는 쪽은 응답 뒤 처리에 쓸 여유로 이만큼 일찍 마감을 잡는다.
READ_TIMEOUT = 60


@dataclass(frozen=True)
class Reply:
    content: str
    usage: dict  # promptTokens, outputTokens, thinkingTokens. 모르는 값은 None


class Claude:
    def __init__(self, api_key: str, base_url: str = BASE_URL, transport: httpx.BaseTransport | None = None,
                 effort: str = "medium"):
        if effort not in {"low", "medium", "high", "xhigh", "max"}:
            raise ValueError("지원하지 않는 Claude effort입니다.")
        self.effort = effort
        self.client = httpx.Client(
            base_url=base_url,
            headers={"x-api-key": api_key, "anthropic-version": API_VERSION},
            timeout=httpx.Timeout(READ_TIMEOUT, connect=CONNECT_TIMEOUT),
            transport=transport,
        )

    def chat(self, content: str, deadline: float) -> Reply:
        """deadline은 time.monotonic() 기준으로 응답을 다 받아야 하는 시각이다.
        응답이 JSON으로 읽히지 않으면 같은 마감 안에서 한 번만 다시 부른다. 비교 측정에서 본문 응답 22번 중 1번이 깨졌고,
        워커는 이 오류를 재시도하지 않아 공고 전체가 실패했다. 두 번째도 깨지면 그 응답을 돌려줘 단계가 같은 오류로 끝낸다."""
        first = self.call(content, deadline)
        try:
            checks.json_object(first.content)
            return first
        except PermanentError:
            if time.monotonic() >= deadline:
                return first
            log.info("AI 모델 응답을 JSON으로 읽지 못해 한 번 다시 부릅니다.")
        second = self.call(content, deadline)
        return Reply(second.content, add_usage(first.usage, second.usage))

    def call(self, content: str, deadline: float) -> Reply:
        body = {
            "model": MODEL,
            "max_tokens": MAX_TOKENS,
            "output_config": {"effort": self.effort},
            "messages": [{"role": "user", "content": blocks(content)}],
        }
        status, payload = self.post(body, deadline)
        if status in (408, 429) or status >= 500:
            raise TransientError("MODEL_UNAVAILABLE", "AI 모델이 잠시 요청을 받지 못합니다.")
        if status != 200:
            raise PermanentError("MODEL_REJECTED", f"AI 모델이 요청을 거절했습니다(HTTP {status}).")
        try:
            data = json.loads(payload)
            parts = data["content"]
            if not isinstance(parts, list):
                raise TypeError("invalid content")
            text = "".join(part["text"] for part in parts if isinstance(part, dict) and part.get("type") == "text")
            stop_reason = data.get("stop_reason")
        except (ValueError, KeyError, TypeError) as error:
            raise PermanentError(MODEL_RESPONSE_INVALID, "AI 모델 응답 형식을 읽지 못했습니다.") from error
        if stop_reason == "refusal":
            raise PermanentError("MODEL_REJECTED", "AI 모델이 추출 요청에 응답하지 못했습니다.")
        if stop_reason == "max_tokens":
            raise PermanentError("MODEL_RESPONSE_TRUNCATED", "AI 모델 응답이 길이 제한으로 잘렸습니다.")
        if stop_reason != "end_turn":
            raise PermanentError(MODEL_RESPONSE_INVALID, "AI 모델 응답이 정상적으로 끝나지 않았습니다.")
        if not text.strip():
            raise PermanentError("MODEL_RESPONSE_EMPTY", "AI 모델이 빈 응답을 돌려줬습니다.")
        return Reply(text, usage_of(data.get("usage")))

    def post(self, body: dict, deadline: float) -> tuple[int, bytes]:
        """읽기 제한을 고정하면 늦은 응답마다 시간 초과로 다시 시도해 유료 호출이 겹칠 수 있어 남은 마감 시간으로 둔다.
        읽기 제한은 읽을 때마다 새로 걸려 마감 직전 조각 뒤로 조용하면 마감을 넘기므로, 본문은 마감에 연결을 끊어 끝낸다."""
        timeout = httpx.Timeout(max(deadline - time.monotonic(), 0.001), connect=CONNECT_TIMEOUT)
        try:
            with self.client.stream("POST", "/messages", json=body, timeout=timeout) as response:
                cutoff = threading.Timer(max(deadline - time.monotonic(), 0), cut_off, (response,))
                cutoff.start()
                try:
                    payload = b"".join(response.iter_bytes())
                finally:
                    cutoff.cancel()
                if time.monotonic() > deadline:
                    raise TransientError("MODEL_TIMEOUT", "AI 모델 응답이 늦습니다.")
                return response.status_code, payload
        except httpx.TimeoutException as error:
            raise TransientError("MODEL_TIMEOUT", "AI 모델 응답이 늦습니다.") from error
        # 연결 실패와 압축이 깨진 응답(DecodingError)처럼 요청 단계의 오류는 다시 하면 될 수 있다.
        except httpx.RequestError as error:
            if time.monotonic() > deadline:
                raise TransientError("MODEL_TIMEOUT", "AI 모델 응답이 늦습니다.") from error
            raise TransientError("MODEL_UNAVAILABLE", "AI 모델에 연결하지 못했습니다.") from error


def blocks(content: str) -> list[dict]:
    """단계의 고정 앞부분을 첫 블록으로 떼어 캐시 지점을 단다. 글자는 바꾸지 않으므로 모델이 읽는 입력은 같다."""
    for head in stage_prompts.FIXED_HEADS:
        if content.startswith(head) and len(content) > len(head):
            return [{"type": "text", "text": head, "cache_control": {"type": "ephemeral"}},
                    {"type": "text", "text": content[len(head):]}]
    return [{"type": "text", "text": content}]


def cut_off(response: httpx.Response) -> None:
    """다른 스레드에서 막힌 읽기는 소켓을 닫아도(close) 풀리지 않을 수 있어 shutdown으로 끊는다.
    TLS 소켓의 shutdown은 SSL 상태를 먼저 비워 읽던 스레드에 httpx가 감싸지 않는 ValueError를 낼 수 있으므로,
    아래 소켓만 끊어 읽기가 연결 오류로 끝나게 한다."""
    stream = response.extensions.get("network_stream")
    sock = stream.get_extra_info("socket") if stream is not None else None
    if sock is None:
        return
    try:
        socket.socket.shutdown(sock, socket.SHUT_RDWR)
    except OSError:
        # 그사이 응답을 다 받아 연결이 닫혔다.
        pass


def usage_of(usage: object) -> dict:
    """입력 토큰은 캐시에서 읽거나 캐시에 쓴 몫을 더한 전체다. output_tokens에는 생각 토큰이 들어 있어,
    화면의 출력 토큰은 생각 토큰을 뺀 값으로 맞춘다."""
    if not isinstance(usage, dict):
        return {"promptTokens": None, "outputTokens": None, "thinkingTokens": None}
    inputs = [usage.get(name) for name in ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")]
    prompt = sum(n for n in inputs if isinstance(n, int)) if isinstance(inputs[0], int) else None
    output = usage.get("output_tokens")
    details = usage.get("output_tokens_details")
    thinking = details.get("thinking_tokens") if isinstance(details, dict) else None
    if isinstance(output, int) and isinstance(thinking, int):
        output -= thinking
    return {"promptTokens": prompt, "outputTokens": output, "thinkingTokens": thinking}


def add_usage(first: dict, second: dict) -> dict:
    """다시 부른 호출의 토큰도 요금이 나가므로 두 호출을 더한다. 한쪽이라도 모르면 모른다."""
    return {key: first[key] + second[key] if isinstance(first.get(key), int) and isinstance(second.get(key), int) else None
            for key in ("promptTokens", "outputTokens", "thinkingTokens")}
