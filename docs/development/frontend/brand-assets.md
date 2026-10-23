# 복덕방 로고 파일

아래 자산·소스 경로는 `frontend/` 기준이다.

사용자가 제공한 `mark.svg`를 기준으로 초록색 집·돋보기 형태와 웃는 표정을 유지했다. 지붕과 양쪽 어깨는 반지름 18, 하단 모서리는 반지름 24의 접선 원호로 연결하고, 선 끝과 연결부는 round로 처리했다. 수치는 164×164 SVG 좌표 기준이다.

## 서비스에서 사용하는 파일

| 파일 | 용도 |
| --- | --- |
| `public/logo-bok-search.svg` | 공통 로고와 첫 방문 안내의 편집 원본. 기존 공통 로고 URL을 유지하며 SVG 파비콘으로도 사용한다. |
| `public/brand/favicon-mark.png` | 원본 SVG에서 만든 투명 배경의 512px 파비콘 |
| `public/favicon-32.png` | 32px PNG 파비콘 |
| `public/favicon.ico` | 16px·32px·48px 브라우저 호환용 아이콘 |
| `public/apple-touch-icon.png` | 흰 배경의 180px 홈 화면 아이콘 |
| `public/brand/social-card.svg`, `public/brand/social-card.png` | 새 로고를 포함한 1200×630 링크 공유 이미지 |

색상은 첨부 원본의 외곽선 `#00b862`, 표정 `#0e2a1e`를 유지한다. 서비스 UI의 브랜드 토큰은 별도로 유지한다. 로고의 가로·세로 비율을 변형하지 않는다.

공통 로고는 40×40px, 지도 왼쪽 메뉴에서는 44×44px(좁은 화면에서는 38×38px)로 표시한다. 첫 방문 안내에서는 88×88px, 작은 화면에서는 64×64px로 표시한다. 모바일 하단 내비게이션의 기존 로고 숨김 동작은 유지한다.

`index.html`에서 SVG·PNG·ICO 파비콘과 Apple touch 아이콘을 연결한다. 버전 쿼리 `20261008-rounded-mark`로 이전 아이콘 캐시와 구분한다. PNG는 SVG를 렌더링한 결과이며 ICO는 512px PNG에서 크기별로 생성한다. Apple touch 아이콘만 흰 배경을 합성한다.

## 링크 미리보기

공유 이미지의 SVG는 같은 로고 원본을 내장한다. SVG 수정 후 PNG도 함께 렌더링한다. `index.html`의 Open Graph와 Twitter Card는 `http://bokduckbang.com/brand/social-card.png?v=20261008-rounded-mark`를 사용한다. 운영 URL을 HTTPS로 전환할 때 관련 메타 태그의 프로토콜도 함께 바꾼다.

## 이전 자산

`public/brand/`의 `logo-bok*`, `logo-on-dark*`, `toad-*`, `app-icon*`, `favicon-toad.png`와 `src/assets/brand/`의 이전 이미지는 과거 디자인 자산이다. 현재 화면과 파비콘에서는 사용하지 않는다. 새 사용처에는 `public/logo-bok-search.svg`를 사용한다.

## 랜딩페이지 서비스 화면

`public/landing/`의 JPEG는 2026-10-12에 공개 운영 서비스 `https://bokduckbang.com/`에서 비로그인으로 직접 캡처한 1280×720 화면이다. 로그인 정보나 회원 데이터를 포함하지 않는다.

- `explore.jpg`: 공공주택 지도와 임대조건 마커
- `filters.jpg`: 보증금·월 임대료 가격 필터
- `announcement.jpg`: 모집 공고 목록과 공고 상세
- `notifications.jpg`: 서울역 센트럴자이 단지 상세의 `알림 받기` 버튼

지도 출처 표기를 포함한 캡처 원본을 보존한다. 사용법의 `explore@2x.jpg`, `filters@2x.jpg`, `announcement@2x.jpg`, `notifications@2x.jpg`는 같은 공개 서비스의 렌더링된 DOM과 원본 스타일을 2배로 다시 렌더링한 2560×1440 이미지다. 검색창·필터·공고·알림 UI의 글자와 도형을 고해상도로 렌더링했으며, 지도 배경은 실제 지도 캡처의 해상도를 유지한다. 회원 정보나 가상 데이터는 추가하지 않았다. 랜딩에서는 1280×720 논리 좌표의 CSS로 핵심 영역을 확대하며, 프레임 아래에도 지도 출처를 표시한다. 화면이나 공고 정보가 바뀌면 같은 공개 화면에서 다시 캡처하고 확대·강조 좌표를 함께 확인한다. 이 이미지는 기능 소개용이며 현재 모집 여부를 보증하지 않는다.
