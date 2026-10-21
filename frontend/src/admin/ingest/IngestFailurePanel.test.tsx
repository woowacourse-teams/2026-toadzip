import { MemoryRouter } from 'react-router'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { IngestFailurePanel } from './IngestFailurePanel'
import { getFailureReview, getFailureReviews, type FailureReview, type FailureReviewPageData } from './failureReviewApi'

vi.mock('./failureReviewApi', () => ({getFailureReviews:vi.fn(), getFailureReview:vi.fn()}))
const fetchFailures = vi.mocked(getFailureReviews)
const row: FailureReview = {
  id:1,category:'announcement',target:'공고 A · 공급행 3',sourceKey:'A:3',source:'MYHOME_ANNOUNCEMENT',
  reason:'COMPLEX_NOT_FOUND',detail:'주소와 단지명에 일치하는 단지가 없습니다.',status:'PENDING',
  occurredAt:'2026-09-26T03:00:00Z',occurrenceCount:2,recurrenceCount:0,lastResolvedAt:null,
  executionId:'run-1',raw:{},productLinkStatus:'UNKNOWN',product:null,
}
const page = (items:FailureReview[] = [row], number = 0):FailureReviewPageData =>
  ({items,page:number,totalElements:41,totalPages:3,hasNext:number<2})
beforeEach(() => {
  fetchFailures.mockReset()
  vi.mocked(getFailureReview).mockReset().mockImplementation(async (category, id) => {
    const data = await fetchFailures.mock.results.at(-1)?.value as FailureReviewPageData
    return data.items.find(item => item.category === category && item.id === id) ?? row
  })
  Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value:function(this:HTMLDialogElement){this.setAttribute('open','')}})
  Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value:function(this:HTMLDialogElement){this.removeAttribute('open');this.dispatchEvent(new Event('close'))}})
})

it('대상·원인·다음 행동과 선택한 실행을 함께 보여준다', async () => {
  fetchFailures.mockResolvedValue(page())
  render(<IngestFailurePanel initialCategory="announcement" executionId="run-1" />, {wrapper:MemoryRouter})
  expect(await screen.findByText(row.target)).toBeVisible()
  expect(screen.getByText('연결할 단지를 찾지 못함')).toBeVisible()
  expect(screen.getByText(row.detail)).toBeVisible()
  expect(screen.getByText('총 41건 · 실행 필터 적용')).toBeVisible()
  expect(fetchFailures).toHaveBeenCalledWith('announcement','announcement','PENDING',0,expect.any(AbortSignal), {keyword:'',executionId:'run-1'})
})

it('조회 실패를 빈 목록으로 표시하지 않고 재시도한다', async () => {
  fetchFailures.mockRejectedValueOnce(new Error('접속 실패')).mockResolvedValueOnce(page())
  render(<IngestFailurePanel initialCategory="announcement" executionId={null} />, {wrapper:MemoryRouter})
  expect(await screen.findByRole('alert')).toHaveTextContent('접속 실패')
  expect(screen.queryByText('해당 상태의 오류가 없습니다.')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button',{name:'다시 조회'}))
  expect(await screen.findByText(row.target)).toBeVisible()
})

it('확인·처리 안에서 원문을 중첩 경로와 값의 표로 표시한다', async () => {
  fetchFailures.mockResolvedValue(page([{...row,raw:{request:{page:0,completed:false},originalUrl:'https://example.com/notice'}}]))
  const {container}=render(<IngestFailurePanel initialCategory="announcement" executionId={null} />, {wrapper:MemoryRouter})
  fireEvent.click(await screen.findByRole('button',{name:`확인·처리: ${row.target}`}))
  const dialog=screen.getByRole('dialog',{name:'문제 확인·처리'})
  fireEvent.click(within(dialog).getByText('실패 기록 원문·실행 정보'))
  const table=within(dialog).getByRole('table',{name:'실패 기록 원문'})
  expect(table).toHaveTextContent('request.page0')
  expect(table).toHaveTextContent('request.completedfalse')
  expect(within(dialog).getByRole('link',{name:'https://example.com/notice'})).toBeVisible()
  expect(container.querySelector('pre')).toBeNull()
  fireEvent.click(within(dialog).getByRole('button',{name:'닫기'}))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

it('페이지와 해결 이력을 조회하고 단계·상태를 바꾸면 첫 페이지로 돌아간다', async () => {
  fetchFailures.mockImplementation(async (_domain,_category,_status,number) => page([row],number))
  render(<IngestFailurePanel initialCategory="announcement" executionId={null} />, {wrapper:MemoryRouter})
  await screen.findByText(row.target)
  fireEvent.click(screen.getByRole('button',{name:'다음'}))
  await waitFor(() => expect(fetchFailures.mock.lastCall?.slice(0,4)).toEqual(['announcement','announcement','PENDING',1]))
  fireEvent.change(screen.getByLabelText('문제 단계'),{target:{value:'collection'}})
  await waitFor(() => expect(fetchFailures.mock.lastCall?.slice(0,4)).toEqual(['announcement','collection','PENDING',0]))
  fireEvent.change(screen.getByLabelText('처리 상태'),{target:{value:'RESOLVED'}})
  await waitFor(() => expect(fetchFailures.mock.lastCall?.slice(0,4)).toEqual(['announcement','collection','RESOLVED',0]))
})

it('수집 요청은 지역과 조치로 표시하고 원문은 상세에 보존한다', async () => {
  const request='brtcCode=47&signguCode=130&pageNo=1&numOfRows=500'
  fetchFailures.mockResolvedValue(page([{...row,category:'collection',target:request,sourceKey:request,source:'MYHOME_COMPLEX',
    reason:'ExternalDataRequestException',detail:'HTTP 429, resultCode=23, 초당 서비스 요청제한 횟수 초과 에러',
    executionId:'old-run',raw:{requestDescription:request}}]))
  render(<IngestFailurePanel domain="complex" initialCategory="collection" executionId="run-1" />, {wrapper:MemoryRouter})
  expect(await screen.findByText('경상북도 경주시')).toBeVisible()
  expect(screen.getByText(/요청 1페이지 · 페이지당 500건/)).toBeVisible()
  expect(screen.getByText('요청 속도 제한')).toBeVisible()
  expect(screen.getByText('총 41건 · 실행 필터 적용')).toBeVisible()
  fireEvent.click(screen.getByRole('button',{name:'확인·처리: 경상북도 경주시'}))
  expectReturnLink(within(screen.getByRole('dialog')).getByRole('link',{name:'API 키 입력·원천 수집'}), '/admin/ingest#complex-pipelines', 'collection:1')
})

it('좌표 오류에서 ZIP 업로드로 이동하고 좌표가 없는 원천의 한계를 안내한다', async () => {
  fetchFailures.mockResolvedValue(page([{...row,category:'complex',reason:'GEOCODING_ERROR'}]))
  render(<IngestFailurePanel domain="complex" executionId={null}/>, {wrapper:MemoryRouter})
  fireEvent.click(await screen.findByRole('button',{name:`확인·처리: ${row.target}`}))
  const dialog=within(screen.getByRole('dialog'))
  expectReturnLink(dialog.getByRole('link',{name:'좌표 ZIP 업로드·단지 정제'}), '/admin/locations', 'complex:1')
  expect(dialog.getByText(/좌표 ZIP을 업로드해도 일치하는 주소나 좌표가 없으면/)).toBeVisible()
  expect(dialog.queryByRole('link',{name:'단지·주택형 확인'})).not.toBeInTheDocument()
})

it.each(['COMPLEX_NOT_FOUND','HOUSING_TYPE_NOT_FOUND'])('%s 오류의 단지명이 있으면 후보 검색으로 연결한다', async reason => {
  fetchFailures.mockResolvedValue(page([{...row,reason,raw:{complexName:'가람 & 나래'}}]))
  render(<IngestFailurePanel domain="announcement" executionId={null}/>, {wrapper:MemoryRouter})
  fireEvent.click(await screen.findByRole('button',{name:`확인·처리: ${row.target}`}))
  const dialog=within(screen.getByRole('dialog'))
  expectReturnLink(dialog.getByRole('link',{name:'단지명으로 후보 검색'}), `/admin/complexes?keyword=${encodeURIComponent('가람 & 나래')}`, 'announcement:1')
  expect(dialog.queryByRole('link',{name:'단지·주택형 확인'})).not.toBeInTheDocument()
})

it('공고의 연결 단지 정보가 없으면 단지·주택형 관리 목록으로 연결한다', async () => {
  fetchFailures.mockResolvedValue(page())
  render(<IngestFailurePanel domain="announcement" executionId={null}/>, {wrapper:MemoryRouter})
  fireEvent.click(await screen.findByRole('button',{name:`확인·처리: ${row.target}`}))
  expectReturnLink(within(screen.getByRole('dialog')).getByRole('link',{name:'단지·주택형 확인'}), '/admin/complexes', 'announcement:1')
})

it('연결된 단지가 있으면 같은 단지 관리 링크를 중복해서 만들지 않는다', async () => {
  fetchFailures.mockResolvedValue(page([{...row,category:'household',reason:'HOUSING_TYPE_NOT_FOUND',productLinkStatus:'EXISTS',
    raw:{complexName:'가람'},product:{id:7,name:'가람',resourceType:'complexes',deleted:false,hasCoordinates:true,
      housingTypeCount:0,linkedComplexCount:null,supplyRowCount:null,applicationScheduleCount:null,attachmentCount:null,
      applicationStartDate:null,applicationEndDate:null,applicationScheduleReviewed:null,
      publicDetailAvailable:true,publicListEligible:true,listExclusionReasons:[]}}]))
  render(<IngestFailurePanel domain="complex" executionId={null}/>, {wrapper:MemoryRouter})
  fireEvent.click(await screen.findByRole('button',{name:`확인·처리: ${row.target}`}))
  const dialog=within(screen.getByRole('dialog'))
  expectReturnLink(dialog.getByRole('link',{name:'단지 확인·수정'}), '/admin/complexes/7', 'household:1')
  expect(dialog.queryByRole('link',{name:'단지명으로 후보 검색'})).not.toBeInTheDocument()
  expect(dialog.queryByRole('link',{name:'단지·주택형 확인'})).not.toBeInTheDocument()
})

it.each([['complex','단지'],['announcement','공고']] as const)('%s 재실행은 저장된 원천 전체임을 안내하고 실행 중 중복 요청을 막는다', async (domain,label) => {
  const onRetry=vi.fn()
  fetchFailures.mockResolvedValue(page([{...row,category:domain}]))
  const {rerender}=render(<IngestFailurePanel domain={domain} executionId="run-1" onRetry={onRetry}/>, {wrapper:MemoryRouter})
  fireEvent.click(await screen.findByRole('button',{name:`확인·처리: ${row.target}`}))
  const retry=within(screen.getByRole('dialog')).getByRole('button',{name:`${label} 정제 다시 실행`})
  await waitFor(() => expect(retry).toBeEnabled())
  fireEvent.click(retry)
  expect(onRetry).not.toHaveBeenCalled()
  const confirmation = screen.getByRole('group', {name:'정제 실행 범위 확인'})
  expect(confirmation).toHaveTextContent(`저장된 ${label} 원천 전체를 정제합니다.`)
  expect(within(confirmation).getByRole('button', {name:`전체 ${label} 정제 실행`})).toHaveFocus()
  fireEvent.click(within(confirmation).getByRole('button', {name:`전체 ${label} 정제 실행`}))
  expect(onRetry).toHaveBeenCalledTimes(1)
  rerender(<IngestFailurePanel domain={domain} executionId="run-1" onRetry={onRetry} retryDisabled/>)
  const disabledRetry = screen.getByRole('button', {name:`${label} 정제 다시 실행`})
  expect(disabledRetry).toBeDisabled()
  fireEvent.click(disabledRetry)
  expect(onRetry).toHaveBeenCalledTimes(1)
})

it.each(['다음','목록 새로고침'])('%s 응답 중 표와 가로·세로 스크롤 위치를 유지한다', async action => {
  let complete:(result:FailureReviewPageData)=>void=()=>{}
  const pending=new Promise<FailureReviewPageData>(resolve=>{complete=resolve})
  fetchFailures.mockResolvedValueOnce(page()).mockReturnValueOnce(pending)
  render(<IngestFailurePanel initialCategory="announcement" executionId={null} />, {wrapper:MemoryRouter})
  const table=await screen.findByRole('table',{name:'공고 오류 목록 · 1페이지'})
  const region=screen.getByRole('region',{name:'실패 목록 가로 스크롤'})
  region.scrollLeft=280;region.scrollTop=200
  fireEvent.click(screen.getByRole('button',{name:action}))
  await waitFor(()=>expect(fetchFailures).toHaveBeenCalledTimes(2))
  expect(screen.getByRole('table')).toBe(table)
  expect(screen.getByRole('region',{name:'실패 목록 가로 스크롤'})).toBe(region)
  expect(screen.getByRole('status')).toHaveTextContent('오류 목록을 불러오는 중')
  expect(screen.getByRole('button',{name:'다음'})).toBeDisabled()
  const number=action==='다음'?1:0
  await act(async()=>complete(page([{...row,target:'새 공고'}],number)))
  expect(screen.getByRole('table',{name:`공고 오류 목록 · ${number+1}페이지`})).toBe(table)
  expect(table).toHaveTextContent('새 공고')
  expect(region.scrollLeft).toBe(280);expect(region.scrollTop).toBe(200)
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
})

it('빈 페이지에서도 표 영역과 이전 페이지 이동을 유지한다', async () => {
  fetchFailures.mockResolvedValueOnce(page()).mockResolvedValueOnce(page([],1))
  render(<IngestFailurePanel initialCategory="announcement" executionId={null} />, {wrapper:MemoryRouter})
  await screen.findByText(row.target)
  const region=screen.getByRole('region',{name:'실패 목록 가로 스크롤'})
  fireEvent.click(screen.getByRole('button',{name:'다음'}))
  expect(await screen.findByText('이 페이지에 오류가 없습니다.')).toBeVisible()
  expect(screen.getByRole('region',{name:'실패 목록 가로 스크롤'})).toBe(region)
  expect(screen.getByRole('button',{name:'이전'})).toBeEnabled()
})

it('해결되어 미해결 목록에서 사라져도 처리창에서 개별 오류의 결과를 확인한다', async () => {
  fetchFailures.mockResolvedValueOnce(page()).mockResolvedValue({...page([],0),totalElements:0,totalPages:0,hasNext:false})
  vi.mocked(getFailureReview).mockResolvedValueOnce(row).mockResolvedValue({...row,status:'RESOLVED',lastResolvedAt:'2026-10-18T07:00:00Z'})
  const {rerender}=render(<IngestFailurePanel initialCategory="announcement" executionId={null} />, {wrapper:MemoryRouter})
  fireEvent.click(await screen.findByRole('button',{name:`확인·처리: ${row.target}`}))
  expect(screen.getByRole('dialog')).toBeVisible()
  await waitFor(() => expect(screen.getByRole('button',{name:'처리 결과 다시 확인'})).toBeEnabled())
  rerender(<IngestFailurePanel initialCategory="announcement" executionId={null} refreshToken={1}/>)
  await waitFor(()=>expect(within(screen.getByRole('dialog')).getAllByText('해결됨')).toHaveLength(2))
  expect(getFailureReview).toHaveBeenLastCalledWith('announcement',1,expect.any(AbortSignal))
  fireEvent.click(screen.getByRole('button',{name:'목록으로 돌아가기'}))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

it('LH 보강 실패에서는 같은 공고 번호로 상세·공급 원천을 각각 확인한다', async () => {
  fetchFailures.mockResolvedValue(page([{...row,category:'enrichment',source:'LH_ANNOUNCEMENT',raw:{panId:'PAN-7'}}]))
  render(<IngestFailurePanel initialCategory="enrichment" executionId={null} />, {wrapper:MemoryRouter})
  fireEvent.click(await screen.findByRole('button',{name:`확인·처리: ${row.target}`}))
  const dialog=within(screen.getByRole('dialog'))
  expectReturnLink(dialog.getByRole('link',{name:'LH 상세 원천 확인'}), '/admin/sources?category=LH_ANNOUNCEMENT_DETAIL&keyword=PAN-7', 'enrichment:1')
  expectReturnLink(dialog.getByRole('link',{name:'LH 공급 원천 확인'}), '/admin/sources?category=LH_ANNOUNCEMENT_SUPPLY&keyword=PAN-7', 'enrichment:1')
})

function expectReturnLink(link: HTMLElement, destination: string, failure: string) {
  const url = new URL(link.getAttribute('href')!, 'https://test.invalid')
  const back = new URL(url.searchParams.get('failureReturnTo')!, 'https://test.invalid')
  expect(back.pathname).toBe('/admin/failures')
  expect(back.searchParams.get('failure')).toBe(failure)
  expect(back.searchParams.get('result')).toBe('1')
  url.searchParams.delete('failureReturnTo')
  expect(url.href).toBe(new URL(destination, 'https://test.invalid').href.replaceAll('%20', '+'))
}

it('검색 조건과 페이지를 서버에 전달하고 직접 접근한 오류를 목록과 별개로 연다', async () => {
  fetchFailures.mockResolvedValue({...page([],2), totalElements:0,totalPages:0,hasNext:false})
  vi.mocked(getFailureReview).mockResolvedValue({...row, status:'RESOLVED'})
  render(<MemoryRouter initialEntries={['/admin/failures?domain=announcement&keyword=한스빌&page=2&failure=announcement:1&result=1']}>
    <IngestFailurePanel domain="announcement" executionId={null}/></MemoryRouter>)
  expect(await screen.findByRole('heading', {name:'3. 처리 결과 확인'})).toHaveFocus()
  expect(fetchFailures).toHaveBeenCalledWith('announcement','all','PENDING',2,expect.any(AbortSignal),{keyword:'한스빌',executionId:null})
  fireEvent.click(screen.getByRole('button',{name:'닫기'}))
  fireEvent.change(screen.getByLabelText('이름·원천 식별자'), {target:{value:'A-26'}})
  fireEvent.click(screen.getByRole('button',{name:'오류 검색'}))
  await waitFor(() => expect(fetchFailures).toHaveBeenLastCalledWith('announcement','all','PENDING',0,expect.any(AbortSignal),{keyword:'A-26',executionId:null}))
})

it('결과 재조회 실패 시 이전 상태를 최신으로 단정하지 않고 재처리를 막는다', async () => {
  fetchFailures.mockResolvedValue(page())
  vi.mocked(getFailureReview).mockResolvedValueOnce(row).mockRejectedValueOnce(new Error('상세 조회 실패')).mockResolvedValue({...row,status:'RESOLVED'})
  render(<MemoryRouter><IngestFailurePanel domain="announcement" executionId={null} onRetry={vi.fn()}/></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button',{name:`확인·처리: ${row.target}`}))
  await waitFor(() => expect(screen.getByRole('button',{name:'처리 결과 다시 확인'})).toBeEnabled())
  fireEvent.click(screen.getByRole('button',{name:'처리 결과 다시 확인'}))
  expect(await screen.findByRole('alert')).toHaveTextContent('이전 표시값을 최신 처리 결과로 판단하지 마세요.')
  expect(screen.getByRole('button',{name:'공고 정제 다시 실행'})).toBeDisabled()
  fireEvent.click(screen.getByRole('button',{name:'상세 다시 조회'}))
  await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  expect(within(screen.getByRole('dialog')).getAllByText('해결됨')).toHaveLength(2)
})

it('다른 도메인의 오류 주소에서는 잘못된 정제 조치를 제공하지 않는다', async () => {
  fetchFailures.mockResolvedValue(page([]))
  render(<MemoryRouter initialEntries={['/admin/failures?domain=complex&failure=announcement:1']}>
    <IngestFailurePanel domain="complex" executionId={null} onRetry={vi.fn()}/></MemoryRouter>)
  expect(await screen.findByRole('alert')).toHaveTextContent('오류 상세 주소가 올바르지 않습니다.')
  expect(getFailureReview).not.toHaveBeenCalled()
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

it('단계가 바뀌면 이전 행을 숨기고 취소한 응답을 버린다', async () => {
  let complete:(result:FailureReviewPageData)=>void=()=>{}
  const pending=new Promise<FailureReviewPageData>(resolve=>{complete=resolve})
  fetchFailures.mockResolvedValueOnce(page()).mockReturnValueOnce(pending).mockResolvedValueOnce(page([{...row,target:'최종 공고'}]))
  render(<IngestFailurePanel initialCategory="announcement" executionId={null} />, {wrapper:MemoryRouter})
  await screen.findByRole('table')
  fireEvent.change(screen.getByLabelText('문제 단계'),{target:{value:'enrichment'}})
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  expect(screen.queryByText(row.target)).not.toBeInTheDocument()
  await waitFor(()=>expect(fetchFailures).toHaveBeenCalledTimes(2))
  fireEvent.change(screen.getByLabelText('문제 단계'),{target:{value:'collection'}})
  expect(await screen.findByText('최종 공고')).toBeVisible()
  await act(async()=>complete(page([{...row,target:'취소된 보강 응답'}])))
  expect(screen.queryByText('취소된 보강 응답')).not.toBeInTheDocument()
})

it('현재 페이지의 같은 공고 주택형 오류를 묶고 원래 필터로 돌아갈 주소를 보존한다', async () => {
  const product: NonNullable<FailureReview['product']> = { id:29,name:'한스빌',resourceType:'announcements',deleted:false,hasCoordinates:null,
    housingTypeCount:null,linkedComplexCount:1,supplyRowCount:1,applicationScheduleCount:0,attachmentCount:2,
    applicationStartDate:null,applicationEndDate:null,applicationScheduleReviewed:false,
    publicDetailAvailable:true,publicListEligible:true,listExclusionReasons:[] }
  const eligible = {...row,reason:'AMBIGUOUS_HOUSING_TYPE',productLinkStatus:'EXISTS' as const,product}
  fetchFailures.mockResolvedValue(page([eligible,{...eligible,id:2,category:'enrichment',reason:'HOUSING_TYPE_NOT_FOUND'},
    {...eligible,id:3,reason:'COMPLEX_NOT_FOUND'}, {...eligible,id:4,product:{...product,id:40,deleted:true}},
    {...eligible,id:5,status:'RESOLVED'}, {...eligible,id:6,product:null,productLinkStatus:'NOT_FOUND'}]))
  render(<MemoryRouter initialEntries={['/admin/failures?domain=announcement&category=all&keyword=한스빌']}><IngestFailurePanel domain="announcement" executionId={null}/></MemoryRouter>)
  const group = await screen.findByRole('region',{name:'PDF로 보정할 공고'})
  expect(within(group).getByText('이 페이지의 주택형 대응 오류')).toBeVisible()
  const link = within(group).getByRole('link',{name:'PDF로 보정 · 2건'})
  expect(within(group).getAllByRole('link')).toHaveLength(1)
  const url = new URL(link.getAttribute('href')!, 'http://localhost')
  expect(url.pathname).toBe('/admin/announcements/29/pdf-correction')
  const back = new URL(url.searchParams.get('failureReturnTo')!, 'http://localhost')
  expect(back.searchParams.get('keyword')).toBe('한스빌')
  expect(back.searchParams.get('failure')).toBe('announcement:1')
})
