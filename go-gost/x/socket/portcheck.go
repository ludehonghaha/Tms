package socket

import (
	"encoding/json"
	"fmt"
	"net"
	"strings"
)

// CheckTcpPortRequest asks the Agent to verify whether a local TCP bind is available.
// It is read-only: the temporary listener is closed immediately and no config is saved.
type CheckTcpPortRequest struct {
	Host string `json:"host"`
	Port int    `json:"port"`
}

// CheckTcpPortResponse is returned even when the port is occupied; occupied is a
// normal probe result rather than a command error.
type CheckTcpPortResponse struct {
	Host      string `json:"host"`
	Port      int    `json:"port"`
	Available bool   `json:"available"`
	Message   string `json:"message"`
}

func (w *WebSocketReporter) handleCheckTcpPort(data interface{}) (CheckTcpPortResponse, error) {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return CheckTcpPortResponse{}, fmt.Errorf("序列化端口检查数据失败: %v", err)
	}

	var req CheckTcpPortRequest
	if err := json.Unmarshal(jsonData, &req); err != nil {
		return CheckTcpPortResponse{}, fmt.Errorf("解析端口检查请求失败: %v", err)
	}
	if req.Port < 1 || req.Port > 65535 {
		return CheckTcpPortResponse{}, fmt.Errorf("端口范围必须为1-65535")
	}

	host := strings.TrimSpace(req.Host)
	if host == "" {
		host = "0.0.0.0"
	}
	if host != "0.0.0.0" && host != "::" && net.ParseIP(host) == nil {
		return CheckTcpPortResponse{}, fmt.Errorf("host必须是本机IP、0.0.0.0或::")
	}

	addr := net.JoinHostPort(host, fmt.Sprintf("%d", req.Port))
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return CheckTcpPortResponse{
			Host:      host,
			Port:      req.Port,
			Available: false,
			Message:   err.Error(),
		}, nil
	}
	_ = ln.Close()

	return CheckTcpPortResponse{
		Host:      host,
		Port:      req.Port,
		Available: true,
		Message:   "bind available",
	}, nil
}
