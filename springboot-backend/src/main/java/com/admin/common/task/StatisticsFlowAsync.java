package com.admin.common.task;

import com.admin.entity.StatisticsFlow;
import com.admin.entity.User;
import com.admin.service.StatisticsFlowService;
import com.admin.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单用户流量历史采样。
 *
 * 旧实现每小时只采一次用户累计流量：如果管理员在两次采样之间重置流量，
 * 重置前那一段用量会直接丢失；同时只保留 48 小时，无法做日/月级回看。
 *
 * 现在改为每分钟采样，但仍然只维护「每用户每小时一行」：
 * - 正常增长：把与上次累计值的差额累加到当前小时；
 * - 发生重置：检测到累计值变小后，从 0 重新计，避免出现负流量；
 * - 重启恢复：优先用最近一条记录的 total_flow 作为基线，不会因为面板重启把
 *   当前累计值整段重复记入；
 * - 保留 31 天，足够展示最近 24 小时、每天以及本月趋势。
 */
@Slf4j
@Configuration
@EnableScheduling
public class StatisticsFlowAsync {

    private static final long RETENTION_MS = 31L * 24 * 60 * 60 * 1000;
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("HH:00");

    /** 同一用户的采样串行，避免定时任务偶发重叠造成重复累计。 */
    private static final ConcurrentHashMap<Long, Object> USER_LOCKS = new ConcurrentHashMap<>();

    /**
     * 进程内最近一次看到的累计流量。第一次采样会从数据库最近记录恢复基线。
     */
    private final ConcurrentHashMap<Long, Long> lastObservedTotals = new ConcurrentHashMap<>();

    @Resource
    UserService userService;

    @Resource
    StatisticsFlowService statisticsFlowService;

    /** 每分钟第 5 秒采样，避开大量整点任务。 */
    @Scheduled(cron = "5 * * * * ?")
    public void statisticsFlow() {
        List<User> users = userService.list();
        for (User user : users) {
            if (user == null || user.getId() == null) {
                continue;
            }
            try {
                sampleUser(user);
            } catch (Exception e) {
                // 单个用户采样失败不能影响其他用户，也不能拖垮调度线程。
                log.warn("用户 {} 流量采样失败: {}", user.getId(), e.getMessage());
            }
        }
    }

    /** 每小时清理一次历史，保留 31 天。 */
    @Scheduled(cron = "20 7 * * * ?")
    public void cleanupHistory() {
        long cutoffMs = System.currentTimeMillis() - RETENTION_MS;
        statisticsFlowService.remove(
                new LambdaQueryWrapper<StatisticsFlow>()
                        .lt(StatisticsFlow::getCreatedTime, cutoffMs)
        );
    }

    private void sampleUser(User user) {
        final Long userId = user.getId();
        synchronized (USER_LOCKS.computeIfAbsent(userId, ignored -> new Object())) {
            long currentTotal = safe(user.getInFlow()) + safe(user.getOutFlow());
            long previousTotal = lastObservedTotals.computeIfAbsent(userId,
                    ignored -> loadPreviousTotal(userId, currentTotal));

            long increment;
            if (currentTotal >= previousTotal) {
                increment = currentTotal - previousTotal;
            } else {
                // 流量被重置。重置后的当前值属于新周期，不能用负差额抵消历史。
                increment = currentTotal;
            }

            upsertCurrentHour(userId, increment, currentTotal);
            lastObservedTotals.put(userId, currentTotal);
        }
    }

    private long loadPreviousTotal(Long userId, long currentTotal) {
        StatisticsFlow last = statisticsFlowService.getOne(
                new LambdaQueryWrapper<StatisticsFlow>()
                        .eq(StatisticsFlow::getUserId, userId)
                        .orderByDesc(StatisticsFlow::getCreatedTime)
                        .last("LIMIT 1")
        );
        if (last == null || last.getTotalFlow() == null) {
            // 新装/首次启用统计时把当前累计值作为基线，避免把历史总量一次性灌进当前小时。
            return currentTotal;
        }
        return last.getTotalFlow();
    }

    private void upsertCurrentHour(Long userId, long increment, long currentTotal) {
        LocalDateTime hour = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        long hourStart = hour.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long hourEnd = hourStart + 60L * 60 * 1000;

        StatisticsFlow bucket = statisticsFlowService.getOne(
                new LambdaQueryWrapper<StatisticsFlow>()
                        .eq(StatisticsFlow::getUserId, userId)
                        .ge(StatisticsFlow::getCreatedTime, hourStart)
                        .lt(StatisticsFlow::getCreatedTime, hourEnd)
                        .orderByAsc(StatisticsFlow::getId)
                        .last("LIMIT 1")
        );

        if (bucket == null) {
            bucket = new StatisticsFlow();
            bucket.setUserId(userId);
            bucket.setFlow(Math.max(0L, increment));
            bucket.setTotalFlow(currentTotal);
            bucket.setTime(hour.format(HOUR_FORMAT));
            // 固定到整点，前端和后续日/月聚合都可以直接使用。
            bucket.setCreatedTime(hourStart);
            statisticsFlowService.save(bucket);
            return;
        }

        if (increment > 0) {
            bucket.setFlow(safe(bucket.getFlow()) + increment);
        }
        bucket.setTotalFlow(currentTotal);
        bucket.setTime(hour.format(HOUR_FORMAT));
        statisticsFlowService.updateById(bucket);
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }
}
