package com.admin.controller;


import com.admin.common.aop.LogAnnotation;
import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.common.task.StatisticsFlowAsync;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Map;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/user")
public class UserController extends BaseController {

    @Resource
    private StatisticsFlowAsync statisticsFlowAsync;

    @LogAnnotation
    @PostMapping("/login")
    public R login(@Validated @RequestBody LoginDto loginDto) {
        return userService.login(loginDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/create")
    public R create(@Validated @RequestBody UserDto userDto) {
        return userService.createUser(userDto);
    }


    @LogAnnotation
    @RequireRole
    @PostMapping("/list")
    public R readAll() {
        return userService.getAllUsers();
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/update")
    public R update(@Validated @RequestBody UserUpdateDto userUpdateDto) {
        return userService.updateUser(userUpdateDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return userService.deleteUser(id);
    }

    @LogAnnotation
    @PostMapping("/package")
    public R getUserPackageInfo() {
        return userService.getUserPackageInfo();
    }

    @LogAnnotation
    @PostMapping("/updatePassword")
    public R updatePassword(@Validated @RequestBody ChangePasswordDto changePasswordDto) {
        return userService.updatePassword(changePasswordDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/reset")
    public R reset(@Validated @RequestBody ResetFlowDto resetFlowDto) {
        // 手动 reset 可能发生在每分钟采样的任意两个时刻之间，先 flush 可避免
        // 最后几十秒流量从日统计里消失。capture 失败不会修改用户流量。
        statisticsFlowAsync.captureUser(resetFlowDto.getId().longValue());
        return userService.reset(resetFlowDto);
    }



}
