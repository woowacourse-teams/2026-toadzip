# 알림함과 웹 푸시 화면

서버의 대상 선정·중복·전달 계약은 [알림 생성·전달](delivery.md), 관심 대상 신청·해제는 [알림 설정](settings.md)을 따른다.

아래 코드·빌드 경로는 `frontend/` 기준이다. Docker 빌드의 `.env`는 저장소 루트를 기준으로 한다.

공개 탐색·알림함·알림 관리 경로는 하나의 알림 Provider를 공유한다. 회원·visible·online인 동안 60초마다 개수만 조회하고 로그인·탭 복귀·FCM 수신 때 갱신한다. 알림함 GET은 읽음으로 바꾸지 않는다.
푸시를 거부하거나 지원하지 않는 환경에서도 보관함을 이용할 수 있다.

`.env.example`의 공개 Firebase 설정과 `VITE_NOTIFICATION_PUSH_ENABLED`를 사용한다. 기본 false이며 미설정 시 권한 팝업을 띄우지 않는다.
비공개 Firebase 키는 브라우저에 넣지 않는다. 권한은 설정의 명시적 버튼에서만 요청한다.
끄기·로그아웃은 서버 기기 바인딩을 해제한다. 탭·계정이 공유하는 Firebase 설치는 삭제하지 않으며, 서버 로그아웃 성공 즉시 화면 상태를 비운다.
로컬 Vite는 `frontend/.env`, Docker 빌드는 루트 `.env`의 웹 푸시 공개 설정을 사용한다. Docker 설정 변경 후에는 frontend 이미지를 다시 빌드한다.
SDK는 13.0.0의 register/onRegistered(FID)를 사용하며 기존 registration token API를 사용하지 않는다.

`npm run dev`는 worker를 `.worker-dev/`에 먼저 빌드하고 개발 서버에서 같은 origin의 `/firebase-messaging-sw.js`로 제공한다.
worker 코드·환경 변수를 수정하면 개발 서버를 재시작한다. production build는 앱 후 worker를 IIFE 단일 파일로 생성한다.
Nginx는 worker를 JavaScript MIME/no-cache로 제공하고 파일이 없으면 404를 반환한다.
manifest는 기존 512px 브랜드 아이콘을 재사용한다. iPhone/iPad는 iOS 16.4 이상·홈 화면 설치가 필요하다.

실제 프로젝트의 권한·전경·배경·페이지 종료·기기 2대·계정 교체·배포 후 worker 갱신 검증은 별도로 필요하다.
[서버 운영·검증 범위](delivery.md)를 따른다.
