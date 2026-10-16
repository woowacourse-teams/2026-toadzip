import { expect, it, vi } from 'vitest'
import { parseExchangeResult, readExchangeResult, writeExchangeRequest, type ExchangeDirectory } from './folderExchange'
import type { ReviewTarget } from './documentReview'
const target: ReviewTarget = { key: 'complexes:7:basic', label: '단지 전체', version: 2, endpoint: '/', current: {}, body: {},
  fields: [{ name: 'totalHouseholdCount', label: '전체 세대수', type: 'number', unit: '세대', scope: '단지 전체' }] }
const document = { fileName: '공고.pdf', sha256: 'a'.repeat(64), totalPages: 1,
  pages: [{ page: 1, text: '전체 120세대', items: [], requiresOcr: false }] }
const result = { schemaVersion: 'document-review/v1', targetKey: target.key, version: 2, sha256: document.sha256,
  proposals: [{ field: 'totalHouseholdCount', value: 120, page: 1, quote: '전체 120세대', unit: '세대', scope: '단지 전체', reason: '공식 근거' }], unresolved: [] }
it('같은 요청·대상·버전·PDF·원문 근거인 결과만 받아들인다', () => {
  const valid = { schemaVersion: 'document-exchange/v1', requestId: 'current', result }
  expect(JSON.parse(parseExchangeResult(JSON.stringify(valid), 'current', target, document))).toEqual(result)
  for (const invalid of [{ ...valid, requestId: 'other' }, { ...valid, result: { ...result, version: 1 } },
    { ...valid, result: { ...result, sha256: 'b'.repeat(64) } }, { ...valid, result: { ...result, proposals: [{ ...result.proposals[0], quote: '없는 인용' }] } }]) {
    expect(() => parseExchangeResult(JSON.stringify(invalid), 'current', target, document)).toThrow()
  }
})
it('새 요청 ID 파일에만 자료를 쓰고 스트림을 닫은 뒤 결과 파일명을 반환한다', async () => {
  const writer = { write: vi.fn(), close: vi.fn(), abort: vi.fn() }
  const handle = { createWritable: vi.fn().mockResolvedValue(writer), getFile: vi.fn() }
  const directory: ExchangeDirectory = { name: '전용 폴더', getFileHandle: vi.fn().mockResolvedValue(handle) }
  const request = await writeExchangeRequest(directory, target, document, 'https://example.com/official')
  expect(request.requestFile).toMatch(/^review-[a-f0-9-]+\.request\.json$/)
  expect(JSON.parse(writer.write.mock.calls[0][0]).requestId).toBe(request.id)
  expect(writer.close).toHaveBeenCalledOnce()
})
it('결과 없음은 대기하고 처리 오류 파일은 명시적인 실패로 표시한다', async () => {
  const missing = new DOMException('없음', 'NotFoundError')
  const getFileHandle = vi.fn().mockRejectedValue(missing)
  const directory = { name: '전용 폴더', getFileHandle }
  const request = { id: 'current', requestFile: 'q', resultFile: 'r', errorFile: 'e' }
  expect(await readExchangeResult(directory, request, target, document)).toBeNull()
  getFileHandle.mockImplementation(async name => {
    if (name === 'r') throw missing
    return { getFile: async () => ({ size: 50, text: async () => JSON.stringify({ requestId: 'current', message: '로그인 필요' }) }) }
  })
  await expect(readExchangeResult(directory, request, target, document)).rejects.toThrow('로그인 필요')
})
