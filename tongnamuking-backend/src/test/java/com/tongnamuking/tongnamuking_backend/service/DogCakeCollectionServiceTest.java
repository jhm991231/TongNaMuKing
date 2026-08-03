package com.tongnamuking.tongnamuking_backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 독케익 수집기 프로세스를 어떤 명령으로 띄우는지 검증한다.
 *
 * <p>프로세스를 실제로 실행하지는 않는다. 명령을 조립하는 부분만 떼어내 확인하므로
 * node 가 깔려 있지 않아도 돌아간다.
 *
 * <p>이 테스트의 존재 이유: 예전에는 OS 를 감지해 경로를 코드에 직접 박아두었고
 * (윈도우 분기는 특정 PC 의 절대 경로였다) 다른 환경에서는 수집기가 뜨지 않았다.
 * 경로가 설정에서 온다는 사실을 여기서 고정한다.
 *
 * <p>스크립트는 {@code @TempDir} 에 만들어 쓴다. 저장소의 실제 파일을 가리키면
 * 테스트가 "어느 디렉터리에서 실행됐는가"에 묶이는데, 그것이 바로 이 클래스가
 * 다루는 문제이므로 테스트 자신은 그 의존에서 자유로워야 한다.
 */
@DisplayName("DogCakeCollectionService - 수집기 실행 명령 조립")
class DogCakeCollectionServiceTest {

    private static final String CLIENT_ID = "DOGCAKE_SESSION";

    @TempDir
    Path tempDir;

    private DogCakeCollectionService service;
    private Path script;

    @BeforeEach
    void setUp() throws IOException {
        service = new DogCakeCollectionService();

        // 실제 배치와 같은 모양으로 만든다: <어딘가>/chat-collector/index.js
        script = tempDir.resolve("chat-collector").resolve("index.js");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "// 테스트용 빈 스크립트");
    }

    @Test
    @DisplayName("설정된 스크립트 경로로 node 명령을 만든다")
    void 설정된_경로로_명령을_만든다() {
        // given
        ReflectionTestUtils.setField(service, "scriptPath", script.toString());

        // when
        ProcessBuilder builder = service.buildCollectorProcess(CLIENT_ID);

        // then
        assertThat(builder.command())
                .as("node <스크립트> <채널ID> <클라이언트ID> 네 토막이어야 한다")
                .hasSize(4);
        assertThat(builder.command().get(0)).isEqualTo("node");
        assertThat(builder.command().get(1))
                .as("설정한 경로가 그대로 쓰여야 한다 (코드에 박힌 경로가 아니라)")
                .isEqualTo(script.toFile().getAbsolutePath());
        assertThat(builder.command().get(3)).isEqualTo(CLIENT_ID);
    }

    @Test
    @DisplayName("실행 위치를 스크립트 폴더로 고정한다")
    void 실행_위치를_스크립트_폴더로_고정한다() {
        // given
        ReflectionTestUtils.setField(service, "scriptPath", script.toString());

        // when
        ProcessBuilder builder = service.buildCollectorProcess(CLIENT_ID);

        // then ─ directory() 를 지정하지 않으면 자식 프로세스가 JVM 의 작업 디렉터리를
        // 물려받아 IntelliJ 와 Docker 에서 서로 달라진다.
        assertThat(builder.directory())
                .as("실행 위치가 JVM 작업 디렉터리에 휘둘리면 안 된다")
                .isEqualTo(script.getParent().toFile());
    }

    @Test
    @DisplayName("스크립트가 없으면 어디를 찾았는지 알려주며 실패한다")
    void 스크립트가_없으면_찾은_경로를_알려준다() {
        // given ─ 설정은 상대 경로인데 그 자리에 파일이 없는 상황.
        // 실행 위치가 바뀌면 실제로 이렇게 된다.
        String missing = "없는폴더/index.js";
        ReflectionTestUtils.setField(service, "scriptPath", missing);
        File attempted = new File(missing).getAbsoluteFile();

        // when / then ─ "module not found" 같은 남의 메시지 대신, 설정값과 실제로 찾아본
        // 절대 경로와 현재 작업 디렉터리를 함께 알려야 원인을 즉시 알 수 있다.
        assertThatThrownBy(() -> service.buildCollectorProcess(CLIENT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(missing)
                .hasMessageContaining(attempted.getAbsolutePath())
                .hasMessageContaining(System.getProperty("user.dir"));
    }
}
