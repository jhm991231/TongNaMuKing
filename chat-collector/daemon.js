import { ChzzkClient } from "chzzk";
import axios from "axios";
import http from "node:http";

const PORT = Number(process.env.COLLECTOR_PORT || 3001);
const BACKEND_URL = process.env.BACKEND_URL || "http://localhost:8080";
const MESSAGE_ENDPOINT = "/api/multi-channel-collection/message/from-collector";

const client = new ChzzkClient();

/** channelId -> { chat, channelName } */
const channels = new Map();

/** 구독 진행 중인 채널. 동시 요청이 커넥션을 두 번 만드는 것을 막는다. */
const pending = new Map();

// ===== 백엔드 전송 =====

async function postToBackend(body) {
  try {
    await axios.post(`${BACKEND_URL}${MESSAGE_ENDPOINT}`, body, {
      headers: { "Content-Type": "application/json" },
    });
  } catch (error) {
    console.error("백엔드 전송 실패:", error.message);
  }
}

function handleChat(channelId, channelName, chat) {
  const message = chat.hidden ? "[블라인드 처리됨]" : chat.message;
  postToBackend({
    type: "chat",
    channelId,
    channelName,
    userId: chat.profile.userIdHash,
    username: chat.profile.nickname,
    message,
    timestamp: new Date().toISOString(),
    hidden: chat.hidden,
  });
}

function handleDonation(channelId, channelName, donation) {
  console.log(`[${channelName}] 후원 ${donation.profile.nickname}: ${donation.payAmount}원`);
  postToBackend({
    type: "donation",
    channelId,
    channelName,
    userId: donation.profile.userIdHash,
    username: donation.profile.nickname,
    message: donation.message,
    timestamp: new Date().toISOString(),
    payAmount: donation.payAmount,
  });
}

// ===== 구독 관리 =====

async function doSubscribe(channelId) {
  const channel = await client.channel(channelId);
  if (!channel) {
    throw new Error(`채널을 찾을 수 없습니다: ${channelId}`);
  }
  const channelName = channel.channelName;

  const chat = client.chat({
    channelId,
    pollInterval: 30 * 1000,
  });

  chat.on("connect", () => console.log(`[${channelName}] 채팅방 연결 성공`));
  chat.on("reconnect", () => console.log(`[${channelName}] 채팅 재연결됨`));
  chat.on("chat", (c) => handleChat(channelId, channelName, c));
  chat.on("donation", (d) => handleDonation(channelId, channelName, d));

  await chat.connect();

  channels.set(channelId, { chat, channelName });
  console.log(`구독 시작: ${channelName} (${channelId}) — 현재 ${channels.size}개 채널`);
  return { ok: true, channelName, already: false };
}

/** 멱등: 이미 구독 중이면 성공을 반환한다. 데몬 재시작 후 replay가 안전하려면 필요하다. */
async function subscribe(channelId) {
  const existing = channels.get(channelId);
  if (existing) {
    return { ok: true, channelName: existing.channelName, already: true };
  }
  if (pending.has(channelId)) {
    return pending.get(channelId);
  }
  const promise = doSubscribe(channelId).finally(() => pending.delete(channelId));
  pending.set(channelId, promise);
  return promise;
}

/** 멱등: 구독 중이 아니어도 성공을 반환한다. */
async function unsubscribe(channelId) {
  const entry = channels.get(channelId);
  if (!entry) {
    return { ok: true, already: true };
  }
  channels.delete(channelId);
  try {
    await entry.chat.disconnect();
  } catch (error) {
    console.error(`[${entry.channelName}] 연결 해제 실패:`, error.message);
  }
  console.log(`구독 해제: ${entry.channelName} (${channelId}) — 현재 ${channels.size}개 채널`);
  return { ok: true, already: false };
}

// ===== HTTP 제어 API =====

const server = http.createServer(async (req, res) => {
  const sendJson = (status, body) => {
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(JSON.stringify(body));
  };

  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);

  if (req.method === "GET" && url.pathname === "/health") {
    sendJson(200, { ok: true, channels: channels.size });
    return;
  }

  const match = url.pathname.match(/^\/channels\/([^/]+)$/);
  if (match) {
    const channelId = decodeURIComponent(match[1]);
    try {
      if (req.method === "POST") {
        sendJson(200, await subscribe(channelId));
        return;
      }
      if (req.method === "DELETE") {
        sendJson(200, await unsubscribe(channelId));
        return;
      }
    } catch (error) {
      console.error(`${req.method} ${url.pathname} 실패:`, error.message);
      sendJson(500, { ok: false, error: error.message });
      return;
    }
  }

  sendJson(404, { ok: false, error: "Not Found" });
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`수집기 데몬 준비 완료 — 127.0.0.1:${PORT}, 백엔드 ${BACKEND_URL}`);
});

// ===== 종료 처리 =====

let shuttingDown = false;

async function shutdown(reason) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`데몬을 종료합니다 (${reason})`);
  server.close();
  for (const [, entry] of channels) {
    try {
      await entry.chat.disconnect();
    } catch {
      // 종료 중이므로 무시
    }
  }
  channels.clear();
  process.exit(0);
}

// 부모(Spring)가 죽으면 stdin이 EOF가 된다. 고아 프로세스 방지.
process.stdin.resume();
process.stdin.on("end", () => shutdown("부모 프로세스 종료 감지"));

process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));
