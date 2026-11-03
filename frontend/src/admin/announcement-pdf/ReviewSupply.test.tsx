import { useState } from 'react'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, it, vi } from 'vitest'
import { ReviewSupply } from './ReviewSupply'
import { scenario, separateHansville } from './fixtures/workspace'
import fixtures from './fixtures/scenarios.json'
import type { Review, ReviewFilter } from './review'

function renderSupply(name: Parameters<typeof scenario>[0], prepare: (review: Review) => void, filter: ReviewFilter = 'all', savesHousingTypes = true, extraction?: unknown, warnings: string[] = []) {
  const { review, workspace, housing } = scenario(name, extraction)
  prepare(review)
  const changed = vi.fn<(review: Review) => void>()
  function Test() {
    const [current, setCurrent] = useState(review)
    return <ReviewSupply review={current} workspace={workspace} housing={housing} complexes={{}} filter={filter} savesHousingTypes={savesHousingTypes} warnings={warnings} empty="없음" onChange={next => { setCurrent(next); changed(next) }} onEvidence={() => {}} selected={0} onSelect={() => {}} />
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
  const changed = renderSupply('hansville', () => {}, 'changed', true, separateHansville())
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
  renderSupply('hansville', review => { review.extraction.checks = ['화면 합계 검산 불일치'] }, 'pending', true, undefined, ['supplyRows[0]: 워커 경고'])
  // 안내는 접힌 한 줄 요약으로 시작하고, 접혀 있어도 워커 경고 건수를 보인다.
  expect(screen.getByRole('list', { name: '안내 요약' })).toHaveTextContent('검산 확인 1건')
  expect(screen.queryByRole('note', { name: '검산 결과' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '안내 펼치기' }))
  const note = screen.getByRole('note', { name: '검산 결과' })
  expect(within(note).getByText('supplyRows[0]: 워커 경고')).toBeInTheDocument()
  expect(within(note).queryByText('화면 합계 검산 불일치')).not.toBeInTheDocument()
})

it('세부 타입 묶음 행이 든 공통 모집수는 저장하지 못하는 까닭과 직접 입력할 모집수를 알린다', () => {
  const extraction = structuredClone(fixtures.hansville.extraction) as unknown as { data: { supplyRows: Record<string, unknown>[] } }
  const [first] = extraction.data.supplyRows
  extraction.data.supplyRows = [{ ...first, sourceHousingTypeName: '26A/B', exclusiveArea: null, supplyArea: null, subtypes: [
    { name: '26A', exclusiveArea: 26.5195, commonArea: null, supplyArea: null, constructedHouseholdCount: null },
    { name: '26B', exclusiveArea: 26.4673, commonArea: null, supplyArea: null, constructedHouseholdCount: null }] },
  { ...first, sourceHousingTypeName: '36', exclusiveArea: 36.1, targets: [{ ...(first.targets as Record<string, unknown>[])[0], rentalDeposit: 9000000 }] }]
  renderSupply('hansville', () => {}, 'all', true, extraction)
  fireEvent.click(screen.getByRole('button', { name: '안내 펼치기' }))
  expect(screen.getByText(/세부 타입 묶음 행은 공통 모집 묶음에 넣을 수 없어 공통 모집수를 저장하지 않습니다\. 세부 타입 묶음 행의 모집수는 직접 입력하고/)).toBeInTheDocument()
  expect(screen.queryByText(/기존 공급행 없이 새로 만드는 행끼리는/)).not.toBeInTheDocument()
})

it('공급정보는 고른 행이 속한 단지의 행만 목록·편집 영역에 두고, 다른 단지 행을 고르면 편집 영역과 주택형 패널이 그 단지로 바뀌며 앞 행의 편집은 남는다', () => {
  const { review, workspace, housing } = scenario('gwangju')
  function Test() {
    const [current, setCurrent] = useState(review), [selected, setSelected] = useState(0)
    return <><button type="button" onClick={() => setSelected(selected === 0 ? 3 : 0)}>다른 단지 탭</button>
      <ReviewSupply review={current} workspace={workspace} housing={housing} complexes={{}} filter="all" empty="없음" onChange={setCurrent} onEvidence={() => {}} selected={selected} onSelect={setSelected} /></>
  }
  render(<MemoryRouter><Test /></MemoryRouter>)
  const list = screen.getByRole('region', { name: '공급행 목록' })
  const groups = () => [...list.querySelectorAll('th[scope=rowgroup]')].map(header => header.textContent)
  expect(groups()).toHaveLength(1)
  expect(groups()[0]).toMatch(/^첨단H-1BL · 주택형 1개 · 모집 합/)
  const first = within(screen.getByRole('region', { name: '공급행 1 검토' })).getByRole('group', { name: '공급행 1 이 공고의 주택형 표기 반영 방법' })
  fireEvent.click(within(first).getByRole('button', { name: '직접 입력' }))
  fireEvent.change(screen.getByRole('textbox', { name: '공급행 1 이 공고의 주택형 표기 수정' }), { target: { value: '39 관리자' } })
  fireEvent.click(screen.getByRole('button', { name: '단지 주택형 보기' }))
  const panelTitle = () => screen.getByRole('button', { name: '주택형 패널 닫기' }).closest('aside')!.getAttribute('aria-label')
  const before = panelTitle()
  fireEvent.click(screen.getByRole('button', { name: '다른 단지 탭' }))
  expect(groups()[0]).toMatch(/^첨단H-3BL · 주택형 4개/)
  expect(within(list).getAllByRole('button', { name: /^공급행 \d 편집$/ }).map(button => button.textContent?.split('.')[0])).toEqual(['4', '5', '6', '7'])
  expect(screen.getByRole('heading', { name: /^첨단H-3BL주택형 4개 중 공급행 4/ })).toBeVisible()
  expect(panelTitle()).not.toBe(before)
  fireEvent.click(screen.getByRole('button', { name: '다른 단지 탭' }))
  expect(screen.getByRole('textbox', { name: '공급행 1 이 공고의 주택형 표기 수정' })).toHaveValue('39 관리자')
})
