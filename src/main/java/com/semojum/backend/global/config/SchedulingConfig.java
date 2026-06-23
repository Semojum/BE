package com.semojum.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 프로젝트에 @EnableScheduling이 없어 @Scheduled가 동작하지 않으므로 별도 설정 클래스로 활성화한다.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
