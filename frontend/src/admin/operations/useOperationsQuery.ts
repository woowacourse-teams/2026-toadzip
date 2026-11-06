import { useEffect, useState } from 'react'

export type OperationsQuery<T> = {
  data: T | null; pending: boolean; error: string; checkedAt: string | null; retry: () => void
}

// 조회마다 결과를 분리해 새로고침 중 이전 값이나 늦게 도착한 응답을 최신으로 표시하지 않는다.
export function useOperationsQuery<T>(load: (signal: AbortSignal) => Promise<T>, revision: number): OperationsQuery<T> {
  const [attempt, setAttempt] = useState(0)
  const key = `${revision}:${attempt}`
  const [result, setResult] = useState<{ key: string; load: typeof load; data: T | null; error: string; checkedAt: string } | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal).then(data => {
      if (!controller.signal.aborted) setResult({ key, load, data, error: '', checkedAt: new Date().toISOString() })
    }).catch(cause => {
      if (!controller.signal.aborted) setResult({ key, load, data: null, checkedAt: new Date().toISOString(),
        error: cause instanceof Error ? cause.message : '현황을 불러오지 못했습니다.' })
    })
    return () => controller.abort()
  }, [key, load])
  const current = result?.key === key && result.load === load ? result : null
  return { data: current?.data ?? null, pending: !current, error: current?.error ?? '',
    checkedAt: current?.checkedAt ?? null, retry: () => setAttempt(value => value + 1) }
}
