import { expect, it } from 'vitest'
import { sampleSheet } from './fixtures/sheet'
import { parseCorrectionInput, parseSupplyWorkspace } from './supplyCorrectionApi'
import { toSheetInput } from './supplySheet'

const workspace = (fields: Record<string, unknown> = {}) => ({ snapshotToken: 't', version: 0, sheet: sampleSheet(), announcementComplexes: [],
  applicationTypeNames: ['46A'], sourceReference: [{ complexLabel: '두꺼비', typeName: '46', exclusiveArea: '46.5', householdCount: 10 }], warnings: [], ...fields })

it('작업실의 공급표·원천 참고를 받고, 칸·묶음이 없는 키를 가리키면 거절한다', () => {
  expect(parseSupplyWorkspace(workspace()).sheet).toEqual(sampleSheet())
  const sheet = sampleSheet()
  expect(() => parseSupplyWorkspace(workspace({ sheet: { ...sheet, cells: [...sheet.cells, { key: 'cell:9', id: 9, housingType: 'row:9', target: 'target:1', displayOrder: 2 }] } }))).toThrow()
  expect(() => parseSupplyWorkspace(workspace({ sheet: { ...sheet, quotas: [{ ...sheet.quotas[0], members: ['cell:1', 'cell:9'] }] } }))).toThrow()
  expect(() => parseSupplyWorkspace(workspace({ sheet: { ...sheet, leaseTerms: [{ ...sheet.leaseTerms[0], members: [] }] } }))).toThrow()
  // 공급행 모양의 이전 응답은 받지 않는다.
  expect(() => parseSupplyWorkspace({ snapshotToken: 't', version: 0, rows: [], sources: [] })).toThrow()
})

it('공고 단지의 연결 방법은 AUTO·ADMIN·MIGRATED이거나 미연결(null)만 받는다', () => {
  const complex = { id: 1, name: '화성동탄2A-40', roadAddress: null, housingComplexId: 7, housingComplexName: '센트럴힐즈', linkMethod: 'AUTO', rowCount: 3 }
  expect(parseSupplyWorkspace(workspace({ announcementComplexes: [complex] })).announcementComplexes[0].linkMethod).toBe('AUTO')
  expect(() => parseSupplyWorkspace(workspace({ announcementComplexes: [{ ...complex, linkMethod: 'GUESS' }] }))).toThrow()
})

it('보관한 적용 입력은 공급표 입력으로만 복원하고, 이전 화면의 공급행 입력은 거절한다', () => {
  const input = { operationId: 'op', snapshotToken: 't', reason: '공고문', sourceUrl: 'https://example.com', sourcePage: null, sheet: toSheetInput(sampleSheet()) }
  expect(parseCorrectionInput(JSON.parse(JSON.stringify(input)))).toEqual(input)
  // 입력의 id는 생략할 수 있다.
  const { id: _id, ...withoutId } = input.sheet.targets[0]
  expect(parseCorrectionInput({ ...input, sheet: { ...input.sheet, targets: [withoutId, input.sheet.targets[1]] } }).sheet.targets[0].id).toBeNull()
  expect(() => parseCorrectionInput({ ...input, sheet: { ...input.sheet, cells: [{ ...input.sheet.cells[0], target: 'target:9' }] } })).toThrow()
  expect(() => parseCorrectionInput({ operationId: 'op', snapshotToken: 't', reason: '공고문', sourceUrl: 'https://example.com', sourcePage: null,
    rows: [{ key: 'row:1' }], deletedRowKeys: [] })).toThrow()
})
