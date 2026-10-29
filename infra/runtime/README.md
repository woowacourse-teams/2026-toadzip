# 배포 런타임

Blue/Green 배포 중 변경되는 서버 상태를 보관한다. 개인 메모·로컬 실험 자료와 구분한다.
설정 원본은 [`../nginx/edge/default.conf`](../nginx/edge/default.conf)로 관리하고 다음 명령으로 준비한다.

```shell
python3 -B scripts/prepare-bluegreen.py
```

`bluegreen/`은 생성 자료이므로 Git에서 제외한다.

| 경로 | 역할 |
| --- | --- |
| `bluegreen/edge/default.conf` | 현재 프론트·백엔드 연결 색상. Docker에 읽기 전용으로 연결 |
| `bluegreen/assets/` | 이전·현재 프론트의 해시 정적 파일 |
| `bluegreen/deploy.lock` | 수동·CI 배포의 공통 잠금 |
| `bluegreen/*.conf.bak` | 전환 이전 Nginx 설정 |
| `bluegreen/releases/` | 이미지 ID·커밋·연결 색상·성공 또는 실패 기록 |

준비 명령은 기존 연결 색상과 정적 파일을 덮어쓰거나 지우지 않는다. 다른 경로에서 이전할 때는
`--from-config /기존/edge/default.conf`로 최초 복사 원본을 지정할 수 있다. 원본은 이동·삭제하지 않는다.
기존 Edge 컨테이너는 새 디렉터리 마운트로 별도 전환해야 하며 준비 명령은 Docker를 실행하지 않는다.
서버 적용은 [배포 운영](../../docs/operations/deployment.md)을 따른다.
