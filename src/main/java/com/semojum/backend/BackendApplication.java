package com.semojum.backend;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class BackendApplication {

	/**
	 * 서비스가 국내 전용이므로 애플리케이션 시간대를 한국 표준시로 고정한다.
	 *
	 * <p>컨테이너 환경변수(TZ)에만 의존하면 배포 환경이 바뀔 때 조용히 UTC로 돌아가고,
	 * 그러면 {@code LocalDateTime.now()}가 9시간 이른 값을 써서 "오늘/어제" 판정과
	 * 카드 날짜가 어긋난다(실제로 발생했던 문제). 코드에서도 못 박아 둔다.
	 */
	@PostConstruct
	void setDefaultTimeZone() {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
	}

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
