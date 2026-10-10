import { type JsonValue, type ManagementValues } from './managementContract'
export const labels: Record<string, string> = {
  LONG_TERM_JEONSE: '장기전세', HAPPY_HOUSING: '행복주택', NATIONAL_RENTAL: '국민임대', PERMANENT_RENTAL: '영구임대',
  PUBLIC_RENTAL_5Y: '5년 공공임대', PUBLIC_RENTAL_10Y: '10년 공공임대', PUBLIC_RENTAL_50Y: '50년 공공임대',
  INTEGRATED_PUBLIC_RENTAL: '통합공공임대', REDEVELOPMENT_RENTAL: '재개발임대', ETC: '기타',
  INDIVIDUAL: '개별난방', CENTRAL: '중앙난방', DISTRICT: '지역난방', APARTMENT: '아파트', OFFICETEL: '오피스텔',
  STAIR: '계단식', CORRIDOR: '복도식', MIXED: '혼합식', UNKNOWN: '확인 필요',
  NEW: '신규 모집', WAITLIST: '예비 입주자', NEW_SUPPLY: '신규 공급', RESUPPLY: '재공급',
  ONLINE: '온라인', VISIT: '방문', MAIL: '우편', true: '설치', false: '미설치',
  CONFIRMED: '확정', CONDITIONAL: '조건부', UPDATE_SCHEDULE: '접수 일정 수정',
  UPDATE: '정보 수정', DELETE: '휴지통 이동', RESTORE: '복구', UPDATE_SUPPLY: '공급정보 수정',
  UPDATE_HOUSING_TYPE: '주택형 수정',
}
export const rentals = ['HAPPY_HOUSING', 'NATIONAL_RENTAL', 'PERMANENT_RENTAL', 'PUBLIC_RENTAL_5Y', 'PUBLIC_RENTAL_10Y',
  'PUBLIC_RENTAL_50Y', 'INTEGRATED_PUBLIC_RENTAL', 'REDEVELOPMENT_RENTAL', 'LONG_TERM_JEONSE', 'ETC']
export const provinces = [['11','서울'],['26','부산'],['27','대구'],['28','인천'],['29','광주'],['30','대전'],['31','울산'],['36','세종'],
  ['41','경기'],['51','강원'],['43','충북'],['44','충남'],['52','전북'],['46','전남'],['47','경북'],['48','경남'],['50','제주']]
export type Field = { name: string; label: string; type?: 'text' | 'number' | 'date' | 'url' | 'month' | 'time'; required?: boolean;
  options?: string[]; min?: number; max?: number; step?: string; boolean?: boolean }
export type Section = { title: string; fields: Field[] }
const common: Field[] = [{ name:'name', label:'이름', required:true }, { name:'rentalType', label:'공급 유형', options:rentals, required:true },
  {name:'agencyCode', label:'공급 기관', options:['LH','SH','GH','ETC'], required:true}]
export const complexSections: Section[] = [
  {title:'기본정보', fields:[...common, {name:'completionDate',label:'준공일',type:'date'}]},
  {title:'주소·위치', fields:[{name:'address.roadAddress',label:'도로명주소',required:true},
    {name:'address.latitude',label:'위도',type:'number',required:true,min:-90,max:90,step:'0.000001'},
    {name:'address.longitude',label:'경도',type:'number',required:true,min:-180,max:180,step:'0.000001'}]},
  {title:'주소 식별정보',fields:[{name:'address.pnu',label:'PNU',required:true},{name:'address.legalDongCode',label:'법정동 코드',required:true},
    {name:'address.provinceCode',label:'시·도 코드',required:true},{name:'address.cityCountyDistrictCode',label:'시·군·구 코드',required:true}]},
  {title:'시설정보', fields:[{name:'totalHouseholdCount',label:'전체 세대수',type:'number',min:0,required:true},
    {name:'totalParkingCount',label:'주차대수',type:'number',min:0,required:true},{name:'moveOutCountLastYear',label:'최근 1년 퇴거자 수',type:'number',min:0},
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
  {title:'접수처',fields:[{name:'receptionPlace.name',label:'접수처명'},{name:'receptionPlace.method',label:'접수 방식',options:['ONLINE','VISIT','MAIL','ETC']},
    {name:'receptionPlace.address',label:'접수처 주소'},{name:'receptionPlace.contact',label:'연락처'},{name:'receptionPlace.url',label:'접수처 URL',type:'url'}]},
]
export const supplyFields: Field[] = [{name:'sourceComplexName',label:'원문 단지명',required:true},
  {name:'sourceHousingTypeName',label:'원문 주택형명',required:true},{name:'supplyPnu',label:'공급 PNU',required:true},
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
  const reception = result.receptionPlace
  if (reception && typeof reception === 'object' && !Array.isArray(reception) && Object.values(reception).every(v => v === null)) result.receptionPlace = null
  return result
}
export function display(value: JsonValue | undefined): string {
  if (value === null || value === undefined || value === '') return '미확인'
  if (typeof value === 'object') return JSON.stringify(value)
  return labels[String(value)] ?? String(value)
}
