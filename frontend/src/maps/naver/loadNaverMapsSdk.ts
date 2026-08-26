const NAVER_MAPS_CALLBACK_NAME = '__toadzipNaverMapsReady'
const NAVER_MAPS_LOAD_TIMEOUT_MS = 15_000
const NAVER_MAPS_SCRIPT_ATTRIBUTE = 'data-toadzip-naver-maps-sdk'
const NAVER_MAPS_SCRIPT_URL = 'https://oapi.map.naver.com/openapi/v3/maps.js'
const retiredReadyCallback = () => undefined

export type NaverMapsSdkErrorCode =
  | 'network'
  | 'authentication'
  | 'invalid-sdk'

export class NaverMapsSdkError extends Error {
  readonly code: NaverMapsSdkErrorCode

  constructor(code: NaverMapsSdkErrorCode, message: string) {
    super(message)
    this.name = 'NaverMapsSdkError'
    this.code = code
  }
}

let sdkPromise: Promise<typeof naver.maps> | null = null
let authenticationError: NaverMapsSdkError | null = null
let previousAuthenticationCallback: (() => void) | undefined
let pendingAuthenticationFailure:
  | ((error: NaverMapsSdkError) => void)
  | null = null
const authenticationFailureListeners = new Set<
  (error: NaverMapsSdkError) => void
>()

function handleAuthenticationFailure() {
  const error = new NaverMapsSdkError(
    'authentication',
    'NAVER Maps SDK 인증에 실패했습니다.',
  )
  authenticationError = error
  authenticationFailureListeners.forEach((listener) => listener(error))
  pendingAuthenticationFailure?.(error)
  previousAuthenticationCallback?.()
}

function ensureAuthenticationCallback() {
  const currentCallback = window.navermap_authFailure

  if (currentCallback === handleAuthenticationFailure) {
    return
  }

  if (currentCallback) {
    previousAuthenticationCallback = currentCallback
  }

  window.navermap_authFailure = handleAuthenticationFailure
}

export function subscribeToNaverMapsAuthenticationFailure(
  listener: (error: NaverMapsSdkError) => void,
): () => void {
  authenticationFailureListeners.add(listener)

  if (authenticationError) {
    listener(authenticationError)
  }

  return () => authenticationFailureListeners.delete(listener)
}

function getReadyNaverMaps(): typeof naver.maps | null {
  const naverCandidate: unknown = Reflect.get(window, 'naver')

  if (typeof naverCandidate !== 'object' || naverCandidate === null) {
    return null
  }

  const mapsCandidate: unknown = Reflect.get(naverCandidate, 'maps')

  if (typeof mapsCandidate !== 'object' || mapsCandidate === null) {
    return null
  }

  const mapConstructor: unknown = Reflect.get(mapsCandidate, 'Map')
  const latLngConstructor: unknown = Reflect.get(mapsCandidate, 'LatLng')

  if (
    typeof mapConstructor !== 'function' ||
    typeof latLngConstructor !== 'function'
  ) {
    return null
  }

  return mapsCandidate as typeof naver.maps
}

function createScript(clientId: string): HTMLScriptElement {
  const url = new URL(NAVER_MAPS_SCRIPT_URL)
  url.searchParams.set('ncpKeyId', clientId)
  url.searchParams.set('submodules', 'gl')
  url.searchParams.set('callback', NAVER_MAPS_CALLBACK_NAME)

  const script = document.createElement('script')
  script.src = url.toString()
  script.async = true
  script.setAttribute(NAVER_MAPS_SCRIPT_ATTRIBUTE, '')
  return script
}

function findExistingScript(): HTMLScriptElement | null {
  return document.querySelector<HTMLScriptElement>(
    `script[${NAVER_MAPS_SCRIPT_ATTRIBUTE}]`,
  )
}

function loadScript(clientId: string): Promise<typeof naver.maps> {
  const existingScript = findExistingScript()
  const script = existingScript ?? createScript(clientId)
  const shouldAppendScript = existingScript === null

  return new Promise((resolve, reject) => {
    let settled = false
    let timeoutId: number | null = null
    const currentReadyCallback = window.__toadzipNaverMapsReady
    const previousReadyCallback =
      currentReadyCallback === retiredReadyCallback
        ? undefined
        : currentReadyCallback

    const restoreReadyCallback = () => {
      if (window.__toadzipNaverMapsReady === handleReady) {
        if (previousReadyCallback) {
          window.__toadzipNaverMapsReady = previousReadyCallback
        } else {
          window.__toadzipNaverMapsReady = retiredReadyCallback
        }
      }
    }

    const clearLoadTimeout = () => {
      if (timeoutId !== null) {
        window.clearTimeout(timeoutId)
        timeoutId = null
      }
    }

    const removeScriptListeners = () => {
      script.removeEventListener('error', handleNetworkFailure)
    }

    const handlePendingAuthenticationFailure = (error: NaverMapsSdkError) => {
      fail(error, false)
    }

    const clearPendingAuthenticationFailure = () => {
      if (pendingAuthenticationFailure === handlePendingAuthenticationFailure) {
        pendingAuthenticationFailure = null
      }
    }

    const fail = (error: NaverMapsSdkError, retryable: boolean) => {
      if (settled) {
        return
      }

      settled = true
      clearLoadTimeout()
      restoreReadyCallback()
      clearPendingAuthenticationFailure()
      removeScriptListeners()
      sdkPromise = null

      if (retryable) {
        script.remove()
      }

      reject(error)
    }

    function handleReady() {
      if (settled) {
        return
      }

      const maps = getReadyNaverMaps()

      if (!maps) {
        fail(
          new NaverMapsSdkError(
            'invalid-sdk',
            'NAVER Maps SDK 전역 객체를 확인할 수 없습니다.',
          ),
          true,
        )
        return
      }

      settled = true
      clearLoadTimeout()
      restoreReadyCallback()
      clearPendingAuthenticationFailure()
      removeScriptListeners()
      queueMicrotask(() => {
        if (!authenticationError) {
          ensureAuthenticationCallback()
        }
      })
      resolve(maps)
    }

    const handleNetworkFailure = () => {
      fail(
        new NaverMapsSdkError(
          'network',
          'NAVER Maps SDK를 내려받지 못했습니다.',
        ),
        true,
      )
    }

    window.__toadzipNaverMapsReady = handleReady
    pendingAuthenticationFailure = handlePendingAuthenticationFailure
    ensureAuthenticationCallback()
    script.addEventListener('error', handleNetworkFailure, { once: true })
    timeoutId = window.setTimeout(() => {
      fail(
        new NaverMapsSdkError(
          'network',
          'NAVER Maps SDK 준비 시간이 초과되었습니다.',
        ),
        true,
      )
    }, NAVER_MAPS_LOAD_TIMEOUT_MS)

    if (shouldAppendScript) {
      document.head.append(script)
    }
  })
}

export function loadNaverMapsSdk(
  clientId: string,
): Promise<typeof naver.maps> {
  if (authenticationError) {
    return Promise.reject(authenticationError)
  }

  const readyMaps = getReadyNaverMaps()

  if (readyMaps) {
    ensureAuthenticationCallback()
    return Promise.resolve(readyMaps)
  }

  if (sdkPromise) {
    return sdkPromise
  }

  sdkPromise = loadScript(clientId.trim())
  return sdkPromise
}
