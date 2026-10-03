import { describe, expect, it } from 'vitest'
import { toHttpUrl } from './httpUrl.ts'

describe('공개 이미지와 원문 URL', () => {
  it.each([
    ['https://example.com', 'https://example.com/'],
    ['http://example.com/a?q=1#page=2', 'http://example.com/a?q=1#page=2'],
    [' HTTPS://EXAMPLE.COM/a ', 'https://example.com/a'],
  ])('HTTP(S) 주소 %s를 정규화한다', (source, expected) => {
    expect(toHttpUrl(source)).toBe(expected)
  })

  it.each([null, '', '/relative', 'not a url', 'javascript:alert(1)', 'data:image/png;base64,a', 'blob:document', 'ftp://example.com'])
   ('공개 링크로 사용할 수 없는 %s를 렌더하지 않는다', (source) => {
      expect(toHttpUrl(source)).toBeNull()
    })
})
