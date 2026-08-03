package com.tongnamuking.tongnamuking_backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 독케익 수집기 프로세스를 어떤 명령으로 띄우는지 검증한다.
 *
 * <p>프로세스를 실제로 실행하지는 않는다. 명령을 조립하는 부분만 떼어내 확인하므로
 * node 가 깔려 있지 않아도, 스크립트 파일이 없어도 돌아간다.
 *
 * <p>이 테스트의 존재 이유: 예전에는 OS 를 감지해 경로를 코드에 직접 박아두었고
 * (윈도우 분기는 특정 PC 의 절대 경로였다) 다른 환경에서는 수집기가 뜨지 않았다.
 * 경로가 설정에서 온다는 사실을 여기서 고정한다.
 */
@DisplayName("DogCakeCollectionService - 수집기 실행 명령 조립")
class DogCakeCollectionServiceTest {

    private static final String CLIENT_ID = "DOGCAKE_SESSION";

    @Test
    @DisplayName("설정된 스크립트 경로로 node 명령을 만든다")
    void 설정된_경로로_명령을_만든다() {
        // given ─ 로컬 개발에서 쓰는 상대 경로를 주입한다
        DogCakeCollectionService service = new DogCakeCollectionService();
        ReflectionTestUtils.setField(service, "scriptPath", "../chat-collector/index.js");

        // when
        ProcessBuilder builder = service.buildCollectorProcess(CLIENT_ID);

        // then ─ 상대 경로는 절대 경로로 풀려서 전달된다.
        // node 가 어느 디렉터리에서 실행되든 같은 파일을 가리키게 하기 위함이다.
        File expected = new File("../chat-collector/index.js").getAbsoluteFile();

        assertThat(builder.command())
                .as("node <스크립트> <채널ID> <클라이언트ID> 네 토막이어야 한다")
                .hasSize(4);
        assertThat(builder.command().get(0)).isEqualTo("node");
        assertThat(builder.command().get(1))
                .as("설정한 경로가 그대로 쓰여야 한다 (코드에 박힌 경로가 아니라)")
                .isEqualTo(expected.getAbsolutePath());
        assertThat(builder.command().get(3)).isEqualTo(CLIENT_ID);
    }

    @Test
    @DisplayName("실행 위치를 스크립트 폴더로 고정한다")
    void 실행_위치를_스크립트_폴더로_고정한다() {
        // given
        DogCakeCollectionService service = new DogCakeCollectionService();
        ReflectionTestUtils.setField(service, "scriptPath", "../chat-collector/index.js");

        // when
        ProcessBuilder builder = service.buildCollectorProcess(CLIENT_ID);

        // then ─ directory() 를 지정하지 않으면 자식 프로세스가 JVM 의 작업 디렉터리를
        // 물려받아 IntelliJ(backend/) 와 Docker(/app) 에서 서로 달라진다.
        // 스크립트 폴더로 고정해 어디서 띄우든 같게 만든다.
        File expected = new File("../chat-collector/index.js").getAbsoluteFile();
        assertThat(builder.directory())
                .as("실행 위치가 JVM 작업 디렉터리에 휘둘리면 안 된다")
                .isEqualTo(expected.getParentFile());
    }
}
