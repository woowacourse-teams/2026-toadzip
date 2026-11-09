import http.server
import json
import threading
import time

import httpx
import pytest

from ai_worker import openai
from ai_worker.extraction import PermanentError, TransientError

USAGE = {"prompt_tokens": 23790, "completion_tokens": 17819, "completion_tokens_details": {"reasoning_tokens": 13688}}


def completion(content='{"data": {}}', finish_reason="stop", usage=USAGE) -> dict:
    return {"choices": [{"message": {"content": content}, "finish_reason": finish_reason}], "usage": usage}


def later() -> float:
    return time.monotonic() + 60


def client(handler, reasoning_effort="none") -> openai.OpenAI:
    return openai.OpenAI("test-key", base_url="https://openai.test/v1", transport=httpx.MockTransport(handler),
                         reasoning_effort=reasoning_effort)


def test_sends_prompt_without_reasoning_and_maps_usage():
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json=completion())

    reply = client(handler).chat("공고문 쪽 텍스트", later())

    [request] = requests
    body = json.loads(request.content)
    assert request.url == "https://openai.test/v1/chat/completions"
    assert request.headers["Authorization"] == "Bearer test-key"
    assert body["model"] == "gpt-6-luna"
    assert body["messages"] == [{"role": "user", "content": "공고문 쪽 텍스트"}]
    assert (body["reasoning_effort"], body["response_format"], body["stream"]) == ("none", {"type": "json_object"}, False)
    assert body["max_completion_tokens"] == 65536
    assert reply == openai.Reply('{"data": {}}', {"promptTokens": 23790, "outputTokens": 4131, "thinkingTokens": 13688})


def test_sends_requested_luna_reasoning_effort():
    requests = []
    model = client(lambda request: (requests.append(request) or httpx.Response(200, json=completion())), "low")

    model.chat("표에서 공급행을 정리", later())

    assert json.loads(requests[0].content)["reasoning_effort"] == "low"


def test_rejects_unsupported_reasoning_effort():
    with pytest.raises(ValueError, match="Luna reasoning effort"):
        openai.OpenAI("test-key", reasoning_effort="pro")


def test_reads_reply_after_keep_alive_blank_lines():
    reply = client(lambda request: httpx.Response(200, content=b"\n\n\n" + json.dumps(completion()).encode())).chat("p", later())

    assert reply.content == '{"data": {}}'


def test_unexpected_finish_reason_is_not_a_success():
    with pytest.raises(PermanentError) as error:
        client(lambda request: httpx.Response(200, json=completion(finish_reason="tool_calls"))).chat("p", later())

    assert error.value.code == "MODEL_RESPONSE_INVALID"


def test_refusal_is_permanent_without_exposing_the_response():
    response = completion(content=None)
    response["choices"][0]["message"]["refusal"] = "private refusal text"

    with pytest.raises(PermanentError) as error:
        client(lambda request: httpx.Response(200, json=response)).chat("p", later())

    assert error.value.code == "MODEL_REJECTED"
    assert "private refusal text" not in error.value.message


def test_unexpected_usage_details_keep_the_reply():
    usage = {"prompt_tokens": 10, "completion_tokens": 7, "completion_tokens_details": ["reasoning"]}

    reply = client(lambda request: httpx.Response(200, json=completion(usage=usage))).chat("p", later())

    assert reply.usage == {"promptTokens": 10, "outputTokens": 7, "thinkingTokens": None}


def test_unknown_usage_is_none():
    reply = client(lambda request: httpx.Response(200, json=completion(usage=None))).chat("p", later())

    assert reply.usage == {"promptTokens": None, "outputTokens": None, "thinkingTokens": None}


@pytest.mark.parametrize("status", [408, 429, 500, 502, 503])
def test_rate_limit_and_server_errors_are_transient(status):
    with pytest.raises(TransientError) as error:
        client(lambda request: httpx.Response(status, text="raw upstream body")).chat("p", later())

    assert error.value.code == "MODEL_UNAVAILABLE"
    assert "raw upstream body" not in error.value.message


@pytest.mark.parametrize("status", [400, 401, 402, 422])
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
            yield json.dumps(completion()).encode()

    with pytest.raises(TransientError) as error:
        client(lambda request: httpx.Response(200, stream=KeepAlive())).chat("p", time.monotonic() + 0.2)

    assert error.value.code == "MODEL_TIMEOUT"


def test_silence_shorter_than_deadline_is_not_timeout(monkeypatch):
    # 읽기 제한보다 늦어도 작업 마감 안에 도착한 응답은 받아야 한다. 시간 제한은 MockTransport가 아니라 실제 연결에서만 걸린다.
    monkeypatch.setattr(openai, "READ_TIMEOUT", 0.1)

    class Slow(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            self.rfile.read(int(self.headers["Content-Length"]))
            time.sleep(0.5)
            payload = json.dumps(completion()).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Slow)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        reply = openai.OpenAI("test-key", base_url=f"http://127.0.0.1:{server.server_port}").chat("p", later())
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
            openai.OpenAI("test-key", base_url=f"http://127.0.0.1:{server.server_port}").chat("p", started + 1)
        elapsed = time.monotonic() - started
    finally:
        server.shutdown()

    assert chunk_sent.is_set()
    assert error.value.code == "MODEL_TIMEOUT"
    assert elapsed < 1.3, f"마감 1초를 {elapsed:.2f}초까지 넘겼다"


@pytest.mark.parametrize("payload, code", [
    (completion(finish_reason="length"), "MODEL_RESPONSE_TRUNCATED"),
    (completion(content="  "), "MODEL_RESPONSE_EMPTY"),
    (completion(content=None), "MODEL_RESPONSE_EMPTY"),
    ({"choices": []}, "MODEL_RESPONSE_INVALID"),
    ({"choices": [{"message": "not an object"}]}, "MODEL_RESPONSE_INVALID"),
    (completion(finish_reason="content_filter"), "MODEL_REJECTED"),
])
def test_unusable_replies_are_permanent(payload, code):
    with pytest.raises(PermanentError) as error:
        client(lambda request: httpx.Response(200, json=payload)).chat("p", later())

    assert error.value.code == code


def test_non_json_body_is_permanent():
    with pytest.raises(PermanentError) as error:
        client(lambda request: httpx.Response(200, text="<html>gateway</html>")).chat("p", later())

    assert error.value.code == "MODEL_RESPONSE_INVALID"
