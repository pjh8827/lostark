import { useCallback, useEffect, useRef, useState } from 'react'
import type { OcrResponse } from './types'

// Document PiP 타입 선언 (Chrome 116+)
declare global {
  interface Window {
    documentPictureInPicture?: {
      requestWindow(opts?: { width?: number; height?: number }): Promise<Window>
    }
  }
}

interface Roi { xPct: number; yPct: number; wPct: number; hPct: number }

interface ScanResult {
  id:       number
  nickname: string
  found:    boolean
  count:    number
  time:     string
  error?:   string
}

// ── Helpers ──────────────────────────────────────────────────────────────────

function roiToPx(roi: Roi, vw: number, vh: number) {
  return {
    x: Math.round(roi.xPct * vw),
    y: Math.round(roi.yPct * vh),
    w: Math.max(1, Math.round(roi.wPct * vw)),
    h: Math.max(1, Math.round(roi.hPct * vh)),
  }
}

function nowStr() {
  return new Date().toLocaleTimeString('ko-KR', { hour12: false })
}

function resClass(r: ScanResult) {
  if (r.error) return 'scan-result-item result-error'
  if (r.found) return 'scan-result-item result-fail'
  return 'scan-result-item result-pass'
}

function resIcon(r: ScanResult) {
  if (r.error) return '\u26a0'
  if (r.found) return '\u2717'
  return '\u2713'
}

function resLabel(r: ScanResult) {
  if (r.error) return 'ERROR'
  if (r.found) return 'FAIL (' + r.count + '\uac74)'
  return 'PASS'
}

// ── Component ─────────────────────────────────────────────────────────────────

export default function ScannerPanel({ apiUrl, apiKey }: { apiUrl: string; apiKey: string }) {
  const videoRef      = useRef<HTMLVideoElement>(null)
  const canvasFullRef    = useRef<HTMLCanvasElement>(null)
  const canvasRoiRef    = useRef<HTMLCanvasElement>(null)


  const [isSharing,    setIsSharing]    = useState(false)
  const [isMonitoring, setIsMonitoring] = useState(false)
  const [fps,          setFps]          = useState(0)
  const [results,      setResults]      = useState<ScanResult[]>([])
  const [status,       setStatus]       = useState('')
  const [error,        setError]        = useState('')
  const [ocrUrl,       setOcrUrl]       = useState('http://localhost:8000')
  const [ocrOnline,    setOcrOnline]    = useState<boolean | null>(null)
  const [showOcrCfg,   setShowOcrCfg]   = useState(false)
  const [ocrUrlInput,  setOcrUrlInput]  = useState('http://localhost:8000')
  const [autoDetect,   setAutoDetect]   = useState(true)

  interface PendingNick { id: number; original: string; edited: string }
  const [pendingNicks, setPendingNicks] = useState<PendingNick[]>([])

  const [roi, setRoi] = useState<Roi>({ xPct: 0.63, yPct: 0.19, wPct: 0.16, hPct: 0.60 })

  const streamRef      = useRef<MediaStream | null>(null)
  const pendingStream  = useRef<MediaStream | null>(null)
  const ocrRunningRef  = useRef(false)
  const knownNicksRef  = useRef<Set<string>>(new Set())
  const isMonRef       = useRef(false)
  const roiRef         = useRef(roi)
  const autoDetectRef  = useRef(autoDetect)
  const pipWindowRef   = useRef<Window | null>(null)
  const channelRef     = useRef<BroadcastChannel | null>(null)

  useEffect(() => { roiRef.current        = roi          }, [roi])
  useEffect(() => { isMonRef.current      = isMonitoring }, [isMonitoring])
  useEffect(() => { autoDetectRef.current = autoDetect   }, [autoDetect])


  // BroadcastChannel 초기화
  useEffect(() => {
    channelRef.current = new BroadcastChannel('inven-overlay')
    return () => channelRef.current?.close()
  }, [])

  // OCR 서비스 상태 확인
  useEffect(() => {
    checkOcrHealth()
  }, [ocrUrl])

  async function checkOcrHealth() {
    setOcrOnline(null)
    try {
      const res = await fetch(ocrUrl + '/health', { signal: AbortSignal.timeout(3000) })
      setOcrOnline(res.ok)
    } catch {
      setOcrOnline(false)
    }
  }

  // DOM 갱신 후 stream을 video에 연결
  useEffect(() => {
    const s = pendingStream.current
    if (!isSharing || !s || !videoRef.current) return
    const video = videoRef.current
    video.srcObject = s
    video.play().catch(e => {
      setError('비디오 재생 실패: ' + (e?.message ?? e))
      stopShare()
    })
  }, [isSharing])

  // ── 화면 공유 ──────────────────────────────────────────────────────────────

  async function startShare() {
    setError('')
    try {
      const s = await navigator.mediaDevices.getDisplayMedia({
        video: { frameRate: 15 } as MediaTrackConstraints,
        audio: false,
      })
      s.getVideoTracks()[0].onended = stopShare
      pendingStream.current = s
      streamRef.current     = s
      setIsSharing(true)
      setStatus(
        autoDetectRef.current
          ? '화면 공유 중. 파티 찾기 [신청자] 탭을 열면 자동으로 감지합니다.'
          : '화면 공유 중. ROI를 닉네임 영역에 맞추고 "모니터링 시작"을 누르세요.'
      )
    } catch (e: any) {
      setError(e?.name === 'NotAllowedError' ? '\ud654\uba74 \uacf5\uc720\uac00 \uac70\ubd80\ub410\uc2b5\ub2c8\ub2e4.' : '\uc624\ub958: ' + e?.message)
    }
  }

  function stopShare() {
    streamRef.current?.getTracks().forEach(t => t.stop())
    streamRef.current = pendingStream.current = null
    if (videoRef.current) videoRef.current.srcObject = null
    setIsSharing(false)
    setIsMonitoring(false)
    setStatus('')
  }

  // ── Document Picture-in-Picture 오버레이 ───────────────────────────────────
  // Chrome 116+ : 항상 최상위(게임 전체화면 위도 작동)

  const [pipSupported] = useState(() => !!window.documentPictureInPicture)
  const [pipOpen, setPipOpen] = useState(false)

  async function openOverlay() {
    if (!window.documentPictureInPicture) {
      setError('Document PiP\ub97c \uc9c0\uc6d0\ud558\uc9c0 \uc54a\ub294 \ube0c\ub77c\uc6b0\uc800\uc785\ub2c8\ub2e4. Chrome 116+ \ud544\uc694')
      return
    }
    if (pipWindowRef.current && !pipWindowRef.current.closed) return

    const pipWin = await window.documentPictureInPicture.requestWindow({ width: 300, height: 500 })
    pipWindowRef.current = pipWin
    setPipOpen(true)

    // PiP 창에 스타일 주입
    const style = pipWin.document.createElement('style')
    style.textContent = PIP_CSS
    pipWin.document.head.appendChild(style)

    // PiP 창에 HTML 주입
    pipWin.document.body.innerHTML = PIP_HTML

    // PiP 창 닫힘 감지
    pipWin.addEventListener('pagehide', () => {
      pipWindowRef.current = null
      setPipOpen(false)
    })
  }

  function closeOverlay() {
    pipWindowRef.current?.close()
    pipWindowRef.current = null
    setPipOpen(false)
  }

  function sendToOverlay(msg: PipMessage) {
    channelRef.current?.postMessage(msg)
    // PiP DOM 직접 조작 (같은 JS 컨텍스트)
    const pipWin = pipWindowRef.current
    if (!pipWin || pipWin.closed) return
    if (msg.type === 'result')  pipAddResult(pipWin, msg)
    if (msg.type === 'clear')   pipClear(pipWin)
    if (msg.type === 'status')  pipSetStatus(pipWin, msg.text)
  }

  // ── 모니터링 ──────────────────────────────────────────────────────────────

  function startMonitoring() {
    if (!ocrOnline) {
      setError('Python OCR \uc11c\ube44\uc2a4\uac00 \uc2e4\ud589 \uc911\uc774\uc5b4\uc57c \ud569\ub2c8\ub2e4. (ocr-service/main.py)')
      return
    }
    knownNicksRef.current.clear()
    setResults([])
    setIsMonitoring(true)
    setStatus('\ubaa8\ub2c8\ud130\ub9c1 \uc911 \u2014 \uc2e0\uccad\uc790\ub97c \uae30\ub2e4\ub9ac\ub294 \uc911...')
    sendToOverlay({ type: 'status', text: '\ubaa8\ub2c8\ud130\ub9c1 \uc911', active: true })
  }

  function stopMonitoring() {
    setIsMonitoring(false)
    setStatus('\ubaa8\ub2c8\ud130\ub9c1 \uc911\uc9c0')
    sendToOverlay({ type: 'status', text: '\ubaa8\ub2c8\ud130\ub9c1 \uc911\uc9c0', active: false })
  }

  // ── OCR 호출 (Python 서비스) ────────────────────────────────────────────────

  const runOcr = useCallback(async () => {
    if (ocrRunningRef.current || !isMonRef.current) return

    // 자동 감지 모드: 전체 프레임, 수동 모드: ROI 크롭
    const ocrCanvas = autoDetectRef.current ? canvasFullRef.current : canvasRoiRef.current
    if (!ocrCanvas || ocrCanvas.width < 10 || ocrCanvas.height < 10) return

    ocrRunningRef.current = true
    try {
      const image = ocrCanvas.toDataURL('image/png')
      const res = await fetch(ocrUrl + '/ocr', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ image }),
        signal:  AbortSignal.timeout(45000),
      })
      if (!res.ok) throw new Error('OCR HTTP ' + res.status)

      const data = await res.json() as OcrResponse

      // 자동 감지 모드: 신청자 탭 미감지 시 조용히 스킵
      if (autoDetectRef.current && !data.party_finder_detected) {
        setStatus('파티 찾기 [신청자] 탭 대기 중...')
        return
      }

      // groups[i][0] = OCR 원본, 나머지 = 자모보정 후보
      // 하위 호환: groups 없으면 nicknames 각각을 1-element 그룹으로 처리
      const groups: string[][] = data.groups?.length
        ? data.groups
        : (data.nicknames ?? []).map(n => [n])

      // 그룹의 primary(첫 번째 원소)로 중복 체크 — 같은 사람을 두 번 검색하지 않음
      const newGroups = groups.filter(g => g.length > 0 && !knownNicksRef.current.has(g[0]))
      newGroups.forEach(g => knownNicksRef.current.add(g[0]))

      if (newGroups.length > 0) {
        const newPending = newGroups.map(g => ({
          id: Date.now() + Math.random(),
          original: g[0],
          edited: g[0],
        }))
        setPendingNicks(prev => [...prev, ...newPending])
        setStatus('닉네임 감지됨 — 확인 후 검색해주세요')
      }

      if (groups.length === 0 && knownNicksRef.current.size > 0) {
        knownNicksRef.current.clear()
      }
    } catch (e: any) {
      const msg = e?.message ?? String(e)
      if (msg.includes('Failed to fetch') || msg.includes('NetworkError')) {
        setError(
          'OCR \uc11c\ube44\uc2a4\uc5d0 \uc5f0\uacb0\ud560 \uc218 \uc5c6\uc2b5\ub2c8\ub2e4.\n' +
          '\u2460 \ud130\ubbf8\ub110\uc5d0\uc11c: python ocr-service/main.py \uc2e4\ud589\n' +
          '\u2461 \ube0c\ub77c\uc6b0\uc800 \uc8fc\uc18c\uc548\uc5d0 "chrome://flags/#block-insecure-private-network-requests" \uc5f0\uc5f4 \u2192 Disabled \uc124\uc815'
        )
        setOcrOnline(false)
        setIsMonitoring(false)
      } else if (msg.includes('timed out') || msg.includes('TimeoutError')) {
        // 타임아웃은 일시적 — 모니터링 유지, 다음 주기에 재시도
        console.warn('OCR \ud0c0\uc784\uc544\uc6c3 (\ub2e4\uc74c \uc8fc\uae30\uc5d0 \uc7ac\uc2dc\ub3c4)')
      } else {
        console.error('OCR \uc624\ub958:', e)
      }
    } finally {
      ocrRunningRef.current = false
    }
  }, [ocrUrl])

  async function searchOne(nickname: string): Promise<{ found: boolean; count: number; error?: string }> {
    let url = apiUrl + '/search?nickname=' + encodeURIComponent(nickname)
    if (apiKey) url += '&apiKey=' + encodeURIComponent(apiKey)
    const res  = await fetch(url)
    const data = await res.json()
    return {
      found: res.ok && Array.isArray(data) && data.length > 0,
      count: res.ok && Array.isArray(data) ? data.length : 0,
      error: !res.ok ? (data.error ?? '\uc624\ub958') : undefined,
    }
  }

  async function checkNicknameGroup(group: string[]) {
    const nickname = group[0]
    try {
      const r = await searchOne(nickname)
      const result: ScanResult = {
        id:       Date.now() + Math.random(),
        nickname,
        found:    r.found,
        count:    r.count,
        time:     nowStr(),
        error:    r.error,
      }
      setResults(prev => [result, ...prev].slice(0, 50))
      setStatus('\ubaa8\ub2c8\ud130\ub9c1 \uc911 \u2014 \uc2e0\uccad\uc790\ub97c \uae30\ub2e4\ub9ac\ub294 \uc911...')
      sendToOverlay({ type: 'result', ...result })
    } catch (e) {
      console.error('API \uc624\ub958', e)
    }
  }

  // pendingNicks → PiP DOM 동기화
  useEffect(() => {
    const pw = pipWindowRef.current
    if (!pw || pw.closed) return
    pipUpdatePending(
      pw,
      pendingNicks.map(p => ({ id: p.id, text: p.edited })),
      (id, text) => {
        setPendingNicks(prev => prev.filter(p => p.id !== id))
        const nick = text.trim()
        if (nick) {
          knownNicksRef.current.add(nick)
          checkNicknameGroup([nick])
        }
      },
      (id) => {
        setPendingNicks(prev => prev.filter(p => p.id !== id))
      },
    )
  }, [pendingNicks, pipOpen])

  // ── 애니메이션 루프 ────────────────────────────────────────────────────────

  useEffect(() => {
    let rafId    = 0
    let frames   = 0
    let lastFps  = performance.now()
    let lastScan = 0
    const SCAN_MS = 3000  // EasyOCR CPU 처리 시간 고려해 3초 간격

    const loop = () => {
      rafId = requestAnimationFrame(loop)
      const video = videoRef.current
      const full  = canvasFullRef.current
      const roiC  = canvasRoiRef.current
      if (!video || !full) return
      if (!autoDetectRef.current && !roiC) return

      const vw = video.videoWidth
      const vh = video.videoHeight
      if (!vw || !vh) return

      if (full.width !== vw || full.height !== vh) { full.width = vw; full.height = vh }
      const fctx = full.getContext('2d')!
      fctx.drawImage(video, 0, 0)

      if (!autoDetectRef.current) {
        // 수동 ROI 모드: 초록 박스 오버레이 + ROI 캔버스 복사
        const { x, y, w, h } = roiToPx(roiRef.current, vw, vh)
        fctx.save()
        fctx.strokeStyle = 'rgba(0,255,100,0.9)'
        fctx.lineWidth = 3
        fctx.strokeRect(x, y, w, h)
        fctx.restore()

        if (roiC.width !== w || roiC.height !== h) { roiC.width = w; roiC.height = h }
        roiC.getContext('2d')!.drawImage(video, x, y, w, h, 0, 0, w, h)
      }

      frames++
      const now = performance.now()
      if (now - lastFps >= 1000) { setFps(frames); frames = 0; lastFps = now }

      if (isMonRef.current && now - lastScan >= SCAN_MS) {
        lastScan = now
        runOcr()
      }
    }

    if (isSharing) rafId = requestAnimationFrame(loop)
    return () => { if (rafId) cancelAnimationFrame(rafId) }
  }, [isSharing, runOcr])

  // ── UI ────────────────────────────────────────────────────────────────────

  const ocrBadge =
    ocrOnline === null  ? '\u23f3 OCR \ud655\uc778 \uc911' :
    ocrOnline           ? '\u2705 OCR \uc11c\ube44\uc2a4 \uc5f0\uacb0\ub428' :
                          '\u274c OCR \uc11c\ube44\uc2a4 \uc624\ud504\ub77c\uc778'

  return (
    <div className="scanner-panel">
      <video ref={videoRef} style={{ display: 'none' }} playsInline muted />

      {/* OCR 서비스 상태 + 설정 */}
      <div className="ocr-service-bar">
        <span className={'ocr-badge ' + (ocrOnline === true ? 'ok' : ocrOnline === false ? 'err' : 'chk')}>
          {ocrBadge}
        </span>
        <button className="btn-cfg" onClick={() => setShowOcrCfg(v => !v)}>
          {'\u2699\ufe0f'}
        </button>
        <button className="btn-cfg" onClick={checkOcrHealth} title="\uc7ac\ud655\uc778">
          {'\u21ba'}
        </button>
      </div>

      {showOcrCfg && (
        <div className="ocr-cfg-box">
          <span style={{ fontSize: '0.82rem', color: '#888' }}>OCR \uc11c\ube44\uc2a4 URL</span>
          <div className="ocr-cfg-row">
            <input
              value={ocrUrlInput}
              onChange={e => setOcrUrlInput(e.target.value)}
              className="ocr-url-input"
              placeholder="http://localhost:8000"
            />
            <button className="btn-save" onClick={() => { setOcrUrl(ocrUrlInput); setShowOcrCfg(false) }}>
              {'\uc800\uc7a5'}
            </button>
          </div>
          <p style={{ fontSize: '0.78rem', color: '#555', marginTop: 4 }}>
            pip install -r ocr-service/requirements.txt &amp;&amp; python ocr-service/main.py
          </p>
        </div>
      )}

      {/* 컨트롤 바 */}
      <div className="scanner-controls">
        {!isSharing ? (
          <button className="btn-scan-start" onClick={startShare}>
            {'\ud83d\udda5\ufe0f \uc720\uc800 \uc2a4\uce94 \uc2dc\uc791'}
          </button>
        ) : (
          <>
            <button className="btn-stop" onClick={stopShare}>{'\uacf5\uc720 \uc911\uc9c0'}</button>
            {!isMonitoring ? (
              <button className="btn-monitor-start" onClick={startMonitoring} disabled={!ocrOnline}>
                {'\u25b6 \ubaa8\ub2c8\ud130\ub9c1 \uc2dc\uc791'}
              </button>
            ) : (
              <button className="btn-monitor-stop" onClick={stopMonitoring}>{'\u25a0 \uc911\uc9c0'}</button>
            )}
          </>
        )}

        {/* 자동 감지 모드 토글 */}
        <button
          className={'btn-auto-detect ' + (autoDetect ? 'active' : '')}
          onClick={() => setAutoDetect(v => !v)}
          title={autoDetect
            ? '자동 감지 ON — 파티 찾기 신청자 탭 자동 인식 중 (클릭 시 수동 ROI 모드로 전환)'
            : '수동 ROI 모드 — 클릭 시 자동 감지 모드로 전환'
          }
        >
          {autoDetect ? '🎯 자동 감지 ON' : '✋ 수동 ROI'}
        </button>

        {/* Document PiP 오버레이 버튼 */}
        {pipSupported ? (
          <button
            className={'btn-overlay ' + (pipOpen ? 'active' : '')}
            onClick={pipOpen ? closeOverlay : openOverlay}
            title="\uac8c\uc784 \ud654\uba74 \uc704\uc5d0 \ud56d\uc0c1 \ud45c\uc2dc\ub418\ub294 \uc624\ubc84\ub808\uc774 (Chrome 116+)"
          >
            {pipOpen ? '\ud83d\udcfa \uc624\ubc84\ub808\uc774 \uc885\ub8cc' : '\ud83d\udcfa \uc624\ubc84\ub808\uc774 \uc2dc\uc791'}
          </button>
        ) : (
          <span className="pip-unsupported">
            {'\u26a0\ufe0f \uc624\ubc84\ub808\uc774 \ubbf8\uc9c0\uc6d0 (Chrome 116+ \ud544\uc694)'}
          </span>
        )}

        <span className="scanner-status-badge">
          {isSharing ? (isMonitoring ? '\ud83d\udd34 \ubaa8\ub2c8\ud130\ub9c1 \uc911' : '\u23f8 \ub300\uae30') : '\u2b1b \uc911\uc9c0'}
        </span>
        {isSharing && <span className="scanner-fps">{fps} fps</span>}
      </div>

      {error  && <div className="scanner-error">{error}</div>}
      {status && <div className="scanner-status">{status}</div>}

      {!isSharing && (
        <div className="scanner-guide">
          <p>① 유저 스캔 시작 → 로스트아크 창 선택</p>
          {autoDetect ? (
            <>
              <p>② 파티 찾기 창 → <strong>[신청자]</strong> 탭 클릭 → 자동 감지</p>
              <p>③ 오버레이 시작 → 팝업 창을 게임 위에 맞춰 위치</p>
              <p>④ 모니터링 시작 → 신청자 감지 시 PASS/FAIL 자동 표시</p>
            </>
          ) : (
            <>
              <p>② ROI 슬라이더로 신청자 리스트 영역 잡기 (Lv.XX 포함)</p>
              <p>③ 오버레이 시작 → 팝업 창을 게임 위에 맞춰 위치</p>
              <p>④ 모니터링 시작 → 신청자 감지 시 PASS/FAIL 자동 표시</p>
            </>
          )}
        </div>
      )}

      <div className="scanner-main" style={{ display: isSharing ? 'grid' : 'none' }}>
        <div className="scanner-left">
          <div className="canvas-label">
            {autoDetect ? '전체 화면 (신청자 탭 자동 감지)' : '전체 화면 (초록 박스 = OCR 영역)'}
          </div>
          <canvas ref={canvasFullRef} className="canvas-full" />
        </div>

        <div className="scanner-right">
          {!autoDetect && (
            <>
              <div className="canvas-label">신청자 영역 미리보기 (Lv.XX 포함)</div>
              <canvas ref={canvasRoiRef} className="canvas-roi" />
              <div className="roi-sliders">
                <RoiSlider label="↔ 좌우" value={roi.xPct} onChange={v => setRoi(r => ({ ...r, xPct: v }))} />
                <RoiSlider label="↕ 상하" value={roi.yPct} onChange={v => setRoi(r => ({ ...r, yPct: v }))} />
                <RoiSlider label="↔ 너비" value={roi.wPct} min={0.02} onChange={v => setRoi(r => ({ ...r, wPct: v }))} />
                <RoiSlider label="↕ 높이" value={roi.hPct} min={0.05} onChange={v => setRoi(r => ({ ...r, hPct: v }))} />
              </div>
            </>
          )}

          {autoDetect && (
            <div className="auto-detect-hint">
              🎯 파티 찾기 [신청자] 탭이 활성화되면 자동으로 닉네임을 감지합니다.
            </div>
          )}

          <div className="scan-results">
            <div className="scan-results-title">
              {'\uac80\uc0ac \uacb0\uacfc'}
              {results.length > 0 && (
                <button className="btn-clear" onClick={() => { setResults([]); sendToOverlay({ type: 'clear' }) }}>
                  {'\ucd08\uae30\ud654'}
                </button>
              )}
            </div>
            {results.length === 0 ? (
              <div className="scan-results-empty">{'\uc2e0\uccad\uc790\ub97c \uae30\ub2e4\ub9ac\ub294 \uc911...'}</div>
            ) : (
              <ul className="scan-results-list">
                {results.map(r => (
                  <li key={r.id} className={resClass(r)}>
                    <span className="result-icon">{resIcon(r)}</span>
                    <span className="result-nick">{r.nickname}</span>
                    <span className="result-status">{resLabel(r)}</span>
                    <span className="result-time">{r.time}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

function RoiSlider({ label, value, min = 0, onChange }: {
  label: string; value: number; min?: number; onChange: (v: number) => void
}) {
  return (
    <div className="roi-slider-row">
      <span>{label}</span>
      <input type="range" min={min} max={1} step={0.005} value={value}
             onChange={e => onChange(Number(e.target.value))} />
      <span className="roi-slider-val">{Math.round(value * 100)}%</span>
    </div>
  )
}

// ── Document PiP 오버레이 타입 ────────────────────────────────────────────────

type PipMessage =
  | { type: 'result'; nickname: string; found: boolean; count: number; time: string; error?: string }
  | { type: 'clear' }
  | { type: 'status'; text: string }

// ── PiP DOM 조작 함수 ─────────────────────────────────────────────────────────

const MAX_PIP_ITEMS = 8
const PIP_KEEP_MS   = 15000

function pipAddResult(w: Window, r: PipMessage & { type: 'result' }) {
  const list = w.document.getElementById('pip-list')
  if (!list) return
  w.document.getElementById('pip-empty')!.style.display = 'none'

  const cls   = r.error ? 'pip-err' : r.found ? 'pip-fail' : 'pip-pass'
  const icon  = r.error ? '\u26a0' : r.found ? '\u2717' : '\u2713'
  const label = r.error ? 'ERROR' : r.found ? 'FAIL (' + r.count + '\uac74)' : 'PASS'

  const div = w.document.createElement('div')
  div.className = 'pip-item ' + cls
  div.innerHTML =
    '<span class="pi">' + icon + '</span>' +
    '<span class="pn">' + r.nickname + '</span>' +
    '<span class="ps">' + label + '</span>' +
    '<span class="pt">' + r.time + '</span>'

  list.insertBefore(div, list.firstChild)

  const items = list.querySelectorAll('.pip-item')
  if (items.length > MAX_PIP_ITEMS) items[items.length - 1].remove()

  setTimeout(() => {
    div.remove()
    if (!list.querySelector('.pip-item')) {
      const e = w.document.getElementById('pip-empty')
      if (e) e.style.display = ''
    }
  }, PIP_KEEP_MS)
}

function pipClear(w: Window) {
  w.document.querySelectorAll('.pip-item').forEach(el => el.remove())
  const e = w.document.getElementById('pip-empty')
  if (e) e.style.display = ''
}

function pipSetStatus(w: Window, text: string) {
  const el = w.document.getElementById('pip-status')
  if (el) el.textContent = text
}

function pipUpdatePending(
  w: Window,
  items: Array<{ id: number; text: string }>,
  onConfirm: (id: number, text: string) => void,
  onDismiss: (id: number) => void,
) {
  const container = w.document.getElementById('pip-pending')
  if (!container) return
  container.innerHTML = ''
  if (items.length === 0) return

  const title = w.document.createElement('div')
  title.className = 'pip-pend-title'
  title.textContent = '\uac10\uc9c0\ub41c \ub2c9\ub124\uc784 \ud655\uc778'
  container.appendChild(title)

  for (const item of items) {
    const row = w.document.createElement('div')
    row.className = 'pip-pend-item'

    const input = w.document.createElement('input')
    input.value = item.text
    input.addEventListener('keydown', e => {
      if (e.key === 'Enter') onConfirm(item.id, input.value)
    })

    const okBtn = w.document.createElement('button')
    okBtn.className = 'pip-pend-btn pip-pend-ok'
    okBtn.textContent = '\uac80\uc0c9'
    okBtn.addEventListener('click', () => onConfirm(item.id, input.value))

    const noBtn = w.document.createElement('button')
    noBtn.className = 'pip-pend-btn pip-pend-no'
    noBtn.textContent = '\ubb34\uc2dc'
    noBtn.addEventListener('click', () => onDismiss(item.id))

    row.append(input, okBtn, noBtn)
    container.appendChild(row)
  }
}

// ── PiP 창 스타일 & HTML ─────────────────────────────────────────────────────

const PIP_CSS = `
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'Malgun Gothic',sans-serif;background:rgba(8,8,18,.92);
  color:#e0e0e0;width:100%;overflow:hidden}
.pip-header{display:flex;align-items:center;justify-content:space-between;
  padding:5px 10px;background:rgba(0,0,0,.5);border-bottom:1px solid #1a1a2a}
.pip-title{color:#f0c040;font-size:11px;font-weight:bold}
.pip-status{padding:2px 10px;font-size:10px;color:#555;background:rgba(0,0,0,.3);
  border-bottom:1px solid #111}
#pip-pending{padding:0 5px}
.pip-pend-title{font-size:10px;color:#8ab4f8;padding:4px 4px 2px}
.pip-pend-item{display:flex;gap:3px;padding:2px 0;align-items:center}
.pip-pend-item input{flex:1;background:#1a1a2e;color:#eee;border:1px solid #444;
  border-radius:3px;padding:2px 5px;font-size:12px;font-family:inherit}
.pip-pend-btn{border:none;border-radius:3px;padding:2px 8px;font-size:11px;cursor:pointer}
.pip-pend-ok{background:#2d5a27;color:#cde6c8}
.pip-pend-no{background:#5a2727;color:#e6c8c8}
#pip-list{padding:4px 5px}
.pip-empty{padding:12px;text-align:center;color:#333;font-size:11px}
.pip-item{display:flex;align-items:center;gap:7px;padding:5px 8px;margin:2px 0;
  border-radius:5px;font-size:12px;animation:si .2s ease}
@keyframes si{from{opacity:0;transform:translateX(-8px)}to{opacity:1}}
.pip-pass{background:rgba(20,60,35,.85);border-left:3px solid #44dd88}
.pip-fail{background:rgba(70,15,15,.85);border-left:3px solid #ff4455}
.pip-err {background:rgba(60,45,0,.85) ;border-left:3px solid #ffaa33}
.pi{font-size:13px;min-width:14px}
.pn{flex:1;font-weight:bold;font-size:13px}
.pip-pass .pi,.pip-pass .pn{color:#44dd88}
.pip-fail .pi,.pip-fail .pn{color:#ff4455}
.pip-err  .pi,.pip-err  .pn{color:#ffaa33}
.ps{font-size:10px;white-space:nowrap}
.pip-pass .ps{color:#2a9a5a}.pip-fail .ps{color:#cc3344}.pip-err .ps{color:#cc8822}
.pt{font-size:9px;color:#333;white-space:nowrap}
`

const PIP_HTML = `
<div class="pip-header">
  <span class="pip-title">\u2694 \uc0ac\uc0ac\uac8c \uc624\ubc84\ub808\uc774</span>
</div>
<div class="pip-status" id="pip-status">\ubaa8\ub2c8\ud130\ub9c1 \ub300\uae30 \uc911...</div>
<div id="pip-pending"></div>
<div id="pip-list">
  <div class="pip-empty" id="pip-empty">\uc2e0\uccad\uc790\ub97c \uae30\ub2e4\ub9ac\ub294 \uc911...</div>
</div>
`
