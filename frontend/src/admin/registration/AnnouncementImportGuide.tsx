import example from './announcementImportExample.json'
import { JsonGuide } from '../shared/JsonGuide'

const exampleText = JSON.stringify(example, null, 2)
const prompt = `첨부한 공식 공고문에서 신규 원공고 등록용 JSON을 작성해 주세요.
아래 예제와 같은 admin-announcement-import/v1 형식의 JSON 객체만 반환하세요.
예제의 이름·날짜·PNU·금액은 형식 설명용이므로 실제 공고에서 확인한 값으로 바꾸세요.
내부 DB ID를 만들지 말고, 원문에서 확인하지 못한 값은 null로 두고 unresolvedFields에 경로와 사유를 적으세요.
모집수와 단지 전체 세대수, 예비자 수를 구분하고 대상별 조건을 임의 합산하지 마세요.
필수값을 확인할 수 없으면 추정하지 마세요. 정정·취소공고라면 신규 등록용 JSON 대신 그 사실을 알려 주세요.
금액은 원 단위 정수, 날짜는 YYYY-MM-DD, 연월은 YYYY-MM, extractedAt에는 시간대 오프셋을 포함하세요.
전용면적·공급면적은 확인한 경우에만 exclusiveArea·supplyArea에 ㎡ 단위 소수로 넣으세요.
\n형식 예제:\n${exampleText}`

export function AnnouncementImportGuide({ updating = false }: { updating?: boolean }) {
  const updatePrompt = prompt.replaceAll('신규 원공고 등록용', '기존 공고 갱신용')
    + '\n기존 공고를 갱신합니다. 미확인 값으로 기존 데이터를 지우지 않으며, 백오피스에서 현재값과 비교한 뒤 확인한 항목만 적용합니다.'
  return <JsonGuide title={updating ? '공고 갱신 JSON 작성 방법과 전체 예제' : '신규 공고 JSON 작성 방법과 전체 예제'} example={exampleText} prompt={updating ? updatePrompt : prompt}>
    {updating ? <p>신규 등록과 같은 <strong>admin-announcement-import/v1</strong> 형식으로 <strong>현재 공고를 갱신</strong>합니다. 기본정보·접수처·일정·첨부·공급정보를 비교합니다. JSON에 없거나 null인 값으로 기존값을 자동 삭제하지 않습니다.</p>
      : <p>새 원공고 등록용입니다. 같은 형식으로 기존 공고를 고치려면 해당 공고의 ‘JSON으로 갱신’을 이용하세요.</p>}
    <ol><li>공식 공고문과 아래 예제를 Codex에 주고 JSON을 요청합니다.</li><li>결과의 JSON 객체만 붙여넣거나 UTF-8 .json 파일로 올립니다. 코드 블록 표시나 설명 문장은 제외하세요.</li><li>{updating ? '현재값과 추출값, 공급행·조건의 대응을 확인하고 변경 미리보기 후 적용합니다.' : 'JSON 검증 후 미확정값과 각 공급행의 단지 연결을 확인하고 등록합니다.'}</li></ol>
    <table><caption>최상위 필수 항목</caption><tbody>
      <tr><th scope="row">schemaVersion</th><td>admin-announcement-import/v1 고정.</td></tr>
      <tr><th scope="row">source</th><td>공식 원문 URL, 공식 공고번호(sourceDocumentId), 추출 시각(extractedAt). 공고번호를 모르면 null.</td></tr>
      <tr><th scope="row">announcement</th><td>공고명·공급유형·모집유형·기관·게시일·접수 시작/종료일·당첨 발표일.</td></tr>
      <tr><th scope="row">receptionPlace</th><td>{updating ? '미확인 시 null로 두면 기존 접수처를 유지합니다. 확인한 하위 항목만 선택 갱신합니다.' : '접수처 이름·방법·연락처 필수. 주소와 URL은 미확인 시 null.'}</td></tr>
      <tr><th scope="row">supplyRows</th><td>{updating ? '0~200행. []이면 기존 공급정보를 유지합니다.' : '1~200행.'} 원문 단지명과 19자리 문자열 PNU, 주택형명·공급구분·모집수·대상별 조건. DB ID는 넣지 않습니다.</td></tr>
      <tr><th scope="row">schedules / attachments</th><td>각각 최대 100개. 없으면 []로 둡니다. 일정의 시각은 YYYY-MM-DDTHH:mm:ss, 첨부는 파일명·종류·HTTP(S) URL.</td></tr>
      <tr><th scope="row">unresolvedFields</th><td>미확인 값의 path·reason 목록. 없으면 []. {updating ? '미확인 항목은 적용하지 않습니다. 예: supplyRows[0].targets[0].monthlyRent.' : '필수값이 null이면 원문을 확인하기 전까지 등록할 수 없습니다.'}</td></tr>
    </tbody></table>
    <p>공급구분은 NEW_SUPPLY(신규 공급) 또는 RESUPPLY(재공급), 기관은 LH·SH·GH·ETC입니다. ETC는 원문이 기타로 분류한 경우에만 사용합니다. 접수 방법은 ONLINE(온라인)·VISIT(방문)·MAIL(우편)·ETC(기타)입니다.</p>
    <details><summary>유형 코드 확인</summary><ul>
      <li>모집유형: NEW(신규 모집), WAITLIST(예비자 모집), ETC(기타).</li>
      <li>임대유형: HAPPY_HOUSING(행복주택), NATIONAL_RENTAL(국민임대), PERMANENT_RENTAL(영구임대), PUBLIC_RENTAL_5Y·PUBLIC_RENTAL_10Y·PUBLIC_RENTAL_50Y(5·10·50년 공공임대), INTEGRATED_PUBLIC_RENTAL(통합공공임대), REDEVELOPMENT_RENTAL(재개발임대), LONG_TERM_JEONSE(장기전세), ETC(기타).</li>
      <li>일정: APPLICATION(접수), DOCUMENT_SUBMISSION(서류 제출), WINNER_ANNOUNCEMENT(당첨 발표), CONTRACT(계약), MOVE_IN(입주), ETC.</li>
      <li>첨부: ANNOUNCEMENT(원공고), CORRECTION(정정), CANCELLATION(취소), REFERENCE(참고), ETC.</li>
    </ul></details>
    <p>수량·금액은 음수가 아닌 정수입니다. 금액은 원 단위이며 ‘만원’이나 쉼표를 넣지 않습니다. 예를 들어 1,000만원은 10000000입니다. 금액은 최대 18자리이며 정확한 숫자 문자열도 허용합니다. 미확인은 null, 실제 0은 0입니다. 대상별 금액은 rentalDeposit·monthlyRent·convertedDeposit을 사용합니다.</p>
    <p>면적은 ㎡ 단위로 exclusiveArea(전용)·supplyArea(공급)에 소수 넷째 자리까지 입력합니다. 선택 항목이며 모르면 생략하거나 null로 둡니다. {updating && '갱신한 면적은 현재 공고에만 적용하며 공통 주택형과 다른 공고는 바꾸지 않습니다.'}</p>
    <p>아래는 형식 설명용 가상 예제입니다. 실제 공고의 값으로 교체하세요. {updating ? '미리보기는 저장하지 않습니다. 최종 적용할 때 현재 공고를 갱신하며 새 공고는 만들지 않습니다.' : '검증은 저장하지 않으며 등록 버튼을 눌렀을 때만 새 공고를 만듭니다.'}</p>
  </JsonGuide>
}
