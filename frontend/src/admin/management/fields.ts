import { type JsonValue, type ManagementValues } from './managementContract'
export const labels: Record<string, string> = {
  INITIAL_REGISTER: '마이홈 신규 단지 최초 등록',
  UPDATE_FROM_JSON: 'JSON으로 공고 갱신', CORRECT_FROM_PDF: 'PDF로 공고 보정',
  CORRECT_SUPPLY: '공급정보 일괄 보정', REVIEW_SUPPLY: '공급정보 범위 검토',
  SAVE_SUPPLY_MATCH: '원천 매칭 선택 저장', RELEASE_SUPPLY_MATCH: '원천 매칭 선택 해제',
  SPLIT_SUPPLY: '원천별 공급행 나누기', PROCESS_FEEDBACK: '사용자 의견 처리',
  UPDATE_REVIEW_TASK: '담당자·기한·업무 상태 변경', SAVE_REVIEW_DRAFT: '공유 작업 초안 저장',
  CHECK_COMPARISON_SOURCES: '재수집 대비 원천 확인',
  ARCHIVE_DOCUMENT: '검토 근거 PDF 원본 보관',
  UPDATE_COMPARED: '원천 비교 후 수정', LINK_COMPARISON_SOURCE: '비교 원천 연결', UNLINK_COMPARISON_SOURCE: '비교 원천 해제',
  LONG_TERM_JEONSE: '장기전세', HAPPY_HOUSING: '행복주택', NATIONAL_RENTAL: '국민임대', PERMANENT_RENTAL: '영구임대',
  PUBLIC_RENTAL: '분양전환 공공임대', PUBLIC_RENTAL_50Y: '50년 공공임대',
  // 합치기 전 이력·검토 스냅샷에 남은 코드
  PUBLIC_RENTAL_5Y: '5년 공공임대', PUBLIC_RENTAL_10Y: '10년 공공임대',
  INTEGRATED_PUBLIC_RENTAL: '통합공공임대', REDEVELOPMENT_RENTAL: '재개발임대', ETC: '기타',
  INDIVIDUAL: '개별난방', CENTRAL: '중앙난방', DISTRICT: '지역난방', APARTMENT: '아파트', OFFICETEL: '오피스텔',
  STAIR: '계단식', CORRIDOR: '복도식', MIXED: '혼합식', UNKNOWN: '확인 필요',
  NEW: '신규 모집', WAITLIST: '예비 입주자', NEW_SUPPLY: '신규 공급', RESUPPLY: '재공급',
  ONLINE: '온라인', VISIT: '방문', MAIL: '우편', true: '설치', false: '미설치',
  CONFIRMED: '확정', CONDITIONAL: '조건부', UPDATE_SCHEDULE: '접수 일정 수정',
  UPDATE: '정보 수정', DELETE: '휴지통 이동', RESTORE: '복구', UPDATE_SUPPLY: '공급정보 수정',
  UPDATE_HOUSING_TYPE: '주택형 수정', UPDATE_HOUSING_TYPE_FROM_PDF: 'PDF 공고로 주택형 갱신',
  UPDATE_SUPPLY_TARGET: '공급대상 임대조건 수정',
}
export const rentals = ['HAPPY_HOUSING', 'NATIONAL_RENTAL', 'PERMANENT_RENTAL', 'PUBLIC_RENTAL',
  'PUBLIC_RENTAL_50Y', 'INTEGRATED_PUBLIC_RENTAL', 'REDEVELOPMENT_RENTAL', 'LONG_TERM_JEONSE', 'ETC']
export const provinces = [['11','서울'],['26','부산'],['27','대구'],['28','인천'],['29','광주'],['30','대전'],['31','울산'],['36','세종'],
  ['41','경기'],['51','강원'],['43','충북'],['44','충남'],['52','전북'],['46','전남'],['47','경북'],['48','경남'],['50','제주']]
export type Field = { name: string; label: string; type?: 'text' | 'number' | 'date' | 'url' | 'month' | 'time'; required?: boolean;
  options?: string[]; min?: number; max?: number; step?: string; boolean?: boolean; maxLength?: number }
export type Section = { title: string; fields: Field[] }
const common: Field[] = [{ name:'name', label:'이름', required:true }, { name:'rentalType', label:'공급 유형', options:rentals, required:true },
  {name:'agencyCode', label:'공급 기관', options:['LH','SH','GH','ETC'], required:true}]
export const complexSections: Section[] = [
  {title:'기본정보', fields:[...common, {name:'completionDate',label:'준공일',type:'date'}]},
  {title:'주소·위치', fields:[{name:'address.roadAddress',label:'도로명주소',required:true},
    {name:'address.latitude',label:'위도',type:'number',required:true,min:-90,max:90,step:'0.000001'},
    {name:'address.longitude',label:'경도',type:'number',required:true,min:-180,max:180,step:'0.000001'}]},
  {title:'주소 식별정보',fields:[{name:'address.pnu',label:'PNU'},{name:'address.legalDongCode',label:'법정동 코드',required:true},
    {name:'address.provinceCode',label:'시·도 코드',required:true},{name:'address.cityCountyDistrictCode',label:'시·군·구 코드',required:true}]},
  {title:'시설정보', fields:[{name:'totalHouseholdCount',label:'전체 세대수',type:'number',min:0},
    {name:'totalParkingCount',label:'주차대수',type:'number',min:0},{name:'moveOutCountLastYear',label:'최근 1년 퇴거자 수',type:'number',min:0},
    {name:'heatingType',label:'난방 유형',options:['INDIVIDUAL','CENTRAL','DISTRICT','ETC']},
    {name:'buildingType',label:'건물 유형',options:['APARTMENT','OFFICETEL','ETC']},
    {name:'corridorType',label:'복도 유형',options:['STAIR','CORRIDOR','MIXED','UNKNOWN']},
    {name:'hasElevator',label:'엘리베이터',options:['true','false'],boolean:true},{name:'overviewImageUrl',label:'대표 이미지 URL',type:'url'}]},
]
export const announcementSections: Section[] = [
  {title:'기본정보',fields:[...common,{name:'recruitmentType',label:'모집 유형',options:['NEW','WAITLIST','ETC'],required:true},
    {name:'originalUrl',label:'공식 원문 URL',type:'url',required:true}]},
  {title:'모집 일정',fields:[{name:'postedDate',label:'게시일',type:'date',required:true},
    {name:'applicationStartDate',label:'접수 시작일',type:'date',required:true},{name:'applicationEndDate',label:'접수 종료일',type:'date',required:true},
    {name:'winnerAnnouncementDate',label:'당첨자 발표일',type:'date',required:true}]},
]
/** 공고의 접수처 한 곳. 공고문에 나온 순서대로 `receptionPlaces` 목록에 담는다. */
export const receptionPlaceFields: Field[] = [{name:'name',label:'접수처명'},{name:'method',label:'접수 방식',options:['ONLINE','VISIT','MAIL','ETC'],required:true},
  {name:'address',label:'접수처 주소'},{name:'contact',label:'연락처'},{name:'url',label:'접수처 URL',type:'url'},{name:'guidance',label:'안내',maxLength:1000}]
export const maxReceptionPlaces = 20
export function receptionPlacesOf(data: ManagementValues): ManagementValues[] {
  const places = data.receptionPlaces
  return Array.isArray(places) ? places.filter((place): place is ManagementValues => place !== null && typeof place === 'object' && !Array.isArray(place)) : []
}
/**
 * 편집 폼의 `receptionPlaces[n].칸` 입력을 화면 순서대로 읽는다. 접수 방식 말고 모든 칸이 빈 접수처는 버린다.
 * positions는 보낸 목록의 순서마다 화면의 몇 번째 접수처인지다. 서버 오류 경로를 화면 칸에 붙일 때 쓴다.
 */
export function receptionPlacesFrom(form: HTMLFormElement | FormData): { places: ManagementValues[]; positions: number[] } {
  const values = form instanceof FormData ? form : new FormData(form)
  const indexes = [...new Set([...values.keys()].flatMap(key => /^receptionPlaces\[(\d+)\]\./.exec(key)?.[1] ?? []))].map(Number).sort((a, b) => a - b)
  const read = formValues(values, indexes.flatMap(index => receptionPlaceFields.map(field => ({ ...field, name: `receptionPlaces[${index}].${field.name}` }))), {})
  const kept = indexes.map(index => ({ index, place: read[`receptionPlaces[${index}]`] as ManagementValues }))
    .filter(({ place }) => receptionPlaceFields.some(field => field.name !== 'method' && place[field.name] !== null))
  return { places: kept.map(({ place }) => place), positions: kept.map(({ index }) => index) }
}
/** 서버가 보낸 목록 순서로 돌려준 `receptionPlaces[n].칸` 오류를 화면의 접수처 칸 이름으로 옮긴다. */
export function receptionPlaceErrors(errors: Record<string, string>, positions: number[]): Record<string, string> {
  return Object.fromEntries(Object.entries(errors).map(([key, message]) => {
    const match = /^receptionPlaces\[(\d+)\](.*)$/.exec(key)
    const position = match ? positions[Number(match[1])] : undefined
    return [match && position !== undefined ? `receptionPlaces[${position}]${match[2]}` : key, message]
  }))
}
export const supplyFields: Field[] = [{name:'announcementHousingTypeName',label:'공고 표시명'},
  {name:'supplyPnu',label:'공급 PNU',required:true},
  {name:'expectedMoveInMonth',label:'입주 예정 연월',type:'month'},
  {name:'supplyCategory',label:'공급 구분',options:['NEW_SUPPLY','RESUPPLY'],required:true},
  {name:'totalSupplyHouseholdCount',label:'공급세대수',type:'number',min:0}]
export function valueAt(data: ManagementValues, name: string): JsonValue | undefined {
  const [parent, child] = name.split('.')
  const value = data[parent]
  if (!child) return value
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value[child] : null
}
export function formValues(form: HTMLFormElement | FormData, fields: Field[], original: ManagementValues): ManagementValues {
  const result = structuredClone(original)
  const values = form instanceof FormData ? form : new FormData(form)
  for (const field of fields) {
    const text = String(values.get(field.name) ?? '').trim()
    const value = text === '' ? null : field.boolean ? text === 'true' : field.type === 'number' ? Number(text) : text
    const [parent, child] = field.name.split('.')
    if (!child) { result[parent] = value; continue }
    const nested = result[parent]
    const object = nested !== null && typeof nested === 'object' && !Array.isArray(nested) ? nested : {}
    object[child] = value
    result[parent] = object
  }
  return result
}
export function display(value: JsonValue | undefined): string {
  if (value === null || value === undefined || value === '') return '미확인'
  if (typeof value === 'object') return JSON.stringify(value)
  return labels[String(value)] ?? String(value)
}
