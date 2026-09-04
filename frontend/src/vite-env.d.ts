interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  readonly VITE_NAVER_MAPS_CLIENT_ID?: string
  readonly VITE_PUBLIC_HOUSING_LOCAL_MOCK?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

interface Window {
  __toadzipNaverMapsReady?: () => void
  navermap_authFailure?: () => void
}
