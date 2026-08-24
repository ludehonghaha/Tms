package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.entity.StatisticsFlow;
import com.admin.entity.User;
import com.admin.service.StatisticsFlowService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理员的逐用户日流量统计。 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/traffic")
public class TrafficStatisticsController extends BaseController {

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 31;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    @Resource
    private StatisticsFlowService statisticsFlowService;

    @LogAnnotation
    @RequireRole
    @PostMapping("/users")
    public R users(@RequestBody(required = false) Map<String, Object> params) {
        int days = parseDays(params);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate firstDay = today.minusDays(days - 1L);
        long startMs = firstDay.atStartOfDay(zone).toInstant().toEpochMilli();

        List<StatisticsFlow> flowRows = statisticsFlowService.list(
                new LambdaQueryWrapper<StatisticsFlow>()
                        .ge(StatisticsFlow::getCreatedTime, startMs)
                        .orderByAsc(StatisticsFlow::getCreatedTime)
        );

        // userId -> yyyy-MM-dd -> bytes
        Map<Long, Map<LocalDate, Long>> totals = new HashMap<>();
        for (StatisticsFlow row : flowRows) {
            if (row.getUserId() == null || row.getCreatedTime() == null) {
                continue;
            }
            LocalDate day = Instant.ofEpochMilli(row.getCreatedTime()).atZone(zone).toLocalDate();
            if (day.isBefore(firstDay) || day.isAfter(today)) {
                continue;
            }
            totals.computeIfAbsent(row.getUserId(), ignored -> new HashMap<>())
                    .merge(day, safe(row.getFlow()), Long::sum);
        }

        List<String> dateLabels = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            dateLabels.add(firstDay.plusDays(i).format(DATE_FORMAT));
        }

        List<Map<String, Object>> users = new ArrayList<>();
        for (User user : userService.list()) {
            if (user.getId() == null) {
                continue;
            }
            Map<LocalDate, Long> perDay = totals.getOrDefault(user.getId(), Map.of());
            LinkedHashMap<String, Long> daily = new LinkedHashMap<>();
            long periodTotal = 0L;
            for (String label : dateLabels) {
                LocalDate day = LocalDate.parse(label, DATE_FORMAT);
                long bytes = perDay.getOrDefault(day, 0L);
                daily.put(label, bytes);
                periodTotal += bytes;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", user.getId());
            item.put("username", user.getUser());
            item.put("status", user.getStatus());
            item.put("today", perDay.getOrDefault(today, 0L));
            item.put("yesterday", perDay.getOrDefault(today.minusDays(1), 0L));
            item.put("periodTotal", periodTotal);
            item.put("currentTotal", safe(user.getInFlow()) + safe(user.getOutFlow()));
            item.put("flowLimitGb", user.getFlow());
            item.put("daily", daily);
            users.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", dateLabels);
        result.put("range", days);
        result.put("generatedAt", System.currentTimeMillis());
        result.put("users", users);
        return R.ok(result);
    }

    private int parseDays(Map<String, Object> params) {
        if (params == null || params.get("days") == null) {
            return DEFAULT_DAYS;
        }
        try {
            int value = Integer.parseInt(String.valueOf(params.get("days")));
            return Math.max(1, Math.min(MAX_DAYS, value));
        } catch (NumberFormatException ignored) {
            return DEFAULT_DAYS;
        }
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }
}
