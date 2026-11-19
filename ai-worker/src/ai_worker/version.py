"""워커 버전. 같은 PDF·입력·버전으로 성공한 작업의 결과를 다시 쓰는 기준이다.

손으로 올리는 번호는 프롬프트나 검산을 고치고 올리는 것을 잊으면 옛 결과를 새 것처럼 쓰게 된다.
그래서 이 패키지의 소스 파일(프롬프트 포함) 내용과 결과를 바꾸는 실행 설정으로 지문을 만든다. 무엇이든 고치면 버전이 바뀐다.
"""

import hashlib
from pathlib import Path

PACKAGE = Path(__file__).resolve().parent


def source_version(*settings: str) -> str:
    """소스 파일의 상대 경로와 내용, 그리고 settings를 차례로 넣은 SHA-256 앞 32자다. 캐시 파일은 넣지 않는다."""
    digest = hashlib.sha256()
    for path in sorted(PACKAGE.rglob("*")):
        if not path.is_file() or "__pycache__" in path.parts:
            continue
        digest.update(path.relative_to(PACKAGE).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    for setting in settings:
        digest.update(setting.encode())
        digest.update(b"\0")
    return digest.hexdigest()[:32]
