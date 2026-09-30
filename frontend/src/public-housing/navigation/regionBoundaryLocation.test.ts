import { describe, expect, it } from 'vitest'
import { parseRegionBoundaryCode, setRegionBoundaryCode } from './regionBoundaryLocation.ts'

describe('region boundary location', () => {
  it('restores city and unsupported province selections independently of housing filters', () => {
    expect(parseRegionBoundaryCode(new URLSearchParams('boundaryRegionCode=41110&complexRegionCode=11'))).toBe('41110')
    expect(parseRegionBoundaryCode(new URLSearchParams('boundaryRegionCode=11'))).toBe('11')
  })
  it.each(['', 'boundaryRegionCode=', 'boundaryRegionCode=4111', 'boundaryRegionCode=abcde', 'boundaryRegionCode=41110&boundaryRegionCode=41111'])('ignores absent, malformed or duplicate selection %s', (query) => {
    expect(parseRegionBoundaryCode(new URLSearchParams(query))).toBeNull()
  })
  it('replaces and clears only the boundary selection without mutating the input', () => {
    const original = new URLSearchParams('boundaryRegionCode=41110&complexRegionCode=11&complexId=17&source=shared')
    const replaced = setRegionBoundaryCode(original, '41111')
    expect(replaced.get('boundaryRegionCode')).toBe('41111')
    expect(original.get('boundaryRegionCode')).toBe('41110')
    expect(setRegionBoundaryCode(replaced, null).toString()).toBe('complexRegionCode=11&complexId=17&source=shared')
  })
})
