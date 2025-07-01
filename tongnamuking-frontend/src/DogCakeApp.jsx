import { useState, useEffect } from "react";
import "./DogCakeApp.css";
import dogcakeImage1 from "./assets/1.jpeg";
import dogcakeImage2 from "./assets/20.jpeg";
import dogRorong from "./assets/dogrorong.png";
import gunCake from "./assets/guncake.png";
import kongIcon from "./assets/kong.jpeg";
import dogcakePunch from "./assets/dogcakepunch.gif";

function DogCakeApp() {
  const [stats, setStats] = useState([]);
  const [loading, setLoading] = useState(false);
  const [timeRange, setTimeRange] = useState(0);
  const [chatCollectionStatus, setChatCollectionStatus] = useState(null);
  const [isCollecting, setIsCollecting] = useState(false);

  // 저챗견 비율 관련 상태
  const [showChatDogSettings, setShowChatDogSettings] = useState(false);
  const [justChatDuration, setJustChatDuration] = useState(120); // 저챗 시간 (분)
  const [useManualTime, setUseManualTime] = useState(false); // 수동 시간 설정 여부
  const [gameSegments, setGameSegments] = useState([
    { id: 1, startMinute: 120, endMinute: 180 }, // 기본 게임 구간
  ]); // 수동 게임 구간들
  const [chatDogRatio, setChatDogRatio] = useState(null);
  const [chatDogStats, setChatDogStats] = useState(null);

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
      const isDogCakeCollecting = data.activeChannels.includes(
        DOGCAKE_CHANNEL.channelId
      );

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

  // 게임 구간 추가
  const addGameSegment = () => {
    const lastSegment = gameSegments[gameSegments.length - 1];
    const newSegment = {
      id: Math.max(...gameSegments.map((s) => s.id)) + 1,
      startMinute: lastSegment.endMinute + 30, // 이전 게임 끝나고 30분 후
      endMinute: lastSegment.endMinute + 90, // 1시간 게임
    };
    setGameSegments([...gameSegments, newSegment]);
  };

  // 게임 구간 제거
  const removeGameSegment = (id) => {
    if (gameSegments.length > 1) {
      setGameSegments(gameSegments.filter((segment) => segment.id !== id));
    }
  };

  // 게임 구간 수정
  const updateGameSegment = (id, field, value) => {
    setGameSegments(
      gameSegments.map((segment) =>
        segment.id === id ? { ...segment, [field]: Number(value) } : segment
      )
    );
  };

  // 저챗견 비율 계산
  const calculateChatDogRatio = async () => {
    try {
      const url = useManualTime
        ? `http://localhost:8080/api/chat-stats/chatdog-ratio/${DOGCAKE_CHANNEL.channelName}/manual`
        : `http://localhost:8080/api/chat-stats/chatdog-ratio/${DOGCAKE_CHANNEL.channelName}?justChatDuration=${justChatDuration}&useManualTime=${useManualTime}`;

      const requestOptions = useManualTime
        ? {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
            },
            body: JSON.stringify({ gameSegments }),
          }
        : {};

      const response = await fetch(url, requestOptions);
      const data = await response.json();
      setChatDogRatio(data.ratio);
      setChatDogStats(data);
    } catch (error) {
      console.error("저챗견 비율 계산 실패:", error);
      alert("저챗견 비율 계산 중 오류가 발생했습니다.");
    }
  };

  return (
    <>
      {/* 왼쪽 독케익 캐릭터 */}
      <img
        src={dogRorong}
        alt="독케익 캐릭터"
        className="dogcake-character-left"
      />

      {/* 오른쪽 독케익 캐릭터 */}
      <img
        src={gunCake}
        alt="독케익 건케익"
        className="dogcake-character-right"
      />

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

          <button
            onClick={() => setShowChatDogSettings(!showChatDogSettings)}
            className="dogcake-chatdog-settings-button"
          >
            {showChatDogSettings ? (
              "저챗견 비율 숨기기"
            ) : (
              <>
                <img src={kongIcon} alt="콩" className="kong-icon" />
                오늘의 저챗견
              </>
            )}
          </button>
        </div>

        {/* 저챗견 비율 설정 패널 */}
        {showChatDogSettings && (
          <div className="dogcake-chatdog-settings-panel">
            <h3>
              <img src={dogcakePunch} alt="독케익 펀치" className="dogcake-punch-icon" />
              오늘의 <span className="chatdog-highlight">저챗견</span> 비율
            </h3>
            <p>저챗에서 게임으로 바뀔 때 사라진 개떡이들의 비율을 계산합니다</p>

            <div className="dogcake-mode-toggle">
              <label className="dogcake-toggle-label">
                <input
                  type="checkbox"
                  checked={useManualTime}
                  onChange={(e) => setUseManualTime(e.target.checked)}
                  className="dogcake-toggle-checkbox"
                />
                <span className="dogcake-toggle-text">
                  {useManualTime
                    ? "수동 시간 설정 모드"
                    : "자동 카테고리 감지 모드"}
                </span>
              </label>
            </div>

            {useManualTime && (
              <div className="dogcake-manual-time-settings">
                <h4>🎮 게임 구간 설정</h4>
                <p className="dogcake-manual-description">
                  방송 시작 후 몇 분부터 몇 분까지가 게임 시간인지 설정해주세요
                </p>

                <div className="dogcake-game-segments">
                  {gameSegments.map((segment, index) => (
                    <div key={segment.id} className="dogcake-game-segment">
                      <div className="dogcake-segment-header">
                        <span className="dogcake-segment-title">
                          게임 {index + 1}
                        </span>
                        {gameSegments.length > 1 && (
                          <button
                            onClick={() => removeGameSegment(segment.id)}
                            className="dogcake-remove-segment-btn"
                          >
                            ✕
                          </button>
                        )}
                      </div>

                      <div className="dogcake-segment-inputs">
                        <div className="dogcake-time-input">
                          <label>시작:</label>
                          <input
                            type="number"
                            value={segment.startMinute}
                            onChange={(e) =>
                              updateGameSegment(
                                segment.id,
                                "startMinute",
                                e.target.value
                              )
                            }
                            min="0"
                            max="1000"
                          />
                          <span>분</span>
                        </div>

                        <div className="dogcake-time-input">
                          <label>종료:</label>
                          <input
                            type="number"
                            value={segment.endMinute}
                            onChange={(e) =>
                              updateGameSegment(
                                segment.id,
                                "endMinute",
                                e.target.value
                              )
                            }
                            min={segment.startMinute + 1}
                            max="1000"
                          />
                          <span>분</span>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>

                <button
                  onClick={addGameSegment}
                  className="dogcake-add-segment-btn"
                >
                  ➕ 게임 구간 추가
                </button>
              </div>
            )}

            {!useManualTime && (
              <div className="dogcake-auto-mode-description">
                <p>📺 자동으로 방송 카테고리 변경을 감지하여 분석합니다</p>
                <p>• 저챗에서 게임으로 바뀔 때마다 저챗견 비율 계산</p>
                <p>• 하루에 여러 게임을 하는 경우 모두 분석하여 평균 산출</p>
              </div>
            )}

            <button
              onClick={calculateChatDogRatio}
              disabled={loading}
              className="dogcake-calculate-button"
            >
              저챗견 비율 계산하기
            </button>

            {chatDogRatio !== null && (
              <div className="dogcake-chatdog-result">
                <div className="dogcake-ratio-display">
                  <div className="dogcake-ratio-percentage">
                    저챗견 비율:{" "}
                    <strong>{(chatDogRatio * 100).toFixed(1)}%</strong>
                  </div>
                  {chatDogStats && (
                    <div className="dogcake-ratio-details">
                      <p>저챗 참여자: {chatDogStats.justChatParticipants}명</p>
                      <p>
                        게임 시작 후 사라진 사람:{" "}
                        {chatDogStats.disappearedParticipants}명
                      </p>
                      <p>
                        게임에서도 채팅한 사람: {chatDogStats.gameParticipants}
                        명
                      </p>
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
        )}

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
