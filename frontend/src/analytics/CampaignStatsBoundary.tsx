import { useLayoutEffect, useRef } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { consumeCampaignEntry, hasCampaignParameters } from './campaignAttribution'
import { captureCampaignStat } from './campaignStats'

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
    if (previous.current === location.pathname) return
    previous.current = location.pathname
    captureCampaignStat('page_view')
  }, [location.pathname, location.search, location.hash, location.state, navigate])
  return null
}
