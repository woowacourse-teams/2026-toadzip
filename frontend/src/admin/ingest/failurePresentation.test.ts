import { expect, it } from 'vitest'
import { collectionCause, failureTarget } from './failurePresentation'
import type { IngestFailure } from './api'
const row:IngestFailure = {target:'',sourceKey:'',source:'MYHOME_COMPLEX',reason:'error',detail:'',status:'PENDING',occurredAt:'2026-09-01',occurrenceCount:1,executionId:null,raw:{}}
it('모르는 지역을 임의의 이름으로 바꾸지 않는다', () => {
  expect(failureTarget({...row,target:'brtcCode=99&signguCode=999'}).title).toBe('지역 코드 99999')
})
it('공고 유형과 LH 공고 식별자를 읽기 쉬운 대상으로 표현한다', () => {
  expect(failureTarget({...row,source:'MYHOME_ANNOUNCEMENT',target:'suplyTy=10&pageNo=2&numOfRows=500'}).title).toBe('행복주택')
  expect(failureTarget({...row,source:'LH_ANNOUNCEMENT_DETAIL',target:'PAN_ID=ABC&UPP_AIS_TP_CD=06'}).title).toBe('공고 ABC')
})
it('일일 한도와 속도 제한을 구분하고 HTTP 429만으로 제한 종류를 단정하지 않는다', () => {
  expect(collectionCause({...row,detail:'HTTP 429, resultCode=22'}).title).toBe('일일 호출 한도 초과')
  expect(collectionCause({...row,detail:'HTTP 429, resultCode=23'}).title).toBe('요청 속도 제한')
  expect(collectionCause({...row,detail:'HTTP 429'}).title).toBe('외부 API 호출 제한')
})
it('원천 정제 대상은 원래 이름을 유지한다', () => {
  expect(failureTarget({...row,source:null,target:'수서6단지'}).title).toBe('수서6단지')
})

it('빈 상세 행은 호출 제한과 구분해 원천 확인을 안내한다', () => {
  expect(collectionCause({...row,detail:'LH 공고 상세 응답에 내용 없는 행이 있습니다.'}).title).toBe('응답 내용 누락')
})
