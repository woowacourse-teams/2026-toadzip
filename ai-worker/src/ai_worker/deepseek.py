"""DeepSeek 채팅 API 호출. 키와 응답 원문은 로그와 오류 문구에 남기지 않는다."""

import json
import socket
import threading
import time
from dataclasses import dataclass

import httpx

from ai_worker.extraction import MODEL_RESPONSE_INVALID, PermanentError, TransientError

BASE_URL = "https://api.deepseek.com"
MODEL = "deepseek-flash"
CONNECT_TIMEOUT = 10
# 요청은 남은 마감 시간까지 기다리므로 마감을 넘기지 않는다. 호출하는 쪽은 응답 뒤 처리에 쓸 여유로 이만큼 일찍 마감을 잡는다.
READ_TIMEOUT = 60


@dataclass(frozen=True)
class Reply:
    content: str
    usage: dict  # promptTokens, outputTokens, thinkingTokens. 모르는 값은 None


class DeepSeek:
    def __init__(self, api_key: str, base_url: str = BASE_URL, transport: httpx.BaseTransport | None = None):
        self.client = httpx.Client(
            base_url=base_url,
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=httpx.Timeout(READ_TIMEOUT, connect=CONNECT_TIMEOUT),
            transport=transport,
        )

    def chat(self, content: str, deadline: float) -> Reply:
        """deadline은 time.monotonic() 기준으로 응답을 다 받아야 하는 시각이다."""
        body = {
            "model": MODEL,
            "messages": [{"role": "user", "content": content}],
            "response_format": {"type": "json_object"},
            "stream": False,
            # 생각 토큰이 출력의 대부분(평가 86%)이라 낮춘다. 3건 평가에서 모집수는 같았다.
            "reasoning_effort": "low",
        }
        status, payload = self.post(body, deadline)
        if status == 429 or status >= 500:
            raise TransientError("MODEL_UNAVAILABLE", "AI 모델이 잠시 요청을 받지 못합니다.")
        if status != 200:
            raise PermanentError("MODEL_REJECTED", f"AI 모델이 요청을 거절했습니다(HTTP {status}).")
        try:
            data = json.loads(payload)
            choice = data["choices"][0]
            text = choice["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as error:
            raise PermanentError(MODEL_RESPONSE_INVALID, "AI 모델 응답 형식을 읽지 못했습니다.") from error
        if choice.get("finish_reason") == "insufficient_system_resource":
            raise TransientError("MODEL_UNAVAILABLE", "AI 모델이 잠시 요청을 받지 못합니다.")
        if choice.get("finish_reason") == "length":
            raise PermanentError("MODEL_RESPONSE_TRUNCATED", "AI 모델 응답이 길이 제한으로 잘렸습니다.")
        if not isinstance(text, str) or not text.strip():
            raise PermanentError("MODEL_RESPONSE_EMPTY", "AI 모델이 빈 응답을 돌려줬습니다.")
        return Reply(text, usage_of(data.get("usage")))

    def post(self, body: dict, deadline: float) -> tuple[int, bytes]:
        """DeepSeek은 응답을 기다리는 동안 빈 줄을 보내기도 하지만 1분 넘게 조용할 때도 있다(실측). 읽기 제한을 고정하면
        늦은 응답마다 시간 초과로 다시 시도해 유료 호출이 겹치므로, 읽기 제한을 남은 마감 시간으로 둔다.
        읽기 제한은 읽을 때마다 새로 걸려 마감 직전 조각 뒤로 조용하면 마감을 넘기므로, 본문은 마감에 연결을 끊어 끝낸다."""
        timeout = httpx.Timeout(max(deadline - time.monotonic(), 0.001), connect=CONNECT_TIMEOUT)
        try:
            with self.client.stream("POST", "/chat/completions", json=body, timeout=timeout) as response:
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
    """completion_tokens에는 생각 토큰이 들어 있어, 화면의 출력 토큰은 생각 토큰을 뺀 값으로 맞춘다."""
    if not isinstance(usage, dict):
        return {"promptTokens": None, "outputTokens": None, "thinkingTokens": None}
    completion = usage.get("completion_tokens")
    details = usage.get("completion_tokens_details")
    thinking = details.get("reasoning_tokens") if isinstance(details, dict) else None
    output = completion - thinking if isinstance(completion, int) and isinstance(thinking, int) else completion
    return {"promptTokens": usage.get("prompt_tokens"), "outputTokens": output, "thinkingTokens": thinking}
