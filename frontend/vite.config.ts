import { readFile } from 'node:fs/promises'
import type { ServerResponse } from 'node:http'
import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { loadEnv, type Plugin } from 'vite'
import { defineConfig } from 'vitest/config'

const LOCAL_PUBLIC_HOUSING_MOCK_FILE = fileURLToPath(
  new URL('../.codex/local-context/public-housing-mock.json', import.meta.url),
)
const LOCAL_PUBLIC_HOUSING_MOCK_ROOT = '/__toadzip-local-public-housing'
const LOCAL_PUBLIC_HOUSING_SNAPSHOT_PATH =
  `${LOCAL_PUBLIC_HOUSING_MOCK_ROOT}/snapshot`

export default defineConfig(({ command, mode }) => {
  const environment = loadEnv(mode, '.', 'VITE_PUBLIC_HOUSING_LOCAL_MOCK')
  const localMockEnabled =
    command === 'serve' &&
    mode !== 'test' &&
    environment.VITE_PUBLIC_HOUSING_LOCAL_MOCK === 'true'

  return {
    plugins: [react(), ...(localMockEnabled ? [localPublicHousingMockPlugin()] : [])],
    test: {
      environment: 'jsdom',
      setupFiles: './src/test/setup.ts',
    },
  }
})

function localPublicHousingMockPlugin(): Plugin {
  return {
    name: 'toadzip-local-public-housing-mock',
    configureServer(server) {
      server.middlewares.use(async (request, response, next) => {
        const pathname = requestPath(request.url)

        if (!isLocalPublicHousingPath(pathname)) {
          next()
          return
        }

        if (pathname !== LOCAL_PUBLIC_HOUSING_SNAPSHOT_PATH) {
          writeJson(response, 404, {
            code: 'LOCAL_MOCK_ROUTE_NOT_FOUND',
            message: '로컬 mock 경로를 찾을 수 없습니다.',
          })
          return
        }

        if (request.method !== 'GET') {
          response.setHeader('Allow', 'GET')
          writeJson(response, 405, {
            code: 'METHOD_NOT_ALLOWED',
            message: 'GET 요청만 지원합니다.',
          })
          return
        }

        await serveLocalPublicHousingSnapshot(response)
      })
    },
  }
}

async function serveLocalPublicHousingSnapshot(response: ServerResponse) {
  try {
    const source = await readFile(LOCAL_PUBLIC_HOUSING_MOCK_FILE, 'utf8')
    writeJson(response, 200, JSON.parse(source))
  } catch (error) {
    if (isMissingFileError(error)) {
      writeJson(response, 404, {
        code: 'LOCAL_MOCK_FILE_NOT_FOUND',
        message: '로컬 mock 데이터가 준비되지 않았습니다.',
      })
      return
    }

    writeJson(response, 500, {
      code: 'LOCAL_MOCK_READ_FAILED',
      message: '로컬 mock 데이터를 읽지 못했습니다.',
    })
  }
}

function requestPath(url: string | undefined) {
  if (!url) {
    return ''
  }

  const queryIndex = url.indexOf('?')
  if (queryIndex === -1) {
    return url
  }

  return url.slice(0, queryIndex)
}

function isLocalPublicHousingPath(pathname: string) {
  return (
    pathname === LOCAL_PUBLIC_HOUSING_MOCK_ROOT ||
    pathname.startsWith(`${LOCAL_PUBLIC_HOUSING_MOCK_ROOT}/`)
  )
}

function isMissingFileError(error: unknown) {
  return (
    typeof error === 'object' &&
    error !== null &&
    'code' in error &&
    error.code === 'ENOENT'
  )
}

function writeJson(response: ServerResponse, statusCode: number, body: unknown) {
  response.statusCode = statusCode
  response.setHeader('Cache-Control', 'no-store')
  response.setHeader('Content-Type', 'application/json; charset=utf-8')
  response.end(JSON.stringify(body))
}
