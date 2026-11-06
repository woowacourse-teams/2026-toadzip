import original from './gh-839-extraction.json'

/** 실제 응답의 대상 공통 묶음은 이제 유효하다. 교정 흐름에는 서로 다른 수량 종류를 섞은 오류를 주입한다. */
export function withMixedSharedCounts() {
  const value = structuredClone(original)
  for (const index of [9, 11, 13, 14, 22]) {
    const paths = value.sharedCounts[index].paths
    paths[paths.length - 1] = paths[paths.length - 1].replace('.supplyHouseholdCount', '.reserveCount')
  }
  return value
}
