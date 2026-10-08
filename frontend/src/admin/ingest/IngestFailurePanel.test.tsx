import { MemoryRouter } from 'react-router'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { IngestFailurePanel } from './IngestFailurePanel'
import { getFailureReviews, type FailureReview, type FailureReviewPageData } from './failureReviewApi'

vi.mock('./failureReviewApi', () => ({getFailureReviews:vi.fn()}))
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
  Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value:function(this:HTMLDialogElement){this.setAttribute('open','')}})
  Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value:function(this:HTMLDialogElement){this.removeAttribute('open');this.dispatchEvent(new Event('close'))}})
})

it('대상·원인·다음 행동과 선택한 실행을 함께 보여준다', async () => {
  fetchFailures.mockResolvedValue(page())
  render(<IngestFailurePanel initialCategory="announcement" executionId="run-1" />, {wrapper:MemoryRouter})
  expect(await screen.findByText(row.target)).toBeVisible()
  expect(screen.getByText('연결할 단지를 찾지 못함')).toBeVisible()
  expect(screen.getByText(row.detail)).toBeVisible()
  expect(screen.getByText('총 41건 · 선택한 실행에서 발생 1건')).toBeVisible()
  expect(fetchFailures).toHaveBeenCalledWith('announcement','announcement','PENDING',0,expect.any(AbortSignal))
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
  expect(screen.getByText('총 41건 · 선택한 실행에서 발생 0건')).toBeVisible()
  fireEvent.click(screen.getByRole('button',{name:'확인·처리: 경상북도 경주시'}))
  expect(within(screen.getByRole('dialog')).getByRole('link',{name:'API 키 입력·원천 수집'})).toHaveAttribute('href','/admin/ingest#complex-pipelines')
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

it('재조회 후 미해결 목록에서 사라진 기록의 처리창을 닫는다', async () => {
  fetchFailures.mockResolvedValueOnce(page()).mockResolvedValueOnce({...page([],0),totalElements:0,totalPages:0,hasNext:false})
  const {rerender}=render(<IngestFailurePanel initialCategory="announcement" executionId={null} />, {wrapper:MemoryRouter})
  fireEvent.click(await screen.findByRole('button',{name:`확인·처리: ${row.target}`}))
  expect(screen.getByRole('dialog')).toBeVisible()
  rerender(<IngestFailurePanel initialCategory="announcement" executionId={null} refreshToken={1}/>)
  await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(screen.getByText('해당 상태의 오류가 없습니다.')).toBeVisible()
})

it('LH 보강 실패에서는 같은 공고 번호로 상세·공급 원천을 각각 확인한다', async () => {
  fetchFailures.mockResolvedValue(page([{...row,category:'enrichment',source:'LH_ANNOUNCEMENT',raw:{panId:'PAN-7'}}]))
  render(<IngestFailurePanel initialCategory="enrichment" executionId={null} />, {wrapper:MemoryRouter})
  fireEvent.click(await screen.findByRole('button',{name:`확인·처리: ${row.target}`}))
  const dialog=within(screen.getByRole('dialog'))
  expect(dialog.getByRole('link',{name:'LH 상세 원천 확인'})).toHaveAttribute('href','/admin/sources?category=LH_ANNOUNCEMENT_DETAIL&keyword=PAN-7')
  expect(dialog.getByRole('link',{name:'LH 공급 원천 확인'})).toHaveAttribute('href','/admin/sources?category=LH_ANNOUNCEMENT_SUPPLY&keyword=PAN-7')
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
