export const ocrDpi = 400

export function ocrRenderScale(width: number, height: number): number {
  if (![width, height].every(value => Number.isFinite(value) && value > 0)) throw new Error('OCR 페이지 크기를 확인해 주세요.')
  return Math.min(ocrDpi / 72, Math.sqrt(16000000 / (width * height)), 10000 / width, 10000 / height)
}

export function ocrReadingText(text: string, tsv: string | null | undefined, width: number, height: number): string {
  if (!tsv) return text
  if (typeof tsv !== 'string' || tsv.length > 4000000) throw new Error('OCR 위치 자료가 너무 크거나 형식이 올바르지 않습니다.')
  const records = tsv.split(/\r?\n/)
  if (records[0].startsWith('level\tpage_num\t')) records.shift()
  // Tesseract.js exposes GetTSVText without the CLI's optional header.
  else if (!/^[1-5]\t\d+\t/.test(records[0])) throw new Error('OCR 위치 자료 형식을 확인해 주세요.')
  if (records.length > 40000) throw new Error('OCR 위치 자료가 너무 많습니다. 페이지 범위를 줄여 주세요.')
  const words: { text: string; x: number; y: number; height: number }[] = []
  for (const record of records) {
    const fields = record.split('\t')
    if (fields[0] !== '5') continue
    const word = fields.slice(11).join(' ').trim()
    if (!word) continue
    const [x, top, wordWidth, wordHeight] = fields.slice(6, 10).map(Number)
    if (fields.length < 12 || ![x, top, wordWidth, wordHeight].every(Number.isSafeInteger)
      || x < 0 || top < 0 || wordWidth <= 0 || wordHeight <= 0 || x + wordWidth > width || top + wordHeight > height) {
      throw new Error('OCR 글자 위치가 원문 이미지 범위를 벗어났습니다.')
    }
    words.push({ text: word, x, y: top, height: wordHeight })
    if (words.length > 20000) throw new Error('OCR 글자가 너무 많습니다. 페이지 범위를 줄여 주세요.')
  }
  const lines: { y: number; height: number; words: typeof words }[] = []
  for (const word of words.sort((a, b) => a.y - b.y || a.x - b.x)) {
    const line = lines.at(-1)
    const overlap = line ? Math.min(line.y + line.height, word.y + word.height) - Math.max(line.y, word.y) : 0
    if (line && overlap >= Math.min(line.height, word.height) * .5) line.words.push(word)
    else lines.push({ y: word.y, height: word.height, words: [word] })
  }
  // Keep every recognized token unchanged; positions restore reading order, not table meaning.
  return lines.map(line => line.words.sort((a, b) => a.x - b.x).map(word => word.text).join(' ')).join('\n') || text
}
