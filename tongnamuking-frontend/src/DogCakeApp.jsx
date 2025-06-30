import { useState, useEffect } from "react";
import "./DogCakeApp.css";
import dogcakeImage1 from "./assets/1.jpeg";
import dogcakeImage2 from "./assets/20.jpeg";
import dogRorong from "./assets/dogrorong.png";
import gunCake from "./assets/guncake.png";

function DogCakeApp() {
  const [stats, setStats] = useState([]);
  const [loading, setLoading] = useState(false);
  const [timeRange, setTimeRange] = useState(0);
  const [chatCollectionStatus, setChatCollectionStatus] = useState(null);
  const [isCollecting, setIsCollecting] = useState(false);

  // 독케익 채널 정보 (고정)
  const DOGCAKE_CHANNEL = {
    channelId: "9c0c6780aa8f2a7d70c4bf2bb3c292c9", // 독케익 채널 ID
    channelName: "독케익",
    displayName: "독케익",
  };

  useEffect(() => {
    checkCollectionStatus();
    checkNodejsCollectionStatus();
    // 독케익은 항상 수집되어야 하므로 자동으로 시작 시도
    autoStartCollection();
  }, []);

  const autoStartCollection = async () => {
    try {
      const response = await fetch(
        "http://localhost:8080/api/nodejs-chat-collection/status"
      );
      const data = await response.json();
      const isDogCakeCollecting = data.activeChannels.includes(DOGCAKE_CHANNEL.channelId);
      
      // 독케익이 수집 중이 아니면 자동으로 시작 (알림 없이)
      if (!isDogCakeCollecting) {
        console.log("독케익 자동 수집 시작 시도...");
        await startChatCollection(false);
      }
    } catch (error) {
      console.error("자동 수집 시작 실패:", error);
    }
  };

  // 실시간 순위 업데이트를 위한 useEffect (항상 실행)
  useEffect(() => {
    let interval = null;

    if (isCollecting) {
      // 즉시 업데이트
      fetchChatStats();
      // 3초마다 순위 자동 업데이트
      interval = setInterval(() => {
        fetchChatStats();
      }, 3000);
    }

    return () => {
      if (interval) clearInterval(interval);
    };
  }, [isCollecting, timeRange]);

  const startChatCollection = async (showAlert = true) => {
    try {
      const response = await fetch(
        `http://localhost:8080/api/nodejs-chat-collection/start/${DOGCAKE_CHANNEL.channelId}`,
        {
          method: "POST",
        }
      );
      const data = await response.json();

      if (data.success) {
        setIsCollecting(true);
        if (showAlert) {
          alert("독케익 채팅 수집을 시작했습니다!");
        }
        // 수집 시작 후 바로 순위 로드
        setTimeout(() => fetchChatStats(), 1000);
      } else {
        if (showAlert) {
          alert("채팅 수집 시작에 실패했습니다: " + data.message);
        }
      }
    } catch (error) {
      console.error("Error starting chat collection:", error);
      if (showAlert) {
        alert("채팅 수집 시작 중 오류가 발생했습니다");
      }
    }
  };

  const stopChatCollection = async () => {
    try {
      const response = await fetch(
        `http://localhost:8080/api/nodejs-chat-collection/stop/${DOGCAKE_CHANNEL.channelId}`,
        {
          method: "POST",
        }
      );
      const data = await response.json();

      if (data.success) {
        setIsCollecting(false);
        alert("독케익 채팅 수집을 중지했습니다!");
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
      // 기존 Java 수집기 상태는 참고용으로만 사용
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
      // 독케익 채널이 수집 중인지 확인
      const isDogCakeCollecting = data.activeChannels.includes(
        DOGCAKE_CHANNEL.channelId
      );
      setIsCollecting(isDogCakeCollecting);
    } catch (error) {
      console.error("Node.js 수집 상태 확인 실패:", error);
    }
  };

  const fetchChatStats = async () => {
    setLoading(true);
    try {
      const url =
        timeRange > 0
          ? `http://localhost:8080/api/chat-stats/channel/${DOGCAKE_CHANNEL.channelName}?hours=${timeRange}`
          : `http://localhost:8080/api/chat-stats/channel/${DOGCAKE_CHANNEL.channelName}`;

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
      {/* 왼쪽 독케익 캐릭터 */}
      <img src={dogRorong} alt="독케익 캐릭터" className="dogcake-character-left" />
      
      {/* 오른쪽 독케익 캐릭터 */}
      <img src={gunCake} alt="독케익 건케익" className="dogcake-character-right" />
      

      <div className="dogcake-app">
        <h1 style={{ gap: "18px" }}>
          <img
            src={dogcakeImage1}
            alt="독케익 통나무"
            className="dogcake-logo"
          />
          독케익 채팅 통나무 순위
          <img
            src={dogcakeImage2}
            alt="독케익 통나무"
            className="dogcake-logo"
          />
        </h1>

        <div className="dogcake-controls">
          <select
            value={timeRange}
            onChange={(e) => setTimeRange(Number(e.target.value))}
            className="dogcake-time-select"
          >
            <option value={0}>전체 기간</option>
            <option value={1}>최근 1시간</option>
            <option value={24}>최근 24시간</option>
            <option value={168}>최근 7일</option>
          </select>

          <div className="dogcake-status-display">
            {loading ? (
              <span className="dogcake-loading">🔄 로딩 중...</span>
            ) : isCollecting ? (
              <span className="dogcake-collecting">🔴 실시간 업데이트 중</span>
            ) : (
              <div className="dogcake-error">
                <span className="dogcake-error-text">⚠️ 서버 연결 실패</span>
                <button
                  onClick={startChatCollection}
                  className="dogcake-retry-button"
                >
                  다시 시도
                </button>
              </div>
            )}
          </div>
        </div>

        {stats.length > 0 && (
          <div className="dogcake-stats-container">
            <div className="dogcake-stats-list">
              {stats.slice(0, 10).map((stat, index) => (
                <div
                  key={stat.userId}
                  className={`dogcake-stat-item rank-${index + 1}`}
                >
                  <div className={`dogcake-rank ${index === 0 ? "crown" : ""}`}>
                    #{stat.rank}
                  </div>
                  <div className="dogcake-user-info">
                    <div className="dogcake-display-name">
                      {stat.displayName}
                    </div>
                  </div>
                  <div className="dogcake-message-count">
                    {stat.messageCount}개
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </>
  );
}

export default DogCakeApp;
