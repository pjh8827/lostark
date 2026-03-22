import { useState } from 'react'
import type { BoardPost } from './types'
import ScannerPanel from './ScannerPanel'
import './App.css'

type Tab = 'search' | 'scanner'

export default function App() {
  const [tab, setTab] = useState<Tab>('search')

  // ─── 검색 탭 상태 ─────────────────────────────────────────────────────────
  const [nickname, setNickname] = useState('')
  const [posts, setPosts] = useState<BoardPost[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [searched, setSearched] = useState(false)
  const [searchedNickname, setSearchedNickname] = useState('')

  // API 키
  const [apiKey, setApiKey] = useState('')
  const [apiKeyInput, setApiKeyInput] = useState('')
  const [showApiKeyArea, setShowApiKeyArea] = useState(false)
  const [apiKeyVisible, setApiKeyVisible] = useState(false)

  const isEnhanced = apiKey.trim().length > 0

  const handleSaveApiKey = () => { setApiKey(apiKeyInput.trim()); setShowApiKeyArea(false) }
  const handleClearApiKey = () => { setApiKey(''); setApiKeyInput('') }

  const handleSearch = async () => {
    const trimmed = nickname.trim()
    if (!trimmed) return
    setLoading(true); setError(''); setPosts([]); setSearched(false)
    try {
      let url = `/api/search?nickname=${encodeURIComponent(trimmed)}`
      if (isEnhanced) url += `&apiKey=${encodeURIComponent(apiKey)}`
      const res = await fetch(url)
      const data = await res.json()
      if (!res.ok) { setError(data.error ?? '검색 중 오류가 발생했습니다.'); return }
      setPosts(data as BoardPost[])
      setSearchedNickname(trimmed)
      setSearched(true)
    } catch {
      setError('서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인해주세요.')
    } finally {
      setLoading(false)
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') handleSearch()
  }

  return (
    <div className="container">
      <h1>로스트아크 인벤 사건사고 검색</h1>
      <p className="subtitle">닉네임 검색 또는 파티 신청 화면을 실시간 모니터링합니다.</p>

      {/* 탭 */}
      <div className="tab-bar">
        <button
          className={`tab-btn ${tab === 'search' ? 'active' : ''}`}
          onClick={() => setTab('search')}
        >
          🔍 사건사고 검색
        </button>
        <button
          className={`tab-btn ${tab === 'scanner' ? 'active' : ''}`}
          onClick={() => setTab('scanner')}
        >
          🖥️ 유저 스캔
        </button>
      </div>

      {/* ── 검색 탭 ── */}
      {tab === 'search' && (
        <>
          {/* API 키 섹션 */}
          <div className="api-key-section">
            <div className="api-key-header">
              <button
                className={`api-key-toggle ${isEnhanced ? 'active' : ''}`}
                onClick={() => setShowApiKeyArea(v => !v)}
              >
                🔑 Open API 키 {isEnhanced ? '설정됨 (강화 검색)' : '설정 (선택)'}
              </button>
              {isEnhanced && (
                <button className="api-key-clear" onClick={handleClearApiKey}>✕ 키 제거</button>
              )}
            </div>
            {showApiKeyArea && (
              <div className="api-key-input-area">
                <p className="api-key-desc">
                  Lost Ark Open API 키를 입력하면 동일 계정의 모든 캐릭터 닉네임으로 검색합니다.
                  <a href="https://developer-lostark.game.onstove.com/getting-started" target="_blank" rel="noopener noreferrer"> API 키 발급 →</a>
                </p>
                <div className="api-key-input-row">
                  <input
                    type={apiKeyVisible ? 'text' : 'password'}
                    value={apiKeyInput}
                    onChange={e => setApiKeyInput(e.target.value)}
                    placeholder="eyJ0eXAiOiJKV1Qi..."
                    className="api-key-input"
                    onKeyDown={e => e.key === 'Enter' && handleSaveApiKey()}
                  />
                  <button className="btn-icon" onClick={() => setApiKeyVisible(v => !v)}>
                    {apiKeyVisible ? '🙈' : '👁️'}
                  </button>
                  <button className="btn-save" onClick={handleSaveApiKey} disabled={!apiKeyInput.trim()}>저장</button>
                </div>
              </div>
            )}
          </div>

          {/* 검색창 */}
          <div className="search-box">
            <input
              type="text"
              value={nickname}
              onChange={e => setNickname(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="닉네임 입력 (2~12글자)"
              maxLength={12}
              disabled={loading}
            />
            <button onClick={handleSearch} disabled={loading || !nickname.trim()}>
              {loading ? '검색 중...' : isEnhanced ? '🔍 강화 검색' : '검색'}
            </button>
          </div>

          {loading && (
            <p className="status">
              {isEnhanced ? '강화 검색 중입니다. 모든 캐릭터를 순서대로 검색하므로 시간이 걸릴 수 있습니다...' : '검색 중입니다. 잠시만 기다려주세요...'}
            </p>
          )}
          {error && <p className="error">{error}</p>}

          {searched && !loading && (
            <div className="results">
              <p className="result-count">
                <strong>{searchedNickname}</strong>
                {isEnhanced && <span className="badge-enhanced"> 강화 검색</span>}
                {' '}검색 결과: <strong>{posts.length}</strong>건
              </p>
              {posts.length === 0 ? (
                <p className="no-results">검색 결과가 없습니다.</p>
              ) : (
                <table>
                  <thead>
                    <tr>
                      <th className="col-title">제목</th>
                      {isEnhanced && <th className="col-nick">닉네임</th>}
                      <th className="col-author">작성자</th>
                      <th className="col-date">날짜</th>
                      <th className="col-views">조회수</th>
                      <th className="col-rec">추천</th>
                    </tr>
                  </thead>
                  <tbody>
                    {posts.map((post, i) => (
                      <tr key={i}>
                        <td className="col-title">
                          <a href={post.link} target="_blank" rel="noopener noreferrer">{post.title}</a>
                        </td>
                        {isEnhanced && (
                          <td className="col-nick"><span className="nick-tag">{post.matchedNickname}</span></td>
                        )}
                        <td className="col-author">{post.author}</td>
                        <td className="col-date">{post.date}</td>
                        <td className="col-views">{post.views}</td>
                        <td className="col-rec">{post.recommends}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          )}
        </>
      )}

      {/* ── 유저 스캔 탭 ── */}
      {tab === 'scanner' && (
        <ScannerPanel apiUrl="/api" apiKey={apiKey} />
      )}
    </div>
  )
}
