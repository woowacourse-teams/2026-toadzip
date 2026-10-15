import { beforeEach, expect, it, vi } from 'vitest'
import { validateOcrText, withPdfOcr } from './pdfOcr'
const worker = vi.hoisted(() => ({ recognize: vi.fn(), terminate: vi.fn(), createWorker: vi.fn() }))
vi.mock('tesseract.js', () => ({ createWorker: worker.createWorker }))
beforeEach(() => { vi.clearAllMocks(); worker.terminate.mockResolvedValue(undefined); worker.createWorker.mockResolvedValue(worker) })

it('OCR 결과의 인식 점수와 크기를 검증하고 추정 텍스트를 보존한다', () => {
  expect(validateOcrText('  전체 120세대\n', 87.5)).toEqual({ text: '전체 120세대', confidence: 87.5 })
  expect(() => validateOcrText('x'.repeat(200001), 90)).toThrow()
  expect(() => validateOcrText('값', NaN)).toThrow()
})
it('취소한 OCR은 모델이나 worker를 로드하지 않는다', async () => {
  const abort = new AbortController(); abort.abort()
  await expect(withPdfOcr(abort.signal, () => {}, async () => 'unused')).rejects.toThrow()
})
it('인식 도중 취소하면 작업을 거부하고 worker를 종료한다', async () => {
  worker.recognize.mockImplementation(() => new Promise(() => {}))
  const abort = new AbortController()
  const result = withPdfOcr(abort.signal, () => {}, recognize => recognize(document.createElement('canvas')))
  const rejected = expect(result).rejects.toThrow()
  await vi.waitFor(() => expect(worker.recognize).toHaveBeenCalledOnce())
  abort.abort()
  await rejected
  expect(worker.terminate).toHaveBeenCalled()
})
