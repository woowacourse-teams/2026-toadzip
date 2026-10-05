# 🐸 공공주택 복덕방

> 살고 싶은 공공주택을 미리 편하게 찾고, 모집이 시작되면 놓치지 않도록 돕는 서비스

**🏠 내 조건에 맞는 공공주택, 이제 놓치지 마세요!**

공공주택 공고는 여러 기관에 흩어져 있고, 복잡한 자격 조건과 모집 일정까지 직접 확인해야 합니다.

**공공주택 복덕방** 은 원하는 공공주택을 편리하게 찾아보고, 관심 있는 주택의 모집이 시작되면 놓치지 않도록 돕기 위해 만들어졌습니다.

흩어진 공고를 찾아다니지 말고, 공공주택 복덕방에서 살고 싶은 집을 미리 찾아보세요.

---

## 레포지토리 구조

| 디렉터리 | 역할 |
| --- | --- |
| [frontend/](frontend/README.md) | 공공주택 지도·검색, 공고·단지 상세, 사용자·관리자 화면을 제공하는 프론트엔드입니다. |
| [backend/](backend/README.md) | 공공주택·공고 조회, 데이터 수집·정제, 로그인·알림 신청을 처리하는 백엔드입니다. |
| [infra/](infra/README.md) | DB, 백업, HTTPS 인증서, 로그·메트릭 모니터링 구성을 관리합니다. |
| [docs/](docs/README.md) | 환경별 실행 방법, 서비스 정책, API 안내와 운영용 SQL·예시 데이터를 관리합니다. |
| [scripts/](scripts/README.md) | 저장소 규칙 검사, HTTPS·DB 점검과 운영 보조 스크립트를 관리합니다. |
| [tests/](tests/README.md) | 저장소 규칙 검사 도구와 DB 백업 자동화의 테스트를 관리합니다. |
| [tmp/](tmp/README.md) | 로고 비교 시안, 이전 자산과 생성 스크립트 등 로컬 작업 자료를 보관합니다. |
| [.github/](.github/README.md) | GitHub Actions 워크플로와 이슈·PR 템플릿을 관리합니다. |
| [.githooks/](.githooks/) | 커밋 전 저장소 규칙과 커밋 메시지를 검사하는 Git 훅을 관리합니다. |
| [.codex/](.codex/) | 코드 탐색·설계 검토·리뷰를 맡는 에이전트 역할 설정을 관리합니다. |

### 루트의 주요 파일

| 파일 | 역할 |
| --- | --- |
| [SERVICE_OVERVIEW.md](SERVICE_OVERVIEW.md) | 서비스 목적, 핵심 기능과 용어를 설명합니다. |
| [CONTRIBUTING.md](CONTRIBUTING.md), [AGENTS.md](AGENTS.md) | 기여·Git 규칙과 에이전트의 작업 지침을 안내합니다. |
| [compose.yaml](compose.yaml) | 백엔드·프론트엔드·로그 수집기의 기본 실행 구성을 정의합니다. |
| [compose.local.yaml](compose.local.yaml), [compose.test.yaml](compose.test.yaml) | 로컬 개발 환경과 테스트용 PostgreSQL 구성을 정의합니다. |
| [compose.monitoring.yaml](compose.monitoring.yaml) | 로그·메트릭 수집과 모니터링 화면을 실행합니다. |
| [compose.https.yaml](compose.https.yaml), [compose.certbot.yaml](compose.certbot.yaml) | HTTPS 서비스와 인증서 발급·갱신 구성을 정의합니다. |
| [.env.example](.env.example) | 환경 변수의 이름과 설정 예시를 제공합니다. |
| [.editorconfig](.editorconfig), [.gitattributes](.gitattributes), [.gitignore](.gitignore) | 편집 형식, Git 파일 속성과 추적 제외 대상을 정의합니다. |

---

## 📚 문서

- [개발 환경 설정 및 실행](docs/SETUP.md)
- [통합 검색 API](docs/INTEGRATED_SEARCH.md)
- [기여 가이드](CONTRIBUTING.md)
- [백엔드 코드 컨벤션](backend/CODE_CONVENTION.md)

---

## 🧑‍💻 Team

<table style="width:100%">
  <thead>
    <tr>
      <th style="width:20%">Backend</th>
      <th style="width:20%">Backend</th>
      <th style="width:20%">Backend</th>
      <th style="width:20%">Backend</th>
      <th style="width:20%">Backend</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td align="center" style="width:20%">
        <a href="https://github.com/symflee">
          <img src="https://github.com/symflee.png" style="width:100%"/><br/>
          나무
        </a>
      </td>
      <td align="center" style="width:20%">
        <a href="https://github.com/koomingu">
          <img src="https://github.com/koomingu.png" style="width:100%"/><br/>
          밍구
        </a>
      </td>
      <td align="center" style="width:20%">
        <a href="https://github.com/JunHyung1206">
          <img src="https://github.com/JunHyung1206.png" style="width:100%"/><br/>
          녀녕
        </a>
      </td>
      <td align="center" style="width:20%">
        <a href="https://github.com/toctoce">
          <img src="https://github.com/toctoce.png" style="width:100%"/><br/>
          봉구스
        </a>
      </td>
      <td align="center" style="width:20%">
        <a href="https://github.com/Jaeminjeong1">
          <img src="https://github.com/Jaeminjeong1.png" style="width:100%"/><br/>
          제이크
        </a>
      </td>
    </tr>
  </tbody>
</table>

---

## 📬 Contact

[![Gmail](https://img.shields.io/badge/Email-toadzip.official%40gmail.com-EA4335?style=for-the-badge&logo=gmail&logoColor=white)](mailto:toadzip.official@gmail.com)
