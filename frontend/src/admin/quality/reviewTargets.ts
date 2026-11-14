import { announcementSections, complexSections, type Field } from '../management/fields'
import { type ManagementDetailData, type ManagementResource, type ManagementValues } from '../management/managementContract'
import { cellName, cellValues } from '../supply/sheetEdits'

export type ReviewField = Field & { unit: string; scope: string; money?: boolean }
export type ReviewTarget = { key: string; label: string; version: number; fields: ReviewField[]; current: ManagementValues }
const housingTypeFields: Field[] = [{ name: 'name', label: '주택형 이름', required: true },
  { name: 'exclusiveArea', label: '전용면적', type: 'number', min: 0, step: '0.0001', required: true },
  { name: 'householdCount', label: '주택형 세대수', type: 'number', min: 0 }]
/** 공급표 칸(cell:{id})의 비교 항목: 공급대상, 그 대상만의 모집·예비 수와 임대조건. */
const supplyCellFields: (Field & { money?: boolean })[] = [
  { name: 'target', label: '공급 대상', required: true },
  { name: 'supplyHouseholdCount', label: '대상별 모집 세대수', type: 'number', min: 0 },
  { name: 'reserveCount', label: '예비자 수', type: 'number', min: 0 },
  { name: 'rentalDeposit', label: '임대보증금', money: true }, { name: 'monthlyRent', label: '월 임대료', money: true },
  { name: 'convertedDeposit', label: '전환보증금', money: true }, { name: 'applicationCondition', label: '신청 조건' },
]

export function targetChoices(resource: ManagementResource, detail: ManagementDetailData): { key: string; label: string }[] {
  return [{ key: 'basic', label: `${resource === 'complexes' ? '단지' : '공고'} 기본정보` },
    ...detail.housingTypes.filter(type => !type.deleted).map(type => ({ key: `type:${type.id}`, label: `주택형 ${type.name} · ${type.exclusiveArea}㎡` })),
    ...[...detail.supplySheet.housingTypes].sort((a, b) => a.displayOrder - b.displayOrder).flatMap(type => detail.supplySheet.cells
      .filter(cell => cell.housingType === type.key).sort((a, b) => a.displayOrder - b.displayOrder)
      .map(cell => ({ key: cell.key, label: `공급대상 · ${type.housingComplexName || type.complexName} · ${cellName(detail.supplySheet, cell.key)}` })))]
}

export function reviewTarget(resource: ManagementResource, detail: ManagementDetailData, selection: string): ReviewTarget {
  const version = Number(detail.data.version)
  let fields: (Field & { money?: boolean })[]; let current: ManagementValues
  let scope = resource === 'complexes' ? '단지 전체' : '해당 공고'
  const label = targetChoices(resource, detail).find(choice => choice.key === selection)?.label
  if (!label) throw new Error('수정 대상을 다시 선택해 주세요.')
  if (selection.startsWith('type:')) {
    const type = detail.housingTypes.find(item => `type:${item.id}` === selection)
    if (!type || resource !== 'complexes') throw new Error('주택형을 확인해 주세요.')
    current = { name: type.name, exclusiveArea: type.exclusiveArea, householdCount: type.householdCount }
    fields = housingTypeFields; scope = '해당 주택형'
  } else if (selection.startsWith('cell:')) {
    const cell = detail.supplySheet.cells.find(item => item.key === selection)
    if (!cell || resource !== 'announcements') throw new Error('공급대상을 확인해 주세요.')
    const value = cellValues(detail.supplySheet, cell)
    current = { target: value.label, applicationCondition: value.applicationCondition, supplyHouseholdCount: value.winner, reserveCount: value.reserve,
      rentalDeposit: value.rentalDeposit, monthlyRent: value.monthlyRent, convertedDeposit: value.convertedDeposit }
    fields = supplyCellFields; scope = '해당 공급대상'
  } else {
    current = detail.data
    // 주소는 PNU·행정구역·좌표를 함께 확인해야 하므로 기존 주소 편집 화면에서 수정한다.
    fields = (resource === 'complexes' ? complexSections : announcementSections).flatMap(section => section.fields)
      .filter(field => !field.name.startsWith('address.') && field.name !== 'overviewImageUrl'
        && !(detail.scheduleReviewed && ['applicationStartDate', 'applicationEndDate'].includes(field.name)))
  }
  return { key: `${resource}:${detail.summary.id}:${selection}`, label, version, current,
    fields: fields.map(field => ({ ...field, unit: field.money ? '원' : field.name === 'exclusiveArea' ? '㎡'
      : /HouseholdCount$|householdCount/.test(field.name) ? '세대' : field.name === 'totalParkingCount' ? '대'
        : ['moveOutCountLastYear', 'reserveCount'].includes(field.name) ? '명' : field.type === 'date' ? '날짜' : field.type === 'month' ? '연월' : '없음', scope })) }
}
