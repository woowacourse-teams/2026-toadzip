import { useState } from 'react'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, it, vi } from 'vitest'
import { ReviewSupply } from './ReviewSupply'
import { daegu, savedSheet, scenario } from './fixtures/workspace'
import type { Extraction } from './extraction'
import type { Review, ReviewFilter } from './review'

const hansvilleLinked = (extracted: Extraction) => savedSheet(extracted, { id: 3055, name: '익산한스빌' })

function renderSupply(found: ReturnType<typeof scenario>, prepare: (review: Review) => void = () => {}, { filter = 'all' as ReviewFilter, savesHousingTypes = true, warnings = [] as string[], selected = 0 } = {}) {
  const { review, workspace, housing } = found
  prepare(review)
  const changed = vi.fn<(review: Review) => void>(), moved = vi.fn<(target: string) => void>()
  function Test() {
    const [current, setCurrent] = useState(review), [index, setIndex] = useState(selected)
    return <><button type="button" onClick={() => setIndex(index === 0 ? 3 : 0)}>다른 단지 보기</button>
      <ReviewSupply review={current} workspace={workspace} housing={housing} complexes={{}} filter={filter} savesHousingTypes={savesHousingTypes} warnings={warnings} empty="없음"
        onChange={next => { setCurrent(next); changed(next) }} onEvidence={() => {}} selected={index} onSelect={setIndex} onJump={moved} /></>
  }
  render(<MemoryRouter><Test /></MemoryRouter>)
  return { changed, moved }
}
const rowList = () => screen.getByRole('region', { name: '주택형 목록' })

it('이전 PDF 보정에서 고른 주택타입 가격은 확인 필요 필터에서도 보이고, 지금 가격 유지를 고르면 확인을 마친다', () => {
  // 26A는 단지 주택타입 10353에 연결돼 있고, 그 가격은 이전 PDF 보정에서 고른 공고문 값이다.
  const linkedType = (extracted: Extraction) => { const sheet = hansvilleLinked(extracted); sheet.housingTypes[0] = { ...sheet.housingTypes[0], housingTypeId: 10353 }; return sheet }
  const { changed } = renderSupply(scenario('hansville', undefined, linkedType), review => {
    review.rows[0].candidates = review.rows[0].candidates.map(type => type.id === 10353 ? { ...type, basicDeposit: 1_000_000, basicMonthlyRent: 10_000, rentalConditionSource: 'ANNOUNCEMENT' as const } : type)
  }, { filter: 'pending' })
  const price = screen.getByRole('region', { name: '주택형 1 주택타입 가격' })
  expect(within(price).getByText('확인 필요')).toBeInTheDocument()
  expect(within(price).getByRole('combobox', { name: '주택형 1 주택타입 가격으로 쓸 대상' })).toHaveValue('')
  fireEvent.click(within(price).getByRole('button', { name: '주택형 1 지금 가격 유지' }))
  expect(changed.mock.lastCall![0].rows[0].priceTarget).toBeNull()
})

it('검토 화면에서 고른 가격 대상과 지금 값 유지를 저장할 선택으로 남긴다', () => {
  const { changed } = renderSupply(scenario('hansville', undefined, hansvilleLinked))
  const select = screen.getByRole('combobox', { name: '주택형 1 주택타입 가격으로 쓸 대상' })
  fireEvent.change(select, { target: { value: '0' } })
  expect(changed.mock.lastCall![0].rows[0].priceTarget).toBe(0)
  fireEvent.change(select, { target: { value: '' } })
  expect(changed.mock.lastCall![0].rows[0].priceTarget).toBeNull()
})

it('PDF로 공급정보 편집표만 채울 때는 저장하지 않는 주택타입 가격 칸을 보이지 않는다', () => {
  renderSupply(scenario('hansville', undefined, hansvilleLinked), undefined, { savesHousingTypes: false })
  expect(screen.queryByRole('region', { name: '주택형 1 주택타입 가격' })).not.toBeInTheDocument()
})

it('AI 워커 경고는 필터와 관계없이 보이고, 화면 검산은 전체 보기에서만 보인다', () => {
  renderSupply(scenario('hansville'), review => { review.extraction.checks = ['화면 합계 검산 불일치'] }, { filter: 'pending', warnings: ['워커 경고'] })
  // 안내는 접힌 한 줄 요약으로 시작하고, 접혀 있어도 워커 경고 건수를 보인다.
  expect(screen.getByRole('list', { name: '안내 요약' })).toHaveTextContent('검산 확인 1건')
  expect(screen.queryByRole('note', { name: '검산 결과' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '안내 펼치기' }))
  const note = screen.getByRole('note', { name: '검산 결과' })
  expect(within(note).getByText('워커 경고')).toBeInTheDocument()
  expect(within(note).queryByText('화면 합계 검산 불일치')).not.toBeInTheDocument()
})

it('공급정보는 고른 주택형이 속한 PDF 단지의 주택형만 목록·편집 영역에 두고, 다른 단지 주택형을 고르면 그 단지로 바뀌며 앞 주택형의 편집은 남는다', () => {
  renderSupply(scenario('gwangju'))
  const groups = () => [...rowList().querySelectorAll('th[scope=rowgroup]')].map(header => header.textContent)
  expect(groups()).toHaveLength(1)
  expect(groups()[0]).toMatch(/^첨단H-1BL · 주택형 1개 · 모집 합/)
  const first = within(screen.getByRole('region', { name: '주택형 1 검토' })).getByRole('group', { name: '주택형 1 주택형 반영 방법' })
  fireEvent.click(within(first).getByRole('button', { name: '직접 입력' }))
  fireEvent.change(screen.getByRole('textbox', { name: '주택형 1 주택형 수정' }), { target: { value: '39 관리자' } })
  fireEvent.click(screen.getByRole('button', { name: '다른 단지 보기' }))
  expect(groups()[0]).toMatch(/^첨단H-3BL · 주택형 4개/)
  expect(within(rowList()).getAllByRole('button', { name: /^\d+\. / }).map(button => button.textContent?.split('.')[0])).toEqual(['4', '5', '6', '7'])
  expect(screen.getByRole('heading', { name: '4. 16(대학생,청년)' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '다른 단지 보기' }))
  expect(screen.getByRole('textbox', { name: '주택형 1 주택형 수정' })).toHaveValue('39 관리자')
})

it('지금 공급표와 이름이 다른 PDF 주택형은 새 주택형으로 보이고, PDF에 없는 지금 주택형은 저장하면 지운다고 알린다', () => {
  const { review, workspace, housing } = daegu()
  renderSupply({ review, workspace, housing, extraction: review.extraction })
  expect(within(rowList()).getAllByRole('row').slice(2).map(row => row.children[3].textContent)).toEqual(['새 주택형', '새 주택형', '새 주택형'])
  // 공고문 PDF가 하나뿐이라 PDF에 없는 단지는 기본으로 지우고, 그 주택형은 단지 안내에서만 알린다.
  const summary = within(screen.getByRole('list', { name: '안내 요약' }))
  expect(summary.getByText('이 PDF에 없는 단지 1곳 삭제')).toBeVisible()
  expect(summary.getByText('PDF에 없는 주택형 1개 삭제')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '안내 펼치기' }))
  expect(within(screen.getByRole('note', { name: 'PDF에 없는 주택형' })).getAllByRole('listitem').map(item => item.textContent)).toEqual(['대구테크노폴리스4 · 공공임대'])
  expect(screen.getByRole('checkbox', { name: '다른 단지 주택형 1개 지우기' })).toBeChecked()
  // 주택타입을 고르게 하지 않고 저장할 때 같은 이름·면적의 단지 주택타입에 연결한다고 미리 알린다.
  expect(screen.getByRole('region', { name: '주택형 1 검토' })).toHaveTextContent("연결 주택타입: 저장하면 '51A'(전용 51.98㎡)에 연결합니다.")
})

it('공고문 PDF가 둘이면 이 PDF에 없는 단지는 그대로 둔다고 알리고, 단지마다 지우기를 고르면 지울 주택형에 더한다', () => {
  const { review, workspace, housing } = daegu()
  workspace.detail.attachments = [{ attachmentId: 1, fileName: '대구 공고문.pdf', fileType: 'ANNOUNCEMENT', fileUrl: '' }, { attachmentId: 2, fileName: '다른 단지 공고문.pdf', fileType: 'ANNOUNCEMENT', fileUrl: '' }]
  const { changed } = renderSupply({ review, workspace, housing, extraction: review.extraction })
  const summary = within(screen.getByRole('list', { name: '안내 요약' }))
  expect(summary.getByText('이 PDF에 없는 단지 1곳 그대로 둠')).toBeVisible()
  expect(summary.getByText('PDF에 없는 주택형 1개 삭제')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '안내 펼치기' }))
  // 그대로 두는 단지 안내는 경고가 아닌 결정 상태로 보인다.
  expect(screen.getByRole('group', { name: '이 PDF에 없는 단지' })).toHaveAttribute('data-tone', 'info')
  fireEvent.click(within(screen.getByRole('group', { name: '이 PDF에 없는 단지' })).getByRole('checkbox', { name: '다른 단지 주택형 1개 지우기' }))
  expect(changed).toHaveBeenLastCalledWith(expect.objectContaining({ removeComplexes: { 'n:다른 단지': true } }))
  expect(summary.getByText('이 PDF에 없는 단지 1곳 삭제')).toBeVisible()
  expect(screen.getByRole('group', { name: '이 PDF에 없는 단지' })).toHaveAttribute('data-tone', 'warning')
  // 지우기로 고른 단지의 주택형은 PDF에서 빠진 주택형 안내에 겹쳐 세지 않는다.
  expect(summary.getByText('PDF에 없는 주택형 1개 삭제')).toBeVisible()
  expect(within(screen.getByRole('note', { name: 'PDF에 없는 주택형' })).getAllByRole('listitem').map(item => item.textContent)).toEqual(['대구테크노폴리스4 · 공공임대'])
})

it('공고문 PDF 하나에 신청서 양식·정정 공고 PDF가 더 있어도 공고문 PDF는 하나로 보고, 이 PDF에 없는 단지를 기본으로 지운다', () => {
  const { review, workspace, housing } = daegu()
  workspace.detail.attachments = [{ attachmentId: 1, fileName: '대구 공고문.pdf', fileType: 'ANNOUNCEMENT', fileUrl: '' }, { attachmentId: 2, fileName: '신청서 양식.pdf', fileType: 'REFERENCE', fileUrl: '' },
    { attachmentId: 3, fileName: '정정 공고.pdf', fileType: 'CORRECTION', fileUrl: '' }]
  renderSupply({ review, workspace, housing, extraction: review.extraction })
  expect(within(screen.getByRole('list', { name: '안내 요약' })).getByText('이 PDF에 없는 단지 1곳 삭제')).toBeVisible()
})

it('금액은 대상별 칸 표와 대조 칸 모두 세 자리마다 쉼표를 넣어 보이고, 직접 입력 칸에는 쉼표 없는 원 단위 값을 둔다', () => {
  renderSupply(scenario('gwangju'))
  const table = screen.getByRole('region', { name: '주택형 1 대상별 칸 표' })
  expect(within(table).getAllByText('41,848,000').length).toBeGreaterThan(0)
  expect(within(table).queryByText('41848000')).not.toBeInTheDocument()
  fireEvent.click(within(table).getByRole('button', { name: '대상 1 대조·수정' }))
  const target = screen.getByRole('region', { name: '주택형 1 대상 1 수정' })
  expect(within(target).getAllByText('41,848,000').length).toBeGreaterThan(0)
  fireEvent.click(within(within(target).getByRole('group', { name: '주택형 1 대상 1 보증금 (원) 반영 방법' })).getByRole('button', { name: '직접 입력' }))
  expect(within(target).getByRole('textbox', { name: '주택형 1 대상 1 보증금 (원) 수정' })).toHaveValue('41848000')
})

it('공급대상 이름을 읽지 못한 칸은 이름 없음으로 표시하고, 펼치면 이름을 넣어야 저장된다고 알린다', () => {
  const found = scenario('hansville')
  found.review.labels[0].cells = found.review.labels[0].cells.map(cell => cell.field === 'label' ? { ...cell, value: '', before: null, selected: true } : cell)
  renderSupply(found)
  const table = screen.getByRole('region', { name: '주택형 1 대상별 칸 표' })
  expect(within(table).getByText('이름 없음')).toBeVisible()
  fireEvent.click(within(table).getByRole('button', { name: '대상 1 대조·수정' }))
  expect(screen.getByText('공급대상 이름을 원문에서 읽지 못했습니다. 이름을 넣어야 저장할 수 있습니다.')).toBeVisible()
})

it('다른 주택형과 함께 쓰는 모집 수는 그 주택형 모두의 편집 영역에 같은 칸으로 보인다', () => {
  renderSupply(scenario('hansville'))
  const shared = () => within(screen.getByRole('region', { name: /^주택형 \d 검토$/ })).getByRole('group', { name: '26A·26B 일반 함께 모집 26A·26B 일반 함께 모집 반영 방법' })
  expect(shared()).toBeInTheDocument()
  fireEvent.click(within(rowList()).getByRole('button', { name: /^2\. / }))
  expect(screen.getByRole('heading', { name: '2. 26B' })).toBeVisible()
  expect(shared()).toBeInTheDocument()
})

it('단지를 정하지 않은 주택형의 상태 배지는 단지 정하기로 옮기고, 확인 필요 필터에서도 단지 정하기를 숨기지 않는다', () => {
  const { moved } = renderSupply(scenario('gwangju'), undefined, { filter: 'pending' })
  expect(screen.getByRole('region', { name: 'PDF 단지 정하기' })).toBeVisible()
  fireEvent.click(within(rowList()).getByRole('button', { name: /^주택형 1 확인 필요: 단지 정하기.* 확인하러 가기$/ }))
  expect(moved).toHaveBeenLastCalledWith('complex')
})

it('상태 배지는 남은 확인을 종류별 개수로 알리고, 누르면 편집 영역 순서로 첫 확인 항목(값 칸)으로 옮긴다', () => {
  let first = ''
  const { moved } = renderSupply(scenario('hansville', undefined, hansvilleLinked), review => {
    const cells = review.rows[0].cells.filter(cell => !cell.locked && ['name', 'exclusiveArea'].includes(cell.field))
    for (const cell of cells) { cell.mode = 'extracted'; cell.reviewed = false; cell.state = 'confirm'; cell.extracted = { ...cell.extracted, state: 'confirm' } }
    first = cells[0].path
  })
  fireEvent.click(within(rowList()).getByRole('button', { name: /^주택형 1 확인 필요: 값 2.* 확인하러 가기$/ }))
  expect(moved).toHaveBeenLastCalledWith(`cell:${first}`)
})
