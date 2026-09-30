/// <reference types="node" />

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('public housing explorer layer order', () => {
  it('지도 필터를 지도 컨트롤 위에, 상세 패널을 필터 위에 둔다', () => {
    const stylesheet = readFileSync(
      resolve(process.cwd(), 'src/index.css'),
      'utf8',
    )

    expect(stylesheet).toMatch(
      /\.housing-map-filter\s*\{[\s\S]*?z-index:\s*200;/,
    )
    expect(stylesheet).toMatch(
      /\.housing-detail-layer\s*\{[\s\S]*?z-index:\s*300;/,
    )
    expect(stylesheet).not.toMatch(
      /@media \(max-width: 767px\)[\s\S]*?\.housing-detail-layer\s*\{[\s\S]*?z-index:\s*(?:[0-9]|[1-9][0-9]|1[0-9]{2}|2[0-9]{2});/,
    )
  })

  it('필터 form의 독립적인 높이 한도를 유지해 하단 적용 버튼을 클립하지 않는다', () => {
    const stylesheet = readFileSync(
      resolve(process.cwd(), 'src/index.css'),
      'utf8',
    )

    expect(stylesheet).not.toMatch(
      /\.housing-map-filter form\s*\{\s*max-height:\s*inherit;/,
    )
  })
})
