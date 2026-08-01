package com.tongnamuking.tongnamuking_backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// 컨텍스트 로딩만으로 node 프로세스가 뜨지 않도록 수집기 데몬을 끈다.
@SpringBootTest(properties = "collector.daemon.enabled=false")
class TongnamukingBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
