# 실제 공개 공식 PDF 검증

검증 원본의 게시 페이지·다운로드 URL·SHA256·페이지 수는 [manifest](../scripts/admin-public-documents.json)에 기록한다.
LH 통합 사전안내 3쪽, GH 동탄2 A105 모집공고 48쪽과 임대료 산정표 1쪽을 사용한다. 과거 공개 문서는 형식 검증용이며 현재 모집 정보로 서비스에 등록하지 않는다.
PDF 원본과 이미지 파생본·검증 출력은 각 checkout의 `.local/`에만 보관한다.

manifest URL에서 원본을 내려받아 다음을 실행한다. 실행 중 네트워크 수집이나 DB 수정은 하지 않는다.

```powershell
node frontend/scripts/verify-admin-public-pdfs.mjs .local/<검증폴더>/lh-2022-notice.pdf .local/<검증폴더>/gh-2023-notice.pdf .local/<검증폴더>/gh-rent-table.pdf
```

이 도구는 실제 PDF.js와 제품의 페이지 추출·표 셀·금액 변환 코드를 사용해 다음을 검사한다.

- manifest 원본의 해시, 페이지 수와 기대 한글 텍스트
- 전체 52쪽의 텍스트·좌표 및 행 추출
- 산정표의 원 단위 보증금 셀 분리와 정수 문자열 변환

원본이 변경되면 해시 검사에서 멈춘다. 변경된 원문을 검토한 뒤 manifest를 갱신한다.
추출 성공은 OCR·복잡한 표의 의미를 모두 이해했다는 뜻이 아니다. 공급호수·임대시세·보증금·월임대료와 단지 전체 값을 혼용하지 않는지 원문을 대조한다.

브라우저 검증에서는 같은 공식 표 PDF를 공유 초안으로 복원해 표·텍스트를 확인한다.
OCR 경로는 실제 표 페이지를 이미지 전용 PDF로 변환한 파생본으로 검사하며, 원본이 스캔 문서였다고 표시하지 않는다.
OCR 인식 점수·숫자·단위는 별도로 기록하고 원문을 사람이 대조한다. 모든 기관·스캔 품질·표 구조를 보증하는 검사는 아니다.

## 이미지 표의 OCR 회귀 확인

같은 GH 표의 텍스트 없는 이미지 전용 PDF를 준비한 뒤 실행한다. 언어 모델은 `npm run build`가 준비한 로컬 OCR 자산을 사용하며 외부로 PDF를 보내지 않는다.

```powershell
node frontend/scripts/verify-admin-table-ocr.mjs .local/<검증폴더>/gh-rent-table.pdf .local/<검증폴더>/gh-table-image-only.pdf
```

이 도구는 원본 해시와 이미지 PDF의 텍스트 층 부재를 확인한다. 기존 144dpi 단일 문단 인식과 제품의 최대 400dpi 영역별 인식을 비교한다. 원본 표의 임대시세·보증금·월임대료 10행을 기준으로 금액 문자열과 행별 배치를 검사한다. 두 방식은 각각 새 worker에서 실행한다.

Tesseract의 [해상도와 영역 구분 지침](https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html)을 참고한다. 제품은 여러 영역을 인식하는 모드와 글자 좌표를 사용한다. 좌표는 읽는 순서를 복원하는 데만 쓰며 표의 의미나 금액을 추정하지 않는다. 인식 점수는 숫자의 정확도를 보증하지 않는다.
