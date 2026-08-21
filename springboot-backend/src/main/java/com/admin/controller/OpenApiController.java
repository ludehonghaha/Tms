package com.admin.controller;


import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import javax.servlet.http.HttpServletResponse;

import com.admin.common.utils.Md5Util;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.service.InboundService;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/open_api")
public class OpenApiController extends BaseController {

    @Autowired
    private InboundService inboundService;

    /** 订阅:按 token 返回该用户所有协议链接的 base64(客户端订阅用,免登录) */
    @GetMapping("/sub")
    public String sub(@RequestParam("token") String token) {
        return inboundService.buildSubscription(token);
    }

    /** NB SS-over-SSH 与原生 Shadowsocks 的 Mihomo / OpenClash / Clash Meta YAML。 */
    @GetMapping(value = "/sub/mihomo", produces = "text/yaml;charset=UTF-8")
    public String mihomoSub(@RequestParam("token") String token) {
        return inboundService.buildMihomoSubscription(token);
    }

    @LogAnnotation
    @GetMapping("/sub_store")
    public Object create(
            @RequestParam("user") String user,
            @RequestParam("pwd") String pwd,
            @RequestParam(value = "tunnel", required = false, defaultValue = "-1") String tunnel,
            HttpServletResponse response) {
        JSONObject result = new JSONObject();
        result.put("upload", 0);
        result.put("download", 0);
        result.put("total", 0);
        result.put("expire", 0);
        // 校验 user 是否为空
        if (user == null || user.isEmpty()) {
            return R.err("用户不能为空");
        }
        if (pwd == null || pwd.isEmpty()) {
            return R.err("密码不能为空");
        }

        User userInfo = userService.getOne(new QueryWrapper<User>().eq("user", user));
        if (userInfo == null) {
            return R.err("鉴权失败");
        }

        String pwdMd5 = Md5Util.md5(pwd);
        if (!Objects.equals(pwdMd5, userInfo.getPwd())) {
            return R.err("鉴权失败");
        }

        final long GIGA = 1024L * 1024L * 1024L;
        String headerValue;

        if ("-1".equals(tunnel)) {
            headerValue = buildSubscriptionHeader(
                    userInfo.getOutFlow(),
                    userInfo.getInFlow(),
                    userInfo.getFlow() * GIGA,
                    // 没设到期就报 0(订阅协议里 expire=0 = 不过期);别直接拆箱,老数据里可能是 null
                    userInfo.getExpTime() == null ? 0 : userInfo.getExpTime() / 1000
            );
        } else {
            UserTunnel tunnelInfo = userTunnelService.getById(tunnel);
            if (tunnelInfo == null) return R.err("隧道不存在");
            if (!tunnelInfo.getUserId().toString().equals(userInfo.getId().toString())) return R.err("隧道不存在");
            headerValue = buildSubscriptionHeader(
                    tunnelInfo.getOutFlow(),
                    tunnelInfo.getInFlow(),
                    tunnelInfo.getFlow() * GIGA,
                    tunnelInfo.getExpTime() == null ? 0 : tunnelInfo.getExpTime() / 1000
            );
        }

        response.setHeader("subscription-userinfo", headerValue);
        return headerValue;
    }



    private String buildSubscriptionHeader(long upload, long download, long total, long expire) {
        return String.format("upload=%d; download=%d; total=%d; expire=%d", download, upload, total, expire);
    }


}
