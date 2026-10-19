// Opt-in comparison with a downloaded official table and its image-only derivative.
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createCanvas } from '@napi-rs/canvas'
import { getDocument } from 'pdfjs-dist/legacy/build/pdf.mjs'
import tesseract from 'tesseract.js'
import { createServer } from 'vite'

const [sourcePath, imagePath] = process.argv.slice(2)
if (!sourcePath || !imagePath) throw new Error('사용법: node frontend/scripts/verify-admin-table-ocr.mjs <GH 원본 표 PDF> <같은 표의 이미지 전용 PDF>')
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const manifest = JSON.parse(await readFile(new URL('./admin-public-documents.json', import.meta.url), 'utf8'))
const expectedSource = manifest.find(item => item.file === 'gh-rent-table.pdf')
const source = await readFile(sourcePath); const image = await readFile(imagePath)
assert.equal(createHash('sha256').update(source).digest('hex'), expectedSource.sha256, '공식 원본 해시 확인')
assert(source.length < 25 * 1024 * 1024 && image.length < 25 * 1024 * 1024, 'PDF 크기 한도')
const options = { useSystemFonts: true, cMapUrl: resolve(root, 'node_modules/pdfjs-dist/cmaps') + '/', cMapPacked: true,
  standardFontDataUrl: resolve(root, 'node_modules/pdfjs-dist/standard_fonts') + '/' }
const sourceTask = getDocument({ ...options, data: new Uint8Array(source) })
const imageTask = getDocument({ ...options, data: new Uint8Array(image) })
const server = await createServer({ root, configFile: false, server: { middlewareMode: true }, appType: 'custom' })
let worker
try {
  const { extractPages } = await server.ssrLoadModule('/src/admin/documents/pdfExtraction.ts')
  const { tableRows } = await server.ssrLoadModule('/src/admin/documents/pdfTable.ts')
  const { configurePdfOcr } = await server.ssrLoadModule('/src/admin/documents/pdfOcr.ts')
  const { ocrRenderScale, ocrReadingText } = await server.ssrLoadModule('/src/admin/documents/pdfOcrLayout.ts')
  const original = await sourceTask.promise; const pdf = await imageTask.promise
  assert.equal(original.numPages, 1); assert.equal(pdf.numPages, 1)
  const [sourcePage] = await extractPages(original, [1], new Map(), new AbortController().signal)
  const expectedRows = tableRows(sourcePage).map(row => row.cells.map(cell => cell.text)
    .filter(text => /^\d{1,3}(?:,\d{3})+$/.test(text) && Number(text.replaceAll(',', '')) >= 100000))
    .filter(row => row.length === 3)
  assert.equal(expectedRows.length, 10, '원본의 임대시세·보증금·월임대료 10행 확인')
  const expectedValues = [...new Set(expectedRows.flat())]
  const page = await pdf.getPage(1)
  assert.equal((await page.getTextContent()).items.filter(item => item.str?.trim()).length, 0, '텍스트 층 없는 이미지 PDF 필요')
  for (const baseline of [true, false]) {
    worker = await tesseract.createWorker(['kor', 'eng'], 1, { langPath: resolve(root, 'public/ocr-7.0.0/lang'), cacheMethod: 'none' })
    const originalViewport = page.getViewport({ scale: 1 })
    const scale = baseline ? 2 : ocrRenderScale(originalViewport.width, originalViewport.height)
    const viewport = page.getViewport({ scale })
    const canvas = createCanvas(Math.ceil(viewport.width), Math.ceil(viewport.height))
    await page.render({ canvas, viewport }).promise
    if (baseline) await worker.setParameters({ tessedit_pageseg_mode: tesseract.PSM.SINGLE_BLOCK, user_defined_dpi: '144', preserve_interword_spaces: '0' })
    else await configurePdfOcr(worker)
    const start = Date.now()
    const { data } = await worker.recognize(canvas.toBuffer('image/png'), {}, { text: true, tsv: true })
    const text = baseline ? data.text : ocrReadingText(data.text, data.tsv, canvas.width, canvas.height)
    const moneyTokens = value => new Set(value.match(/(?<![\d,])\d{1,3}(?:,\d{3})+(?![\d,])/g) ?? [])
    const values = moneyTokens(text)
    const lines = text.split('\n').map(moneyTokens)
    const matchedValues = expectedValues.filter(value => values.has(value))
    const matchedRows = expectedRows.filter(row => lines.some(line => row.every(value => line.has(value))))
    console.log(JSON.stringify({ mode: baseline ? 'before' : 'after', sourceSha256: expectedSource.sha256,
      imageSha256: createHash('sha256').update(image).digest('hex'), confidence: data.confidence,
      exactMoneyValues: `${matchedValues.length}/${expectedValues.length}`, exactMoneyRows: `${matchedRows.length}/${expectedRows.length}`,
      missing: expectedValues.filter(value => !matchedValues.includes(value)), elapsedMs: Date.now() - start, text }))
    if (!baseline) {
      assert.equal(matchedValues.length, expectedValues.length, '원본 금액의 정확한 문자열 인식')
      assert.equal(matchedRows.length, expectedRows.length, '원본 금액의 행별 배치 확인')
    }
    await worker.terminate(); worker = undefined
  }
} finally {
  await worker?.terminate()
  await sourceTask.destroy(); await imageTask.destroy(); await server.close()
}
