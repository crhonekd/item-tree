package com.myxcomp.ice.xtree.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Switches on {@code @Scheduled} processing. The actual {@link
 * org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler} bean is auto-configured by
 * Spring Boot from {@code spring.task.scheduling.pool.size} (set to 1 in {@code application.yml}
 * — design §7 "Scheduling concurrency").
 */
@Configuration
@EnableScheduling
public class ScheduleConfig {}
