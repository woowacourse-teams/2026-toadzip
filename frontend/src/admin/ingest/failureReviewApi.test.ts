import { beforeEach, expect, it, vi } from 'vitest'
import { requestManagementApi } from '../management/api'
import { getFailureReviews } from './failureReviewApi'

vi.mock('../management/api',()=>({requestManagementApi:vi.fn()}))
const response={items:[{
  id:1,category:'announcement',sourceKey:'notice:3',source:'MYHOME_ANNOUNCEMENT',reason:'COMPLEX_NOT_FOUND',
  detail:'연결 단지 없음',status:'PENDING',lastOccurredAt:'2026-10-03T00:00:00Z',occurrenceCount:2,
  recurrenceCount:1,lastResolvedAt:'2026-10-02T00:00:00Z',lastExecutionId:'run-1',
  sourceComplexIdentifier:null,sourceAnnouncementIdentifier:'notice',targetName:'공고',
  metadata:{sourceHouseSerialNumber:3},productLinkStatus:'UNKNOWN',product:null,
}],page:0,totalElements:1,totalPages:1,hasNext:false}
beforeEach(()=>{ vi.mocked(requestManagementApi).mockReset() })

it('서버의 전체 건수와 대상 불확실성을 보존하고 조회·취소 조건을 전달한다',async()=>{
  vi.mocked(requestManagementApi).mockResolvedValue(response)
  const signal=new AbortController().signal
  const result=await getFailureReviews('announcement','announcement','PENDING',0,signal)
  expect(requestManagementApi).toHaveBeenCalledWith('/api/admin/ingest/failure-reviews?domain=announcement&category=announcement&status=PENDING&page=0&size=20','GET',undefined,signal)
  expect(result).toMatchObject({totalElements:1,totalPages:1,items:[{target:'공고 · 공급행 3',productLinkStatus:'UNKNOWN',product:null,recurrenceCount:1}]})
})

it.each([
  {totalElements:-1}, {items:[{...response.items[0],category:'toString'}]},
  {items:[{...response.items[0],status:'SUCCESS'}]},
  {items:[{...response.items[0],productLinkStatus:'EXISTS'}]},
  {items:[{...response.items[0],productLinkStatus:'EXISTS',product:{id:'../../admin'}}]},
])('손상된 건수·종류·상태·제품 링크를 거절한다: %j',async change=>{
  vi.mocked(requestManagementApi).mockResolvedValue({...response,...change})
  await expect(getFailureReviews('announcement','all','ALL',0)).rejects.toThrow('오류 검토 응답 형식')
})

it('조회 실패를 빈 데이터로 바꾸지 않는다',async()=>{
  vi.mocked(requestManagementApi).mockRejectedValue(new Error('권한 없음'))
  await expect(getFailureReviews('complex','all','PENDING',0)).rejects.toThrow('권한 없음')
})
