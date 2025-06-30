import { ChzzkClient } from 'chzzk';
import axios from 'axios';

class ChatCollector {
    constructor() {
        this.client = new ChzzkClient();
        this.chat = null;
        this.isCollecting = false;
        this.channelId = null;
        this.channelName = null;
        this.backendUrl = 'http://localhost:8080';
    }

    async startCollection(channelId) {
        if (this.isCollecting) {
            console.log('이미 수집 중입니다.');
            return false;
        }

        try {
            console.log(`채팅 수집 시작: ${channelId}`);
            
            // 채널 정보 조회
            const channel = await this.client.channel(channelId);
            console.log(`채널명: ${channel.channelName}`);

            // 채팅 인스턴스 생성
            this.chat = this.client.chat({
                channelId: channelId,
                pollInterval: 30 * 1000 // 30초마다 폴링
            });

            // 이벤트 리스너 설정
            this.setupEventListeners();

            // 채팅 연결
            await this.chat.connect();

            this.isCollecting = true;
            this.channelId = channelId;
            this.channelName = channel.channelName;
            console.log('채팅 수집이 시작되었습니다.');
            
            return true;
        } catch (error) {
            console.error('채팅 수집 시작 실패:', error);
            return false;
        }
    }

    setupEventListeners() {
        // 연결 성공
        this.chat.on('connect', (chatChannelId) => {
            console.log(`채팅방 연결 성공: ${chatChannelId}`);
        });

        // 일반 채팅 메시지
        this.chat.on('chat', (chat) => {
            this.handleChatMessage(chat);
        });

        // 후원 메시지
        this.chat.on('donation', (donation) => {
            this.handleDonationMessage(donation);
        });

        // 연결 끊김 시 재연결
        this.chat.on('reconnect', () => {
            console.log('채팅 재연결됨');
        });
    }

    async handleChatMessage(chat) {
        try {
            const message = chat.hidden ? "[블라인드 처리됨]" : chat.message;
            
            console.log(`${chat.profile.nickname}: ${message}`);

            // Java 백엔드로 채팅 데이터 전송
            await this.sendToBackend({
                type: 'chat',
                channelId: this.channelId,
                channelName: this.channelName,
                userId: chat.profile.userIdHash,
                username: chat.profile.nickname,
                displayName: chat.profile.nickname,
                message: message,
                timestamp: new Date().toISOString(),
                hidden: chat.hidden
            });
        } catch (error) {
            console.error('채팅 메시지 처리 실패:', error);
        }
    }

    async handleDonationMessage(donation) {
        try {
            console.log(`💰 ${donation.profile.nickname}: ${donation.message} (${donation.payAmount}원)`);

            // 후원 메시지도 채팅으로 처리
            await this.sendToBackend({
                type: 'donation',
                channelId: this.channelId,
                userId: donation.profile.userIdHash,
                username: donation.profile.nickname,
                displayName: donation.profile.nickname,
                message: donation.message,
                timestamp: new Date().toISOString(),
                payAmount: donation.payAmount
            });
        } catch (error) {
            console.error('후원 메시지 처리 실패:', error);
        }
    }

    async sendToBackend(data) {
        try {
            await axios.post(`${this.backendUrl}/api/chat/message/from-collector`, data, {
                headers: {
                    'Content-Type': 'application/json'
                }
            });
        } catch (error) {
            console.error('백엔드 전송 실패:', error.message);
        }
    }

    async stopCollection() {
        if (!this.isCollecting) {
            console.log('수집 중이 아닙니다.');
            return false;
        }

        try {
            if (this.chat) {
                await this.chat.disconnect();
            }
            
            this.isCollecting = false;
            this.channelId = null;
            this.chat = null;
            
            console.log('채팅 수집이 중지되었습니다.');
            return true;
        } catch (error) {
            console.error('채팅 수집 중지 실패:', error);
            return false;
        }
    }

    getStatus() {
        return {
            isCollecting: this.isCollecting,
            channelId: this.channelId
        };
    }
}

// 메인 실행 부분
const collector = new ChatCollector();

// 프로세스 종료 시 정리
process.on('SIGINT', async () => {
    console.log('\n프로그램을 종료합니다...');
    await collector.stopCollection();
    process.exit(0);
});

// 명령줄 인자로 채널 ID 받기
const channelId = process.argv[2];

if (channelId) {
    console.log('채팅 수집기 시작...');
    await collector.startCollection(channelId);
} else {
    console.log('사용법: node index.js <channelId>');
    console.log('예시: node index.js a7e175625fdea5a7d98428302b7aa57f');
}