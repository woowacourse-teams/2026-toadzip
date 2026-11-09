import { fireEvent, render, screen, within } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import original from './fixtures/gh-839-extraction.json'
import { withMixedSharedCounts } from './fixtures/mixedSharedCounts'
import { readExtraction, readExtractionDocument, sharedCountIssues } from './extraction'
import { SharedCountsRepair } from './SharedCountsRepair'

function open(text = JSON.stringify(withMixedSharedCounts())) {
  const onRepair = vi.fn()
  render(<SharedCountsRepair text={text} onRepair={onRepair} onEvidence={vi.fn()} />)
  return { onRepair, panel: screen.getByRole('region', { name: '공통 모집수 수정' }) }
}

it('실제 응답에 수량 종류를 섞은 다섯 오류를 모두 표시하고 미수정 상태에서는 교정본을 받지 않는다', () => {
  const { onRepair, panel } = open()
  const groups = within(panel).getAllByRole('group', { name: /^공통 모집수 / })
  expect(groups.map(group => group.querySelector('legend')?.textContent)).toEqual([
    '공통 모집수 10', '공통 모집수 12', '공통 모집수 14', '공통 모집수 15', '공통 모집수 23',
  ])
  expect(groups[0]).toHaveTextContent('16A1')
  expect(groups[0]).toHaveTextContent('16A2')
  fireEvent.click(within(panel).getByRole('button', { name: '수정한 묶음 확인' }))
  expect(screen.getByRole('alert')).toHaveTextContent('서로 다른 수량 필드')
  expect(onRepair).not.toHaveBeenCalled()
})

// 실제 응답 전체(56행)를 렌더하고 수십 번 입력하므로 병렬 실행하는 CI에서 기본 5초를 넘긴다.
it('운영자가 입력한 유효한 묶음으로만 교정하며 정상 24개·56행·근거·미확인·큰 금액을 보존한다', () => {
  const text = JSON.stringify(withMixedSharedCounts()).replace(/"rentalDeposit":\d+/, '"rentalDeposit":123456789012345678')
  const before = readExtractionDocument(text)
  if (!before.ok) throw new Error(before.error)
  const issues = sharedCountIssues(before.value)
  const { onRepair, panel } = open(text)
  for (const { index } of issues) {
    const paths = [...new Set(original.sharedCounts[index].paths.map(path => path.replace(/\.targets\[\d+\]\.supplyHouseholdCount$/, '.totalSupplyHouseholdCount')))]
    const group = within(panel).getByRole('group', { name: `공통 모집수 ${index + 1}` })
    const selects = within(group).getAllByRole('combobox')
    paths.forEach((path, at) => fireEvent.change(selects[at], { target: { value: path } }))
    for (let at = selects.length - 1; at >= paths.length; at--) fireEvent.click(within(group).getByRole('button', { name: `묶음 ${index + 1}의 위치 ${at + 1} 제거` }))
  }
  fireEvent.change(within(panel).getByLabelText('공통 모집수 10'), { target: { value: '8' } })
  fireEvent.click(within(panel).getByRole('button', { name: '수정한 묶음 확인' }))
  expect(onRepair).toHaveBeenCalledOnce()
  const after = readExtractionDocument(onRepair.mock.calls[0][0])
  if (!after.ok) throw new Error(after.error)
  expect(after.value.data).toEqual(before.value.data)
  expect(after.value.evidence).toEqual(before.value.evidence)
  expect(after.value.notApplicable).toEqual(before.value.notApplicable)
  const groups = after.value.sharedCounts as typeof original.sharedCounts
  const source = before.value.sharedCounts as typeof original.sharedCounts
  expect(groups).toHaveLength(29)
  source.forEach((group, index) => { if (!issues.some(issue => issue.index === index)) expect(groups[index]).toEqual(group) })
  expect(groups[9].count).toBe(8)
  const checked = readExtraction(onRepair.mock.calls[0][0])
  if (!checked.ok) throw new Error(checked.error)
  expect(checked.value.supplyRows).toHaveLength(56)
  expect(checked.value.supplyRows[0].targets[0].rentalDeposit).toBe('123456789012345678')
}, 20_000)

it('개별 묶음 형식이 맞아도 다른 정상 묶음과 경로가 겹치면 교정을 완료하지 않는다', () => {
  const { onRepair, panel } = open()
  for (const checkbox of within(panel).getAllByRole('checkbox').slice(1)) fireEvent.click(checkbox)
  const group = within(panel).getByRole('group', { name: '공통 모집수 10' })
  const selects = within(group).getAllByRole('combobox')
  original.sharedCounts[0].paths.forEach((path, at) => fireEvent.change(selects[at], { target: { value: path } }))
  fireEvent.click(within(group).getByRole('button', { name: '묶음 10의 위치 3 제거' }))
  fireEvent.click(within(panel).getByRole('button', { name: '수정한 묶음 확인' }))
  expect(screen.getByRole('alert')).toHaveTextContent('한 공급행을 여러 공통 모집수')
  expect(onRepair).not.toHaveBeenCalled()
})

it('여러 공급행에 걸친 실제 대상 공통 수량은 교정 없이 읽는다', () => {
  expect(sharedCountIssues(original)).toEqual([])
  expect(readExtraction(JSON.stringify(original)).ok).toBe(true)
})
