interface ImportMetaEnv {
  readonly VITE_POSTHOG_KEY?: string
  readonly VITE_POSTHOG_HOST?: string
  readonly VITE_ANALYTICS_ENV?: string
  readonly VITE_POSTHOG_LOCAL_ENABLED?: string
  readonly VITE_GA_MEASUREMENT_ID?: string
  readonly VITE_GA_DEBUG_MODE?: string
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
