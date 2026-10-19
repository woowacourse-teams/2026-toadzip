import { beforeEach, expect, it, vi } from 'vitest'
import { validateOcrText, withPdfOcr } from './pdfOcr'
const worker = vi.hoisted(() => ({ recognize: vi.fn(), setParameters: vi.fn(), terminate: vi.fn(), createWorker: vi.fn() }))
vi.mock('tesseract.js', () => ({ createWorker: worker.createWorker, PSM: { SPARSE_TEXT: '11' } }))
beforeEach(() => { vi.clearAllMocks(); worker.setParameters.mockResolvedValue(undefined); worker.terminate.mockResolvedValue(undefined); worker.createWorker.mockResolvedValue(worker) })

it('표의 여러 영역을 읽고 결과의 행 순서를 복원한다', async () => {
  const canvas = document.createElement('canvas'); canvas.width = 1000; canvas.height = 1000
  const tsv = 'level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n'
    + '5\t1\t1\t1\t1\t1\t300\t50\t100\t20\t80\t33,660,000\n'
    + '5\t1\t1\t1\t1\t1\t100\t50\t100\t20\t80\t99,000,000'
  worker.recognize.mockResolvedValue({ data: { text: '33,660,000\n\n99,000,000', confidence: 80, tsv } })
  const result = await withPdfOcr(new AbortController().signal, () => {}, recognize => recognize(canvas))
  expect(result.text).toBe('99,000,000 33,660,000')
  expect(worker.setParameters).toHaveBeenCalledWith(expect.objectContaining({ tessedit_pageseg_mode: '11', user_defined_dpi: '400' }))
  expect(worker.recognize).toHaveBeenCalledWith(canvas, {}, expect.objectContaining({ tsv: true }))
  expect(worker.terminate).toHaveBeenCalled()
})

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

it('인식 설정에 실패하면 결과를 만들지 않고 worker를 종료한다', async () => {
  worker.setParameters.mockRejectedValueOnce(new Error('OCR 설정 실패'))
  await expect(withPdfOcr(new AbortController().signal, () => {}, recognize => recognize(document.createElement('canvas'))))
    .rejects.toThrow('OCR 설정 실패')
  expect(worker.recognize).not.toHaveBeenCalled()
  expect(worker.terminate).toHaveBeenCalled()
})
