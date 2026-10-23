import { expect, it } from 'vitest'
import { parseOfficialCsv } from './officialListCsv'
const metadata = { sourceUrl: 'https://www.i-sh.co.kr/list', asOf: '2026-10-17', agencyCode: 'SH' }
it('공식 CSV의 따옴표·쉼표·줄바꿈과 표 붙여넣기를 보존한다', () => {
  expect(parseOfficialCsv('\uFEFF단지명,도로명주소\r\n"단지, 1","서울 중구 세종대로 110"', metadata).complexes).toEqual([{ name: '단지, 1', roadAddress: '서울 중구 세종대로 110' }])
  expect(parseOfficialCsv('단지명\t도로명주소\n"첫\n단지"\t', metadata).complexes).toEqual([{ name: '첫\n단지', roadAddress: '' }])
})
it('주소 미제공 기관 목록을 유지하고 잘못된 열·출처·행은 거부한다', () => {
  expect(parseOfficialCsv('주택명,지번주소\n단지,서울 중구 태평로1가 31', metadata).complexes[0].roadAddress).toBe('')
  for (const input of ['주소\n서울', '단지명,단지명\n가,나', '단지명,도로명주소\n가,나,다', '단지명\n"열림']) expect(() => parseOfficialCsv(input, metadata)).toThrow()
  expect(() => parseOfficialCsv('단지명\n단지', { ...metadata, sourceUrl: 'javascript:alert(1)' })).toThrow()
})
