import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { hasExplorerState } from './explorerState'

const cases: ReadonlyArray<readonly [string, boolean]> = [
  ['', false],
  ['?utm_source=naver', false],
  ['?complexId=1', true],
  ['?announcementId=101&tab=announcements', true],
  ['?mapLat=37.5&mapLng=127&mapZoom=12', true],
  ['?map', true],
  ['?boundaryRegionCode=11', true],
  ['?login=required', true],
  ['?login', true],
  ['?tab=announcements', true],
  ['?inbox=1', true],
  ['?utm_source=x&complexId=1', true],
  ['?tabs=1', false],
  ['?logins=1', false],
  ['?xcomplexId=1', false],
  ['?ref=complexId', false],
]

// nginx/app.conf의 `/` 이동 규칙. 서버에서 먼저 /explore로 보내 랜딩이 잠깐 보이지 않게 한다.
function nginxExplorerStatePattern() {
  const config = readFileSync(resolve('nginx/app.conf'), 'utf8')
  const pattern = /if \(\$args ~ "([^"]+)"\) \{\s*return 308 \/explore\?\$args;/.exec(config)?.[1]
  if (!pattern) throw new Error('nginx/app.conf에서 탐색 상태 이동 규칙을 찾지 못했습니다.')
  return new RegExp(pattern)
}

describe('랜딩 주소의 탐색 상태', () => {
  it.each(cases)('%s → %s', (search, expected) => {
    expect(hasExplorerState(search)).toBe(expected)
  })

  it.each(cases)('nginx 규칙도 %s를 같은 결과(%s)로 판단한다', (search, expected) => {
    expect(nginxExplorerStatePattern().test(search.replace(/^\?/, ''))).toBe(expected)
  })
})
