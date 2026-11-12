import { findCatalogRegion } from '../../public-housing/model/publicHousingRegion'
import { FIELD_COPY, type StepNumber } from './conditionFieldCopy'
import {
  LATER,
  hasValue,
  isAnswered,
  isFamilyDetailField,
  setFamilyDetails,
  visibleItems,
  type ConditionDraft,
  type DraftItem,
} from './conditionDraft'
import type { ProfileField } from './conditionsModel'

/** 입력 창을 연 곳. 완료 버튼 문구와 저장 뒤 동작이 달라진다. */
export type InputOrigin = 'detail' | 'list' | 'mypage'

export type InputRequest =
  | { readonly mode: 'full'; readonly step: StepNumber | 4; readonly origin: InputOrigin }
  | { readonly mode: 'partial'; readonly fields: readonly ProfileField[]; readonly origin: InputOrigin }

export type FieldErrors = Partial<Record<DraftItem, string>>

function missingMessage(item: DraftItem) {
  if (item === 'FAMILY_GATE') return '하나를 골라 주세요.'
  const kind = FIELD_COPY[item].kind
  if (kind === 'date' || kind === 'dateEvent') return '날짜를 입력해 주세요.'
  if (kind === 'region') return '시·군·구까지 골라 주세요.'
  if (kind === 'money') return "금액을 입력하거나 '나중에 입력할게요'를 눌러 주세요."
  return '답을 골라 주세요.'
}

/** 다음 단계로 가기 전 확인. 빈 칸 안내와 이미 있던 칸 오류(잘못된 날짜 등)를 함께 돌려준다. */
export function stepErrors(step: StepNumber, draft: ConditionDraft, current: FieldErrors): FieldErrors {
  const errors: FieldErrors = {}
  for (const item of visibleItems(step, draft)) {
    // 잘못된 날짜처럼 칸이 이미 알려 준 오류가 있으면 그 문구를 그대로 둔다.
    if (current[item]) errors[item] = current[item]
    else if (!isAnswered(item, draft)) errors[item] = missingMessage(item)
  }
  return errors
}

/** 부분 입력 창에 보여 줄 칸. 차량 가격은 소유 여부와 함께 묻는다. */
export function partialFields(requested: readonly ProfileField[], draft: ConditionDraft): ProfileField[] {
  const out: ProfileField[] = []
  const add = (field: ProfileField) => { if (!out.includes(field)) out.push(field) }
  for (const field of requested) {
    if (field === 'FAMILY_CAR_VALUE') add('FAMILY_OWNS_CAR')
    if (field === 'APPLICANT_CAR_VALUE') add('APPLICANT_OWNS_CAR')
    add(field)
    if (field === 'FAMILY_OWNS_CAR') add('FAMILY_CAR_VALUE')
    if (field === 'APPLICANT_OWNS_CAR') add('APPLICANT_CAR_VALUE')
  }
  return out.filter((field) =>
    !(field === 'FAMILY_CAR_VALUE' && draft.answers.FAMILY_OWNS_CAR !== true)
    && !(field === 'APPLICANT_CAR_VALUE' && draft.answers.APPLICANT_OWNS_CAR !== true))
}

/** 부분 입력을 시작할 때: 가족 항목이면 가족 정보를 입력하는 것으로 바꾸고, "나중에" 표시는 지운다. */
export function partialStartDraft(requested: readonly ProfileField[], draft: ConditionDraft): ConditionDraft {
  let next = draft
  if (requested.some(isFamilyDetailField) && next.familyDetails !== 'PROVIDED') next = setFamilyDetails(next, 'PROVIDED')
  const answers = { ...next.answers }
  for (const field of requested) if (answers[field] === LATER) delete answers[field]
  const region = answers.RESIDENCE_REGION
  if (requested.includes('RESIDENCE_REGION') && hasValue(region) && typeof region === 'string' && !findCatalogRegion(region)) {
    delete answers.RESIDENCE_REGION
    return { ...next, answers, provinceCode: null }
  }
  return { ...next, answers }
}

export function partialErrors(fields: readonly ProfileField[], draft: ConditionDraft, current: FieldErrors): FieldErrors {
  const errors: FieldErrors = {}
  for (const field of fields) {
    if (current[field]) errors[field] = current[field]
    else if (!isAnswered(field, draft)) errors[field] = '값을 입력해 주세요.'
  }
  return errors
}

export function partialTitle(requested: readonly ProfileField[], original: ConditionDraft): string {
  const editing = requested.every((field) => hasValue(original.answers[field]))
  if (requested.length === 1) return `${FIELD_COPY[requested[0]].label} ${editing ? '수정' : '입력'}`
  return editing ? '정보 수정' : '추가 정보 입력'
}

export function submitLabel(request: InputRequest, editing: boolean, member: boolean): string {
  if (request.mode === 'partial') return request.origin === 'detail' ? '저장하고 다시 비교' : '저장하기'
  if (editing || request.origin === 'mypage') return '저장하기'
  const goal = request.origin === 'list' ? '지원 가능한 공고 확인하기' : '이 공고와 비교하기'
  return member ? goal : `로그인하고 ${goal}`
}
