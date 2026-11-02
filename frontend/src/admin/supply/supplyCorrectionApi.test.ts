import { expect, it } from 'vitest'
import { blankRow, parseSupplyWorkspace } from './supplyCorrectionApi'

it('지번을 모르는 공급행의 PNU(null)는 빈 칸으로 받는다', () => {
  const row = { ...blankRow(0), id: 1, key: 'row:1', complexName: '화성 리츠', typeName: '36', pnu: null }
  const workspace = parseSupplyWorkspace({ snapshotToken: 't', version: 0, rows: [row], sources: [] })
  expect(workspace.rows[0].pnu).toBe('')
})
