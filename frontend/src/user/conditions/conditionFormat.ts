const number = new Intl.NumberFormat('ko-KR')

export function formatWon(won: number): string {
  if (won === 0) return '0원'
  const eok = Math.floor(won / 1e8)
  const man = Math.floor((won % 1e8) / 1e4)
  const rest = won % 1e4
  const parts: string[] = []
  if (eok) parts.push(`${number.format(eok)}억`)
  if (man) parts.push(`${number.format(man)}만`)
  if (rest) parts.push(number.format(rest))
  return parts.join(' ') + (rest ? '원' : ' 원')
}

export function formatDots(iso: string): string {
  return iso.replaceAll('-', '.')
}

export function formatDigitsAsDate(digits: string): string {
  if (digits.length <= 4) return digits
  if (digits.length <= 6) return `${digits.slice(0, 4)}.${digits.slice(4)}`
  return `${digits.slice(0, 4)}.${digits.slice(4, 6)}.${digits.slice(6)}`
}

export function ageAt(birthIso: string, referenceIso: string): number {
  const [by, bm, bd] = birthIso.split('-').map(Number)
  const [ry, rm, rd] = referenceIso.split('-').map(Number)
  return ry - by - (rm < bm || (rm === bm && rd < bd) ? 1 : 0)
}

export function formatAge(birthIso: string, referenceIso: string): string {
  return `만 ${ageAt(birthIso, referenceIso)}세`
}

/** "M월 D일" */
export function formatMonthDay(iso: string): string {
  const dayInSeoul = iso.includes('T') ? new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(iso)) : iso
  const [, month, day] = dayInSeoul.slice(0, 10).split('-').map(Number)
  return `${month}월 ${day}일`
}

export function withJosa(word: string, pair: '을/를' | '이/가' | '은/는'): string {
  const code = word.charCodeAt(word.length - 1)
  const hasFinal = code >= 0xac00 && code <= 0xd7a3 && (code - 0xac00) % 28 !== 0
  const [withFinal, withoutFinal] = pair.split('/')
  return word + (hasFinal ? withFinal : withoutFinal)
}
