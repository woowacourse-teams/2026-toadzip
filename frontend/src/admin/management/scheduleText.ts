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

type CollectedEvent = { data: Record<string, unknown>; complexName: string | null }
type ComplexRow = { housingComplexId: number | null; housingComplexName: string | null; sourceComplexName: string }
const compact = (text: string) => text.replace(/\s+/g, '')
/** 저장용 00:00은 시각 미상이라 시각을 비운다. */
function split(value: unknown) {
  const text = typeof value === 'string' ? value : ''
  const date = /^\d{4}-\d{2}-\d{2}/.exec(text)?.[0] ?? null
  const time = /T(\d{2}:\d{2})/.exec(text)?.[1] ?? null
  return { date, time: time === '00:00' ? null : time }
}

/**
 * 수집·PDF로 저장된 접수(APPLICATION) 일정을 확정 접수 일정 초안으로 바꾼다.
 * 모든 단지의 기간이 같으면 '공고 전체' 한 줄로, 다르면 단지별로 만든다. 단지는 공급행 단지명과 맞을 때만 연결한다.
 */
export function confirmedSchedulesFromEvents(events: CollectedEvent[], supplyRows: ComplexRow[]) {
  const applications = events.filter(event => event.data.scheduleType === 'APPLICATION').map(event => {
    const start = split(event.data.startAt), end = split(event.data.endAt)
    const name = compact(event.complexName ?? '')
    const complex = name ? supplyRows.find(row => row.housingComplexId !== null
      && [row.sourceComplexName, row.housingComplexName].some(label => label && name.includes(compact(label)))) : undefined
    return { housingComplexId: complex?.housingComplexId ?? null, startDate: start.date, startTime: start.time, endDate: end.date ?? start.date, endTime: end.time }
  }).filter(row => row.startDate)
  const period = (row: typeof applications[number]) => [row.startDate, row.startTime, row.endDate, row.endTime].join('|')
  const unique = new Map(applications.map(row => [`${row.housingComplexId}|${period(row)}`, row]))
  const rows = new Set(applications.map(period)).size === 1 ? [{ ...applications[0], housingComplexId: null }] : [...unique.values()]
  return rows.map(row => ({ ...row, supplyRank: null, state: 'CONFIRMED', condition: null }))
}
