export function pushInstallationHelp(): 'home-screen' | 'ios-update' | null {
  const appleMobile = /iPhone|iPad|iPod/.test(navigator.userAgent)
    || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
  if (!appleMobile) return null
  const version = /OS (\d+)_(\d+)/.exec(navigator.userAgent) ?? /Version\/(\d+)\.(\d+)/.exec(navigator.userAgent)
  if (version && (Number(version[1]) < 16 || (Number(version[1]) === 16 && Number(version[2]) < 4))) return 'ios-update'
  const standalone = window.matchMedia?.('(display-mode: standalone)').matches
    || ('standalone' in navigator && navigator.standalone === true)
  return standalone ? null : 'home-screen'
}
