package com.myxcomp.ice.xtree.config;

import com.myxcomp.ice.xtree.ItemTreeApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ItemTreeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class ScheduleConfigTest {

    @Autowired
    private TaskScheduler taskScheduler;

    @Test
    void taskSchedulerPoolSizeIsOne() {
        assertThat(taskScheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
        ThreadPoolTaskScheduler tpts = (ThreadPoolTaskScheduler) taskScheduler;
        assertThat(tpts.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
    }
}
