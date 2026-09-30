# BOK 두꺼비 로고 파일

첨부한 BOK 두꺼비 로고를 기준으로 형태를 벡터로 재구성했다. 원본 이미지의 질감은 제거하고 평면 색상으로 정리했으며, 글자와 얼굴은 모두 경로로 그려 폰트 설치 없이 사용할 수 있다.

## 실제 서비스에 연결한 파일

| 파일 | 용도 |
| --- | --- |
| `public/logo-bok-search.svg` | 홈, 로그인, 관리자 화면의 공통 BOK 로고. 기존 URL을 유지했으며 내용은 두꺼비 로고다. |
| `public/brand/favicon-toad.png` | 초록 배경과 둥근 모서리를 가진 두꺼비 파비콘의 512px 원본 및 고해상도 파비콘 |
| `public/favicon-32.png` | 기본 32px PNG 파비콘 |
| `public/favicon.ico` | 같은 이미지의 16px·32px·48px 브라우저 호환용 파일 |
| `public/apple-touch-icon.png` | 같은 이미지의 iOS 홈 화면용 180px 아이콘 |

`index.html`에서 파비콘과 Apple touch 아이콘을 연결한다. 파비콘은 승인된 정사각형 두꺼비 이미지를 사용하며, 배경은 서비스 메인 색 `#00c96c`, 둥근 모서리 바깥은 투명하다. 기존 SVG 파비콘은 제거하고 PNG·ICO로 통일했다. 기존 파일명에는 버전 쿼리를 붙여 이전 아이콘 캐시와 구분한다. 서비스 로고는 가로 88px, 세로 34px 상자 안에 비율을 유지해 표시한다. 새 로고의 가로로 긴 비율에 맞춰 너비를 조정했다.

## 로고 배포 파일: `public/brand/`

| 파일 | 용도 |
| --- | --- |
| `logo-bok.png` | 투명 배경의 기본 로고, 가로 1220px |
| `logo-bok-white.png` | 흰 배경의 기본 로고, 가로 1220px |
| `logo-on-dark.svg`, `logo-on-dark.png` | 어두운 배경용. 글자와 얼굴 윤곽이 밝은 크림색인 컬러 로고 |
| `social-card.svg`, `social-card.png` | 링크 공유용 1200×630 이미지. `index.html`의 Open Graph와 Twitter Card 메타 태그에 연결했다. |

기본 로고의 편집 원본은 `public/logo-bok-search.svg`다. SVG는 확대해도 선명하며, PNG는 SVG에서 만든 배포본이다.

## 심볼과 작은 아이콘: `public/brand/`

| 파일 | 용도 |
| --- | --- |
| `toad-symbol.svg`, `toad-symbol.png` | 볼터치와 배 무늬를 유지한 얼굴 심볼, PNG는 512px |
| `toad-icon.svg`, `toad-icon.png` | 볼터치와 배 무늬를 없앤 작은 UI용 아이콘, PNG는 64px |
| `app-icon.svg`, `app-icon.png` | 흰 배경의 정사각형 앱·프로필 아이콘, PNG는 512px |
| `app-icon-maskable.svg` | 원형 등으로 잘려도 얼굴이 남도록 안전 여백을 둔 벡터 아이콘 |

앱 아이콘은 파일만 준비했다. 웹 앱 manifest나 설치 기능은 추가하지 않았다.

## 색과 크기 선택

- B·K와 외곽선: `#1e2124`
- 얼굴 민트: `#87dda9`
- 눈과 배의 크림색: `#f6fbe8`
- 볼터치: `#ffa699`
- 눈동자와 입: `#103735`

색상은 원본 이미지를 평면으로 정리한 값이다. 기존 UI 브랜드 컬러 `#00c96c`는 변경하지 않았다.

파비콘의 볼터치와 배 무늬는 승인된 이미지 그대로 유지한다. `public/brand/favicon-toad.png`를 원본으로 같은 비율의 크기별 PNG·ICO를 만든다. 작은 UI에는 `toad-icon`, 충분히 큰 캐릭터 표시에는 `toad-symbol`을 사용한다. 로고·UI 심볼 PNG는 기본 크기 하나만 보관하고 다른 크기가 필요하면 SVG를 사용한다. 컬러 로고를 임의로 한 색으로 덮거나 가로·세로 비율을 변형하지 않는다.

첨부 원본과 이전 활성 로고·파비콘, 미리보기는 저장소 루트 `tmp/brand-assets/`에 보관한다. 현재 배포 파일과 이 안내를 묶은 ZIP은 같은 폴더의 `bok-brand-assets.zip`이다.

## 링크 미리보기

`index.html`에 제목, 설명, 이미지 주소를 직접 넣어 JavaScript 실행 없이도 공유 서비스가 읽을 수 있게 했다. 이미지 URL은 현재 공유한 운영 주소 `http://bokduckbang.com/brand/social-card.png?v=20260930-centered`이며, 운영 URL을 HTTPS로 전환하면 `og:url`, `og:image`, `twitter:image`의 프로토콜도 함께 바꾼다. 모든 화면은 공통 서비스 미리보기를 사용한다.

배포 후 카카오톡에서 이전 이미지가 계속 나오면 [카카오 공유 디버거](https://developers.kakao.com/tool/debugger/sharing)에 공유 URL을 입력해 미리보기 캐시를 초기화한다. 서버의 HTML과 이미지가 외부에 공개되어 있어야 수집할 수 있다.
