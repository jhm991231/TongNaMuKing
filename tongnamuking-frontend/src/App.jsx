import { useState, useEffect } from "react";
import "./App.css";

function App() {
  const [channelName, setChannelName] = useState("");
  const [stats, setStats] = useState([]);
  const [loading, setLoading] = useState(false);
  const [timeRange, setTimeRange] = useState(0);
  const [searchResults, setSearchResults] = useState([]);
  const [showSearchResults, setShowSearchResults] = useState(false);
  const [chatCollectionStatus, setChatCollectionStatus] = useState(null);
  const [isCollecting, setIsCollecting] = useState(false);
  const [activeCollectors, setActiveCollectors] = useState(new Set());
  const [selectedChannelId, setSelectedChannelId] = useState(null);
  const [maxCollectors, setMaxCollectors] = useState(3);
  const [channelIdToName, setChannelIdToName] = useState(new Map());

  useEffect(() => {
    checkCollectionStatus();
    checkNodejsCollectionStatus();
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
      }, 3000);
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
        `http://localhost:8080/api/channels/search?query=${encodeURIComponent(
          query
        )}`
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
    setChannelName(channel.channelName);
    setSelectedChannelId(channel.channelId);
    setShowSearchResults(false);

    // 채널 ID와 이름 매핑 저장
    setChannelIdToName(
      (prev) => new Map(prev.set(channel.channelId, channel.channelName))
    );

    // 채널 선택 시 기존 순위 초기 로드
    setTimeout(() => fetchChatStats(), 100);
  };

  const startChatCollection = async (channelId) => {
    try {
      const response = await fetch(
        `http://localhost:8080/api/chat-collection/start/${channelId}`,
        {
          method: "POST",
        }
      );
      const data = await response.json();

      if (data.success) {
        setIsCollecting(true);
        setChatCollectionStatus(data.status);
        alert("채팅 수집을 시작했습니다!");
        // 수집 시작 후 바로 순위 로드
        setTimeout(() => fetchChatStats(), 1000);
      } else {
        alert("채팅 수집 시작에 실패했습니다: " + data.message);
      }
    } catch (error) {
      console.error("Error starting chat collection:", error);
      alert("채팅 수집 시작 중 오류가 발생했습니다");
    }
  };

  const stopChatCollection = async () => {
    try {
      const response = await fetch(
        "http://localhost:8080/api/chat-collection/stop",
        {
          method: "POST",
        }
      );
      const data = await response.json();

      if (data.success) {
        setIsCollecting(false);
        setChatCollectionStatus(data.status);
        alert("채팅 수집을 중지했습니다!");
      } else {
        alert("채팅 수집 중지에 실패했습니다: " + data.message);
      }
    } catch (error) {
      console.error("Error stopping chat collection:", error);
      alert("채팅 수집 중지 중 오류가 발생했습니다");
    }
  };

  const checkCollectionStatus = async () => {
    try {
      const response = await fetch(
        "http://localhost:8080/api/chat-collection/status"
      );
      const data = await response.json();
      setIsCollecting(data.isCollecting);
      setChatCollectionStatus(data.status);
    } catch (error) {
      console.error("Error checking collection status:", error);
    }
  };

  const checkNodejsCollectionStatus = async () => {
    try {
      const response = await fetch(
        "http://localhost:8080/api/nodejs-chat-collection/status"
      );
      const data = await response.json();
      setActiveCollectors(new Set(data.activeChannels));
      setMaxCollectors(data.maxCount);

      // 활성 채널들의 이름을 가져와서 매핑 복원
      if (data.activeChannels && data.activeChannels.length > 0) {
        for (const channelId of data.activeChannels) {
          try {
            const channelResponse = await fetch(
              `http://localhost:8080/api/channels/${channelId}/info`
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
      console.error("Node.js 수집 상태 확인 실패:", error);
    }
  };

  const startNodejsChatCollection = async () => {
    if (!selectedChannelId) return;

    try {
      const response = await fetch(
        `http://localhost:8080/api/nodejs-chat-collection/start/${selectedChannelId}`,
        {
          method: "POST",
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
        `http://localhost:8080/api/nodejs-chat-collection/stop/${selectedChannelId}`,
        {
          method: "POST",
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

  const fetchChatStats = async () => {
    if (!channelName.trim()) return;

    setLoading(true);
    try {
      const url =
        timeRange > 0
          ? `http://localhost:8080/api/chat-stats/channel/${channelName}?hours=${timeRange}`
          : `http://localhost:8080/api/chat-stats/channel/${channelName}`;

      const response = await fetch(url);
      const data = await response.json();
      setStats(data);
    } catch (error) {
      console.error("Error fetching chat stats:", error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      {activeCollectors.size > 0 && (
        <div
          className="active-collectors-fixed"
          style={{
            transform:
              stats.length > 0
                ? `translate(-50%, -50%) translateX(450px) translateY(${
                    -20 - Math.min(stats.length, 10) * 40
                  }px)`
                : undefined,
          }}
        >
          <h4>
            🔴 수집중 ({activeCollectors.size}/{maxCollectors})
          </h4>
          <div className="collector-list-fixed">
            {Array.from(activeCollectors).map((channelId) => (
              <div key={channelId} className="collector-item-fixed">
                <span className="channel-name-fixed">
                  {channelIdToName.get(channelId) ||
                    `${channelId.substring(0, 8)}...`}
                </span>
                <button
                  className="stop-small-button-fixed"
                  onClick={() => {
                    fetch(
                      `http://localhost:8080/api/nodejs-chat-collection/stop/${channelId}`,
                      {
                        method: "POST",
                      }
                    )
                      .then((res) => res.json())
                      .then((data) => {
                        if (data.success) {
                          setActiveCollectors(new Set(data.activeChannels));
                        }
                      });
                  }}
                >
                  ✕
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="app">
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

          <select
            value={timeRange}
            onChange={(e) => setTimeRange(Number(e.target.value))}
            className="time-select"
          >
            <option value={0}>전체 기간</option>
            <option value={1}>최근 1시간</option>
            <option value={24}>최근 24시간</option>
            <option value={168}>최근 7일</option>
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

        {stats.length > 0 && (
          <div className="stats-container">
            <div className="stats-header"></div>
            <div className="stats-list">
              {stats.slice(0, 10).map((stat, index) => (
                <div
                  key={stat.userId}
                  className={`stat-item rank-${index + 1}`}
                >
                  <div className={`rank ${index === 0 ? "crown" : ""}`}>
                    #{stat.rank}
                  </div>
                  <div className="user-info">
                    <div className="display-name">{stat.displayName}</div>
                  </div>
                  <div className="message-count">{stat.messageCount}개</div>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </>
  );
}

export default App;
