import { useState } from 'react'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, it, vi } from 'vitest'
import { ReviewSupply } from './ReviewSupply'
import { daegu, scenario, separateHansville } from './fixtures/workspace'
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
  const changed = renderSupply('hansville', () => {}, 'all', true, separateHansville())
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

it('단지가 다른 공급행끼리 함께 쓰는 공통 모집수는 공급행 모집수로 정하지 않는다고 알린다', () => {
  const extraction = structuredClone(fixtures.gwangju.extraction) as unknown as { sharedCounts: unknown[] }
  extraction.sharedCounts = [{ count: 80, paths: ['supplyRows[1].totalSupplyHouseholdCount', 'supplyRows[3].totalSupplyHouseholdCount'], page: 2, quote: '80' }]
  const { review, workspace, housing } = scenario('gwangju', extraction)
  render(<MemoryRouter><ReviewSupply review={review} workspace={workspace} housing={housing} complexes={{}} filter="all" empty="없음" onChange={() => {}} onEvidence={() => {}} selected={1} onSelect={() => {}} /></MemoryRouter>)
  fireEvent.click(screen.getByRole('button', { name: '안내 펼치기' }))
  expect(screen.getByText(/단지가 다른 공급행끼리 함께 쓰는 모집수라 공급행 모집수로 정하지 않습니다/)).toBeInTheDocument()
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
  // 같은 면적 26형 두 PDF 행은 공급행 하나로 접혀 이 단지의 주택형은 셋이다.
  expect(groups()[0]).toMatch(/^첨단H-3BL · 주택형 3개/)
  expect(within(list).getAllByRole('button', { name: /^\d+\. / }).map(button => button.textContent?.split('.')[0])).toEqual(['4', '5', '6'])
  expect(screen.getByRole('heading', { name: '4. 16(대학생,청년)' })).toBeVisible()
  expect(panelTitle()).not.toBe(before)
  fireEvent.click(screen.getByRole('button', { name: '다른 단지 탭' }))
  expect(screen.getByRole('textbox', { name: '공급행 1 이 공고의 주택형 표기 수정' })).toHaveValue('39 관리자')
})

/** 대구 공고: 옛 LH 통합행 #747(240세대, 면적 없음)이 있는 단지를 PDF는 051.9800A 200 · 059.9100A 25 · 059.9300B 15로 적었다. */
function renderDaegu() {
  const { review, workspace, housing } = daegu()
  const changed = vi.fn<(review: Review) => void>()
  function Test() {
    const [current, setCurrent] = useState(review), [selected, setSelected] = useState(0)
    return <ReviewSupply review={current} workspace={workspace} housing={housing} complexes={{}} filter="all" empty="없음" onChange={next => { setCurrent(next); changed(next) }} onEvidence={() => {}} selected={selected} onSelect={setSelected} />
  }
  render(<MemoryRouter><Test /></MemoryRouter>)
  const show = (row: number) => fireEvent.click(within(screen.getByRole('region', { name: '공급행 목록' })).getByRole('button', { name: new RegExp(`^${row}\\. `) }))
  const placeOf = (row: number) => { show(row); return screen.getByLabelText(`공급행 ${row} 저장할 곳`).textContent }
  return { changed, show, placeOf }
}

it('전용면적을 모르는 LH 통합행은 PDF 행과 맞추지 않아 PDF 행은 확인 없이 새 공급정보가 되고, 남는 통합행은 이번 보정에서 제외할 수 있다', () => {
  const { changed, placeOf } = renderDaegu()
  // LH 행을 나누거나 붙이는 단지 대응은 없다.
  expect(screen.queryByRole('region', { name: 'LH·PDF 대응' })).not.toBeInTheDocument()
  expect([1, 2, 3].map(placeOf)).toEqual(['새 공급정보로 추가합니다.', '새 공급정보로 추가합니다.', '새 공급정보로 추가합니다.'])
  expect(screen.queryByText('저장할 곳이 맞는지 확인하세요.')).not.toBeInTheDocument()
  expect(within(screen.getByRole('region', { name: '공급행 목록' })).getAllByRole('row').slice(2).map(row => row.children[3].textContent)).toEqual(['새로 추가', '새로 추가', '새로 추가'])
  expect(within(screen.getByRole('list', { name: '안내 요약' })).getByText('PDF에 없는 기존 공급행 1행')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '안내 펼치기' }))
  const leftovers = screen.getByRole('note', { name: 'PDF에 없는 기존 공급행' })
  // 다른 단지(#748)의 행은 이 단지의 남는 행이 아니다.
  expect(within(leftovers).getAllByRole('listitem').map(item => item.firstChild?.textContent)).toEqual(['#747 공공임대 · 240세대 · 면적 미확인'])
  fireEvent.click(within(leftovers).getByRole('button', { name: '#747 이번 보정에서 제외' }))
  expect(changed.mock.lastCall![0].excludedRows).toEqual(['row:747'])
  expect(within(leftovers).getByRole('button', { name: '#747 제외 취소' })).toHaveAttribute('aria-pressed', 'true')
})

it('주택형을 고르게 하지 않고 저장할 때 같은 단지·전용면적 주택형에 연결한다고 미리 알린다', () => {
  const { show } = renderDaegu()
  show(1)
  const row = screen.getByRole('region', { name: '공급행 1 검토' })
  expect(row).toHaveTextContent("연결 주택형: 저장하면 같은 전용면적의 '51A'(전용 51.98㎡)에 연결합니다.")
  expect(row).not.toHaveTextContent('맞는 주택형 없음')
  expect(within(screen.getByRole('region', { name: '공급행 목록' })).getAllByRole('row').slice(2).map(item => item.children[4].textContent)).not.toContain('주택형 미연결')
})

it('공급행에 붙은 LH 줄은 줄 수·합계를 검산 정보로만 보이고, 같은 주택형의 옛 공급행을 함께 제외한다고 알린다', () => {
  const { review, workspace, housing } = scenario('gwangju')
  const lh = (order: number, attachedRowKey: string, householdCount: number) => ({ rowIdentifier: `LH:P:SUPPLY:${order}`, complexLabel: '광주첨단H-3BL', typeName: '26', exclusiveArea: '26.97', householdCount, attachedRowKey, ambiguous: false })
  const withLh = { ...workspace, supply: { ...workspace.supply, lhSupplyRows: [lh(1, 'row:26', 7), lh(2, 'row:27', 9)] } }
  const index = review.rows.findIndex(row => row.proposal.destination === 'row:26')
  render(<MemoryRouter><ReviewSupply review={review} workspace={withLh} housing={housing} complexes={{}} filter="all" empty="없음" onChange={() => {}} onEvidence={() => {}} selected={index} onSelect={() => {}} /></MemoryRouter>)
  const row = screen.getByRole('region', { name: `공급행 ${index + 1} 검토` })
  expect(within(row).getByRole('note', { name: `공급행 ${index + 1} LH 검산` })).toHaveTextContent('LH 2줄 합 16세대 · PDF 모집수 20세대와 4세대 다릅니다. 공고문과 대조하세요(저장은 막지 않습니다).')
  expect(row).toHaveTextContent('옛 공급행 #27(26형(주거급여수급자))은 이 공급행에 합쳐지므로 저장하면 제외합니다')
  expect(within(row).getByLabelText(`공급행 ${index + 1} 저장할 곳`)).toHaveTextContent('기존 공급정보 #26(26형(고령(주거약자용)))을 고칩니다.')
  expect(row).not.toHaveTextContent('저장할 곳이 맞는지 확인하세요.')
})

it('금액은 임대조건 표와 조건 대조 칸 모두 세 자리마다 쉼표를 넣어 보이고, 직접 입력 칸에는 쉼표 없는 원 단위 값을 둔다', () => {
  renderSupply('gwangju', () => {})
  const table = screen.getByRole('region', { name: '공급행 1 임대조건 표' })
  expect(within(table).getAllByText('41,848,000').length).toBeGreaterThan(0)
  expect(within(table).queryByText('41848000')).not.toBeInTheDocument()
  expect(within(table).getAllByText('226,670').length).toBeGreaterThan(0)
  fireEvent.click(within(table).getByRole('button', { name: '조건 1 대조·수정' }))
  const condition = screen.getByRole('region', { name: '공급행 1 조건 1 수정' })
  expect(within(condition).getAllByText('41,848,000').length).toBeGreaterThan(0)
  fireEvent.click(within(within(condition).getByRole('group', { name: '공급행 1 조건 1 보증금 (원) 반영 방법' })).getByRole('button', { name: '직접 입력' }))
  expect(within(condition).getByRole('textbox', { name: '공급행 1 조건 1 보증금 (원) 수정' })).toHaveValue('41848000')
})

it('단지를 정하지 않은 행의 상태 배지는 단지 정하기로 옮긴다', () => {
  const { review, workspace, housing } = scenario('gwangju')
  workspace.supply.rows.forEach(row => { row.housingComplexId = null })
  const moved = vi.fn<(target: string) => void>()
  // 확인 필요 필터에서도 단지 정하기는 숨기지 않는다(접는 안내가 아니라 단지 영역 맨 위에 늘 보인다).
  render(<MemoryRouter><ReviewSupply review={review} workspace={workspace} housing={housing} complexes={{}} filter="pending" empty="없음" onChange={() => {}} onEvidence={() => {}} selected={0} onSelect={() => {}} onJump={moved} /></MemoryRouter>)
  expect(screen.getByRole('region', { name: 'PDF 단지 정하기' })).toBeVisible()
  fireEvent.click(within(screen.getByRole('region', { name: '공급행 목록' })).getByRole('button', { name: '공급행 1 확인 필요: 단지 정하기 확인하러 가기' }))
  expect(moved).toHaveBeenLastCalledWith('complex')
})

it('상태 배지는 남은 확인을 종류별 개수로 알리고, 누르면 편집 영역 순서로 첫 확인 항목(값 칸)으로 옮긴다', () => {
  const { review, workspace, housing } = scenario('gwangju')
  const row = review.rows[0]
  row.reviewed = true
  const [first, second] = row.cells.filter(cell => !cell.locked && cell.field !== 'housingTypeId')
  for (const cell of [first, second]) { cell.mode = 'extracted'; cell.reviewed = false; cell.extracted = { ...cell.extracted, state: 'confirm' } }
  const moved = vi.fn<(target: string) => void>()
  render(<MemoryRouter><ReviewSupply review={review} workspace={workspace} housing={housing} complexes={{}} filter="all" empty="없음" onChange={() => {}} onEvidence={() => {}} selected={0} onSelect={() => {}} onJump={moved} /></MemoryRouter>)
  fireEvent.click(within(screen.getByRole('region', { name: '공급행 목록' })).getByRole('button', { name: '공급행 1 확인 필요: 값 2 확인하러 가기' }))
  expect(moved).toHaveBeenLastCalledWith(`cell:${first.path}`)
})

it('공통 모집 안내는 지금 단지의 행이 든 묶음만 보인다', () => {
  const extraction = structuredClone(fixtures.gwangju.extraction) as unknown as { sharedCounts: unknown[] }
  // 단지가 다른 공급행끼리 함께 쓰는 모집수다(같은 단지의 행은 공급행 하나로 접힌다).
  extraction.sharedCounts = [{ count: 80, paths: ['supplyRows[1].totalSupplyHouseholdCount', 'supplyRows[3].totalSupplyHouseholdCount'], page: 2, quote: '80' }]
  const { review, workspace, housing } = scenario('gwangju', extraction)
  function Test() {
    const [selected, setSelected] = useState(0)
    return <><button type="button" onClick={() => setSelected(1)}>첨단H-2BL 보기</button>
      <ReviewSupply review={review} workspace={workspace} housing={housing} complexes={{}} filter="all" empty="없음" onChange={() => {}} onEvidence={() => {}} selected={selected} onSelect={setSelected} /></>
  }
  render(<MemoryRouter><Test /></MemoryRouter>)
  expect(screen.queryByText('공통 모집 80세대')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '첨단H-2BL 보기' }))
  expect(within(screen.getByRole('list', { name: '안내 요약' })).getByText('공통 모집 80세대')).toBeVisible()
})
