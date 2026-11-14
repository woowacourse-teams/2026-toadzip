import type { JsonUpdateInput, JsonWorkspace } from '../announcement-json/api'
import type { SupplyWorkspace } from '../supply/supplyCorrectionApi'
import type { QuotaKind } from '../supply/supplySheet'

type SheetLike = { housingTypes: { key: string; housingComplexId: number | null; name: string; exclusiveArea: number | null; housingTypeId: number | null; newHousingType?: unknown }[]
  cells: { key: string; housingType: string }[]; quotas: { kind: QuotaKind; count: number; members: string[] }[] }
/** 주택형마다 멤버가 모두 그 주택형 칸인 모집 묶음의 합. 저장하면 키가 바뀌어도 같은 값이다. */
function typeCounts(sheet: SheetLike) {
  const typeOf = new Map(sheet.cells.map(cell => [cell.key, cell.housingType])), counts = new Map<string, number>()
  for (const quota of sheet.quotas) {
    const types = new Set(quota.members.map(member => typeOf.get(member)))
    const [only] = types
    if (quota.kind === 'WINNER' && types.size === 1 && only) counts.set(only, (counts.get(only) ?? 0) + quota.count)
  }
  return counts
}
/**
 * 같은 요청을 다시 보내 409(ADMIN_DATA_CONFLICT)를 받았을 때, 보내려던 공급표가 최신 공급표에 이미 들어 있는지.
 * 새 주택형은 저장 후 키가 바뀌므로 단지·표기·전용면적·모집수·주택타입으로 대응하고, 주택형 수도 같아야 한다(PDF에 없는 주택형은 지워졌어야 한다).
 * 주택타입을 비워 보낸 주택형은 서버가 같은 이름으로 정하므로 주택타입은 비교하지 않는다.
 */
export function correctionReflected(intended: SheetLike, latest: Pick<SupplyWorkspace, 'sheet'>) {
  const intendedCounts = typeCounts(intended), latestCounts = typeCounts(latest.sheet)
  const signature = (type: SheetLike['housingTypes'][number], count: number | undefined, typed: boolean) =>
    JSON.stringify([type.housingComplexId, type.name, type.exclusiveArea, count ?? null, typed ? type.housingTypeId : null])
  const pool = [...latest.sheet.housingTypes]
  if (pool.length !== intended.housingTypes.length) return false
  return intended.housingTypes.every(type => {
    const typed = type.housingTypeId !== null
    const at = pool.findIndex(saved => signature(saved, latestCounts.get(saved.key), typed) === signature(type, intendedCounts.get(type.key), typed))
    if (at < 0) return false
    pool.splice(at, 1)
    return true
  })
}

/**
 * 같은 요청을 이미 처리했다는 응답일 때, 이 요청이 고친 기본정보·접수처와 일정이 최신값에 들어 있는지.
 * 공급표만 대조하면 기본정보·일정만 고친 요청은 저장되지 않았어도 반영된 것으로 보이므로 함께 확인한다.
 */
export function documentReflected(input: Pick<JsonUpdateInput, 'document' | 'fields' | 'schedules'>, latest: Pick<JsonWorkspace, 'detail' | 'events'>) {
  const at = (root: unknown, path: string) => path.split('.').reduce<unknown>((node, key) => typeof node === 'object' && node !== null ? (node as Record<string, unknown>)[key] : undefined, root)
  const same = (left: unknown, right: unknown) => JSON.stringify(left ?? null) === JSON.stringify(right ?? null)
  const fields = input.fields.every(field => same(at(latest.detail.data, field), at(input.document, field === 'receptionPlaces' ? field : `announcement.${field}`)))
  const keys = ['scheduleType', 'name', 'startAt', 'endAt']
  const schedules = input.schedules.every(selection => latest.events.some(event => (selection.existingId === null || event.id === selection.existingId)
    && keys.every(key => same(event.data[key], input.document.schedules[selection.inputIndex]?.[key]))))
  return fields && schedules
}
