import json
import time

import httpx
import pytest

from ai_worker import deepseek
from ai_worker.extraction import PermanentError, TransientError

USAGE = {"prompt_tokens": 23790, "completion_tokens": 17819, "completion_tokens_details": {"reasoning_tokens": 13688}}


def completion(content='{"data": {}}', finish_reason="stop", usage=USAGE) -> dict:
    return {"choices": [{"message": {"content": content}, "finish_reason": finish_reason}], "usage": usage}


def later() -> float:
    return time.monotonic() + 60


def client(handler) -> deepseek.DeepSeek:
    return deepseek.DeepSeek("test-key", base_url="https://deepseek.test", transport=httpx.MockTransport(handler))


def test_sends_prompt_with_low_reasoning_and_maps_usage():
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json=completion())

    reply = client(handler).chat("공고문 쪽 텍스트", later())

    [request] = requests
    body = json.loads(request.content)
    assert request.url == "https://deepseek.test/chat/completions"
    assert request.headers["Authorization"] == "Bearer test-key"
    assert body["model"] == deepseek.MODEL
    assert body["messages"] == [{"role": "user", "content": "공고문 쪽 텍스트"}]
    assert (body["reasoning_effort"], body["response_format"], body["stream"]) == ("low", {"type": "json_object"}, False)
    assert reply == deepseek.Reply('{"data": {}}', {"promptTokens": 23790, "outputTokens": 4131, "thinkingTokens": 13688})


def test_reads_reply_after_keep_alive_blank_lines():
    reply = client(lambda request: httpx.Response(200, content=b"\n\n\n" + json.dumps(completion()).encode())).chat("p", later())

    assert reply.content == '{"data": {}}'


def test_insufficient_system_resource_is_transient():
    with pytest.raises(TransientError) as error:
        client(lambda request: httpx.Response(200, json=completion(finish_reason="insufficient_system_resource"))).chat("p", later())

    assert error.value.code == "MODEL_UNAVAILABLE"


def test_unexpected_usage_details_keep_the_reply():
    usage = {"prompt_tokens": 10, "completion_tokens": 7, "completion_tokens_details": ["reasoning"]}

    reply = client(lambda request: httpx.Response(200, json=completion(usage=usage))).chat("p", later())

    assert reply.usage == {"promptTokens": 10, "outputTokens": 7, "thinkingTokens": None}


def test_unknown_usage_is_none():
    reply = client(lambda request: httpx.Response(200, json=completion(usage=None))).chat("p", later())

    assert reply.usage == {"promptTokens": None, "outputTokens": None, "thinkingTokens": None}


@pytest.mark.parametrize("status", [429, 500, 502, 503])
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


@pytest.mark.parametrize("payload, code", [
    (completion(finish_reason="length"), "MODEL_RESPONSE_TRUNCATED"),
    (completion(content="  "), "MODEL_RESPONSE_EMPTY"),
    (completion(content=None), "MODEL_RESPONSE_EMPTY"),
    ({"choices": []}, "MODEL_RESPONSE_INVALID"),
])
def test_unusable_replies_are_permanent(payload, code):
    with pytest.raises(PermanentError) as error:
        client(lambda request: httpx.Response(200, json=payload)).chat("p", later())

    assert error.value.code == code


def test_non_json_body_is_permanent():
    with pytest.raises(PermanentError) as error:
        client(lambda request: httpx.Response(200, text="<html>gateway</html>")).chat("p", later())

    assert error.value.code == "MODEL_RESPONSE_INVALID"
