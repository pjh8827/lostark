import { useState } from 'react'
import type { BoardPost } from './types'
import './App.css'

export default function App() {
  const [nickname, setNickname] = useState('')
  const [posts, setPosts] = useState<BoardPost[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [searched, setSearched] = useState(false)
  const [searchedNickname, setSearchedNickname] = useState('')

  const handleSearch = async () => {
    const trimmed = nickname.trim()
    if (!trimmed) return

    setLoading(true)
    setError('')
    setPosts([])
    setSearched(false)

    try {
      const res = await fetch(`/api/search?nickname=${encodeURIComponent(trimmed)}`)
      const data = await res.json()

      if (!res.ok) {
        setError(data.error ?? '검색 중 오류가 발생했습니다.')
        return
      }

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
      <p className="subtitle">닉네임을 입력하면 인벤 사건사고 게시판에서 언급된 글을 찾아드립니다.</p>

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
          {loading ? '검색 중...' : '검색'}
        </button>
      </div>

      {loading && <p className="status">검색 중입니다. 잠시만 기다려주세요...</p>}

      {error && <p className="error">{error}</p>}

      {searched && !loading && (
        <div className="results">
          <p className="result-count">
            <strong>{searchedNickname}</strong> 검색 결과: {posts.length}건
          </p>
          {posts.length === 0 ? (
            <p className="no-results">검색 결과가 없습니다.</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th className="col-title">제목</th>
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
                      <a href={post.link} target="_blank" rel="noopener noreferrer">
                        {post.title}
                      </a>
                    </td>
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
    </div>
  )
}
