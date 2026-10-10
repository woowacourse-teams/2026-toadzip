import { labels } from '../management/fields'
import { SourceUrl } from './SourceUrl'

export function storedDataRows(value: unknown, path = '전체 값'): Array<{ path: string; value: unknown }> {
  if (Array.isArray(value)) {
    if (!value.length) return [{ path, value }]
    return value.flatMap((item, index) => storedDataRows(item, `${path === '전체 값' ? '' : path}[${index}]`))
  }
  if (typeof value === 'object' && value !== null) {
    const entries = Object.entries(value)
    if (!entries.length) return [{ path, value }]
    return entries.flatMap(([key, item]) => storedDataRows(item, path === '전체 값' ? key : `${path}.${key}`))
  }
  return [{ path, value }]
}

export function storedDataValue(path: string, value: unknown) {
  if (path.split('.').at(-1)?.toLowerCase() === 'servicekey') return '비공개'
  if (value === null) return '값 없음 (null)'
  if (value === undefined) return '기록 없음'
  if (value === '') return '빈 문자열'
  if (Array.isArray(value)) return '항목 없는 배열'
  if (typeof value === 'object') return '항목 없는 객체'
  if (typeof value === 'string') {
    if (/url$/i.test(path) || /^https?:\/\//i.test(value)) return <SourceUrl url={value} />
    const translated = value !== 'true' && value !== 'false' && Object.hasOwn(labels, value) ? labels[value] : undefined
    return translated ? `${translated} (${value})` : value
  }
  return String(value)
}
