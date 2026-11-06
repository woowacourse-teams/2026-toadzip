import json

import httpx
import pytest

from ai_worker import jev


def client(handler):
    return jev.Jev("test-key", transport=httpx.MockTransport(handler))


def test_count_meanings_sends_one_choice_per_column_and_reads_the_chosen_probability():
    sent = {}

    def handler(request):
        sent.update(json.loads(request.content))
        assert request.headers["Authorization"] == "Bearer test-key"
        return httpx.Response(200, json={"answers": {"column_4": {
            "choice": "new_reserve_this_notice",
            "probabilities": {"new_reserve_this_notice": 0.91, "existing_waitlist": 0.09}}}})

    result = client(handler).count_meanings({"table": {}}, {"column_4": "모집호수 > 예비자"}, reserve_only=True)

    assert result == {"column_4": jev.Meaning("new_reserve_this_notice", 0.91)}
    question = sent["questions"]["column_4"]
    assert sent["model"] == jev.MODEL and "`모집호수 > 예비자`" in question["instructions"]
    # 예비자만 모집하는 공고에서 당첨자 선택지는 정답일 수 없어 빼고 묻는다.
    assert not set(jev.WINNER_OPTIONS) & set(question["criteria"])


def test_winner_options_stay_for_a_notice_that_also_selects_winners():
    sent = {}

    def handler(request):
        sent.update(json.loads(request.content))
        return httpx.Response(200, json={"answers": {"c": {"choice": "winners_priority_share",
                                                          "probabilities": {"winners_priority_share": 1.0}}}})

    client(handler).count_meanings({}, {"c": "모집호수 > 당첨자 > 우선공급"}, reserve_only=False)

    assert set(jev.WINNER_OPTIONS) <= set(sent["questions"]["c"]["criteria"])


@pytest.mark.parametrize("response", [
    httpx.Response(403, json={"detail": "bad key"}),
    httpx.Response(200, json={"answers": {}}),
    httpx.Response(200, text="not json"),
    httpx.Response(200, text='{"answers": {"c": {"choice": "x", "probabilities": {"x": NaN}}}}'),
], ids=["rejected", "missing-answer", "not-json", "nan-probability"])
def test_failed_or_unreadable_answers_raise_instead_of_returning_a_judgment(response):
    with pytest.raises(jev.JevError):
        client(lambda request: response).count_meanings({}, {"c": "모집호수"}, reserve_only=False)


def test_connection_failure_raises_jev_error():
    def handler(request):
        raise httpx.ConnectError("down")

    with pytest.raises(jev.JevError):
        client(handler).count_meanings({}, {"c": "모집호수"}, reserve_only=False)


def test_header_paths_follow_horizontal_and_vertical_merges():
    cells = [
        ["주택형", "공급계층", "모집호수", None, None, None],
        [None, None, "당첨자", None, None, "예비자"],
        [None, None, "계", "우선공급", "일반공급", None],
    ]

    assert jev.header_paths(cells, 3, 6) == [
        "주택형", "공급계층", "모집호수 > 당첨자 > 계", "모집호수 > 당첨자 > 우선공급",
        "모집호수 > 당첨자 > 일반공급", "모집호수 > 예비자"]


def test_reserve_notice_is_recognized_from_the_first_pages():
    assert jev.is_reserve_notice(["'26년 하반기 경기행복주택 예비입주자 추가모집\n공고"])
    assert not jev.is_reserve_notice(["부산문현2 1블록 행복주택 입주자 모집"])


def test_request_timeout_is_capped_by_the_remaining_time():
    seen = {}

    def handler(request):
        seen.update(request.extensions["timeout"])
        return httpx.Response(200, json={"answers": {"c": {"choice": "x", "probabilities": {"x": 1.0}}}})

    client(handler).count_meanings({}, {"c": "모집호수"}, reserve_only=False, timeout=2.5)

    assert seen["read"] == 2.5 and seen["connect"] == 2.5
