// Opt-in verification of downloaded official PDFs; never downloads or changes data.
import { readFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { resolve, dirname, basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { getDocument } from 'pdfjs-dist/legacy/build/pdf.mjs'

const files = process.argv.slice(2)
if (!files.length) throw new Error('사용법: node frontend/scripts/verify-admin-public-pdfs.mjs <PDF 파일> [...]')
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const manifest = JSON.parse(await readFile(new URL('./admin-public-documents.json', import.meta.url), 'utf8'))
const server = await createServer({ root, configFile: false, server: { middlewareMode: true }, appType: 'custom' })
try {
  const { extractPages } = await server.ssrLoadModule('/src/admin/documents/pdfExtraction.ts')
  const { tableRows, tableProposal } = await server.ssrLoadModule('/src/admin/documents/pdfTable.ts')
  for (const path of files) {
    const bytes = await readFile(path)
    assert(bytes.length > 0 && bytes.length <= 25 * 1024 * 1024, 'PDF 크기 한도')
    const hash = createHash('sha256').update(bytes).digest('hex')
    const expected = manifest.find(item => item.file === basename(path))
    if (expected) assert.equal(hash, expected.sha256, '공식 검증 원본의 해시가 변경됐습니다. 갱신 전에 원문을 검토하세요.')
    const loading = getDocument({ data: new Uint8Array(bytes), useSystemFonts: true,
      cMapUrl: resolve(root, 'node_modules/pdfjs-dist/cmaps') + '/', cMapPacked: true,
      standardFontDataUrl: resolve(root, 'node_modules/pdfjs-dist/standard_fonts') + '/' })
    try {
      const pdf = await loading.promise
      const pages = await extractPages(pdf, Array.from({ length: Math.min(pdf.numPages, 50) }, (_, i) => i + 1), new Map(), new AbortController().signal)
      assert.equal(pages.length, Math.min(pdf.numPages, 50))
      if (expected) {
        assert.equal(pdf.numPages, expected.pages)
        assert(pages.some(page => page.text.replace(/\s/g, '').includes(expected.text)), '기대 한글 원문 누락')
        if (expected.moneyCell) {
          const page = pages.find(page => tableRows(page).some(row => row.cells.some(cell => cell.text === expected.moneyCell)))
          assert(page, '금액 표 셀 분리 확인')
          const proposal = tableProposal(page, expected.moneyCell, { name: 'deposit', type: 'text', money: true, unit: '원', scope: '공급대상', label: '보증금' }, '원', '원문의 대학생 공급대상 행')
          assert.equal(proposal.value, expected.moneyWon)
        }
      }
      console.log(JSON.stringify({ file: resolve(path), sha256: hash, totalPages: pdf.numPages,
        pages: pages.map(page => ({ page: page.page, characters: page.text.length, requiresOcr: page.requiresOcr, rows: tableRows(page).length })) }))
    } finally { await loading.destroy() }
  }
} finally { await server.close() }
