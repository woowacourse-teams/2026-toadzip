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

  it('필터 제목과 액션을 상단 한 줄에 두고 입력과 오류는 전체 폭을 쓴다', () => {
    const stylesheet = readFileSync(
      resolve(
        process.cwd(),
        'src/public-housing/filters/ComplexFilterToolbar.module.css',
      ),
      'utf8',
    )

    expect(stylesheet).toMatch(
      /\.form\s*\{[\s\S]*?display:\s*grid;[\s\S]*?grid-template-areas:\s*'heading actions'\s*'fields fields'\s*'error error';/,
    )
    expect(stylesheet).toMatch(
      /\.popoverHeading\s*\{[\s\S]*?grid-area:\s*heading;/,
    )
    expect(stylesheet).toMatch(
      /\.fields\s*\{[\s\S]*?grid-area:\s*fields;/,
    )
    expect(stylesheet).toMatch(
      /\.actions\s*\{[\s\S]*?grid-area:\s*actions;/,
    )
    expect(stylesheet).toMatch(
      /\.error\s*\{[\s\S]*?grid-area:\s*error;/,
    )
  })
})
