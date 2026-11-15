/**
 * 공급대상 분류(서버 SupplyTargetGroup과 같은 상수·순서). 공급대상 하나에 여러 개를 고른다('신혼부부 예비신혼부부 한부모가족'은 셋).
 * 신청자격 경로 이름이 공급대상 표기와 맞지 않을 때 분류로 경로를 잇는다. 주거약자용은 다른 분류와 함께 붙는 구분이다.
 */
export const targetGroups = ['GENERAL', 'YOUTH', 'UNIVERSITY_STUDENT', 'NEWLYWED', 'PRE_NEWLYWED', 'SINGLE_PARENT', 'ELDERLY',
  'HOUSING_BENEFIT_RECIPIENT', 'GA_CLASS', 'NA_CLASS', 'EXISTING_RESIDENT', 'INDUSTRIAL_WORKER', 'HOUSING_VULNERABLE'] as const
export type TargetGroup = typeof targetGroups[number]
export const targetGroupLabels: Record<TargetGroup, string> = {
  GENERAL: '일반', YOUTH: '청년', UNIVERSITY_STUDENT: '대학생', NEWLYWED: '신혼부부', PRE_NEWLYWED: '예비신혼부부', SINGLE_PARENT: '한부모가족',
  ELDERLY: '고령자', HOUSING_BENEFIT_RECIPIENT: '주거급여수급자', GA_CLASS: '가군', NA_CLASS: '나군', EXISTING_RESIDENT: '기존 거주자',
  INDUSTRIAL_WORKER: '산업단지 근로자', HOUSING_VULNERABLE: '주거약자용',
}

const known = (value: unknown): value is TargetGroup => targetGroups.includes(value as TargetGroup)
/** 상수 순서로 맞춘 분류. */
export const orderedGroups = (groups: readonly TargetGroup[]) => targetGroups.filter(group => groups.includes(group))

/** 관리자 공급표 응답의 분류. 목록이 아니거나 모르는 이름이 있으면 null이다. */
export function parseTargetGroups(value: unknown): TargetGroup[] | null {
  return Array.isArray(value) && value.every(known) ? orderedGroups(value) : null
}

/** AI 워커 추출의 분류. 이 화면이 모르는 이름(새 워커 상수)과 형식이 틀린 값은 버린다. */
export const extractedGroups = (value: unknown): TargetGroup[] => Array.isArray(value) ? orderedGroups(value.filter(known)) : []

export const sameGroups = (left: readonly TargetGroup[], right: readonly TargetGroup[]) =>
  orderedGroups(left).join(',') === orderedGroups(right).join(',')

export const toggledGroup = (groups: readonly TargetGroup[], group: TargetGroup, on: boolean) =>
  orderedGroups(on ? [...groups, group] : groups.filter(item => item !== group))

/** 화면 표기("신혼부부·예비신혼부부"). 분류가 없으면 '분류 없음'이다. */
export const groupsText = (groups: readonly TargetGroup[]) =>
  groups.length ? orderedGroups(groups).map(group => targetGroupLabels[group]).join('·') : '분류 없음'
