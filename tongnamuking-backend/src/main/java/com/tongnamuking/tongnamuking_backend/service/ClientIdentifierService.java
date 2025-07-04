package com.tongnamuking.tongnamuking_backend.service;

import org.springframework.stereotype.Service;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 클라이언트 식별자 관리 서비스
 * 삼성 인터넷의 세션 불안정 문제를 해결하기 위해 
 * 세션 ID 대신 안정적인 클라이언트 ID를 사용
 */
@Service
public class ClientIdentifierService {

    /**
     * 클라이언트 식별자를 추출합니다.
     * 우선순위: X-Client-ID 헤더 > 세션 ID
     * 
     * @param request HTTP 요청
     * @return 클라이언트 식별자
     */
    public String resolveClientId(HttpServletRequest request) {
        // 1. X-Client-ID 헤더 우선 확인 (프론트엔드에서 LocalStorage 기반 ID)
        String clientId = request.getHeader("X-Client-ID");
        if (clientId != null && !clientId.trim().isEmpty()) {
            return clientId.trim();
        }
        
        // 2. 세션 ID를 fallback으로 사용
        String sessionId = request.getSession().getId();
        return "session_" + sessionId;
    }

}