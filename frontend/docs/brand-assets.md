# BOK 두꺼비 로고 파일

첨부한 BOK 두꺼비 로고를 기준으로 형태를 벡터로 재구성했다. 원본 이미지의 질감은 제거하고 평면 색상으로 정리했으며, 글자와 얼굴은 모두 경로로 그려 폰트 설치 없이 사용할 수 있다.

## 실제 서비스에 연결한 파일

| 파일 | 용도 |
| --- | --- |
| `public/logo-bok-search.svg` | 홈, 로그인, 관리자 화면의 공통 BOK 로고. 기존 URL을 유지했으며 내용은 두꺼비 로고다. |
| `public/favicon.svg` | 크기별로 확대 가능한 두꺼비 파비콘 |
| `public/favicon-16.png`, `favicon-32.png`, `favicon-48.png` | 크기가 고정된 파비콘 |
| `public/favicon.ico` | 16·32·48px를 함께 포함한 브라우저 호환용 파일 |
| `public/apple-touch-icon.png` | iOS 홈 화면용 180px 아이콘, 흰 배경 |

`index.html`에서 파비콘과 Apple touch 아이콘을 연결한다. 서비스 로고는 가로 88px, 세로 34px 상자 안에 비율을 유지해 표시한다. 새 로고의 가로로 긴 비율에 맞춰 너비를 조정했다.

## 로고 배포 파일: `public/brand/`

| 파일 | 용도 |
| --- | --- |
| `logo-bok-512.png`, `logo-bok-1220.png`, `logo-bok-2440.png` | 투명 배경의 기본 로고. 숫자는 가로 픽셀 크기다. |
| `logo-bok-white.png` | 흰 배경의 기본 로고, 가로 2440px |
| `logo-on-dark.svg`, `logo-on-dark.png` | 어두운 배경용. 글자와 얼굴 윤곽이 밝은 크림색인 컬러 로고 |
| `logo-monochrome.svg`, `logo-monochrome.png` | 한 가지 짙은 색으로 인쇄하는 로고. 눈과 입은 투명한 여백 |
| `logo-white.svg`, `logo-white.png` | 단색 흰색 로고, 투명 배경 |
| `social-card.svg`, `social-card.png` | 공유·소개용 1200×630 흰 배경 이미지. 공유 메타 태그에는 아직 연결하지 않았다. |

기본 로고의 편집 원본은 `public/logo-bok-search.svg`다. SVG는 확대해도 선명하며, PNG는 SVG에서 만든 배포본이다.

## 심볼과 작은 아이콘: `public/brand/`

| 파일 | 용도 |
| --- | --- |
| `toad-symbol.svg`, `toad-symbol-{128,256,512,1024}.png` | 볼터치와 배 무늬를 유지한 두꺼비 얼굴 심볼 |
| `toad-icon.svg`, `toad-icon-{24,32,48,64,128,256,512}.png` | 볼터치와 배 무늬를 없앤 작은 UI용 얼굴 아이콘 |
| `toad-icon-monochrome.svg`, `toad-icon-monochrome-512.png` | 단색 아이콘, 눈과 입은 투명한 여백 |
| `toad-icon-white.svg`, `toad-icon-white-512.png` | 어두운 바탕에 쓰는 단색 흰색 아이콘 |
| `app-icon.svg`, `app-icon-{192,512}.png` | 흰 배경의 정사각형 앱·프로필 아이콘 |
| `app-icon-maskable.svg`, `app-icon-maskable-512.png` | 원형 등으로 잘려도 얼굴이 남도록 안전 여백을 둔 아이콘 |

앱 아이콘은 파일만 준비했다. 웹 앱 manifest나 설치 기능은 추가하지 않았다.

## 색과 크기 선택

- B·K와 외곽선: `#1e2124`
- 얼굴 민트: `#87dda9`
- 눈과 배의 크림색: `#f6fbe8`
- 볼터치: `#ffa699`
- 눈동자와 입: `#103735`

색상은 원본 이미지를 평면으로 정리한 값이다. 기존 UI 브랜드 컬러 `#00c96c`는 변경하지 않았다.

16~48px 파비콘은 기본 심볼을 그대로 축소하지 않고 볼터치·배 무늬를 빼고 눈·입의 비율을 키웠다. 작은 UI에는 `toad-icon`, 충분히 큰 캐릭터 표시에는 `toad-symbol`을 사용한다. 단색 버전의 눈·입과 B의 내부는 투명하므로 배경색이 드러난다. 컬러 로고를 임의로 한 색으로 덮거나 가로·세로 비율을 변형하지 않는다.

첨부 원본과 이전 활성 로고·파비콘, 생성 스크립트, 전체 미리보기는 저장소 루트 `tmp/brand-assets/`에 보관한다. 배포 파일과 이 안내를 묶은 ZIP은 같은 폴더의 `bok-brand-assets.zip`이다.
