import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { targetGroupLabels, targetGroups } from './targetGroups'

describe('공급대상 분류 상수', () => {
  it('AI 워커·서버와 함께 쓰는 계약의 상수와 같은 순서이고 모두 화면 이름이 있다', () => {
    const contract = JSON.parse(readFileSync(resolve(process.cwd(), '../ai-worker/contract/announcement-pdf-extraction/supply-target-groups.json'), 'utf8')) as { groups: string[] }
    expect([...targetGroups]).toEqual(contract.groups)
    expect(Object.keys(targetGroupLabels).sort()).toEqual([...contract.groups].sort())
  })
})
