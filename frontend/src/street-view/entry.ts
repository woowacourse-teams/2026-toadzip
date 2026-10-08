import { startStreetViewRuntime } from './runtime'
import './runtime.css'

const container = document.getElementById('street-view')
if (container) {
  const clientId: unknown = import.meta.env.VITE_NAVER_MAPS_CLIENT_ID
  const dispose = startStreetViewRuntime(container, typeof clientId === 'string' ? clientId : '')
  import.meta.hot?.dispose(dispose)
}
