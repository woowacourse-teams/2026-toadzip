# 환경별 설정

## 사전 준비

- Git
- Docker
- Docker Compose
- Docker Buildx

- [로컬 환경](LOCAL_SETUP.md)
- [개발 서버](DEV_SERVER_SETUP.md)
- [운영 서버](PROD_SERVER_SETUP.md)
- [DB 서버](../infra/db/SETUP.md)
- [모니터링 서버](MONITORING_SERVER_SETUP.md)


## 공고 첨부파일 다운로드·미리보기

- `ANNOUNCEMENT_ATTACHMENT_MAX_SIZE=100MB`: 기본 100 MiB(104,857,600바이트). Docker Compose가 백엔드에 전달하며 환경변수를 생략해도 같은 기본값이다. 이 제한은 PDF·HWP·HWPX를 포함한 원본 다운로드와 PDF 미리보기에 공통 적용된다.
- 외부 파일을 최대 120초 동안 임시파일로 수신한다. 헤더와 실제 수신량 모두 검사하며 완료·오류·전송 중 연결 종료 시 임시파일을 정리한다. 파일 전체를 Java 힙에 적재하지 않는다. 동시 요청 4개가 응답 전송을 마칠 때까지 슬롯을 유지하므로 서버 임시 디스크에 최대 약 400 MiB와 여유 공간을 확보한다. JVM 임시 디렉터리는 기본 `java.io.tmpdir`이다.
- 이 제한은 다운로드 응답 제한이다. Nginx `client_max_body_size`나 Spring multipart 업로드 제한을 높이는 것으로 해결되지 않는다.
- PDF.js worker `.mjs`는 `application/javascript`여야 한다. HWP·HWPX는 브라우저 worker와 WASM으로 표시하므로 별도 변환 서버·API 키가 필요하지 않다. 원본 서식과 완전히 같지는 않으며 암호·손상 파일 또는 지원되지 않는 구조는 다운로드를 안내한다.

변경이 develop에 병합된 뒤 개발 서버에서 다음 명령으로 백엔드와 프론트엔드 모두 재배포한다.

```bash
git pull --ff-only origin develop
docker compose up -d --build --no-deps backend frontend
```

배포 후 기존 413 파일이 200으로 응답하는지, PDF worker의 MIME, HWP·HWPX 페이지 표시와 원본 다운로드를 확인한다. 한도를 바꾸려면 `.env` 값을 수정하고 백엔드 컨테이너를 재생성한다.
