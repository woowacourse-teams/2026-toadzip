import { useState } from 'react'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { expect, it } from 'vitest'
import { DestinationChooser } from './DestinationChooser'
import { daegu } from './fixtures/workspace'
import { assembleReview, chooseCellValue, createReview, editCell, rebaseReview, type Review } from './review'

const evidence = { reason: '공고문과 대조', sourceUrl: 'https://example.com/daegu', sourcePage: '3' }

/** 대구테크노폴리스4의 PDF 행 셋(051.9800A·059.9100A·059.9300B)의 저장할 곳 바꾸기를 함께 띄운다. 처음 자동 제안은 셋 다 새 공급행이다. */
function renderChoosers(prepare?: (fixture: ReturnType<typeof daegu>) => Review) {
  const fixture = daegu(), initial = prepare?.(fixture) ?? fixture.review
  let latest = initial
  function Test() {
    const [review, setReview] = useState(initial)
    latest = review
    return <>{review.rows.map((_, index) => <DestinationChooser key={index} review={review} index={index} workspace={fixture.workspace} housing={fixture.housing}
      onChange={row => setReview(current => ({ ...current, rows: current.rows.map((item, at) => at === index ? row : item) }))} />)}</>
  }
  render(<Test />)
  return { ...fixture, current: () => latest, request: () => assembleReview(latest, fixture.workspace, evidence), assembled: () => assembleReview(latest, fixture.workspace, evidence).correction.rows }
}
function openChooser(index: number) {
  fireEvent.click(screen.getByRole('button', { name: `공급행 ${index + 1} 저장할 곳 바꾸기` }))
  return screen.getByRole('group', { name: `공급행 ${index + 1} 저장할 곳 고르기` })
}

it('추천이 틀리면 검색한 다른 기존 공급정보를 고르고, 저장하면 그 행을 고친다', () => {
  const { assembled, current } = renderChoosers()
  const picker = openChooser(0)
  // 열면 추천과 새로 추가만 보이고, 다른 기존 공급정보는 검색해야 나온다.
  expect(within(picker).getByRole('button', { name: '추천 · 새 공급정보로 추가' })).toHaveAttribute('aria-pressed', 'true')
  expect(within(picker).queryByRole('list', { name: '공급행 1 찾은 기존 공급정보' })).not.toBeInTheDocument()
  fireEvent.change(within(picker).getByRole('searchbox', { name: '공급행 1 다른 기존 공급정보 찾기' }), { target: { value: '공공임대' } })
  fireEvent.click(within(picker).getByRole('button', { name: /^대구테크노폴리스4 공공임대 .* #747$/ }))
  expect(screen.getByLabelText('공급행 1 저장할 곳')).toHaveTextContent('고칠 기존 공급정보: 대구테크노폴리스4 공공임대 · 면적 미확인 · 모집 240세대 · LH 공고에서 만든 행 · #747')
  expect(current().rows[0]).toMatchObject({ key: 'row:747', reviewed: true, proposal: { destination: 'row:747', needsConfirmation: false, automatic: { destination: 'new' } } })
  const rows = assembled()
  expect(rows.find(row => row.id === 747)).toMatchObject({ exclusiveArea: 51.98, householdCount: 200, excluded: false })
  // 051.9800A는 #747에 저장되고, 새 공급행은 나머지 두 PDF 행 몫뿐이다.
  expect(rows.filter(row => row.id === null).map(row => row.exclusiveArea)).toEqual([59.91, 59.93])
})

it('추천으로 되돌리면 자동 제안으로 돌아가고, 새 공급행은 앞서 고른 기존 행의 키를 쓰지 않는다', () => {
  const { assembled, current } = renderChoosers()
  fireEvent.change(within(openChooser(0)).getByRole('searchbox'), { target: { value: '#747' } })
  fireEvent.click(screen.getByRole('button', { name: /#747$/ }))
  fireEvent.click(within(openChooser(0)).getByRole('button', { name: '추천 · 새 공급정보로 추가' }))
  expect(current().rows[0].proposal).toMatchObject({ destination: 'new', anchorKey: 'row:747', automatic: undefined })
  expect(current().rows[0].key).not.toBe('row:747')
  const rows = assembled()
  expect(rows.filter(row => row.key === 'row:747')).toHaveLength(1)
  expect(rows.find(row => row.id === 747)).toMatchObject({ typeName: '공공임대', householdCount: 240 })
  expect(rows.filter(row => row.id === null)).toHaveLength(3)
})

it('다른 PDF 행이 저장할 곳으로 쓰는 기존 공급정보는 고를 수 없다', () => {
  renderChoosers()
  fireEvent.change(within(openChooser(0)).getByRole('searchbox'), { target: { value: '#747' } })
  fireEvent.click(screen.getByRole('button', { name: /#747$/ }))
  fireEvent.change(within(openChooser(1)).getByRole('searchbox'), { target: { value: '#747' } })
  expect(screen.getByRole('button', { name: /#747 · 공급행 1이 씀$/ })).toBeDisabled()
})

it('같은 단지에 전용면적이 같은 기존 행을 두고 새로 추가하면, 서버가 거절하지 않게 그 행을 함께 제외한다', () => {
  // #748을 대구테크노폴리스4의 059.9100A 행으로 바꿔, 처음 자동 제안이 그 행을 고치게 한다.
  const { assembled, current } = renderChoosers(({ workspace, housing, review }) => {
    Object.assign(workspace.supply.rows[1], { complexName: '대구테크노폴리스4', housingComplexId: 41, housingComplexName: '대구테크노폴리스4', typeName: '59A', exclusiveArea: 59.91 })
    return createReview(review.extraction, workspace, housing)
  })
  expect(current().rows[1].proposal.destination).toBe('row:748')
  fireEvent.click(within(openChooser(1)).getByRole('button', { name: '새 공급정보로 추가' }))
  expect(current().rows[1].proposal).toMatchObject({ destination: 'new', supersedes: ['row:748'], automatic: { destination: 'row:748' } })
  const rows = assembled()
  expect(rows.find(row => row.id === 748)).toMatchObject({ excluded: true })
  expect(rows.filter(row => row.id === null && !row.excluded).map(row => row.exclusiveArea)).toEqual([51.98, 59.91, 59.93])
})

it('다른 단지의 기존 공급정보를 고르면 그 단지의 주택형으로 저장한다', () => {
  const { current } = renderChoosers()
  fireEvent.change(within(openChooser(0)).getByRole('searchbox'), { target: { value: '다른 단지' } })
  fireEvent.click(screen.getByRole('button', { name: /^다른 단지 .* #748$/ }))
  expect(current().rows[0].proposal).toMatchObject({ destination: 'row:748', anchorKey: 'row:748', complexId: 42, supersedes: undefined })
})

it('저장할 곳을 바꿔도 관리자가 직접 입력한 칸은 이어받고, 손대지 않은 칸은 새 저장할 곳 기준으로 다시 정한다', () => {
  const { assembled } = renderChoosers(({ review }) => {
    const row = review.rows[0]
    return { ...review, rows: review.rows.map((item, at) => at === 0 ? { ...row, cells: row.cells.map(cell => cell.field === 'totalSupplyHouseholdCount' ? editCell(cell, '201') : cell) } : item) }
  })
  fireEvent.change(within(openChooser(0)).getByRole('searchbox'), { target: { value: '#747' } })
  fireEvent.click(screen.getByRole('button', { name: /#747$/ }))
  expect(assembled().find(row => row.id === 747)).toMatchObject({ householdCount: 201, exclusiveArea: 51.98 })
})

/** #748을 대구테크노폴리스4의 39형(#504, 전용 39) 공급행으로 바꾼다. PDF에 없는 면적이라 자동 제안은 이 행을 고르지 않는다. */
function with39({ workspace, housing, review }: ReturnType<typeof daegu>) {
  housing[41] = [...housing[41], { id: 504, name: '39', exclusiveArea: 39, supplyArea: 55, householdCount: 30 }]
  Object.assign(workspace.supply.rows[1], { complexName: '대구테크노폴리스4', housingComplexId: 41, housingComplexName: '대구테크노폴리스4', typeName: '39', exclusiveArea: 39, housingTypeId: 504 })
  return createReview(review.extraction, workspace, housing)
}
function pick748(index: number) {
  fireEvent.change(within(openChooser(index)).getByRole('searchbox'), { target: { value: '#748' } })
  fireEvent.click(screen.getByRole('button', { name: /#748$/ }))
}

it('전용면적이 다른 기존 행을 고르면 그 행의 옛 주택형이 아니라 PDF 전용면적의 주택형에 연결하고 옛 주택형 값은 덮지 않는다', () => {
  const { request, current } = renderChoosers(with39)
  pick748(0)
  // 지금 값은 #748의 39형(#504) 그대로 보이고, 저장하면 같은 면적의 51A(#501)에 연결한다고 알린다.
  expect(current().rows[0]).toMatchObject({ relinkTo: 501 })
  expect(current().rows[0].cells.find(cell => cell.field === 'housingTypeId')).toMatchObject({ before: 504, note: "저장할 곳의 지금 주택타입과 전용면적이 달라 같은 전용면적의 '51A'에 연결합니다." })
  const input = request()
  expect(input.correction.rows.find(row => row.id === 748)).toMatchObject({ housingTypeId: 501, newHousingType: null, exclusiveArea: 51.98, excluded: false })
  expect(input.housingTypeValues?.some(value => value.rowKey === 'row:748' && value.name === '39')).toBe(false)
})

it('PDF 전용면적의 주택형이 단지에 없으면 고른 기존 행을 새 주택형에 연결한다', () => {
  const { request } = renderChoosers(fixture => {
    fixture.housing[41] = fixture.housing[41].filter(type => type.id !== 501)
    return with39(fixture)
  })
  pick748(0)
  expect(request().correction.rows.find(row => row.id === 748)).toMatchObject({ housingTypeId: null, newHousingType: { name: '051.9800A', exclusiveArea: 51.98 } })
})

it('초안을 다시 열면 고른 저장할 곳은 유지하고, 그 행이 제외됐으면 새 자동 제안으로 돌아가 대응을 다시 확인하게 한다', () => {
  const { current, workspace, housing } = renderChoosers()
  fireEvent.change(within(openChooser(0)).getByRole('searchbox'), { target: { value: '#747' } })
  fireEvent.click(screen.getByRole('button', { name: /#747$/ }))
  const kept = rebaseReview(current(), workspace, housing).rows[0]
  expect(kept).toMatchObject({ reviewed: true, proposal: { destination: 'row:747', automatic: { destination: 'new' } } })
  const gone = rebaseReview(current(), { ...workspace, supply: { ...workspace.supply, rows: workspace.supply.rows.map(row => row.id === 747 ? { ...row, excluded: true } : row) } }, housing).rows[0]
  expect(gone).toMatchObject({ reviewed: false, proposal: { destination: 'new' } })
  expect(gone.proposal.automatic).toBeUndefined()
})

/**
 * #748을 대구테크노폴리스4의 59A(전용 59.91) 공급행으로, #750을 같은 면적의 옛 공급행으로 두고, 다른 단지 행 #749를 더한다.
 * 059.9100A의 자동 제안은 #748을 고치고 #750을 함께 제외한다.
 */
function withSameArea({ workspace, housing, review }: ReturnType<typeof daegu>) {
  const rows = workspace.supply.rows, other = { ...rows[1], id: 749, key: 'row:749', displayOrder: 3 }
  Object.assign(rows[1], { complexName: '대구테크노폴리스4', housingComplexId: 41, housingComplexName: '대구테크노폴리스4', typeName: '59A', exclusiveArea: 59.91 })
  rows.push(other, { ...rows[1], id: 750, key: 'row:750', typeName: '59A 우선', displayOrder: 4 })
  return createReview(review.extraction, workspace, housing)
}

it('다른 단지 행을 거쳐 새로 추가로 바꾸면 새 행은 자동 제안의 단지에 만든다', () => {
  const { current, assembled } = renderChoosers(withSameArea)
  expect(current().rows[1].proposal).toMatchObject({ destination: 'row:748', supersedes: ['row:750'] })
  fireEvent.change(within(openChooser(1)).getByRole('searchbox'), { target: { value: '#749' } })
  fireEvent.click(screen.getByRole('button', { name: /#749$/ }))
  expect(current().rows[1].proposal).toMatchObject({ destination: 'row:749', complexId: 42 })
  fireEvent.click(within(openChooser(1)).getByRole('button', { name: '새 공급정보로 추가' }))
  expect(screen.getByLabelText('공급행 2 저장할 곳')).toHaveTextContent('대구테크노폴리스4에 새 공급정보로 추가합니다.')
  const added = assembled().find(row => row.key === current().rows[1].key)
  expect(added).toMatchObject({ id: null, housingComplexId: 41, exclusiveArea: 59.91 })
  // 같은 단지에서 같은 면적인 #748·#750은 함께 제외하고, 다른 단지 #749는 그대로 둔다.
  expect(assembled().filter(row => row.excluded).map(row => row.id)).toEqual([748, 750])
})

it('추천으로 되돌릴 때 그 사이 다른 PDF 행이 고른 옛 행은 함께 제외하지 않고 안내에서도 뺀다', () => {
  const { current, assembled } = renderChoosers(withSameArea)
  // 059.9100A를 다른 단지 행으로 옮겨 #750을 풀고, 051.9800A가 #750을 고른 뒤, 059.9100A를 추천으로 되돌린다.
  fireEvent.change(within(openChooser(1)).getByRole('searchbox'), { target: { value: '#749' } })
  fireEvent.click(screen.getByRole('button', { name: /#749$/ }))
  fireEvent.change(within(openChooser(0)).getByRole('searchbox'), { target: { value: '#750' } })
  fireEvent.click(screen.getByRole('button', { name: /#750$/ }))
  fireEvent.click(within(openChooser(1)).getByRole('button', { name: /^추천 · .* #748$/ }))
  expect(current().rows[1].proposal.destination).toBe('row:748')
  expect(current().rows[1].proposal.supersedes).toBeUndefined()
  expect(current().rows[1].proposal.reason).not.toContain('#750')
  expect(assembled().find(row => row.id === 750)).toMatchObject({ excluded: false, exclusiveArea: 51.98 })
})

it('면적이 다른 기존 행을 골라도 연결 주택타입 칸에서 기존 값 사용을 고르면 지금 주택타입을 그대로 둔다', () => {
  const { request, current, workspace } = renderChoosers(fixture => {
    fixture.housing[41] = fixture.housing[41].filter(type => type.id !== 501)
    return with39(fixture)
  })
  pick748(0)
  expect(request().correction.rows.find(row => row.id === 748)).toMatchObject({ housingTypeId: null, newHousingType: { exclusiveArea: 51.98 } })
  const row = current().rows[0]
  const kept = { ...row, cells: row.cells.map(cell => cell.field === 'housingTypeId' ? chooseCellValue(cell, 'keep') : cell) }
  const input = assembleReview({ ...current(), rows: current().rows.map((item, at) => at === 0 ? kept : item) }, workspace, evidence)
  expect(input.correction.rows.find(row => row.id === 748)).toMatchObject({ housingTypeId: 504, exclusiveArea: 51.98 })
  expect(input.correction.rows.find(row => row.id === 748)?.newHousingType ?? null).toBeNull()
})

it('면적이 다른 기존 행을 골라도 전용면적을 기존 값으로 두면 새 주택타입을 만들지 않고 지금 연결을 유지한다', () => {
  const { current, workspace } = renderChoosers(fixture => {
    fixture.housing[41] = fixture.housing[41].filter(type => type.id !== 501)
    return with39(fixture)
  })
  pick748(0)
  const row = current().rows[0]
  const kept = { ...row, cells: row.cells.map(cell => cell.field === 'exclusiveArea' ? chooseCellValue(cell, 'keep') : cell) }
  const saved = assembleReview({ ...current(), rows: current().rows.map((item, at) => at === 0 ? kept : item) }, workspace, evidence).correction.rows.find(item => item.id === 748)
  expect(saved).toMatchObject({ housingTypeId: 504, exclusiveArea: 39 })
  expect(saved?.newHousingType ?? null).toBeNull()
})

it('단지가 연결되지 않은 기존 행은 저장할 곳으로 고를 수 없고 단지 정하기로 안내한다', () => {
  renderChoosers(({ workspace, housing, review }) => {
    Object.assign(workspace.supply.rows[1], { housingComplexId: null, housingComplexName: null })
    return createReview(review.extraction, workspace, housing)
  })
  fireEvent.change(within(openChooser(0)).getByRole('searchbox'), { target: { value: '#748' } })
  expect(screen.getByRole('button', { name: /#748 · 단지 미연결\(단지 정하기에서 정하세요\)$/ })).toBeDisabled()
})

it('같은 면적 주택타입이 있어도 전용면적을 기존 값으로 두면 다시 연결하지 않고, 새로 만드는 주택타입 이름은 공고 표기 칸을 기존 값으로 둬도 PDF 값에서 정한다', () => {
  const { current, workspace } = renderChoosers(with39)
  pick748(0)
  const keep = (field: string) => {
    const row = current().rows[0]
    return { ...current(), rows: current().rows.map((item, at) => at === 0 ? { ...row, cells: row.cells.map(cell => cell.field === field ? chooseCellValue(cell, 'keep') : cell) } : item) }
  }
  expect(assembleReview(keep('exclusiveArea'), workspace, evidence).correction.rows.find(row => row.id === 748)).toMatchObject({ housingTypeId: 504, exclusiveArea: 39 })
  // 전용면적을 직접 고쳐 입력해도 PDF 면적이 아니므로 다시 연결하지 않는다.
  const typed = current().rows[0]
  const edited = { ...current(), rows: current().rows.map((item, at) => at === 0 ? { ...typed, cells: typed.cells.map(cell => cell.field === 'exclusiveArea' ? editCell(cell, '59.91') : cell) } : item) }
  expect(assembleReview(edited, workspace, evidence).correction.rows.find(row => row.id === 748)).toMatchObject({ housingTypeId: 504, exclusiveArea: 59.91 })
  cleanup()
  const created = renderChoosers(fixture => {
    fixture.housing[41] = fixture.housing[41].filter(type => type.id !== 501)
    return with39(fixture)
  })
  pick748(0)
  const row = created.current().rows[0]
  const named = { ...created.current(), rows: created.current().rows.map((item, at) => at === 0 ? { ...row, cells: row.cells.map(cell => cell.field === 'sourceHousingTypeName' ? chooseCellValue(cell, 'keep') : cell) } : item) }
  expect(assembleReview(named, created.workspace, evidence).correction.rows.find(item => item.id === 748)?.newHousingType).toMatchObject({ name: '051.9800A', exclusiveArea: 51.98 })
})
