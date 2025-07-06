import { ChzzkClient } from "chzzk";
import axios from "axios";

class ChatCollector {
  constructor() {
    this.client = new ChzzkClient();
    this.chat = null;
    this.isCollecting = false;
    this.channelId = null;
    this.channelName = null;
    this.clientId = null;
    this.backendUrl = process.env.BACKEND_URL || "http://localhost:8080";
    this.currentCategory = null;
    this.categoryCheckInterval = null;
  }

  async startCollection(channelId, clientId) {
    if (this.isCollecting) {
      console.log("이미 수집 중입니다.");
      return false;
    }

    try {
      console.log(`채팅 수집 시작: ${channelId}, 클라이언트: ${clientId}`);

      // 채널 정보 조회
      const channel = await this.client.channel(channelId);
      console.log(`채널명: ${channel.channelName}`);

      // 채팅 인스턴스 생성
      this.chat = this.client.chat({
        channelId: channelId,
        pollInterval: 30 * 1000, // 30초마다 폴링
      });

      // 이벤트 리스너 설정
      this.setupEventListeners();

      // 채팅 연결
      await this.chat.connect();

      this.isCollecting = true;
      this.channelId = channelId;
      this.channelName = channel.channelName;
      this.clientId = clientId;

      // 카테고리 모니터링 시작 (독케익 전용)
      if (clientId === "DOGCAKE_SESSION") {
        this.startCategoryMonitoring(channelId);
      }

      console.log("채팅 수집이 시작되었습니다.");

      return true;
    } catch (error) {
      console.error("채팅 수집 시작 실패:", error);
      return false;
    }
  }

  setupEventListeners() {
    // 연결 성공
    this.chat.on("connect", () => {
      console.log(`채팅방 연결 성공`);
    });

    // 일반 채팅 메시지
    this.chat.on("chat", (chat) => {
      this.handleChatMessage(chat);
    });

    // 후원 메시지
    this.chat.on("donation", (donation) => {
      this.handleDonationMessage(donation);
    });

    // 연결 끊김 시 재연결
    this.chat.on("reconnect", () => {
      console.log("채팅 재연결됨");
    });
  }

  async handleChatMessage(chat) {
    try {
      const message = chat.hidden ? "[블라인드 처리됨]" : chat.message;

      console.log(`${chat.profile.nickname}: ${message}`);

      // Java 백엔드로 채팅 데이터 전송
      await this.sendToBackend({
        type: "chat",
        channelId: this.channelId,
        channelName: this.channelName,
        clientId: this.clientId,
        userId: chat.profile.userIdHash,
        username: chat.profile.nickname,
        message: message,
        timestamp: new Date().toISOString(),
        hidden: chat.hidden,
      });
    } catch (error) {
      console.error("채팅 메시지 처리 실패:", error);
    }
  }

  async handleDonationMessage(donation) {
    try {
      console.log(
        `💰 ${donation.profile.nickname}: ${donation.message} (${donation.payAmount}원)`
      );

      // 후원 메시지도 채팅으로 처리
      await this.sendToBackend({
        type: "donation",
        channelId: this.channelId,
        channelName: this.channelName,
        clientId: this.clientId,
        userId: donation.profile.userIdHash,
        username: donation.profile.nickname,
        message: donation.message,
        timestamp: new Date().toISOString(),
        payAmount: donation.payAmount,
      });
    } catch (error) {
      console.error("후원 메시지 처리 실패:", error);
    }
  }

  async sendToBackend(data) {
    try {
      // 독케익 전용 수집기인지 판단 (세션ID로만)
      const isDogCake = data.clientId === "DOGCAKE_SESSION";

      // URL 선택: 독케익 전용이면 독케익 API, 아니면 범용 API
      const endpoint = isDogCake
        ? "/api/dogcake-collection/message"
        : "/api/chat/message/from-collector";

      console.log(`메시지 전송: ${endpoint} (클라이언트: ${data.clientId})`);

      await axios.post(`${this.backendUrl}${endpoint}`, data, {
        headers: {
          "Content-Type": "application/json",
        },
      });
    } catch (error) {
      console.error("백엔드 전송 실패:", error.message);
    }
  }

  startCategoryMonitoring(channelId) {
    // 카테고리 체크 30초마다 실행
    this.categoryCheckInterval = setInterval(async () => {
      try {
        const liveStatus = await this.client.live.status(channelId);

        if (liveStatus && liveStatus.status === "OPEN") {
          const newCategory = {
            categoryType: liveStatus.categoryType,
            liveCategory: liveStatus.liveCategory,
            liveCategoryValue: liveStatus.liveCategoryValue,
            timestamp: new Date().toISOString(),
          };

          // 카테고리 변경 감지
          if (this.hasCategoryChanged(newCategory)) {
            console.log(
              `📺 카테고리 변경: ${
                this.currentCategory?.liveCategoryValue || "알 수 없음"
              } → ${newCategory.liveCategoryValue}`
            );

            // 백엔드로 카테고리 변경 이벤트 전송
            await this.sendCategoryChangeToBackend({
              channelId,
              channelName: this.channelName,
              previousCategory: this.currentCategory,
              newCategory: newCategory,
              changeDetectedAt: new Date().toISOString(),
            });

            this.currentCategory = newCategory;
          }
        }
      } catch (error) {
        console.error("카테고리 모니터링 오류:", error);
      }
    }, 30000); // 30초 간격
  }

  hasCategoryChanged(newCategory) {
    if (!this.currentCategory) {
      this.currentCategory = newCategory;
      return true; // 첫 번째 카테고리 감지
    }

    return (
      this.currentCategory.liveCategory !== newCategory.liveCategory ||
      this.currentCategory.categoryType !== newCategory.categoryType
    );
  }

  async sendCategoryChangeToBackend(data) {
    try {
      await axios.post(`${this.backendUrl}/api/category/change`, data, {
        headers: { "Content-Type": "application/json" },
      });
      console.log("카테고리 변경 이벤트 전송 완료");
    } catch (error) {
      console.error("카테고리 변경 이벤트 전송 실패:", error.message);
    }
  }

  async stopCollection() {
    if (!this.isCollecting) {
      console.log("수집 중이 아닙니다.");
      return false;
    }

    try {
      if (this.chat) {
        await this.chat.disconnect();
      }

      // 카테고리 모니터링 중지
      if (this.categoryCheckInterval) {
        clearInterval(this.categoryCheckInterval);
        this.categoryCheckInterval = null;
      }

      this.isCollecting = false;
      this.channelId = null;
      this.channelName = null;
      this.clientId = null;
      this.chat = null;
      this.currentCategory = null;

      console.log("채팅 수집이 중지되었습니다.");
      return true;
    } catch (error) {
      console.error("채팅 수집 중지 실패:", error);
      return false;
    }
  }

  getStatus() {
    return {
      isCollecting: this.isCollecting,
      channelId: this.channelId,
      clientId: this.clientId,
    };
  }
}

// 메인 실행 부분
const collector = new ChatCollector();

// 프로세스 종료 시 정리
process.on("SIGINT", async () => {
  console.log("\n프로그램을 종료합니다...");
  await collector.stopCollection();
  process.exit(0);
});

// 명령줄 인자로 채널 ID와 세션 ID 받기
const channelId = process.argv[2];
const clientId = process.argv[3];

if (channelId && clientId) {
  console.log("채팅 수집기 시작...");
  await collector.startCollection(channelId, clientId);
} else {
  console.log("사용법: node index.js <channelId> <clientId>");
  console.log("예시: node index.js a7e175625fdea5a7d98428302b7aa57f CLIENT123");
}
