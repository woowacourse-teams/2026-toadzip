# 로컬 에이전트 폴더 연결

PDF 선택·추출 후 `로컬 폴더로 에이전트 자동 연결`에서 전용 폴더를 선택한다.
이 기능은 폴더 쓰기를 지원하는 Chrome·Edge의 HTTPS 또는 localhost에서 사용한다.
지원하지 않는 브라우저나 권한 거부 시 기존 다운로드·결과 가져오기를 사용한다.

1. 사용하는 Codex CLI에 `codex login`으로 ChatGPT 로그인한다.
2. 전용 폴더를 만들고 다음 처리 도구를 실행한다. API 키 로그인은 도구가 거부한다.
3. 화면에서 같은 폴더를 선택하고 `요청 전달·결과 대기`를 누른다.
4. 요청 ID별 결과를 감지하면 대상·DB 버전·PDF 해시·허용 항목·단위·범위·인용을 검증해 가져온다.
5. 수정안은 원문 확인·선택·메모 후 저장한다. 자동 DB 수정은 수행하지 않는다.

```powershell
node scripts/admin-review-exchange.mjs <전용-폴더-절대경로> --watch
```

`--watch`를 빼면 현재 파일만 한 번 처리한다. Ctrl+C로 처리 도구를 종료한다.
Codex 실행 파일이 PATH에 있어야 한다. 실행은 현재 ChatGPT 사용 한도를 사용하며 별도 API 키를 전달하지 않는다.
[공식 실행 문서](https://learn.chatgpt.com/docs/non-interactive-mode),
[로그인 방식](https://learn.chatgpt.com/docs/auth)을 기준으로 한다.
도구는 CLI의 읽기 전용 모드·셸/하위 에이전트 비활성·출력 스키마를 사용한다.
사용자 설정과 프로젝트 문서를 요청에 추가하지 않고 선택 자료만 프롬프트로 전달한다.
요청·자료 속 지시는 실행하지 않으며 확정할 수 없는 값은 보류한다.

## 파일과 제한

- `review-{UUID}.request.json`: `document-exchange/v1`, `requestId`, 기존 에이전트 자료 `packet`.
- `review-{UUID}.result.json`: 같은 스키마·ID와 `document-review/v1` 형식의 `result`.
- `review-{UUID}.error.json`: 요청 ID와 처리 실패 안내. 새 요청으로 다시 시도한다.
- 요청 5MB·50쪽, 결과 1MB·수정안/보류 각 100개. Codex 처리 3분, 화면 결과 대기 10분.
- `.lock`은 중복 처리를 막으며 `.run-{UUID}`는 스키마·실행 결과를 보관한다.
- 요청·결과·검증 자료는 자동 삭제하지 않는다. 동일 요청을 반복 실행하지 않는다.
- 대기 중지는 화면의 감지만 멈추며 이미 시작된 로컬 처리는 유지한다.
- 늦게 도착한 결과·다른 요청·다른 버전은 자동 반영하지 않는다. 기존 수동 결과 가져오기로 확인한다.
- 원본 PDF 바이트는 전달하지 않으며 OCR·표의 숫자와 단위는 관리자가 다시 확인한다.

검증: `node --test tests/admin-review/exchange.test.mjs`, 프론트엔드의 `folderExchange`·`FolderExchangePanel` 테스트.
