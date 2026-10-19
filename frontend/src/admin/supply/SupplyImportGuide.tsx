import example from './supplyImportExample.json'
import { JsonGuide } from '../shared/JsonGuide'
import type { CorrectionRow } from './supplyCorrectionApi'

const supplyImportExample = JSON.stringify(example, null, 2)

export function SupplyImportGuide({ rows }: { rows: CorrectionRow[] }) {
  const conditions = rows.flatMap(row => row.targets.filter(target => target.id).map(target => ({
    supplyRowId: row.id, complexName: row.complexName, typeName: row.typeName,
    targetId: target.id, target: target.target, supplyRank: target.supplyRank,
  })))
  const prompt = `첨부한 공식 공고문을 읽고 기존 공고의 공급정보 보정용 JSON 객체만 작성해 주세요.
아래 예제의 키와 단위(areaUnit: m2, moneyUnit: KRW)를 사용하세요. 예제의 값은 가상이므로 실제 자료로 바꾸세요.
이번에 확인한 값만 넣으세요. 항목 생략은 기존값 유지, null은 미확인으로 변경, 0은 확인된 0입니다.
금액은 원 단위 숫자 문자열로 작성하세요. 면적은 ㎡ 숫자, 수량은 음수가 아닌 정수입니다.
모집수와 단지 전체 세대수, 예비자 수를 구분하고 A/B 대응이나 개별 모집수를 추정하지 마세요.
기존 조건을 수정할 때만 아래 목록의 targetId를 targets 안의 id로 넣으세요. 다른 공급행의 조건을 이동하거나 ID를 만들지 마세요.
새 조건은 id를 생략하세요. 공급행 대응과 단지·주택형 연결은 백오피스에서 사람이 선택합니다.
최상위 rows에는 공급행만 넣고 schemaVersion이나 공급행 id, housingComplexId, housingTypeId는 넣지 마세요.
\n현재 공고의 기존 조건 ID 목록:\n${JSON.stringify(conditions, null, 2)}
\n형식 예제:\n${supplyImportExample}`
  return <JsonGuide title="공급정보 보정 JSON 작성 방법과 예제" example={supplyImportExample} prompt={prompt}>
    <p><strong>이미 등록된 이 공고의 공급정보를 고치는 형식</strong>입니다. 신규 공고 등록용 JSON과 필드 이름이 다릅니다.</p>
    <ol><li>공식 공고문과 아래 요청문을 Codex에 전달합니다. 확인한 값만 JSON에 넣으세요.</li><li>설명이나 코드 블록 표시 없이 JSON 객체만 붙여넣고 ‘입력행 확인’을 누릅니다.</li><li>입력행마다 기존 행, 새 공급행, 가져오지 않음을 직접 선택합니다. 이름이 같아도 자동으로 연결하지 않습니다.</li><li>‘선택한 행을 변경안에 추가’ 후 단지·주택형 연결과 변경 미리보기를 확인합니다. 근거와 사유를 적고 최종 적용해야 저장됩니다.</li></ol>
    <table><caption>입력 항목과 값의 형식</caption><tbody>
      <tr><th scope="row">areaUnit / moneyUnit / rows</th><td>필수. 각각 "m2", "KRW", 1~500개의 공급행 배열입니다.</td></tr>
      <tr><th scope="row">complexName / typeName / pnu</th><td>공고에 적힌 단지명·주택형명·PNU 문자열. PNU는 19자리 주소 식별자이며 주소 문장이나 단지 ID가 아닙니다.</td></tr>
      <tr><th scope="row">category / moveInMonth</th><td>NEW_SUPPLY(신규 공급) 또는 RESUPPLY(재공급). 입주 예정 연월은 "2027-03" 또는 null.</td></tr>
      <tr><th scope="row">householdCount</th><td>해당 공고의 금회 모집수. 음수가 아닌 정수 또는 null. 단지 전체 세대수가 아닙니다.</td></tr>
      <tr><th scope="row">exclusiveArea / supplyArea</th><td>전용·공급면적. ㎡ 숫자 또는 null. 입력한 면적은 이 공고에서 별도로 보정·보호합니다.</td></tr>
      <tr><th scope="row">targets</th><td>대상별 조건 배열, 행당 최대 200개. 기존 조건은 id로 수정하며 id가 없으면 새 조건을 추가합니다.</td></tr>
      <tr><th scope="row">조건의 target / supplyRank / condition</th><td>공급대상·순위·신청조건 문자열. 순위와 조건은 null도 가능합니다.</td></tr>
      <tr><th scope="row">조건의 householdCount / reserveCount</th><td>대상별 모집수(세대)와 예비자 수(명). 음수가 아닌 정수 또는 null.</td></tr>
      <tr><th scope="row">조건의 deposit / rent / convertedDeposit</th><td>보증금·월 임대료·전환보증금. 원 단위 숫자 문자열(최대 18자리) 또는 null. 1,000만원은 "10000000"입니다.</td></tr>
      <tr><th scope="row">행·조건의 excluded</th><td>true로 공개 제외, false로 복원. 실제 삭제하지 않습니다.</td></tr>
    </tbody></table>
    <p><strong>생략한 값·행·조건은 유지합니다.</strong> null은 기존 값을 미확인으로 바꿉니다. 0은 확인된 0입니다. targets: []도 기존 조건을 지우지 않습니다. 제외하려면 해당 조건의 id와 excluded: true를 넣으세요.</p>
    <p>예제에는 조건 id가 없으므로 새 조건이 추가됩니다. 기존 조건의 금액만 고치려면 아래 ID를 확인해 {`{"areaUnit":"m2","moneyUnit":"KRW","rows":[{"targets":[{"id":123,"rent":"200000"}]}]}`}처럼 작성하고, 그 조건이 속한 공급행을 선택하세요. 123은 실제 ID로 바꿉니다.</p>
    {conditions.length > 0 ? <details><summary>이 공고의 기존 조건 ID 확인</summary><table><caption>JSON에서 수정할 수 있는 기존 조건</caption><thead><tr><th scope="col">공급행</th><th scope="col">대상·순위</th><th scope="col">조건 id</th></tr></thead><tbody>
      {conditions.map(item => <tr key={`${item.supplyRowId}:${item.targetId}`}><td>{item.complexName} · {item.typeName} · #{item.supplyRowId}</td><td>{item.target} · {item.supplyRank ?? '순위 미확인'}</td><td>{item.targetId}</td></tr>)}
    </tbody></table></details> : <p>이 공고에는 저장된 조건 ID가 없습니다. 새 조건은 id 없이 작성하세요.</p>}
    <p>새 공급행은 단지명·주택형명·PNU 등 필수값을 채워야 합니다. 단지와 공통 주택형 연결, 누락 주택형 생성은 가져온 후 화면에서 선택하세요. 아래 예제의 이름·PNU·수량은 형식 설명용입니다.</p>
  </JsonGuide>
}
