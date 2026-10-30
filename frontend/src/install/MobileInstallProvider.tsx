import { useEffect, useRef, useState, type ReactNode } from 'react'
import { MobileInstallContext, type InstallPlatform } from './MobileInstallContext'
import { MobileInstallGuide } from './MobileInstallGuide'

interface InstallPrompt extends Event {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}
function standalone() {
  return !!window.matchMedia?.('(display-mode: standalone)').matches
    || ('standalone' in navigator && navigator.standalone === true)
}
function platform(): InstallPlatform {
  return /iPhone|iPad|iPod/.test(navigator.userAgent)
    || navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1 ? 'ios' : 'android'
}

export function MobileInstallProvider({ children }: { readonly children: ReactNode }) {
  const [narrow, setNarrow] = useState(() => !!window.matchMedia?.('(max-width: 767px)').matches)
  const [installed, setInstalled] = useState(standalone)
  const [dismissed, setDismissed] = useState(false)
  const [guideOpen, setGuideOpen] = useState(false)
  const [canPrompt, setCanPrompt] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const promptRef = useRef<InstallPrompt | null>(null)
  const requesting = useRef(false)
  const mounted = useRef(true)
  const device = platform()
  useEffect(() => {
    mounted.current = true
    const width = window.matchMedia?.('(max-width: 767px)')
    const display = window.matchMedia?.('(display-mode: standalone)')
    const updateWidth = () => { setNarrow(!!width?.matches); if (!width?.matches) setGuideOpen(false) }
    const updateDisplay = () => { if (standalone()) { setInstalled(true); setGuideOpen(false) } }
    const onPrompt = (event: Event) => {
      if (!width?.matches || standalone()) return
      if (!('prompt' in event) || typeof event.prompt !== 'function' || !('userChoice' in event)) return
      event.preventDefault()
      promptRef.current = event as InstallPrompt
      setCanPrompt(true)
    }
    const onInstalled = () => {
      setInstalled(true); setGuideOpen(false); setCanPrompt(false); promptRef.current = null
    }
    width?.addEventListener?.('change', updateWidth)
    display?.addEventListener?.('change', updateDisplay)
    window.addEventListener('beforeinstallprompt', onPrompt)
    window.addEventListener('appinstalled', onInstalled)
    return () => {
      mounted.current = false
      width?.removeEventListener?.('change', updateWidth)
      display?.removeEventListener?.('change', updateDisplay)
      window.removeEventListener('beforeinstallprompt', onPrompt)
      window.removeEventListener('appinstalled', onInstalled)
    }
  }, [])
  const dismiss = () => setDismissed(true)
  const open = async () => {
    if (requesting.current) return
    setMessage('')
    const event = promptRef.current
    if (device === 'ios' || !event) { setGuideOpen(true); return }
    setGuideOpen(false)
    requesting.current = true
    setBusy(true)
    promptRef.current = null
    setCanPrompt(false)
    try {
      // This call must remain synchronous with the user's click.
      await event.prompt()
      const choice = await event.userChoice
      if (!mounted.current) return
      if (choice.outcome === 'accepted') {
        setMessage('설치를 요청했어요. 완료되면 홈 화면의 복덕방 아이콘으로 열어 주세요.')
        dismiss()
      } else {
        setMessage('설치를 취소했어요. 원할 때 다시 추가할 수 있어요.')
      }
    } catch {
      if (mounted.current) { setMessage('설치창을 열지 못했어요. 브라우저 메뉴에서 추가해 주세요.'); setGuideOpen(true) }
    } finally {
      requesting.current = false
      if (mounted.current) setBusy(false)
    }
  }
  return <MobileInstallContext value={{ visible: narrow && !installed, dismissed, canPrompt, busy, message, platform: device, open: () => { void open() }, dismiss }}>
    {children}
    {narrow && !installed && guideOpen && <MobileInstallGuide initialPlatform={device} onClose={() => setGuideOpen(false)} message={message} />}
  </MobileInstallContext>
}
