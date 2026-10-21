# 공용 문서

사람과 AI가 함께 사용하는 기술 문서의 시작점이다. 설치·개발·기능·운영 중 하려는 작업에 맞는 폴더에서 문서를 찾는다.

## 문서 구조

Git으로 관리하는 폴더와 역할을 표시한다. 기술 문서는 `setup/`, `development/`, `features/`, `operations/`로 분류하고, 개별 문서 목록은 각 폴더의 README에서 안내한다. `fixtures/`와 `queries/`는 관련 자료를 보관한다.

```text
docs/
├── setup/                      # 설치·환경변수 준비·최초 실행
├── development/                # 공통 API·문서 작성 등 구현·검증 기준
│   ├── backend/                # 계층·DB·보안·테스트·로그·계측
│   └── frontend/               # 화면 구조·상태·디자인·테스트·자산
├── features/                   # 현재 기능의 동작·정책·계약
│   ├── admin/                  # 관리자 조회·수정·비교·검토·JSON 등록
│   ├── announcements/          # 공고 기능·조회수 집계
│   ├── authentication/         # 소셜 로그인·계정 생명주기
│   ├── documents/              # 공고 첨부파일·다운로드·문서 뷰어
│   ├── ingest/                 # 수집·정제·매칭·원천 보존
│   ├── notifications/          # 알림 신청·생성·전달·사용자 화면
│   ├── privacy/                # 동의·수집 제어·저장·제거 경계
│   └── search/                 # 검색·지도·지역 데이터·거리뷰
├── operations/                 # 배포·관측·점검·복구 절차
│   ├── analytics/              # GA4·PostHog 설정·이벤트·지표
│   ├── database/               # DB 서버·Flyway·전환·복구
│   └── ingest/                 # 수집 중지·잠금 복구·품질·성능 점검
├── fixtures/                   # 문서·테스트가 사용하는 공고 JSON 예시
└── queries/                    # 알림 데이터·스키마 점검 SQL
```

## 폴더별 문서 안내

[환경 설정](setup/README.md) · [개발 기준](development/README.md) · [기능 명세](features/README.md) · [운영](operations/README.md) · [예시 입력](fixtures/README.md) · [점검 SQL](queries/README.md)

## 관련 문서

문서 추가·수정은 [문서 작성·관리 규칙](development/documentation.md)을 따른다. 서비스 목적은 [서비스 소개](../SERVICE_OVERVIEW.md), 기여 절차는 [기여 가이드](../CONTRIBUTING.md), AI 작업 지침은 [AGENTS](../AGENTS.md)에서 확인한다.
