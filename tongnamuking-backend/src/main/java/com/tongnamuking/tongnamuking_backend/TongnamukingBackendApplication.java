package com.tongnamuking.tongnamuking_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Autowired;
import com.tongnamuking.tongnamuking_backend.service.DogCakeCollectionService;

@SpringBootApplication
@EnableScheduling
public class TongnamukingBackendApplication implements CommandLineRunner {

	@Autowired
	private DogCakeCollectionService dogCakeCollectionService;
	
	// 독케익 채널 ID
	private static final String DOGCAKE_CHANNEL_ID = "b68af124ae2f1743a1dcbf5e2ab41e0b";

	public static void main(String[] args) {
		SpringApplication.run(TongnamukingBackendApplication.class, args);
	}

	@Override
	public void run(String... args) throws Exception {
		System.out.println("=== 자동 채팅 수집 시작 ===");
		
		// 3초 대기 (애플리케이션 초기화 완료 후)
		Thread.sleep(3000);
		
		boolean success = dogCakeCollectionService.startDogCakeCollection();
		if (success) {
			System.out.println("✅ 독케익 채널 채팅 수집이 자동으로 시작되었습니다.");
		} else {
			System.out.println("❌ 독케익 채널 채팅 수집 자동 시작에 실패했습니다.");
		}
	}
}
