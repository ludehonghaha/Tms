package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * 新建协议入站(合体面板)。支持 shadowsocks(默认,稳)与 vless-reality(无域名)。
 */
@Data
public class InboundDto {

    /** 编辑已有入站时使用 */
    private Long id;

    @NotNull(message = "节点不能为空")
    private Long nodeId;

    /** 协议:shadowsocks(默认) / vless */
    private String protocol;

    /** sing-box 本机监听口,可空(自动分配 40000+) */
    private Integer listenPort;

    /** Reality 借用的 SNI(仅 vless-reality 需要,如 www.microsoft.com) */
    private String sni;

    /** Reality 握手目标站点,可空则用 sni */
    private String dest;

    /** 落地ID:空=直连(协议管理),有=中转(该入站经此落地出网) */
    private Long landingId;

    /** NB 7CM SS-over-SSH: 外层 SSH 入口端口 */
    private Integer sshPort;

    /** NB 7CM SS-over-SSH: SSH 用户名 */
    private String sshUsername;

    /** NB 7CM SS-over-SSH: SSH 私钥内容(OpenSSH PEM/openssh-key-v1) */
    private String sshPrivateKey;

    /** NB 原生 Shadowsocks: NoBrand NAT 对外公布的 IP 或域名 */
    private String publicServer;

    /** NB 原生 Shadowsocks: NoBrand NAT 对外映射端口 */
    private Integer publicPort;

    /** NB 原生 Shadowsocks: 节点上 sing-box 的实际监听地址，默认 0.0.0.0 */
    private String internalListenAddress;

    /** NB 原生 Shadowsocks: 当前首版固定为 2022-blake3-aes-256-gcm */
    private String cipher;

    private String remark;
}
