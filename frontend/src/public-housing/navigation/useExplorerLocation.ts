import { useCallback, useLayoutEffect, useMemo, useRef } from 'react'
import { useLocation, useNavigate, type NavigateFunction, type NavigateOptions, type To } from 'react-router'
import { toExplorerQueryLocation, toPublicLocation } from './detailLocation.ts'

/** 지도 화면은 상세·지역 주소(`/complexes/1`, `/regions/11140`)도 `/explore?…` 쿼리로 읽는다. */
export function useExplorerLocation() {
  const location = useLocation()
  return useMemo(() => toExplorerQueryLocation(location), [location])
}

/** 지도 화면이 만든 `/explore?…` 이동을 주소창에는 상세·지역 주소로 남긴다. */
export function useExplorerNavigate(): NavigateFunction {
  const navigate = useNavigate()
  const location = useLocation()
  // 이동 함수는 지도 화면 경로가 바뀔 때만 새로 만든다. 지역 주소 유지 판단에 쓰는 현재 주소는 ref로 읽는다.
  const current = useRef(location)
  useLayoutEffect(() => { current.current = location }, [location])
  const explorerPathname = toExplorerQueryLocation(location).pathname
  return useCallback((to: To | number, options?: NavigateOptions) => {
    if (typeof to === 'number') return navigate(to)
    return navigate(toPublicTo(to, explorerPathname, current.current), options)
  }, [navigate, explorerPathname]) as NavigateFunction
}

function toPublicTo(to: To, explorerPathname: string, current: { pathname: string; search: string }): To {
  if (typeof to === 'string') {
    const url = new URL(to, 'http://explorer.invalid' + explorerPathname)
    const next = toPublicLocation({ pathname: url.pathname, search: url.search }, current)
    return next.pathname + next.search + url.hash
  }
  const next = toPublicLocation({ pathname: to.pathname ?? explorerPathname, search: to.search ?? '' }, current)
  return { ...to, pathname: next.pathname, search: next.search }
}
