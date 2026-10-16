import type { ExtractedPage, PdfTextItem } from './pdfExtraction'
import type { Proposal, ReviewField } from './documentReview'
export type TableRow = { y: number; cells: { x: number; text: string }[] }
export function tableRows(page: ExtractedPage): TableRow[] {
  if (page.requiresOcr || page.method === 'ocr') return []
  const lines: { y: number; items: PdfTextItem[] }[] = []
  for (const item of [...page.items].filter(item => item.str.trim() && [item.x, item.y, item.width, item.height].every(Number.isFinite))
    .sort((a, b) => b.y - a.y || a.x - b.x)) {
    const previous = lines.at(-1)
    if (previous && Math.abs(previous.y - item.y) <= 2) previous.items.push(item)
    else lines.push({ y: item.y, items: [item] })
  }
  return lines.map(line => {
    const cells: TableRow['cells'] = []; let right = -Infinity
    for (const item of line.items.sort((a, b) => a.x - b.x)) {
      const previous = cells.at(-1)
      if (previous && item.x >= right - 2 && item.x - right <= Math.max(6, item.height * .7)) previous.text += ` ${item.str}`
      else cells.push({ x: item.x, text: item.str })
      right = item.x + item.width
    }
    return { y: line.y, cells }
  })
}
export function tableProposal(page: ExtractedPage, cell: string, field: ReviewField, unit: string, scope: string): Proposal {
  if (page.requiresOcr || !scope.trim() || scope.length > 500 || cell.length > 2000
    || !page.text.replace(/\s/g, '').includes(cell.replace(/\s/g, ''))) throw new Error('원문 셀과 적용 대상을 확인해 주세요.')
  if (!field.money && field.type !== 'number') throw new Error('표 수정안은 숫자 항목을 선택해 주세요.')
  const match = /^\s*((?:0|[1-9]\d*|[1-9]\d{0,2}(?:,\d{3})+)(?:\.\d+)?)\s*(만원|천원|원|㎡|세대|대|명)?\s*$/.exec(cell)
  if (!match || match[2] && match[2] !== unit) throw new Error('단일 숫자와 일치하는 원문 단위를 확인해 주세요. 범위·혼합 값은 수기로 검토하세요.')
  const raw = match[1].replaceAll(',', '')
  let value: Proposal['value']
  if (field.money) {
    const factor = ({ 원: 1n, 천원: 1000n, 만원: 10000n } as Record<string, bigint>)[unit]
    if (!factor) throw new Error('금액의 원문 단위는 원·천원·만원 중 선택해 주세요.')
    const [whole, decimal = ''] = raw.split('.')
    if (whole.length > 18 || decimal.length > 4) throw new Error('금액의 크기와 소수 자릿수를 확인해 주세요.')
    const denominator = 10n ** BigInt(decimal.length)
    const numerator = BigInt(whole + decimal) * factor
    if (numerator % denominator !== 0n) throw new Error('원 미만의 금액은 자동 환산할 수 없습니다.')
    value = String(numerator / denominator)
    if (value.length > 18) throw new Error('원 단위 금액은 18자리 이하여야 합니다.')
  } else {
    if (unit !== field.unit) throw new Error('항목과 같은 단위만 사용할 수 있습니다.')
    value = Number(raw)
    if (!Number.isFinite(value) || value < (field.min ?? 0) || value > (field.max ?? 2147483647)
      || (field.name !== 'exclusiveArea' && !Number.isSafeInteger(value))
      || (field.name === 'exclusiveArea' && !/^\d+(?:\.\d{1,4})?$/.test(raw))) throw new Error('항목의 숫자 범위와 소수 자릿수를 확인해 주세요.')
  }
  return { field: field.name, value, page: page.page, quote: cell, unit: field.unit, scope: field.scope,
    reason: `표 셀·원문 단위 ${unit}·적용 대상 확인: ${scope.trim()}` }
}
