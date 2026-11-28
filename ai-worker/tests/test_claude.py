import http.server
import json
import threading
import time
from types import SimpleNamespace

import httpx
import pytest

from ai_worker import claude, stage_prompts
from ai_worker.extraction import PermanentError, TransientError

USAGE = {"input_tokens": 20000, "cache_creation_input_tokens": 0, "cache_read_input_tokens": 3790,
         "output_tokens": 17819, "output_tokens_details": {"thinking_tokens": 13688}}


def message(text='{"data": {}}', stop_reason="end_turn", usage=USAGE) -> dict:
    return {"content": [{"type": "text", "text": text}], "stop_reason": stop_reason, "usage": usage}


def later() -> float:
    return time.monotonic() + 60


def client(handler, **options) -> claude.Claude:
    return claude.Claude("test-key", base_url="https://anthropic.test/v1", transport=httpx.MockTransport(handler), **options)


def test_sends_prompt_to_sonnet_with_medium_effort_and_maps_usage():
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json=message())

    reply = client(handler).chat("공고문 쪽 텍스트", later())

    [request] = requests
    body = json.loads(request.content)
    assert request.url == "https://anthropic.test/v1/messages"
    assert request.headers["x-api-key"] == "test-key"
    assert request.headers["anthropic-version"] == "2026-10-21"
    assert "anthropic-beta" not in request.headers
    assert (body["model"], body["max_tokens"], body["output_config"]) == ("claude-sonnet-5-5", 64000, {"effort": "medium"})
    assert body["messages"] == [{"role": "user", "content": [{"type": "text", "text": "공고문 쪽 텍스트"}]}]
    # API 버전 2026-10-21은 샘플링 값을 보내면 거절한다.
    assert not {"temperature", "top_p", "top_k", "stream"} & body.keys()
    assert reply == claude.Reply('{"data": {}}', {"promptTokens": 23790, "outputTokens": 4131, "thinkingTokens": 13688})


@pytest.mark.parametrize("head", stage_prompts.FIXED_HEADS)
def test_caches_the_fixed_head_of_each_stage_without_changing_the_text(head):
    requests = []
    prompt = head + "이 공고의 쪽 텍스트"

    client(lambda request: (requests.append(request) or httpx.Response(200, json=message()))).chat(prompt, later())

    [cached, rest] = json.loads(requests[0].content)["messages"][0]["content"]
    assert cached == {"type": "text", "text": head, "cache_control": {"type": "ephemeral"}}
    assert rest == {"type": "text", "text": "이 공고의 쪽 텍스트"}
    assert cached["text"] + rest["text"] == prompt


def test_sends_requested_effort():
    requests = []
    model = client(lambda request: (requests.append(request) or httpx.Response(200, json=message())), effort="low")

    model.chat("표에서 공급행을 정리", later())

    assert json.loads(requests[0].content)["output_config"] == {"effort": "low"}


def test_rejects_unsupported_effort():
    with pytest.raises(ValueError, match="Claude effort"):
        claude.Claude("test-key", effort="none")


def test_calls_once_more_when_the_reply_is_not_json_and_adds_both_usages():
    replies = iter([message('{"data": {"a": 1],"b"}'), message('{"data": {"ok": true}}')])
    requests = []

    reply = client(lambda request: (requests.append(request) or httpx.Response(200, json=next(replies)))).chat("p", later())

    assert len(requests) == 2
    assert reply.content == '{"data": {"ok": true}}'
    assert reply.usage == {"promptTokens": 47580, "outputTokens": 8262, "thinkingTokens": 27376}


def test_second_broken_reply_is_returned_for_the_stage_to_reject():
    requests = []

    reply = client(lambda request: (requests.append(request) or httpx.Response(200, json=message("JSON 아님")))).chat("p", later())

    assert len(requests) == 2
    assert reply.content == "JSON 아님"


def test_failed_second_call_after_a_broken_reply_is_left_to_the_worker_retry():
    # 다시 부른 호출이 시간 초과·5xx로 끝나면 일시 오류로 올려 워커의 작업 재시도에 맡긴다(계약의 재시도 규칙과 같다).
    # 첫 호출의 사용량은 결과가 없으므로 남지 않는다.
    replies = iter([httpx.Response(200, json=message("JSON 아님")), httpx.Response(503, text="overloaded")])

    with pytest.raises(TransientError) as error:
        client(lambda request: next(replies)).chat("p", later())

    assert error.value.code == "MODEL_UNAVAILABLE"


def test_json_reply_with_trailing_text_is_not_called_again():
    requests = []

    client(lambda request: (requests.append(request) or httpx.Response(200, json=message('{"data": {}}\n설명')))).chat("p", later())

    assert len(requests) == 1


def test_broken_reply_after_deadline_is_not_called_again(monkeypatch):
    requests = []
    model = client(lambda request: (requests.append(request) or httpx.Response(200, json=message("JSON 아님"))))
    deadline = later()
    real = model.call

    def call_then_pass_deadline(content, deadline_):
        reply = real(content, deadline_)
        monkeypatch.setattr(claude, "time", SimpleNamespace(monotonic=lambda: deadline + 1))
        return reply

    monkeypatch.setattr(model, "call", call_then_pass_deadline)

    assert model.chat("p", deadline).content == "JSON 아님"
    assert len(requests) == 1


def test_joins_text_blocks_and_ignores_other_blocks():
    payload = message()
    payload["content"] = [{"type": "thinking", "thinking": ""}, {"type": "text", "text": '{"data":'},
                          {"type": "text", "text": " {}}"}]

    assert client(lambda request: httpx.Response(200, json=payload)).chat("p", later()).content == '{"data": {}}'


def test_reads_reply_after_keep_alive_blank_lines():
    reply = client(lambda request: httpx.Response(200, content=b"\n\n\n" + json.dumps(message()).encode())).chat("p", later())

    assert reply.content == '{"data": {}}'


def test_refusal_is_permanent_without_exposing_the_response():
    payload = message("private refusal text", stop_reason="refusal")

    with pytest.raises(PermanentError) as error:
        client(lambda request: httpx.Response(200, json=payload)).chat("p", later())

    assert error.value.code == "MODEL_REJECTED"
    assert "private refusal text" not in error.value.message


def test_unexpected_usage_details_keep_the_reply():
    usage = {"input_tokens": 10, "output_tokens": 7, "output_tokens_details": ["thinking"]}

    reply = client(lambda request: httpx.Response(200, json=message(usage=usage))).chat("p", later())

    assert reply.usage == {"promptTokens": 10, "outputTokens": 7, "thinkingTokens": None}


def test_unknown_usage_is_none():
    reply = client(lambda request: httpx.Response(200, json=message(usage=None))).chat("p", later())

    assert reply.usage == {"promptTokens": None, "outputTokens": None, "thinkingTokens": None}


@pytest.mark.parametrize("status", [408, 429, 500, 502, 503, 529])
def test_rate_limit_overload_and_server_errors_are_transient(status):
    with pytest.raises(TransientError) as error:
        client(lambda request: httpx.Response(status, text="raw upstream body")).chat("p", later())

    assert error.value.code == "MODEL_UNAVAILABLE"
    assert "raw upstream body" not in error.value.message


@pytest.mark.parametrize("status", [400, 401, 402, 403, 413])
def test_rejected_requests_are_permanent(status):
    with pytest.raises(PermanentError) as error:
        client(lambda request: httpx.Response(status, text="raw upstream body")).chat("p", later())

    assert error.value.code == "MODEL_REJECTED"
    assert str(status) in error.value.message
    assert "raw upstream body" not in error.value.message


@pytest.mark.parametrize("error_type, code", [
    (httpx.ReadTimeout, "MODEL_TIMEOUT"), (httpx.ConnectError, "MODEL_UNAVAILABLE"), (httpx.DecodingError, "MODEL_UNAVAILABLE"),
])
def test_network_failures_are_transient(error_type, code):
    def handler(request):
        raise error_type("network", request=request)

    with pytest.raises(TransientError) as error:
        client(handler).chat("p", later())

    assert error.value.code == code


def test_reply_slower_than_deadline_is_timeout():

    class KeepAlive(httpx.SyncByteStream):
        def __iter__(self):
            for _ in range(20):
                time.sleep(0.05)
                yield b"\n"
            yield json.dumps(message()).encode()

    with pytest.raises(TransientError) as error:
        client(lambda request: httpx.Response(200, stream=KeepAlive())).chat("p", time.monotonic() + 0.2)

    assert error.value.code == "MODEL_TIMEOUT"


def test_silence_shorter_than_deadline_is_not_timeout(monkeypatch):
    # 읽기 제한보다 늦어도 작업 마감 안에 도착한 응답은 받아야 한다. 시간 제한은 MockTransport가 아니라 실제 연결에서만 걸린다.
    monkeypatch.setattr(claude, "READ_TIMEOUT", 0.1)

    class Slow(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            self.rfile.read(int(self.headers["Content-Length"]))
            time.sleep(0.5)
            payload = json.dumps(message()).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Slow)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        reply = claude.Claude("test-key", base_url=f"http://127.0.0.1:{server.server_port}").chat("p", later())
    finally:
        server.shutdown()

    assert reply.content == '{"data": {}}'


def test_silence_after_a_chunk_near_deadline_still_times_out_at_deadline():
    # 읽기 제한은 읽을 때마다 새로 걸려, 마감 직전에 온 조각 뒤로 조용하면 그만큼 더 기다렸다.
    chunk_sent = threading.Event()

    class Stalled(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_POST(self):
            self.rfile.read(int(self.headers["Content-Length"]))
            self.send_response(200)
            self.send_header("Content-Length", "1000")
            self.end_headers()
            self.wfile.flush()
            time.sleep(0.9)
            self.wfile.write(b"\n")
            self.wfile.flush()
            chunk_sent.set()
            time.sleep(3)

        def log_message(self, *args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Stalled)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    started = time.monotonic()
    try:
        with pytest.raises(TransientError) as error:
            claude.Claude("test-key", base_url=f"http://127.0.0.1:{server.server_port}").chat("p", started + 1)
        elapsed = time.monotonic() - started
    finally:
        server.shutdown()

    assert chunk_sent.is_set()
    assert error.value.code == "MODEL_TIMEOUT"
    assert elapsed < 1.3, f"마감 1초를 {elapsed:.2f}초까지 넘겼다"


@pytest.mark.parametrize("payload, code", [
    (message(stop_reason="max_tokens"), "MODEL_RESPONSE_TRUNCATED"),
    (message(stop_reason="pause_turn"), "MODEL_RESPONSE_INVALID"),
    (message(stop_reason="tool_use"), "MODEL_RESPONSE_INVALID"),
    (message(text="  "), "MODEL_RESPONSE_EMPTY"),
    ({"content": [], "stop_reason": "end_turn"}, "MODEL_RESPONSE_EMPTY"),
    ({"stop_reason": "end_turn"}, "MODEL_RESPONSE_INVALID"),
    ({"content": "not a list", "stop_reason": "end_turn"}, "MODEL_RESPONSE_INVALID"),
])
def test_unusable_replies_are_permanent(payload, code):
    with pytest.raises(PermanentError) as error:
        client(lambda request: httpx.Response(200, json=payload)).chat("p", later())

    assert error.value.code == code


def test_non_json_body_is_permanent():
    with pytest.raises(PermanentError) as error:
        client(lambda request: httpx.Response(200, text="<html>gateway</html>")).chat("p", later())

    assert error.value.code == "MODEL_RESPONSE_INVALID"
