import { useLayoutEffect, useRef } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { consumeCampaignEntry, hasCampaignParameters } from './campaignAttribution'
import { captureCampaignStat } from './campaignStats'
import { explorerPagePath } from '../public-housing/navigation/detailLocation'

export function CampaignStatsBoundary() {
  const location = useLocation()
  const navigate = useNavigate()
  const previous = useRef<string | undefined>(undefined)
  useLayoutEffect(() => {
    if (hasCampaignParameters(location.search) && location.pathname === window.location.pathname) {
      if (consumeCampaignEntry()) captureCampaignStat('landing')
      void navigate(window.location.pathname + window.location.search + window.location.hash,
        { replace: true, state: location.state })
    }
    // 상세를 여닫는 것은 지도 화면 안의 이동이라 페이지 조회로 세지 않는다.
    const page = explorerPagePath(location.pathname)
    if (previous.current === page) return
    previous.current = page
    captureCampaignStat('page_view')
  }, [location.pathname, location.search, location.hash, location.state, navigate])
  return null
}
