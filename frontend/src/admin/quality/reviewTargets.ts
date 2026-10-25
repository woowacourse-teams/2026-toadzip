import { announcementSections, complexSections, supplyFields, type Field } from '../management/fields'
import { type ManagementDetailData, type ManagementResource, type ManagementValues } from '../management/managementContract'
import { managementResourcePath } from '../management/api'

export type ReviewField = Field & { unit: string; scope: string; money?: boolean }
export type ReviewTarget = { key: string; label: string; version: number; endpoint: string; fields: ReviewField[]; current: ManagementValues; body: ManagementValues }
const housingTypeFields: Field[] = [{ name: 'name', label: '주택형 이름', required: true },
  { name: 'exclusiveArea', label: '전용면적', type: 'number', min: 0, step: '0.0001', required: true },
  { name: 'householdCount', label: '주택형 세대수', type: 'number', min: 0 }]
const supplyTargetFields: (Field & { money?: boolean })[] = [
  { name: 'target', label: '공급 대상', required: true }, { name: 'supplyRank', label: '공급 순위' },
  { name: 'supplyHouseholdCount', label: '대상별 공급 세대수', type: 'number', min: 0 },
  { name: 'reserveCount', label: '예비자 수', type: 'number', min: 0 },
  { name: 'rentalDeposit', label: '임대보증금', money: true }, { name: 'monthlyRent', label: '월 임대료', money: true },
  { name: 'convertedDeposit', label: '전환보증금', money: true }, { name: 'applicationCondition', label: '신청 조건' },
]

export function targetChoices(resource: ManagementResource, detail: ManagementDetailData): { key: string; label: string }[] {
  return [{ key: 'basic', label: `${resource === 'complexes' ? '단지' : '공고'} 기본정보` },
    ...detail.housingTypes.filter(type => !type.deleted).map(type => ({ key: `type:${type.id}`, label: `주택형 ${type.name} · ${type.exclusiveArea}㎡` })),
    ...detail.supplyRows.map(row => ({ key: `row:${row.id}`, label: `공급행 ${row.sourceComplexName} · ${String(row.data.announcementHousingTypeName ?? row.sourceHousingTypeName)}` })),
    ...(detail.supplyTargets ?? []).map(target => ({ key: `target:${target.id}`,
      label: `임대조건 · ${detail.supplyRows.find(row => row.id === target.supplyRowId)?.data.announcementHousingTypeName ?? target.supplyRowId} · ${target.data.target} · ${target.data.supplyRank ?? '순위 미확인'}` }))]
}

export function reviewTarget(resource: ManagementResource, detail: ManagementDetailData, selection: string): ReviewTarget {
  const endpoint = `${managementResourcePath(resource)}/${detail.summary.id}`
  const version = Number(detail.data.version)
  let fields: (Field & { money?: boolean })[]; let current: ManagementValues; let body: ManagementValues; let path = endpoint
  let scope = resource === 'complexes' ? '단지 전체' : '해당 공고'
  const label = targetChoices(resource, detail).find(choice => choice.key === selection)?.label
  if (!label) throw new Error('수정 대상을 다시 선택해 주세요.')
  if (selection.startsWith('type:')) {
    const type = detail.housingTypes.find(item => `type:${item.id}` === selection)
    if (!type || resource !== 'complexes') throw new Error('주택형을 확인해 주세요.')
    current = { name: type.name, exclusiveArea: type.exclusiveArea, householdCount: type.householdCount }
    fields = housingTypeFields; body = { version, ...current }; path += `/housing-types/${type.id}`; scope = '해당 주택형'
  } else if (selection.startsWith('target:')) {
    const condition = detail.supplyTargets?.find(item => `target:${item.id}` === selection)
    if (!condition || resource !== 'announcements') throw new Error('공급대상을 확인해 주세요.')
    current = condition.data; fields = supplyTargetFields; scope = '해당 공급대상'
    path += `/supply-targets/${condition.id}`; body = { version, ...current }
  } else if (selection.startsWith('row:')) {
    const row = detail.supplyRows.find(item => `row:${item.id}` === selection)
    if (!row || resource !== 'announcements') throw new Error('공급행을 확인해 주세요.')
    current = row.data; fields = supplyFields; scope = '해당 공급행'; path += `/supply-rows/${row.id}`
    body = { version, housingComplexId: row.housingComplexId, housingTypeId: row.housingTypeId, supplyRow: current }
  } else {
    current = detail.data; body = detail.data
    // 주소는 PNU·행정구역·좌표를 함께 확인해야 하므로 기존 주소 편집 화면에서 수정한다.
    fields = (resource === 'complexes' ? complexSections : announcementSections).flatMap(section => section.fields)
      .filter(field => !field.name.startsWith('address.') && field.name !== 'overviewImageUrl'
        && !(detail.scheduleReviewed && ['applicationStartDate', 'applicationEndDate'].includes(field.name)))
  }
  return { key: `${resource}:${detail.summary.id}:${selection}`, label, version, endpoint: path, current, body,
    fields: fields.map(field => ({ ...field, unit: field.money ? '원' : field.name === 'exclusiveArea' ? '㎡'
      : /HouseholdCount$|householdCount/.test(field.name) ? '세대' : field.name === 'totalParkingCount' ? '대'
        : ['moveOutCountLastYear', 'reserveCount'].includes(field.name) ? '명' : field.type === 'date' ? '날짜' : field.type === 'month' ? '연월' : '없음', scope })) }
}
