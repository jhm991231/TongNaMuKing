/* eslint-disable react-hooks/exhaustive-deps */
import { useState, useEffect } from "react";
import "./App.css";

// API 기본 URL 환경변수로 설정
const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

// 안정적인 클라이언트 식별자 생성
function generateStableClientId() {
  let clientId = localStorage.getItem("client_id");
  if (!clientId) {
    const timestamp = Date.now();
    const randomStr = Math.random().toString(36).substring(2, 15);
    clientId = `client_${timestamp}_${randomStr}`;
    try {
      localStorage.setItem("client_id", clientId);
    } catch (e) {
      console.warn("LocalStorage 저장 실패:", e);
    }
  }
  return clientId;
}

// API 호출 시 공통 헤더 생성
function getApiHeaders() {
  const clientId = generateStableClientId();
  return {
    "Content-Type": "application/json",
    "X-Client-ID": clientId,
  };
}

function App() {
  const [channelName, setChannelName] = useState("");
  const [stats, setStats] = useState([]);
  const [loading, setLoading] = useState(false);
  const [timeRange, setTimeRange] = useState(0);
  const [searchResults, setSearchResults] = useState([]);
  const [showSearchResults, setShowSearchResults] = useState(false);
  const [activeCollectors, setActiveCollectors] = useState(new Set());
  const [selectedChannelId, setSelectedChannelId] = useState(null);
  const [maxCollectors, setMaxCollectors] = useState(3);
  const [channelIdToName, setChannelIdToName] = useState(new Map());

  // 핑 관련 상태
  const [pingInterval, setPingInterval] = useState(null);

  useEffect(() => {
    loadActiveCollectors();

    // 핑 시작
    startPing();
  }, []);

  // 바깥 클릭 시 검색 결과 숨기기
  useEffect(() => {
    const handleClickOutside = (event) => {
      const searchContainer = document.querySelector(".search-container");
      if (searchContainer && !searchContainer.contains(event.target)) {
        setShowSearchResults(false);
      }
    };

    document.addEventListener("mousedown", handleClickOutside);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
    };
  }, []);

  // 실시간 순위 업데이트를 위한 useEffect (현재 채널이 수집 중일 때만)
  useEffect(() => {
    let interval = null;

    const isCurrentChannelCollecting =
      selectedChannelId && activeCollectors.has(selectedChannelId);

    if (isCurrentChannelCollecting && channelName.trim()) {
      // 즉시 업데이트
      fetchChatStats();
      // 현재 채널이 수집 중일 때만 3초마다 순위 자동 업데이트
      interval = setInterval(() => {
        fetchChatStats();
      }, 10000);
    }

    return () => {
      if (interval) clearInterval(interval);
    };
  }, [activeCollectors, selectedChannelId, channelName, timeRange]);

  const searchChannels = async (query) => {
    if (!query.trim()) {
      setSearchResults([]);
      setShowSearchResults(false);
      return;
    }

    try {
      const response = await fetch(
        `${API_BASE_URL}/api/channels/search?query=${encodeURIComponent(
          query
        )}`,
        {
          headers: getApiHeaders(),
        }
      );
      const data = await response.json();
      console.log("Search query:", query);
      console.log("API response:", data);
      console.log("Data type:", typeof data);
      console.log("Is array:", Array.isArray(data));
      if (data && data.length > 0) {
        console.log("First channel:", data[0]);
        console.log("Channel keys:", Object.keys(data[0]));
      }
      setSearchResults(data);
      setShowSearchResults(true);
    } catch (error) {
      console.error("Error searching channels:", error);
      setSearchResults([]);
    }
  };

  const handleChannelInputChange = (e) => {
    const value = e.target.value;
    setChannelName(value);
    searchChannels(value);
  };

  const handleInputFocus = () => {
    if (channelName.trim() && searchResults.length > 0) {
      setShowSearchResults(true);
    }
  };

  const selectChannel = async (channel) => {
    // 이전 데이터 즉시 초기화
    setStats([]);
    setLoading(true);

    setChannelName(channel.channelName);
    setSelectedChannelId(channel.channelId);
    setShowSearchResults(false);

    // 채널 ID와 이름 매핑 저장
    setChannelIdToName(
      (prev) => new Map(prev.set(channel.channelId, channel.channelName))
    );

    // 즉시 새로운 데이터 로드 (채널명 직접 전달)
    await fetchChatStats(channel.channelName);
  };

  const loadActiveCollectors = async () => {
    try {
      const response = await fetch(
        `${API_BASE_URL}/api/multi-channel-collection/status`,
        {
          headers: getApiHeaders(),
        }
      );
      const data = await response.json();
      setActiveCollectors(new Set(data.activeChannels));
      setMaxCollectors(data.maxCount);

      // 활성 채널들의 이름을 가져와서 매핑 복원
      if (data.activeChannels && data.activeChannels.length > 0) {
        for (const channelId of data.activeChannels) {
          try {
            const channelResponse = await fetch(
              `${API_BASE_URL}/api/channels/${channelId}/info`,
              {
                headers: getApiHeaders(),
              }
            );
            const channelData = await channelResponse.json();
            if (channelData && channelData.channelName) {
              setChannelIdToName(
                (prev) => new Map(prev.set(channelId, channelData.channelName))
              );
            }
          } catch (error) {
            console.error(`채널 ${channelId} 정보 가져오기 실패:`, error);
          }
        }
      }
    } catch (error) {
      console.error("활성 수집기 로드 실패:", error);
    }
  };

  const startNodejsChatCollection = async () => {
    if (!selectedChannelId) return;

    try {
      const response = await fetch(
        `${API_BASE_URL}/api/multi-channel-collection/start/${selectedChannelId}`,
        {
          method: "POST",
          headers: getApiHeaders(),
        }
      );
      const data = await response.json();

      if (data.success) {
        setActiveCollectors(new Set(data.activeChannels));
        alert("실시간 채팅 수집을 시작했습니다!");
      } else {
        alert("채팅 수집 시작에 실패했습니다: " + data.message);
      }
    } catch (error) {
      console.error("Node.js 채팅 수집 시작 실패:", error);
      alert("채팅 수집 시작 중 오류가 발생했습니다");
    }
  };

  const stopNodejsChatCollection = async () => {
    if (!selectedChannelId) return;

    try {
      const response = await fetch(
        `${API_BASE_URL}/api/multi-channel-collection/stop/${selectedChannelId}`,
        {
          method: "POST",
          headers: getApiHeaders(),
        }
      );
      const data = await response.json();

      if (data.success) {
        setActiveCollectors(new Set(data.activeChannels));
        alert("실시간 채팅 수집을 중지했습니다!");
      } else {
        alert("채팅 수집 중지에 실패했습니다: " + data.message);
      }
    } catch (error) {
      console.error("Node.js 채팅 수집 중지 실패:", error);
      alert("채팅 수집 중지 중 오류가 발생했습니다");
    }
  };

  // 핑 관련 함수들
  const sendPing = async () => {
    try {
      const response = await fetch(`${API_BASE_URL}/api/multi-channel-collection/ping`, {
        method: "GET",
        headers: getApiHeaders(),
      });
      const data = await response.json();
      console.log("Ping sent:", data.sessionId);
    } catch (error) {
      console.error("Ping failed:", error);
    }
  };

  const startPing = () => {
    // 이미 실행중이면 중지
    if (pingInterval) {
      clearInterval(pingInterval);
    }

    // 즉시 한 번 핑 전송
    sendPing();

    // 30초마다 핑 전송
    const interval = setInterval(sendPing, 30000);
    setPingInterval(interval);
    console.log("Ping started - every 30 seconds");
  };

  const stopPing = () => {
    if (pingInterval) {
      clearInterval(pingInterval);
      setPingInterval(null);
      console.log("Ping stopped");
    }
  };

  // 컴포넌트 언마운트시 핑 정리
  useEffect(() => {
    return () => {
      stopPing();
    };
  }, []);

  const fetchChatStats = async (targetChannelName = null) => {
    const nameToUse = targetChannelName || channelName;
    if (!nameToUse.trim()) return;

    // 첫 번째 로딩시에만 로딩 상태 표시
    if (!stats || stats.length === 0) {
      setLoading(true);
    }

    try {
      const url =
        timeRange > 0
          ? `${API_BASE_URL}/api/chat-stats/client/channel/${nameToUse}?hours=${timeRange}`
          : `${API_BASE_URL}/api/chat-stats/client/channel/${nameToUse}`;

      // 10초 타임아웃 설정
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 10000);

      const response = await fetch(url, {
        headers: getApiHeaders(),
        signal: controller.signal,
      });

      clearTimeout(timeoutId);
      const data = await response.json();
      setStats(data);
    } catch (error) {
      if (error.name === "AbortError") {
        console.error("API 요청 타임아웃:", error);
        setStats([]); // 빈 배열로 설정하여 로딩 해제
      } else {
        console.error("Error fetching chat stats:", error);
      }
    } finally {
      // 첫 번째 로딩시에만 로딩 상태 해제
      if (!stats || stats.length === 0) {
        setLoading(false);
      }
    }
  };

  return (
    <div className="app">
      <div className="header">
        <div>
          <h1>🪵 채팅 통나무 순위</h1>

          <div className="controls">
            <div className="search-container">
              <input
                type="text"
                placeholder="채널명을 입력하세요"
                value={channelName}
                onChange={handleChannelInputChange}
                onFocus={handleInputFocus}
                className="channel-input"
              />
              {showSearchResults && searchResults.length > 0 && (
                <div className="search-results">
                  {searchResults.map((channel, index) => (
                    <div
                      key={channel.channelId || `channel-${index}`}
                      className="search-result-item"
                      onClick={() => selectChannel(channel)}
                    >
                      <div className="channel-info">
                        <div className="channel-image-container">
                          {channel.channelImageUrl ? (
                            <img
                              src={channel.channelImageUrl}
                              alt={channel.channelName}
                              className="channel-image"
                            />
                          ) : (
                            <div className="channel-image-placeholder"></div>
                          )}
                        </div>
                        <div className="channel-details">
                          <div className="channel-name">
                            {channel.channelName}
                          </div>
                          {channel.followerCount > 0 && (
                            <div className="follower-count">
                              팔로워 {channel.followerCount.toLocaleString()}명
                            </div>
                          )}
                        </div>
                      </div>
                      <div className="channel-actions">
                        {channel.openLive && (
                          <div className="live-badge">LIVE</div>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            <div className="controls-row">
              <select
                value={timeRange}
                onChange={(e) => setTimeRange(Number(e.target.value))}
                className="time-select"
              >
                <option value={0}>전체 기간</option>
                <option value={5 / 60}>최근 5분</option>
                <option value={10 / 60}>최근 10분</option>
                <option value={0.5}>최근 30분</option>
                <option value={1}>최근 1시간</option>
              </select>

              <button
                onClick={
                  selectedChannelId && activeCollectors.has(selectedChannelId)
                    ? stopNodejsChatCollection
                    : startNodejsChatCollection
                }
                disabled={loading || !channelName.trim() || !selectedChannelId}
                className={
                  selectedChannelId && activeCollectors.has(selectedChannelId)
                    ? "stop-button"
                    : "start-button"
                }
              >
                {loading
                  ? "로딩 중..."
                  : selectedChannelId && activeCollectors.has(selectedChannelId)
                  ? "실시간 업데이트 중지"
                  : activeCollectors.size >= maxCollectors
                  ? `최대 ${maxCollectors}개까지`
                  : "실시간 업데이트 시작"}
              </button>
            </div>
          </div>
        </div>
        {activeCollectors.size > 0 && (
          <div className="active-collectors-fixed">
            <h4>
              🔴 수집중 ({activeCollectors.size}/{maxCollectors})
            </h4>
            <div className="collector-list-fixed">
              {Array.from(activeCollectors).map((channelId) => (
                <div key={channelId} className="collector-item-fixed">
                  <span
                    className="channel-name-fixed clickable-channel"
                    onClick={async () => {
                      const channelName = channelIdToName.get(channelId);
                      if (channelName) {
                        // 이전 데이터 즉시 초기화
                        setStats([]);
                        setLoading(true);

                        // 해당 채널명으로 설정
                        setChannelName(channelName);
                        setSelectedChannelId(channelId);
                        setShowSearchResults(false);

                        // 즉시 새로운 데이터 로드 (채널명 직접 전달)
                        await fetchChatStats(channelName);
                      }
                    }}
                    title={`${
                      channelIdToName.get(channelId) || channelId
                    } 채팅 순위 보기`}
                  >
                    {channelIdToName.get(channelId) ||
                      `${channelId.substring(0, 8)}...`}
                  </span>
                  <button
                    className="stop-small-button-fixed"
                    onClick={async () => {
                      console.log(`🔴 X버튼 클릭: ${channelId}`);
                      try {
                        const response = await fetch(
                          `${API_BASE_URL}/api/multi-channel-collection/stop/${channelId}`,
                          {
                            method: "POST",
                            headers: getApiHeaders(),
                          }
                        );

                        console.log(`📡 응답 상태: ${response.status}`);
                        console.log(
                          `🍪 응답 헤더:`,
                          Object.fromEntries(response.headers.entries())
                        );

                        const data = await response.json();
                        console.log(`📄 응답 데이터:`, data);

                        if (data.success) {
                          setActiveCollectors(new Set(data.activeChannels));
                          console.log(`✅ 수집 중지 성공`);
                        } else {
                          console.error(`❌ 수집 중지 실패: ${data.message}`);
                          alert(`수집 중지 실패: ${data.message}`);
                        }
                      } catch (error) {
                        console.error(`💥 X버튼 에러:`, error);
                        alert(`X버튼 오류: ${error.message}`);
                      }
                    }}
                  >
                    ✕
                  </button>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      {!loading && stats.length > 0 && (
        <div className="stats-container">
          <div className="stats-header"></div>
          <div className="stats-list">
            {stats.slice(0, 10).map((stat, index) => (
              <div key={stat.userId} className={`stat-item rank-${index + 1}`}>
                <div className={`rank ${index === 0 ? "crown" : ""}`}>
                  #{stat.rank}
                </div>
                <div className="user-info">
                  <div className="display-name">{stat.username}</div>
                </div>
                <div className="message-count">{stat.messageCount}개</div>
              </div>
            ))}
          </div>
        </div>
      )}

      {loading && (
        <div className="loading-container">
          <div>로딩 중...</div>
        </div>
      )}
    </div>
  );
}

export default App;
