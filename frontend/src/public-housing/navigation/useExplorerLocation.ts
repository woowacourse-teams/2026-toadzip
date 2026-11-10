import { useCallback, useMemo } from 'react'
import { useLocation, useNavigate, type NavigateFunction, type NavigateOptions, type To } from 'react-router'
import { toDetailPathLocation, toExplorerQueryLocation } from './detailLocation.ts'

/** 지도 화면은 상세 주소(`/complexes/1`)도 `/explore?complexId=1`로 읽는다. */
export function useExplorerLocation() {
  const location = useLocation()
  return useMemo(() => toExplorerQueryLocation(location), [location])
}

/** 지도 화면이 만든 `/explore?complexId=1` 이동을 주소창에는 상세 주소로 남긴다. */
export function useExplorerNavigate(): NavigateFunction {
  const navigate = useNavigate()
  const current = useExplorerLocation()
  return useCallback((to: To | number, options?: NavigateOptions) => {
    if (typeof to === 'number') return navigate(to)
    return navigate(toDetailPath(to, current.pathname), options)
  }, [navigate, current.pathname]) as NavigateFunction
}

function toDetailPath(to: To, currentPathname: string): To {
  if (typeof to === 'string') {
    const url = new URL(to, 'http://explorer.invalid' + currentPathname)
    const next = toDetailPathLocation({ pathname: url.pathname, search: url.search })
    return next.pathname + next.search + url.hash
  }
  const next = toDetailPathLocation({ pathname: to.pathname ?? currentPathname, search: to.search ?? '' })
  return { ...to, pathname: next.pathname, search: next.search }
}
