interface ImportMetaEnv {
  readonly VITE_NAVER_MAPS_CLIENT_ID?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

interface Window {
  __toadzipNaverMapsReady?: () => void
  navermap_authFailure?: () => void
}
