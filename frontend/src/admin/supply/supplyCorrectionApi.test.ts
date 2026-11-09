import { expect, it } from 'vitest'
import { blankRow, parseCorrectionInput, parseSupplyWorkspace } from './supplyCorrectionApi'

it('지번을 모르는 공급행의 PNU(null)는 빈 칸으로 받는다', () => {
  const row = { ...blankRow(0), id: 1, key: 'row:1', complexName: '화성 리츠', typeName: '36', pnu: null }
  const workspace = parseSupplyWorkspace({ snapshotToken: 't', version: 0, rows: [row], sources: [] })
  expect(workspace.rows[0].pnu).toBe('')
})

it('공고 단지의 연결 방법은 AUTO·ADMIN·MIGRATED이거나 미연결(null)만 받는다', () => {
  const complex = { id: 1, name: '화성동탄2A-40', roadAddress: null, housingComplexId: 7, housingComplexName: '센트럴힐즈', linkMethod: 'AUTO', rowCount: 3 }
  expect(parseSupplyWorkspace({ snapshotToken: 't', version: 0, rows: [], sources: [], announcementComplexes: [complex] }).announcementComplexes?.[0].linkMethod).toBe('AUTO')
  expect(() => parseSupplyWorkspace({ snapshotToken: 't', version: 0, rows: [], sources: [], announcementComplexes: [{ ...complex, linkMethod: 'GUESS' }] })).toThrow()
})

it('이전 화면이 남긴 보류 적용 입력은 지금 서버가 받지 않는 필드(newGroups·saveMode)를 빼고 복원한다', () => {
  const row = { ...blankRow(0), id: 1, key: 'row:1', complexName: '화성 리츠', typeName: '36', pnu: '', saveMode: 'SPLIT', lhSourceRowIdentifier: 'LH:P:SUPPLY:0' }
  const input = parseCorrectionInput({ operationId: 'op', snapshotToken: 't', reason: '공고문', sourceUrl: 'https://example.com', sourcePage: null,
    rows: [row], newGroups: [{ sourceRowId: 1, rowKeys: ['row:1'], sharedHouseholdCount: 3 }], deletedRowKeys: ['row:9'] })
  expect(input).not.toHaveProperty('newGroups')
  expect(input.rows[0]).not.toHaveProperty('saveMode')
  expect(input.rows[0]).not.toHaveProperty('lhSourceRowIdentifier')
  expect(input).toMatchObject({ operationId: 'op', deletedRowKeys: ['row:9'], rows: [{ key: 'row:1', typeName: '36' }] })
})
