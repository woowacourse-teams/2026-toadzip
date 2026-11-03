const compact = (value: string) => value.normalize('NFKC').replace(/\s+/g, '').toLowerCase()

/**
 * 근거 강조 후보 중 같은 텍스트 줄(pdf.js 쪽 내용의 줄바꿈 사이, 표에서는 한 행)에 문맥 표기(주택형·면적)가 있는 것만 남긴다.
 * 같은 쪽의 다른 칸에 같은 숫자가 있어도(건설호수 468 옆의 모집수 200) 그 행의 값만 강조한다. 문맥이 없으면 그대로 둔다.
 */
export function matchesInContext<T extends { index: number; length: number }>(content: string, matches: T[], context: readonly string[] | null | undefined): T[] {
  const terms = (context ?? []).map(compact).filter(Boolean)
  if (!terms.length) return matches
  return matches.filter(({ index, length }) => {
    const start = content.lastIndexOf('\n', index) + 1, end = content.indexOf('\n', index + length)
    const line = compact(content.slice(start, end < 0 ? content.length : end))
    return terms.some(term => line.includes(term))
  })
}
