import { expect, it } from 'vitest'
import { blankRow, parseSupplyWorkspace } from './supplyCorrectionApi'

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
