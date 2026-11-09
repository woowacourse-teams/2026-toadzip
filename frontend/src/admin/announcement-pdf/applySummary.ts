import type { JsonUpdateInput, JsonWorkspace } from '../announcement-json/api'
import type { CorrectionRow, SupplyWorkspace } from '../supply/supplyCorrectionApi'

/**
 * 같은 요청을 다시 보내 409(ADMIN_DATA_CONFLICT)를 받았을 때, 보내려던 공급행이 최신 공급행에 이미 들어 있는지.
 * 새 행은 저장 후 키가 바뀔 수 있어 단지·표기·모집수·전용면적·주택형으로 대응한다. 주택형을 비워 보낸 행은 서버가 전용면적으로 정하므로
 * 주택형은 비교하지 않는다.
 */
export function correctionReflected(intended: CorrectionRow[], latest: Pick<SupplyWorkspace, 'rows'>) {
  const signature = (row: CorrectionRow, typed: boolean) => JSON.stringify([row.housingComplexId, row.typeName, row.householdCount, row.exclusiveArea, typed ? row.housingTypeId : null, row.excluded])
  const pool = [...latest.rows]
  return intended.every(row => {
    const typed = row.housingTypeId !== null || row.newHousingType !== null
    const at = pool.findIndex(saved => signature(saved, typed) === signature(row, typed))
    if (at < 0) return false
    pool.splice(at, 1)
    return true
  })
}

/**
 * 같은 요청을 이미 처리했다는 응답일 때, 이 요청이 고친 기본정보·접수처와 일정이 최신값에 들어 있는지.
 * 공급행만 대조하면 기본정보·일정만 고친 요청은 저장되지 않았어도 반영된 것으로 보이므로 함께 확인한다.
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
