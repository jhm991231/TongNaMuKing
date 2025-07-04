package com.tongnamuking.tongnamuking_backend.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class SamsungBrowserCookieFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String userAgent = httpRequest.getHeader("User-Agent");
        boolean isSamsungBrowser = userAgent != null && userAgent.contains("SamsungBrowser");

        if (isSamsungBrowser) {
            // 삼성인터넷용 응답 래퍼 생성
            HttpServletResponseWrapper responseWrapper = new HttpServletResponseWrapper(httpResponse) {
                @Override
                public void addHeader(String name, String value) {
                    if ("Set-Cookie".equals(name) && value.contains("JSESSIONID")) {
                        // 삼성인터넷용: HttpOnly=false, Secure=false, SameSite 제거
                        String modifiedValue = value.replace("HttpOnly;", "")
                                                   .replace("Secure;", "")
                                                   .replace("SameSite=None;", "")
                                                   .replace(";;", ";");
                        super.addHeader(name, modifiedValue);
                    } else {
                        super.addHeader(name, value);
                    }
                }
                
                @Override
                public void setHeader(String name, String value) {
                    if ("Set-Cookie".equals(name) && value.contains("JSESSIONID")) {
                        // 삼성인터넷용: HttpOnly=false, Secure=false, SameSite 제거
                        String modifiedValue = value.replace("HttpOnly;", "")
                                                   .replace("Secure;", "")
                                                   .replace("SameSite=None;", "")
                                                   .replace(";;", ";");
                        super.setHeader(name, modifiedValue);
                    } else {
                        super.setHeader(name, value);
                    }
                }
            };
            
            chain.doFilter(request, responseWrapper);
        } else {
            // 다른 브라우저는 그대로 처리
            chain.doFilter(request, response);
        }
    }

    private static class HttpServletResponseWrapper extends jakarta.servlet.http.HttpServletResponseWrapper {
        public HttpServletResponseWrapper(HttpServletResponse response) {
            super(response);
        }
    }
}