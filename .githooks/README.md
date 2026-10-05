# Git 훅

커밋 시 저장소 규칙과 메시지 형식을 검사합니다.

## 파일 구성

| 파일 | 역할 |
| --- | --- |
| [pre-commit](pre-commit) | 로컬 계획·설계 자료의 Git 추적을 막고 필수 문서·에이전트 설정을 검사합니다. |
| [commit-msg](commit-msg) | 커밋 메시지의 타입, 한글 요약과 이슈 번호 형식을 검사합니다. |

검사 구현은 [scripts/](../scripts/README.md), 메시지 규칙은 [기여 가이드](../CONTRIBUTING.md)에 있습니다.

## 로컬 적용

저장소 루트에서 Git 훅 경로를 설정합니다.

```sh
git config core.hooksPath .githooks
```
