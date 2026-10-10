import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { SourceDataPage } from './SourceDataPage'
import { getSourceData, type SourcePage } from './sourceApi'

vi.mock('./sourceApi', async original => ({
  ...(await original<typeof import('./sourceApi')>()), getSourceData: vi.fn(),
}))
const fetchSources = vi.mocked(getSourceData)
const fixture: SourcePage = {
  items: [{ id: 7, sourceKey: 'A:7', name: '두꺼비 단지',
    sourceUrl: 'https://apis.data.go.kr/1613000/HWSPR04/rentalHouseGwList',
    originalUrl: 'https://www.myhome.go.kr/announcement/7',
    collectedAt: '2026-10-03T03:00:00Z', sourceUpdatedAt: null,
    raw: { hsmp_nm: '두꺼비 단지', bass_cnvrs_gtn_lmt: '20000000', bass_mt_rntchrg: '170800',
      household_count: 0, nested: { available: false } } }],
  page: 0, totalElements: 21, totalPages: 2, hasNext: true,
}
beforeEach(() => {
  fetchSources.mockReset().mockResolvedValue(fixture)
})

it('SH 원천은 공식 게시판과 키 없는 수집으로 안내하고 HTML을 텍스트로 표시한다', async () => {
  fetchSources.mockResolvedValue({ ...fixture, items: [{ ...fixture.items[0], name: 'SH 공고',
    raw: { title: 'SH 공고', raw_list_html: '<html>목록</html>',
      raw_detail_html: '<script>window.shUnsafe = true</script>' } }] })
  renderPage('/admin/sources?category=SH_ANNOUNCEMENT')
  const table = await screen.findByRole('table', { name: 'SH 공고 원천 목록 · 1페이지' })
  expect(screen.getByRole('link', { name: '공식 게시판' })).toHaveAttribute('href',
    'https://www.i-sh.co.kr/app/lay2/program/S48T561C563/www/brd/m_247/list.do?multi_itm_seq=2')
  expect(screen.getByRole('link', { name: '키 없이 SH 공고 수집' })).toHaveAttribute('href', '/admin/ingest')
  expect(screen.queryByRole('link', { name: 'API 설명·키 발급' })).not.toBeInTheDocument()
  expect(table).toHaveTextContent('<script>window.shUnsafe = true</script>')
  expect(table).toHaveTextContent('<html>목록</html>')
  expect(table.querySelector('script')).toBeNull()
})
function renderPage(entry = '/admin/sources') {
  return render(<MemoryRouter initialEntries={[entry]}><SourceDataPage /></MemoryRouter>)
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(complete => { resolve = complete })
  return { promise, resolve }
}

it('모든 저장 항목을 펼침 없이 한 행에 표시하고 항목 뜻과 원천 키를 함께 보여준다', async () => {
  renderPage()
  expect(await screen.findByText('두꺼비 단지')).toBeVisible()
  const table = screen.getByRole('table', { name: '마이홈 단지 원천 목록 · 1페이지' })
  expect(screen.getByRole('link', { name: fixture.items[0].sourceUrl })).toHaveAttribute('href', fixture.items[0].sourceUrl)
  expect(within(table).getByRole('link', { name: fixture.items[0].originalUrl! })).toHaveAttribute('href', fixture.items[0].originalUrl)
  expect(screen.getByText('21건')).toBeVisible()
  expect(within(table).getByRole('columnheader', { name: '기본 전환보증금 (bass_cnvrs_gtn_lmt)' })).toBeVisible()
  expect(within(table).getByRole('columnheader', { name: '기본 월임대료 (bass_mt_rntchrg)' })).toBeVisible()
  expect(within(table).getByRole('columnheader', { name: '의미 확인 필요 (household_count)' })).toBeVisible()
  expect(within(table).getByRole('cell', { name: '20000000' })).toBeVisible()
  expect(within(table).getByRole('cell', { name: '0' })).toBeVisible()
  expect(within(table).getByRole('cell', { name: 'false' })).toBeVisible()
  expect(within(table).getAllByRole('row')).toHaveLength(2)
  expect(screen.queryByRole('button', { name: /저장 항목/ })).not.toBeInTheDocument()
  expect(document.querySelector('pre')).toBeNull()
  expect(screen.getByRole('link', { name: 'API 설명·키 발급' })).toHaveAttribute('href', 'https://www.data.go.kr/data/15110581/openapi.do')
})

it('검색·분류를 URL에 보존하고 페이지 이동 후 새 조회에서 첫 페이지로 돌아간다', async () => {
  renderPage('/admin/sources?category=MYHOME_ANNOUNCEMENT&keyword=두꺼비')
  await screen.findByText('두꺼비 단지')
  expect(fetchSources).toHaveBeenLastCalledWith('MYHOME_ANNOUNCEMENT', '두꺼비', 0, expect.any(AbortSignal))
  fireEvent.click(screen.getByRole('button', { name: /^다음$/ }))
  await waitFor(() => expect(fetchSources).toHaveBeenLastCalledWith('MYHOME_ANNOUNCEMENT', '두꺼비', 1, expect.any(AbortSignal)))
  fireEvent.click(screen.getByRole('tab', { name: 'LH 공고 공급' }))
  fireEvent.change(screen.getByLabelText('이름·원천 식별자'), { target: { value: '  공급  ' } })
  fireEvent.click(screen.getByRole('button', { name: /^조회$/ }))
  await waitFor(() => expect(fetchSources).toHaveBeenLastCalledWith('LH_ANNOUNCEMENT_SUPPLY', '공급', 0, expect.any(AbortSignal)))
})

it.each(['페이지 이동', '새로고침'])('%s 중 표와 스크롤 위치를 유지하고 새 결과로 바꾼다', async action => {
  const pending = deferred<SourcePage>()
  fetchSources.mockResolvedValueOnce(fixture).mockReturnValueOnce(pending.promise)
  renderPage()
  const table = await screen.findByRole('table', { name: '마이홈 단지 원천 목록 · 1페이지' })
  const region = screen.getByRole('region', { name: '원천 데이터 목록 가로 스크롤' })
  const pagination = screen.getByRole('navigation', { name: '목록 페이지' })
  region.scrollLeft = 360
  region.scrollTop = 240

  fireEvent.click(screen.getByRole('button', { name: action === '페이지 이동' ? /^다음$/ : '목록 새로고침' }))
  await waitFor(() => expect(fetchSources).toHaveBeenCalledTimes(2))
  expect(screen.getByRole('status')).toHaveTextContent('원천 데이터를 불러오는 중')
  expect(region.closest('[aria-busy]')).toHaveAttribute('aria-busy', 'true')
  expect(screen.getByRole('table', { name: '마이홈 단지 원천 목록 · 1페이지' })).toBe(table)
  expect(screen.getByRole('region', { name: '원천 데이터 목록 가로 스크롤' })).toBe(region)
  expect(screen.getByRole('navigation', { name: '목록 페이지' })).toBe(pagination)
  expect(table).toHaveTextContent('두꺼비 단지')
  expect(region.scrollLeft).toBe(360)
  expect(region.scrollTop).toBe(240)
  expect(within(pagination).getByRole('button', { name: '2페이지' })).toBeDisabled()
  expect(within(pagination).getByRole('button', { name: '다음' })).toBeDisabled()
  expect(within(pagination).getByRole('button', { name: '이동' })).toBeDisabled()

  const nextPage = action === '페이지 이동' ? 1 : 0
  await act(async () => pending.resolve({ ...fixture, page: nextPage, hasNext: nextPage === 0,
    items: [{ ...fixture.items[0], id: 8, sourceKey: 'A:8', raw: { ...fixture.items[0].raw, hsmp_nm: '새로 받은 단지' } }] }))
  expect(screen.getByRole('table', { name: `마이홈 단지 원천 목록 · ${nextPage + 1}페이지` })).toBe(table)
  expect(screen.getByRole('region', { name: '원천 데이터 목록 가로 스크롤' })).toBe(region)
  expect(screen.getByRole('navigation', { name: '목록 페이지' })).toBe(pagination)
  expect(table).toHaveTextContent('새로 받은 단지')
  expect(table).not.toHaveTextContent('두꺼비 단지')
  expect(region.scrollLeft).toBe(360)
  expect(region.scrollTop).toBe(240)
  expect(region.closest('[aria-busy]')).toHaveAttribute('aria-busy', 'false')
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
  expect(within(pagination).getByRole('button', { name: `${nextPage + 1}페이지` })).toHaveAttribute('aria-current', 'page')
  expect(within(pagination).getByRole('button', { name: '이동' })).toBeEnabled()
})

it.each(['분류', '검색어'])('%s 변경 후 새 조회 중에는 이전 원천 데이터를 표시하지 않는다', async change => {
  const pending = deferred<SourcePage>()
  fetchSources.mockResolvedValueOnce(fixture).mockReturnValueOnce(pending.promise)
  renderPage()
  await screen.findByRole('table', { name: '마이홈 단지 원천 목록 · 1페이지' })
  if (change === '분류') {
    fireEvent.click(screen.getByRole('tab', { name: 'LH 임대 단지' }))
  } else {
    fireEvent.change(screen.getByLabelText('이름·원천 식별자'), { target: { value: '새 조회' } })
    fireEvent.click(screen.getByRole('button', { name: '조회' }))
  }
  await waitFor(() => expect(fetchSources).toHaveBeenCalledTimes(2))
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  expect(screen.queryByText('두꺼비 단지')).not.toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('원천 데이터를 불러오는 중')

  await act(async () => pending.resolve({ ...fixture, items: [{ ...fixture.items[0], raw: { hsmp_nm: '새 조회 단지' } }] }))
  expect(screen.getByRole('table', { name: `${change === '분류' ? 'LH 임대 단지' : '마이홈 단지'} 원천 목록 · 1페이지` })).toHaveTextContent('새 조회 단지')
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
})

it('원천별 탭을 키보드로 이동하고 선택한 분류를 조회한다', async () => {
  renderPage()
  await screen.findByText('두꺼비 단지')
  expect(screen.getAllByRole('tab')).toHaveLength(7)
  const first = screen.getByRole('tab', { name: '마이홈 단지' })
  expect(first).toHaveAttribute('aria-selected', 'true')
  fireEvent.keyDown(first, { key: 'ArrowRight' })
  await waitFor(() => expect(fetchSources).toHaveBeenLastCalledWith('LH_LEASE_CATALOG', '', 0, expect.any(AbortSignal)))
  const next = screen.getByRole('tab', { name: 'LH 임대 단지' })
  expect(next).toHaveAttribute('aria-selected', 'true')
  expect(next).toHaveFocus()
  fireEvent.keyDown(next, { key: 'End' })
  await waitFor(() => expect(fetchSources).toHaveBeenLastCalledWith('SH_ANNOUNCEMENT', '', 0, expect.any(AbortSignal)))
})

it('마이홈 공고의 최소 월임대료를 단지의 기본 월임대료와 구분한다', async () => {
  fetchSources.mockResolvedValueOnce({ ...fixture, items: [{ ...fixture.items[0], raw: { pblanc_nm: '두꺼비 공고', mt_rntchrg: '12300' } }] })
  renderPage('/admin/sources?category=MYHOME_ANNOUNCEMENT')
  const table = await screen.findByRole('table', { name: '마이홈 공고 원천 목록 · 1페이지' })
  expect(within(table).getByRole('columnheader', { name: '최소 월임대료 (mt_rntchrg)' })).toBeVisible()
  expect(within(table).getByRole('cell', { name: '12300' })).toBeVisible()
})

it('행마다 다른 저장 항목을 같은 열에 맞추고 null·누락·큰 숫자를 보존한다', async () => {
  fetchSources.mockResolvedValueOnce({ ...fixture, items: [
    { ...fixture.items[0], raw: { hsmp_nm: '첫 단지', bass_rent_gtn: '9007199254740993123', unknown: null,
      extra: '첫 행에만 있는 항목', serviceKey: 'test-only-secret' } },
    { ...fixture.items[0], id: 8, sourceKey: 'A:8', raw: { hsmp_nm: '둘째 단지', bass_rent_gtn: '0', unknown: '' } },
  ] })
  renderPage()
  const table = await screen.findByRole('table', { name: '마이홈 단지 원천 목록 · 1페이지' })
  expect(within(table).getAllByRole('row')).toHaveLength(3)
  expect(table).toHaveTextContent('9007199254740993123')
  expect(table).toHaveTextContent('값 없음 (null)')
  expect(table).toHaveTextContent('빈 문자열')
  expect(table).toHaveTextContent('기록 없음')
  expect(table).toHaveTextContent('비공개')
  expect(table).not.toHaveTextContent('test-only-secret')
})

it('조회 실패를 빈 결과로 표시하지 않고 재시도한다', async () => {
  fetchSources.mockRejectedValueOnce(new Error('접속 실패'))
  renderPage()
  expect(await screen.findByRole('alert')).toHaveTextContent('접속 실패')
  expect(screen.queryByText('표시할 원천 데이터가 없습니다.')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '다시 불러오기' }))
  expect(await screen.findByText('두꺼비 단지')).toBeVisible()
})

it('빈 목록과 수집 시각·공식 원문 미기록을 구분해 표시한다', async () => {
  fetchSources.mockResolvedValueOnce({ ...fixture, items: [{ ...fixture.items[0], originalUrl: null, collectedAt: null }] })
  renderPage()
  await screen.findByText('두꺼비 단지')
  expect(screen.getAllByText('기록 없음')).toHaveLength(2)
  fetchSources.mockResolvedValueOnce({ items: [], page: 0, totalElements: 0, totalPages: 0, hasNext: false })
  fireEvent.click(screen.getByRole('button', { name: '목록 새로고침' }))
  expect(await screen.findByText('표시할 원천 데이터가 없습니다.')).toBeVisible()
  expect(screen.getByRole('button', { name: /^다음$/ })).toBeDisabled()
})

it.each(['category=UNSUPPORTED', 'page=-1', 'page=0.5', 'page=2147483648'])('잘못된 조회 주소 %s를 요청 전에 안내한다', async query => {
  renderPage(`/admin/sources?${query}`)
  expect(screen.getByRole('alert')).toHaveTextContent('조회 주소')
  expect(fetchSources).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '검색 초기화' }))
  expect(await screen.findByText('두꺼비 단지')).toBeVisible()
})
