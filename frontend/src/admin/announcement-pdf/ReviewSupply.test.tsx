import { useState } from 'react'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
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
  expect(within(list).getAllByRole('button', { name: /^\d+\. / }).map(button => button.textContent?.split('.')[0])).toEqual(['4', '5', '6', '7'])
  expect(screen.getByRole('heading', { name: '4. 16(대학생,청년)' })).toBeVisible()
  expect(panelTitle()).not.toBe(before)
  fireEvent.click(screen.getByRole('button', { name: '다른 단지 탭' }))
  expect(screen.getByRole('textbox', { name: '공급행 1 이 공고의 주택형 표기 수정' })).toHaveValue('39 관리자')
})

/** 대구 공고: LH가 통합행 #747(240세대, 면적 없음) 하나로 준 단지를 PDF는 051.9800A 200 · 059.9100A 25 · 059.9300B 15로 적었다. */
function renderDaegu(options: Parameters<typeof daegu>[0] = {}) {
  const { review, workspace, housing } = daegu(options)
  const jump = vi.fn<(target: string) => void>()
  function Test() {
    const [current, setCurrent] = useState(review), [selected, setSelected] = useState(0)
    return <ReviewSupply review={current} workspace={workspace} housing={housing} complexes={{}} filter="all" empty="없음" onChange={setCurrent} onEvidence={() => {}} selected={selected} onSelect={setSelected} onJump={jump} />
  }
  render(<MemoryRouter><Test /></MemoryRouter>)
  const show = (row: number) => fireEvent.click(within(screen.getByRole('region', { name: '공급행 목록' })).getByRole('button', { name: new RegExp(`^${row}\\. `) }))
  const placeOf = (row: number) => { show(row); return screen.getByLabelText(`공급행 ${row} 저장할 곳`).textContent }
  return { jump, show, placeOf, match: () => screen.getByRole('region', { name: 'LH·PDF 대응' }) }
}

it('모집수가 아직 PDF 값으로 정해지지 않았어도 대응의 PDF 합계는 PDF 값으로 세고, 나누기를 고르면 나눌 행의 모집수를 PDF 값으로 정한다', () => {
  const { match } = renderDaegu({ unresolvedCount: true, lhSourceRow: true })
  expect(within(match()).getByRole('list', { name: 'PDF 행' }).nextElementSibling).toHaveTextContent('합계 240세대')
  const decision = within(match()).getByRole('group', { name: "LH 행 '공공임대 240세대' 대응" })
  const fix = within(decision).queryByRole('button', { name: 'PDF 모집수로 정하기' })
  fireEvent.click(fix ?? within(decision).getByRole('button', { name: 'PDF대로 3행으로 나누기 (권장)' }))
  expect(decision).toHaveTextContent('#747(240세대)이 051.9800A 200세대가 되고 059.9100A 25세대 · 059.9300B 15세대가 새로 생깁니다.')
  expect(within(decision).queryByRole('button', { name: 'PDF 모집수로 정하기' })).not.toBeInTheDocument()
})

it('LH 통합행 240세대를 PDF가 세 행으로 적은 단지는 탭 위 대응에서 LH와 PDF 행을 나란히 두고 권장 나누기를 물으며, 고르면 결과를 쉬운 말로 보인다', () => {
  const { jump, placeOf, match } = renderDaegu()
  expect(within(match()).getByRole('list', { name: 'LH가 준 행' })).toHaveTextContent('공공임대 · 면적 없음 · 240세대')
  expect(within(match()).getAllByRole('listitem').filter(item => item.closest('ul')?.getAttribute('aria-label') === 'PDF 행').map(item => item.textContent))
    .toEqual(['1. 051.9800A · 전용 51.98㎡ · 200세대', '2. 059.9100A · 전용 59.91㎡ · 25세대', '3. 059.9300B · 전용 59.93㎡ · 15세대'])
  const decision = within(match()).getByRole('group', { name: "LH 행 '공공임대 240세대' 대응" })
  expect(decision).toHaveTextContent("LH는 '공공임대' 주택형을 1행(240세대, 면적 없음)으로, PDF는 3행(200·25·15, 합계 240)으로 적었습니다.")
  // 행마다 고르던 저장 방식 라디오와 AI 대응 문장은 보이지 않는다.
  expect(screen.queryByRole('radio')).not.toBeInTheDocument()
  expect(screen.queryByText(/AI가|통합행 분리를 제안/)).not.toBeVisible()
  expect(screen.getByRole('region', { name: '공급행 1 검토' })).toHaveTextContent('위 LH·PDF 대응에서 나눌지 1행으로 둘지 고르세요.')
  fireEvent.click(within(screen.getByRole('region', { name: '공급행 1 검토' })).getByRole('button', { name: 'LH·PDF 대응 보기' }))
  expect(jump).toHaveBeenLastCalledWith('match:0')
  fireEvent.click(within(decision).getByRole('button', { name: 'PDF대로 3행으로 나누기 (권장)' }))
  expect(within(decision).getByRole('button', { name: 'PDF대로 3행으로 나누기 (권장)' })).toHaveAttribute('aria-pressed', 'true')
  expect(decision).toHaveTextContent('#747(240세대)이 051.9800A 200세대가 되고 059.9100A 25세대 · 059.9300B 15세대가 새로 생깁니다.')
  // 나누기를 고르면 세 행 모두 대응을 확인한 것으로 본다.
  expect(screen.queryByRole('button', { name: /^공급행 \d 대응 확인$/ })).not.toBeInTheDocument()
  expect(placeOf(1)).toBe('#747(240세대)을 나눈 첫 행입니다. 저장하면 #747이 이 주택형이 됩니다.')
  expect(placeOf(2)).toBe('#747(240세대)을 나눈 행입니다. 새 공급정보로 저장합니다.')
  expect(within(screen.getByRole('region', { name: '공급행 목록' })).getAllByRole('row').slice(2).map(row => row.children[3].textContent)).toEqual(['#747 나눔', '#747 나눔', '#747 나눔'])
})

it('LH대로 1행 유지를 고르면 첫 PDF 행이 #747을 고치고 나머지 행은 반영하지 않는다고 보이며, 첫 행 모집수를 그대로 두면 두 번 세지 않아 오류가 없다', () => {
  const { placeOf, match } = renderDaegu()
  const decision = within(match()).getByRole('group', { name: "LH 행 '공공임대 240세대' 대응" })
  fireEvent.click(within(decision).getByRole('button', { name: 'LH대로 1행 유지' }))
  expect(within(decision).getByRole('button', { name: 'LH대로 1행 유지' })).toHaveAttribute('aria-pressed', 'true')
  expect(decision).toHaveTextContent('#747(240세대)을 051.9800A 행 값으로 고칩니다. 저장하면 모집수는 200세대입니다. 059.9100A · 059.9300B 행은 이번 보정에서 반영하지 않습니다.')
  expect(within(match()).getByRole('list', { name: 'PDF 행' })).toHaveTextContent('2. 059.9100A · 전용 59.91㎡ · 25세대 · 반영 안 함')
  expect(placeOf(1)).toBe('기존 공급정보 #747(공공임대)을 고칩니다.')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.getByRole('checkbox', { name: '공급행 1 적용' })).toBeChecked()
})

it('나누는 첫 행이 합친 모집수 240을 그대로 두면 두 번 센다고 쉬운 말로 알리고 그 행 모집수 칸으로 보낸다', () => {
  const { jump, show } = renderDaegu()
  show(1)
  fireEvent.click(within(screen.getByRole('group', { name: '공급행 1 모집수 반영 방법' })).getByRole('button', { name: '기존 값 사용' }))
  show(2)
  const alert = within(screen.getByRole('region', { name: '공급행 2 검토' })).getByRole('alert')
  expect(alert).toHaveTextContent('공급행 1(051.9800A)은 #747을 이어받는 첫 행인데 모집수가 합친 240세대 그대로라, 나머지 행의 모집수와 두 번 셉니다.')
  fireEvent.click(within(alert).getByRole('button', { name: '공급행 1(051.9800A) 모집수 고치기' }))
  expect(jump).toHaveBeenLastCalledWith('cell:supplyRows[0].totalSupplyHouseholdCount')
})

it('대응 확인 안내는 의심스러울 때만 까닭과 함께 보인다: AI 표시와 같은 곳이면 없고, 바꾸기로 다른 기존 행을 고르면 그 까닭을 보인다', () => {
  const { placeOf } = renderDaegu({ lhSourceRow: true })
  expect(screen.queryByText(/이 대응이 맞는지 확인하세요/)).not.toBeInTheDocument()
  placeOf(3)
  fireEvent.click(screen.getByRole('button', { name: '공급행 3 저장할 곳 바꾸기' }))
  fireEvent.click(within(screen.getByRole('group', { name: '공급행 3 저장할 곳 고르기' })).getByRole('button', { name: /#748 · / }))
  expect(placeOf(3)).toBe('기존 공급정보 #748(39)을 고칩니다.')
  expect(screen.getByRole('region', { name: '공급행 3 검토' })).toHaveTextContent("이 대응이 맞는지 확인하세요. AI는 이 행을 LH 행 '공공임대 240세대'에서 나온 행으로 봤지만, 지금은 LH 행 '84 5세대'에 저장합니다.")
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

it('저장할 곳 확인이 단지 단위 결정을 기다리면 상태 배지는 그 결정으로 옮긴다: LH 통합행 나누기는 LH·PDF 대응, 단지 미정은 단지 정하기', () => {
  const { jump } = renderDaegu()
  fireEvent.click(within(screen.getByRole('region', { name: '공급행 목록' })).getByRole('button', { name: '공급행 1 확인 필요: LH·PDF 대응 확인하러 가기' }))
  expect(jump).toHaveBeenLastCalledWith('match:0')
  cleanup()
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
  extraction.sharedCounts = [{ count: 80, paths: ['supplyRows[1].totalSupplyHouseholdCount', 'supplyRows[2].totalSupplyHouseholdCount'], page: 2, quote: '80' }]
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
