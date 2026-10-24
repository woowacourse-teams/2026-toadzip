const DATE_IN_TEXT = /(?:(\d{4}|\d{2})\s*[.\-/년]\s*)?(\d{1,2})\s*[.\-/월]\s*(\d{1,2})\s*일?(?:\s*\([^)]*\))?(?:\s*(\d{1,2}):(\d{2}))?/
/** "2026.10.27(화) 10:00 ~ 10.30(금) 17:00"처럼 공고문에 적힌 기간을 날짜·시각으로 바꾼다. 종료 연도·월이 없으면 시작 값을 쓴다. */
export function parseRange(line: string, defaultYear: number) {
  const [from, to] = line.split(/~|∼|〜|부터/)
  const start = DATE_IN_TEXT.exec(from ?? '')
  if (!start) return null
  const year = start[1] ? Number(start[1].length === 2 ? `20${start[1]}` : start[1]) : defaultYear
  const end = to ? DATE_IN_TEXT.exec(to) : null
  const dayOnly = to && !end ? /(\d{1,2})\s*일?(?:\s*\([^)]*\))?(?:\s*(\d{1,2}):(\d{2}))?/.exec(to) : null
  const pad = (number: number | string) => String(number).padStart(2, '0')
  const date = (y: number, m: string, d: string) => `${y}-${pad(m)}-${pad(d)}`
  const time = (h?: string, m?: string) => h && m ? `${pad(h)}:${m}` : null
  const startDate = date(year, start[2], start[3])
  const endDate = end ? date(end[1] ? Number(end[1].length === 2 ? `20${end[1]}` : end[1]) : year, end[2], end[3])
    : dayOnly ? date(year, start[2], dayOnly[1]) : startDate
  const endTime = end ? time(end[4], end[5]) : dayOnly ? time(dayOnly[2], dayOnly[3]) : null
  return { startDate, startTime: time(start[4], start[5]), endDate, endTime }
}
