# 🐸 공공주택 복덕방

> 살고 싶은 공공주택을 미리 편하게 찾고, 모집이 시작되면 놓치지 않도록 돕는 서비스

공공주택의 위치·임대 조건·모집 공고·신청 일정을 연결해 원하는 단지를 찾고 모집 시기를 확인하도록 돕는다.
서비스 목적과 기능은 [서비스 소개](SERVICE_OVERVIEW.md)를 참고한다.

## 시작하기

- [환경 설정](docs/SETUP.md)
- [백엔드 개발·운영](backend/docs/README.md)
- [서비스 API·데이터 운영](docs/README.md)
- [기여 규칙](CONTRIBUTING.md)

## 레포지토리 구조

| 디렉터리 | 역할 |
| --- | --- |
| [frontend/](frontend/README.md) | 공공주택 지도·검색, 공고·단지 상세, 사용자·관리자 화면을 제공하는 프론트엔드입니다. |
| [backend/](backend/README.md) | 공공주택·공고 조회, 데이터 수집·정제, 로그인·알림 신청을 처리하는 백엔드입니다. |
| [infra/](infra/README.md) | DB, 백업, HTTPS 인증서, 로그·메트릭 모니터링 구성을 관리합니다. |
| [docs/](docs/README.md) | 환경별 실행 방법, 서비스 정책, API 안내와 운영용 SQL·예시 데이터를 관리합니다. |
| [scripts/](scripts/README.md) | 저장소 규칙 검사, HTTPS·DB 점검과 운영 보조 스크립트를 관리합니다. |
| `.local/` | 개인 메모·계획·실험·검증 자료를 보관하는 Git 제외 폴더입니다. 사용 기준은 [기여 규칙](CONTRIBUTING.md#로컬-작업-자료)을 따릅니다. |
| [tmp/](tmp/README.md) | 기존 로고 비교 시안과 이전 자산의 보관 위치를 안내합니다. 새 개인 작업 자료는 `.local/`에 둡니다. |
| [.github/](.github/README.md) | GitHub Actions 워크플로와 이슈·PR 템플릿을 관리합니다. |
| [.codex/](.codex/) | 코드 탐색·설계 검토·리뷰를 맡는 에이전트 역할 설정을 관리합니다. |

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
