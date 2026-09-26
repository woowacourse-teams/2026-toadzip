import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { ManagementList } from './ManagementList'
import { ManagementDetail } from './ManagementDetail'
import { ComplexPicker } from './ComplexPicker'
import { ManagementError, type Detail } from './api'
const mocks = vi.hoisted(() => ({list:vi.fn(),detail:vi.fn(),request:vi.fn(),history:vi.fn()}))
vi.mock('./api',async original => ({...(await original<typeof import('./api')>()),...mocks}))
const summary = {id:7,name:'두꺼비 단지',subtitle:'서울 중구 세종대로',provider:'LH',rental:'HAPPY_HOUSING',deleted:false,modified:false,reviewRequired:false,updatedAt:null}
function fixture(): Detail { return {summary,sourceIdentifier:'TEST-7',data:{version:2,name:'두꺼비 단지',rentalType:'HAPPY_HOUSING',agencyCode:'LH',address:{roadAddress:summary.subtitle,pnu:'1114010100100010000',legalDongCode:'1114010100',provinceCode:'11',cityCountyDistrictCode:'11140',latitude:37.5,longitude:127},totalHouseholdCount:10,totalParkingCount:5},housingTypes:[],announcements:[],supplyRows:[],scheduleReviewed:false,schedules:[]} }
beforeEach(() => {vi.clearAllMocks();mocks.list.mockResolvedValue({items:[summary],page:0,hasNext:false});mocks.detail.mockResolvedValue(fixture());mocks.history.mockResolvedValue([])})
it('검색 조건을 URL로 보존하고 상세에서 목록으로 돌아간다',async () => {
  render(<MemoryRouter initialEntries={['/admin/complexes?keyword=두꺼비&provider=LH']}><Routes><Route path="/admin/complexes" element={<ManagementList resource="complexes" />} /><Route path="/admin/complexes/:id" element={<ManagementDetail resource="complexes" />} /></Routes></MemoryRouter>)
  fireEvent.click(await screen.findByRole('link',{name:'두꺼비 단지'}))
  expect(await screen.findByRole('heading',{name:'두꺼비 단지'})).toBeVisible()
  fireEvent.click(screen.getByRole('link',{name:'← 목록으로'}))
  expect(await screen.findByLabelText('단지명·주소')).toHaveValue('두꺼비')
  expect(screen.getByLabelText('기관')).toHaveValue('LH')
})
it('수정 충돌이면 입력값을 유지하고 새로 조회할 수 있다',async () => {
  mocks.request.mockRejectedValue(new ManagementError('다른 작업에서 변경했습니다.',409))
  render(<MemoryRouter initialEntries={['/admin/complexes/7']}><Routes><Route path="/admin/complexes/:id" element={<ManagementDetail resource="complexes" />} /></Routes></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button',{name:'수정'}))
  fireEvent.change(screen.getByLabelText('이름'),{target:{value:'수정한 단지'}})
  fireEvent.submit(screen.getByRole('button',{name:'변경사항 저장'}).closest('form')!)
  expect(await screen.findByRole('alert')).toHaveTextContent('다른 작업에서 변경했습니다.')
  expect(screen.getByLabelText('이름')).toHaveValue('수정한 단지')
  expect(mocks.request).toHaveBeenCalledWith('/api/admin/housing-complexes/7','PUT',expect.objectContaining({version:2,name:'수정한 단지'}))
})
it('삭제는 영향 확인 후 요청하고 복구 동선을 제공한다',async () => {
  mocks.request.mockResolvedValue(null)
  render(<MemoryRouter initialEntries={['/admin/complexes/7']}><Routes><Route path="/admin/complexes/:id" element={<ManagementDetail resource="complexes" />} /></Routes></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button',{name:'삭제'}))
  expect(screen.getByRole('alertdialog',{name:'삭제 확인'})).toHaveTextContent('연결 공고 0건')
  expect(mocks.request).not.toHaveBeenCalled()
  mocks.detail.mockResolvedValue({...fixture(),summary:{...summary,deleted:true},data:{...fixture().data,version:3}})
  fireEvent.click(screen.getByRole('button',{name:'휴지통으로 이동'}))
  expect(await screen.findByRole('button',{name:'복구'})).toBeVisible()
  expect(mocks.request).toHaveBeenCalledWith('/api/admin/housing-complexes/7?version=2','DELETE')
})
it('단지 ID를 몰라도 이름으로 검색해 선택한다',async () => {
  const onSelect=vi.fn();render(<ComplexPicker onSelect={onSelect} />)
  fireEvent.change(screen.getByLabelText('단지명·주소 검색'),{target:{value:'두꺼비'}})
  fireEvent.click(screen.getByRole('button',{name:'단지 검색'}))
  fireEvent.click(await screen.findByRole('button',{name:'선택: 두꺼비 단지'}))
  expect(onSelect).toHaveBeenCalledWith(summary)
})
it('목록 로딩 실패를 빈 결과로 표시하지 않고 재시도한다',async () => {
  mocks.list.mockRejectedValueOnce(new Error('연결 실패'))
  render(<MemoryRouter><ManagementList resource="announcements" /></MemoryRouter>)
  expect(await screen.findByRole('alert')).toHaveTextContent('연결 실패')
  expect(screen.queryByText('표시할 공고가 없습니다.')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button',{name:'다시 불러오기'}))
  await waitFor(() => expect(screen.getByRole('link',{name:'두꺼비 단지'})).toBeVisible())
})

it('저장하지 않은 수정은 메뉴 이동 전에 확인하고 취소하면 유지한다',async () => {
  const confirm = vi.spyOn(window,'confirm').mockReturnValue(false)
  render(<MemoryRouter initialEntries={['/admin/complexes/7']}><Routes><Route path="/admin/complexes/:id" element={<ManagementDetail resource="complexes" />} /><Route path="/admin/complexes" element={<p>목록 도착</p>} /></Routes></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button',{name:'수정'}))
  fireEvent.change(screen.getByLabelText('이름'),{target:{value:'저장 전 이름'}})
  fireEvent.click(screen.getByRole('link',{name:'← 목록으로'}))
  expect(confirm).toHaveBeenCalledOnce()
  expect(screen.getByLabelText('이름')).toHaveValue('저장 전 이름')
  expect(screen.queryByText('목록 도착')).not.toBeInTheDocument()
  confirm.mockReturnValue(true)
  fireEvent.click(screen.getByRole('link',{name:'← 목록으로'}))
  expect(screen.getByText('목록 도착')).toBeVisible()
  confirm.mockRestore()
})
