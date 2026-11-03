import { useState } from 'react'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, it, vi } from 'vitest'
import { ReviewSupply } from './ReviewSupply'
import { scenario } from './fixtures/workspace'
import type { Review, ReviewFilter } from './review'

function renderSupply(name: Parameters<typeof scenario>[0], prepare: (review: Review) => void, filter: ReviewFilter = 'all', savesHousingTypes = true, warnings: string[] = []) {
  const { review, workspace, housing } = scenario(name)
  prepare(review)
  const changed = vi.fn<(review: Review) => void>()
  function Test() {
    const [current, setCurrent] = useState(review)
    return <ReviewSupply review={current} workspace={workspace} housing={housing} complexes={{}} filter={filter} savesHousingTypes={savesHousingTypes} warnings={warnings} empty="없음" onChange={next => { setCurrent(next); changed(next) }} onEvidence={() => {}} />
  }
  render(<MemoryRouter><Test /></MemoryRouter>)
  return changed
}

it('이전 PDF 보정에서 고른 주택형 가격은 확인 필요 필터에서도 보이고, 지금 가격 유지를 고르면 확인을 마친다', () => {
  const changed = renderSupply('hansville', review => {
    const row = review.rows[0]
    row.proposal = { ...row.proposal, candidates: row.proposal.candidates.map(type => type.id === 10353 ? { ...type, basicDeposit: 1_000_000, basicMonthlyRent: 10_000, rentalConditionSource: 'ANNOUNCEMENT' as const } : type) }
    // 다른 확인 항목은 모두 마쳐, 이 행이 주택형 가격 때문에만 확인 필요 필터에 남게 한다.
    const reviewed = (cells: typeof row.cells) => cells.map(cell => ({ ...cell, reviewed: true }))
    Object.assign(row, { reviewed: true, cells: reviewed(row.cells), targets: row.targets.map(target => ({ ...target, cells: reviewed(target.cells) })) })
  }, 'pending')
  const price = screen.getByRole('region', { name: '공급행 1 주택형 가격' })
  expect(within(price).getByText('확인 필요')).toBeInTheDocument()
  expect(within(price).getByRole('combobox', { name: '공급행 1 주택형 가격으로 쓸 대상' })).toHaveValue('')
  fireEvent.click(within(price).getByRole('button', { name: '공급행 1 지금 가격 유지' }))
  expect(changed.mock.lastCall![0].rows[0].priceTarget).toBeNull()
})

it('검토 화면에서 고른 가격 대상과 지금 값 유지를 저장할 선택으로 남긴다', () => {
  const changed = renderSupply('hansville', () => {}, 'changed')
  const select = screen.getByRole('combobox', { name: '공급행 1 주택형 가격으로 쓸 대상' })
  fireEvent.change(select, { target: { value: '' } })
  expect(changed.mock.lastCall![0].rows[0].priceTarget).toBeNull()
  fireEvent.change(select, { target: { value: '0' } })
  expect(changed.mock.lastCall![0].rows[0].priceTarget).toBe(0)
})

it('PDF로 공급정보 편집표만 채울 때는 저장하지 않는 주택형 가격 칸을 보이지 않는다', () => {
  renderSupply('hansville', () => {}, 'all', false)
  expect(screen.queryByRole('region', { name: '공급행 1 주택형 가격' })).not.toBeInTheDocument()
  expect(screen.queryByText(/연결한 단지 주택형 이름도 이 표기로 갱신합니다/)).not.toBeInTheDocument()
})

it('AI 워커 경고는 필터와 관계없이 보이고, 화면 검산은 전체 보기에서만 보인다', () => {
  renderSupply('hansville', review => { review.extraction.checks = ['화면 합계 검산 불일치'] }, 'pending', true, ['supplyRows[0]: 워커 경고'])
  const note = screen.getByRole('note', { name: '검산 결과' })
  expect(within(note).getByText('supplyRows[0]: 워커 경고')).toBeInTheDocument()
  expect(within(note).queryByText('화면 합계 검산 불일치')).not.toBeInTheDocument()
})
