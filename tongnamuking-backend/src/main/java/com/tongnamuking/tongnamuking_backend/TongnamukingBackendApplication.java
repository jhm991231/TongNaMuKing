package com.tongnamuking.tongnamuking_backend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.CommandLineRunner;
import com.tongnamuking.tongnamuking_backend.service.DogCakeCollectionService;

@SpringBootApplication
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class TongnamukingBackendApplication implements CommandLineRunner {

	private final DogCakeCollectionService dogCakeCollectionService;

	public static void main(String[] args) {
		SpringApplication.run(TongnamukingBackendApplication.class, args);
	}

	@Override
	public void run(String... args) throws Exception {
		log.info("=== 자동 채팅 수집 시작 ===");

		// 3초 대기 (애플리케이션 초기화 완료 후)
		Thread.sleep(3000);

		boolean success = dogCakeCollectionService.startDogCakeCollection();
		if (success) {
			log.info("독케익 채널 채팅 수집이 자동으로 시작되었습니다.");
		} else {
			log.warn("독케익 채널 채팅 수집 자동 시작에 실패했습니다.");
		}
	}
}
