import { findCatalogRegion } from '../../public-housing/model/publicHousingRegion'
import { FAMILY_TYPE_COPY, FIELD_COPY } from './conditionFieldCopy'
import { isDateEvent, NOT_APPLICABLE, type ConditionDraft } from './conditionDraft'
import { formatAge, formatDots, formatWon } from './conditionFormat'
import { regionLabel } from './conditionRegions'
import type { DateEventKind, FamilyType, ProfileField } from './conditionsModel'

const EVENT_SHORT: Record<DateEventKind, string> = { REGISTERED: '신고', PLANNED: '신고 예정', BORN: '출생', EXPECTED: '출산 예정' }

/** 입력한 값을 표에 쓰는 짧은 문구로. 값이 없으면 null. */
export function displayAnswer(field: ProfileField, draft: ConditionDraft, today: string): string | null {
  const value = draft.answers[field]
  if (value === undefined || value === 'LATER') return null
  if (value === NOT_APPLICABLE) return '해당 없음'
  switch (FIELD_COPY[field].kind) {
    case 'bool': return value ? '예' : '아니요'
    case 'date': return typeof value === 'string' ? field === 'BIRTH_DATE' ? `${formatDots(value)} (${formatAge(value, today)})` : formatDots(value) : null
    case 'region': return typeof value === 'string' && findCatalogRegion(value) ? regionLabel(value) : null
    case 'money': return typeof value === 'number' ? `${FIELD_COPY[field].monthly ? '월 ' : ''}${formatWon(value)}` : null
    case 'count': return `${String(value)}${FIELD_COPY[field].countUnit ?? '명'}`
    case 'familyType': return FAMILY_TYPE_COPY[value as FamilyType]?.label ?? null
    case 'dateEvent': return isDateEvent(value) && value.date ? `${formatDots(value.date)} ${EVENT_SHORT[value.kind]}` : null
  }
}
