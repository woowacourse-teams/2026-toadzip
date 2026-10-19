import { expect, it } from 'vitest'
import example from '../registration/announcementImportExample.json'
import { blankRow, blankTarget } from '../supply/supplyCorrectionApi'
import { confirmedSupplyRow, defaultChildDestination, defaultRowDestination, defaultTargetDestinations, mergeAnnouncementRows, parseAnnouncementJson } from './importValues'

const row = { ...blankRow(0), id: 7, key: 'row:7', complexName: '두꺼비 행복주택', typeName: '36A', pnu: '1114010100100010000', householdCount: 20,
  targets: [{ ...blankTarget(0), id: 12, key: 'target:12', target: '청년', supplyRank: '1순위', rent: '123456', reserveCount: 5 }] }
it('같은 공고 JSON을 기존 공급행과 조건에 대응해 ID와 입력에 없는 값을 유지한다', () => {
  const document = parseAnnouncementJson(JSON.stringify(example))
  expect(defaultRowDestination(document.supplyRows[0], [row])).toBe('row:7')
  expect(defaultTargetDestinations(document.supplyRows[0], row)).toEqual(['12'])
  const result = mergeAnnouncementRows([row], document, ['row:7'], [['12']])
  expect(result[0]).toMatchObject({ id: 7, householdCount: 20, exclusiveArea: 36.5, supplyArea: 49.8, targets: [{ id: 12, rent: '200000', deposit: '10000000' }] })
  expect(result[0].targets).toHaveLength(1)
})
it('한스빌의 같은 이름 26 두 개를 26A·26B에 임의 대응하거나 새 행으로 단정하지 않는다', () => {
  const document = parseAnnouncementJson(JSON.stringify(example))
  const input = { ...document.supplyRows[0], sourceHousingTypeName: '26A' }
  expect(defaultRowDestination(input, [{ ...row, typeName: '26' }, { ...row, id: 8, key: 'row:8', typeName: '26' }])).toBe('')
  expect(defaultRowDestination({ ...input, sourceHousingTypeName: '26' }, [{ ...row, typeName: '26' }, { ...row, id: 8, key: 'row:8', typeName: '26' }])).toBe('')
})
it('미확인 값으로 기존 수량·금액을 지우지 않고 18자리 숫자 토큰의 정밀도를 지킨다', () => {
  const text = JSON.stringify(example).replace('10000000', '999999999999999999').replace('"monthlyRent":200000', '"monthlyRent":null').replace('"totalSupplyHouseholdCount":20', '"totalSupplyHouseholdCount":null')
  const document = parseAnnouncementJson(text)
  const result = mergeAnnouncementRows([row], document, ['row:7'], [['12']])
  expect(result[0]).toMatchObject({ householdCount: 20, targets: [{ deposit: '999999999999999999', rent: '123456' }] })
})
it('같은 대상과 순위가 여러 개면 조건 대응을 확인하게 한다', () => {
  const document = parseAnnouncementJson(JSON.stringify(example))
  expect(defaultTargetDestinations(document.supplyRows[0], { ...row, targets: [row.targets[0], { ...row.targets[0], id: 13, key: 'target:13' }] })).toEqual([''])
})
it('값이 있어도 미확인으로 표시한 면적·금액은 기존값을 유지하고 미확인 식별자는 자동 대응하지 않는다', () => {
  const document = parseAnnouncementJson(JSON.stringify({ ...example, unresolvedFields: [
    { path: '$.supplyRows[0].exclusiveArea', reason: '표 판독 불가' },
    { path: 'supplyRows[0].targets[0].monthlyRent', reason: '금액 판독 불가' },
    { path: 'supplyRows[0].complexReference.pnu', reason: '지번 미확인' },
  ] }))
  expect(defaultRowDestination(confirmedSupplyRow(document, 0), [row])).toBe('')
  const result = mergeAnnouncementRows([{ ...row, exclusiveArea: 35.1 }], document, ['row:7'], [['12']])
  expect(result[0]).toMatchObject({ pnu: row.pnu, exclusiveArea: 35.1, supplyArea: 49.8, targets: [{ id: 12, rent: '123456', deposit: '10000000' }] })
})
it('일정은 유형·이름, 첨부는 URL이 유일하게 같을 때 기존 항목을 제안한다', () => {
  expect(defaultChildDestination({ scheduleType: 'APPLICATION', name: '인터넷 접수' }, [{ id: 3, data: { scheduleType: 'APPLICATION', name: '인터넷 접수' } }], 'schedule')).toBe('3')
  const attachment = { fileUrl: 'https://example.com/notice.pdf' }
  expect(defaultChildDestination(attachment, [{ id: 9, data: attachment }], 'attachment')).toBe('9')
  expect(defaultChildDestination(attachment, [{ id: 9, data: attachment }, { id: 10, data: attachment }], 'attachment')).toBe('')
})
it('구조가 다른 자료와 잘못된 대상 배열을 가져오지 않는다', () => {
  expect(() => parseAnnouncementJson('{"areaUnit":"m2","rows":[]}')).toThrow('admin-announcement-import/v1')
  expect(() => parseAnnouncementJson(JSON.stringify({ ...example, supplyRows: [{ ...example.supplyRows[0], targets: [null] }] }))).toThrow('targets')
})
